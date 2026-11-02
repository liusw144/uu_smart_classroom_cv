import importlib.util
from pathlib import Path
import unittest


SPEC = importlib.util.spec_from_file_location("local_dev", Path(__file__).resolve().parents[1] / "scripts/local_dev.py")


class LocalEnvironmentTest(unittest.TestCase):
    def test_edge_vision_and_frontend_never_receive_business_credentials(self):
        module=importlib.util.module_from_spec(SPEC)
        SPEC.loader.exec_module(module)
        values={'DB_ADMIN_USER':'admin-fixture','DB_ADMIN_PASSWORD':'never-forward','DB_USER':'app-fixture','DB_PASSWORD':'app-secret','VISION_SERVICE_TOKEN':'vision-only','EDGE_SERVICE_TOKEN':'node-only','EDGE_NODE_ID':'fixture-node','AI_SERVICE_TOKEN':'ai-only','REAL_DEMO_ENABLED':'true'}
        for target in ('vision','edge','teacher','admin','ai'):
            env=module.environment(values,target=target)
            self.assertFalse(any(key.startswith(('DB_','TEST_DB_','REDIS_')) for key in env),target)
            if target not in ('ai',): self.assertNotIn('AI_SERVICE_TOKEN',env)
            if target!='edge': self.assertNotIn('EDGE_SERVICE_TOKEN',env)
    def test_java_packaged_manifest_matches_public_lock(self):
        root=Path(__file__).resolve().parents[1]
        self.assertEqual((root/'models/demo-assets.lock.json').read_bytes(),(root/'backend/api/src/main/resources/models/demo-assets.lock.json').read_bytes())
    def test_database_admin_credentials_are_not_forwarded_to_backend(self):
        module = importlib.util.module_from_spec(SPEC)
        SPEC.loader.exec_module(module)
        values = {"DB_ADMIN_USER":"administrative-fixture", "DB_ADMIN_PASSWORD":"never-forward", "DB_USER":"fixture-app", "DB_PASSWORD":"fixture-only", "VISION_SERVICE_TOKEN":"fixture-token", "TEST_DB_USER":"fixture-test", "TEST_DB_PASSWORD":"fixture-only-test", "TEST_REDIS_DATABASE":"15"}
        environment=module.environment(values)
        self.assertFalse("DB_ADMIN_PASSWORD" in environment, "管理员凭据不能传入后端")
        self.assertEqual("fixture-app",environment["DB_USER"])


if __name__ == "__main__":
    unittest.main()
