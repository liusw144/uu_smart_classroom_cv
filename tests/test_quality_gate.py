import importlib.util
from pathlib import Path
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("quality_gate", ROOT / "scripts/quality_gate.py")


class QualityGateTest(unittest.TestCase):
    def setUp(self):
        self.module = importlib.util.module_from_spec(SPEC)
        SPEC.loader.exec_module(self.module)

    def scan_file(self, relative, content):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / relative
            target.parent.mkdir(parents=True)
            target.write_text(content, encoding="utf-8")
            return self.module.scan_sources(root)

    def test_domain_cannot_import_spring(self):
        errors = self.scan_file("backend/domain/src/main/java/Bad.java", "import org.springframework.stereotype.Service;\n")
        self.assertTrue(errors)

    def test_missing_ci_workflow_cannot_pass_foundation(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertTrue(self.module.workflow_errors(Path(directory)))

    def test_unpinned_remote_action_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);(root/'.github/workflows').mkdir(parents=True)
            (root/'.github/workflows/verify.yml').write_text('jobs:\n  test:\n    steps:\n      - uses: actions/checkout@main\n',encoding='utf-8')
            self.assertTrue(self.module.workflow_errors(root))

    def test_frontend_cannot_import_backend_source(self):
        errors = self.scan_file("frontend/apps/teacher/src/bad.ts", "import x from '../../../../backend/internal';\n")
        self.assertTrue(errors)

    def test_english_python_comment_is_rejected(self):
        errors = self.scan_file("fastapi/src/bad.py", "# bad explanation\nvalue = 1\n")
        self.assertTrue(errors)

    def test_chinese_comment_and_url_are_accepted(self):
        errors = self.scan_file("frontend/apps/teacher/src/ok.ts", "// 仅允许业务接口\nconst url = 'https://example.test';\n")
        self.assertEqual([], errors)

    def test_production_requires_real_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertTrue(self.module.production_errors(Path(directory)))

    def test_domain_fully_qualified_framework_type_is_rejected(self):
        self.assertTrue(self.scan_file("backend/domain/src/main/java/Bad.java", "class Bad { org.springframework.context.ApplicationContext value; }"))

    def test_hashes_and_empty_blocker_list_cannot_authorize_production(self):
        import hashlib
        import json
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            (root/"harness").mkdir()
            (root/"harness/manifest.json").write_text(json.dumps({"production_blockers":[]}),encoding="utf-8")
            entries={}
            for name in ["model","tenant_isolation","recovery","capacity","deployment","approval"]:
                target=root/(name+".txt")
                target.write_text("未执行的占位材料",encoding="utf-8")
                entries[name]={"path":target.name,"sha256":hashlib.sha256(target.read_bytes()).hexdigest()}
            (root/"harness/release-evidence.json").write_text(json.dumps(entries),encoding="utf-8")
            self.assertTrue(self.module.production_errors(root))

    def test_yaml_jdbc_url_is_not_a_comment(self):
        errors = self.scan_file("deploy/config.yaml", "url: jdbc:mysql://127.0.0.1:3306/classroom\n")
        self.assertEqual([], errors)

    def test_xml_repository_url_is_not_a_comment(self):
        errors = self.scan_file("backend/settings.xml", "<url>https://repo.maven.apache.org/maven2</url>\n")
        self.assertEqual([], errors)


if __name__ == "__main__":
    unittest.main()
