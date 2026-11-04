import hashlib
import importlib.util
import json
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "scripts/package_build.py"
COMMIT = "a" * 40


class PackageBuildTest(unittest.TestCase):
    def setUp(self):
        self.assertTrue(SCRIPT.is_file(), "尚未实现构建包打包器")
        spec = importlib.util.spec_from_file_location("package_build", SCRIPT)
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for name in [
            "LICENSE",
            "NOTICE",
            "THIRD_PARTY_NOTICES.md",
            "docs/guides/构建包安装说明.md",
            "models/demo-assets.lock.json",
            "frontend/nginx.conf",
            "contracts/edge-lease.schema.json",
        ]:
            self.write(name, "公开说明")
        self.write(
            "backend/api/target/classroom-api-0.1.0-SNAPSHOT.jar", b"jar-fixture"
        )
        for app in ["teacher", "admin"]:
            self.write(f"frontend/apps/{app}/dist/index.html", "<html></html>")
            self.write(
                f"frontend/apps/{app}/dist/assets/index.js", "console.log('fixture')"
            )
        for module in ["fastapi", "edge", "ops"]:
            self.write(
                f"{module}/dist/{module}-0.1.0-py3-none-any.whl", b"wheel-fixture"
            )
            self.write(
                f"{module}/dist/requirements.txt", "example==1.0 --hash=sha256:abc\n"
            )
            self.write(f"{module}/pyproject.toml", '[project]\nversion="0.1.0"\n')
            self.write(f"{module}/uv.lock", "version = 1\n")
        self.output = self.root / "runtime/builds"

    def write(self, name, value):
        target = self.root / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(value if isinstance(value, bytes) else value.encode("utf-8"))
        return target

    def test_zip_contains_complete_modules_and_verifiable_hashes_without_local_secrets(
        self,
    ):
        self.write(".env.local", "DO_NOT_PACKAGE=secret")
        self.write(".harness/pki/server.key", "private-key")
        self.write("fastapi/.venv/secret.txt", "virtual-env")
        self.write("models/weights/private.onnx", "model")
        archive = self.module.create_bundle(self.root, self.output, COMMIT)
        with zipfile.ZipFile(archive) as bundle:
            names = set(bundle.namelist())
            self.assertIn("backend/classroom-api.jar", names)
            self.assertIn("frontend/teacher/index.html", names)
            self.assertIn("frontend/admin/assets/index.js", names)
            self.assertIn("python/fastapi/fastapi-0.1.0-py3-none-any.whl", names)
            self.assertIn("python/edge/requirements.txt", names)
            self.assertIn("python/ops/uv.lock", names)
            self.assertIn("安装说明.md", names)
            self.assertIn("contracts/edge-lease.schema.json", names)
            self.assertFalse(
                any(".env" in x or ".harness" in x or "private" in x for x in names)
            )
            manifest = json.loads(bundle.read("build-manifest.json"))
            self.assertEqual(COMMIT, manifest["commit"])
            self.assertFalse(manifest["production_ready"])
            self.assertFalse(manifest["source_dirty"])
            self.assertEqual(
                names - {"build-manifest.json"}, {x["path"] for x in manifest["files"]}
            )
            for entry in manifest["files"]:
                value = bundle.read(entry["path"])
                self.assertEqual(len(value), entry["size"])
                self.assertEqual(hashlib.sha256(value).hexdigest(), entry["sha256"])
        digest = archive.with_suffix(".zip.sha256").read_text().split()[0]
        self.assertEqual(hashlib.sha256(archive.read_bytes()).hexdigest(), digest)

    def test_missing_frontend_entry_refuses_incomplete_archive(self):
        (self.root / "frontend/apps/admin/dist/index.html").unlink()
        with self.assertRaises(ValueError):
            self.module.create_bundle(self.root, self.output, COMMIT)
        self.assertFalse(list(self.output.glob("*.zip")))

    def test_multiple_jars_or_wheels_refuse_stale_artifact_selection(self):
        for extra in [
            "backend/api/target/classroom-api-old.jar",
            "edge/dist/edge-old.whl",
        ]:
            with self.subTest(extra=extra):
                target = self.write(extra, b"old")
                with self.assertRaises(ValueError):
                    self.module.create_bundle(self.root, self.output, COMMIT)
                target.unlink()

    def test_sensitive_file_inside_generated_frontend_refuses_archive(self):
        for name in [".env.local", "server.key", "nested/certificate.pem"]:
            with self.subTest(name=name):
                target = self.write("frontend/apps/teacher/dist/" + name, "secret")
                with self.assertRaises(ValueError):
                    self.module.create_bundle(self.root, self.output, COMMIT)
                target.unlink()

    def test_recordings_models_and_data_cannot_enter_frontend_archive(self):
        for name in [
            "lesson.mp4",
            "pose.onnx",
            "llm.gguf",
            "ascend.om",
            "students.csv",
            "students.xlsx",
        ]:
            with self.subTest(name=name):
                target = self.write(
                    "frontend/apps/teacher/dist/" + name, "不应打包的数据"
                )
                try:
                    with self.assertRaises(ValueError):
                        self.module.create_bundle(self.root, self.output / name, COMMIT)
                finally:
                    target.unlink()

    def test_symlink_cannot_smuggle_external_file_into_archive(self):
        external = self.write("outside.txt", "private")
        link = self.root / "frontend/apps/teacher/dist/linked.txt"
        try:
            link.symlink_to(external)
        except OSError as error:
            self.skipTest("当前系统不允许创建符号链接：" + str(error))
        with self.assertRaises(ValueError):
            self.module.create_bundle(self.root, self.output, COMMIT)

    def test_existing_archive_is_never_overwritten(self):
        archive = self.module.create_bundle(self.root, self.output, COMMIT)
        original = archive.read_bytes()
        with self.assertRaises(FileExistsError):
            self.module.create_bundle(self.root, self.output, COMMIT)
        self.assertEqual(original, archive.read_bytes())

    def test_invalid_commit_refuses_path_injection(self):
        with self.assertRaises(ValueError):
            self.module.create_bundle(self.root, self.output, "../../escape")

    def test_dirty_local_build_is_disclosed(self):
        archive = self.module.create_bundle(
            self.root, self.output, COMMIT, source_dirty=True
        )
        with zipfile.ZipFile(archive) as bundle:
            self.assertTrue(
                json.loads(bundle.read("build-manifest.json"))["source_dirty"]
            )

    def test_ci_rejects_wrong_commit_and_dirty_source(self):
        def git(*args):
            return subprocess.check_output(
                ["git", "-C", str(self.root), *args], text=True
            ).strip()

        git("init", "-q")
        git("add", ".")
        git(
            "-c",
            "user.name=构建测试",
            "-c",
            "user.email=fixture@example.invalid",
            "commit",
            "-qm",
            "合成构建输入",
        )
        actual = git("rev-parse", "HEAD")
        self.assertEqual(
            (actual, False), self.module.source_metadata(self.root, actual, ci=True)
        )
        with self.assertRaises(ValueError):
            self.module.source_metadata(self.root, COMMIT, ci=True)
        self.write("untracked.txt", "未提交改动")
        self.assertEqual((actual, True), self.module.source_metadata(self.root, actual))
        with self.assertRaises(ValueError):
            self.module.source_metadata(self.root, actual, ci=True)


if __name__ == "__main__":
    unittest.main()
