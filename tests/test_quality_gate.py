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

    def test_yaml_jdbc_url_is_not_a_comment(self):
        errors = self.scan_file("deploy/config.yaml", "url: jdbc:mysql://127.0.0.1:3306/classroom\n")
        self.assertEqual([], errors)

    def test_xml_repository_url_is_not_a_comment(self):
        errors = self.scan_file("backend/settings.xml", "<url>https://repo.maven.apache.org/maven2</url>\n")
        self.assertEqual([], errors)


if __name__ == "__main__":
    unittest.main()
