"""按明确产物清单组装服务构建包，不收集运行数据和本机秘密。"""

import argparse
import hashlib
import json
import re
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SENSITIVE_SUFFIXES = {".pem", ".key", ".p12", ".pfx", ".jks", ".env", ".sqlite", ".db"}
FRONTEND_SUFFIXES = {
    ".html",
    ".js",
    ".css",
    ".svg",
    ".png",
    ".jpg",
    ".jpeg",
    ".webp",
    ".gif",
    ".ico",
    ".woff",
    ".woff2",
    ".ttf",
    ".otf",
    ".webmanifest",
    ".wasm",
}


def source_metadata(root: Path, requested_commit: str | None, *, ci: bool = False):
    def git(*args):
        return subprocess.check_output(
            ["git", "-C", str(root), *args], text=True, encoding="utf-8"
        ).strip()

    commit = git("rev-parse", "HEAD")
    if requested_commit is not None and requested_commit != commit:
        raise ValueError("请求的提交号与实际检出版本不同，拒绝标记构建来源")
    dirty = bool(git("status", "--porcelain", "--untracked-files=normal"))
    if ci and dirty:
        raise ValueError("CI工作区包含未提交改动，拒绝生成提交构建包")
    return commit, dirty


def create_bundle(
    root: Path, output_dir: Path, commit: str, *, source_dirty: bool = False
) -> Path:
    if not re.fullmatch(r"[a-f0-9]{40}", commit):
        raise ValueError("提交号必须为完整的40位小写Git SHA")
    root = root.resolve(strict=True)
    entries = {}

    def add(source: Path, destination: str):
        if not source.resolve(strict=True).is_relative_to(root):
            raise ValueError("产物路径越出项目根目录：" + destination)
        relative = source.relative_to(root)
        current = root
        for part in relative.parts:
            current = current / part
            if current.is_symlink():
                raise ValueError("构建包不接受符号链接：" + relative.as_posix())
        if (
            any(part.startswith(".") for part in relative.parts)
            or source.suffix.lower() in SENSITIVE_SUFFIXES
        ):
            raise ValueError("构建目录出现隐藏或敏感文件：" + relative.as_posix())
        if not source.is_file() or source.stat().st_size == 0:
            raise ValueError("必要产物为空或不是普通文件：" + destination)
        if destination in entries:
            raise ValueError("构建包目标路径重复：" + destination)
        entries[destination] = source

    def one(pattern: str) -> Path:
        files = list(root.glob(pattern))
        if len(files) != 1:
            raise ValueError("必须且只能存在一份产物：" + pattern)
        return files[0]

    try:
        add(one("backend/api/target/classroom-api-*.jar"), "backend/classroom-api.jar")
        for app in ("teacher", "admin"):
            base = root / "frontend/apps" / app / "dist"
            if not (base / "index.html").is_file():
                raise ValueError("前端缺少构建入口：" + app)
            for file in sorted(base.rglob("*")):
                if file.is_symlink() or file.is_file():
                    if file.suffix.lower() not in FRONTEND_SUFFIXES:
                        raise ValueError("前端构建目录出现非静态Web文件：" + file.name)
                    add(file, f"frontend/{app}/" + file.relative_to(base).as_posix())
        for module in ("fastapi", "edge", "ops"):
            wheel = one(f"{module}/dist/*.whl")
            add(wheel, f"python/{module}/" + wheel.name)
            add(
                root / module / "dist/requirements.txt",
                f"python/{module}/requirements.txt",
            )
            for name in ("pyproject.toml", "uv.lock"):
                add(root / module / name, f"python/{module}/" + name)
        for name in (
            "LICENSE",
            "NOTICE",
            "THIRD_PARTY_NOTICES.md",
            "models/demo-assets.lock.json",
            "frontend/nginx.conf",
            "contracts/edge-lease.schema.json",
        ):
            add(root / name, name)
        add(root / "docs/guides/构建包安装说明.md", "安装说明.md")
    except FileNotFoundError as error:
        raise ValueError("必要产物缺失，请先完成所有模块构建") from error

    output_dir.mkdir(parents=True, exist_ok=True)
    suffix = "-local" if source_dirty else ""
    archive = output_dir / f"uu-smart-classroom-cv-{commit[:12]}{suffix}.zip"
    checksum = archive.with_suffix(".zip.sha256")
    if archive.exists() or checksum.exists():
        raise FileExistsError("已有同名构建包或摘要，请使用新的输出目录")
    manifest = {
        "schema_version": 1,
        "commit": commit,
        "source_dirty": source_dirty,
        "vision_device": "cpu",
        "production_ready": False,
        "files": [],
    }
    with zipfile.ZipFile(archive, "x", compression=zipfile.ZIP_DEFLATED) as bundle:
        for destination, source in sorted(entries.items()):
            digest = hashlib.sha256()
            size = 0
            with (
                source.open("rb") as input_file,
                bundle.open(destination, "w", force_zip64=True) as output,
            ):
                while chunk := input_file.read(1024 * 1024):
                    output.write(chunk)
                    digest.update(chunk)
                    size += len(chunk)
            manifest["files"].append(
                {"path": destination, "size": size, "sha256": digest.hexdigest()}
            )
        bundle.writestr(
            "build-manifest.json",
            json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        )
    with archive.open("rb") as value:
        digest = hashlib.file_digest(value, "sha256").hexdigest()
    with checksum.open("x", encoding="utf-8", newline="\n") as value:
        value.write(f"{digest}  {archive.name}\n")
    return archive


def main():
    import os

    parser = argparse.ArgumentParser(description="生成不含秘密与模型的服务构建ZIP")
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--output-dir", type=Path, default=ROOT / "runtime/builds")
    parser.add_argument("--commit")
    args = parser.parse_args()
    commit, dirty = source_metadata(
        args.root, args.commit, ci=os.environ.get("GITHUB_ACTIONS") == "true"
    )
    archive = create_bundle(args.root, args.output_dir, commit, source_dirty=dirty)
    print("已生成服务构建包：" + str(archive))


if __name__ == "__main__":
    main()
