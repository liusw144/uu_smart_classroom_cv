import importlib.util
from pathlib import Path
import tempfile
import unittest
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))

SPEC = importlib.util.spec_from_file_location(
    "backup_local", Path(__file__).resolve().parents[1] / "scripts/backup_local.py"
)


class BackupBoundaryTest(unittest.TestCase):
    def setUp(self):
        self.module = importlib.util.module_from_spec(SPEC)
        SPEC.loader.exec_module(self.module)

    def test_restore_never_accepts_business_database_name(self):
        for name in (
            "uu_classroom_local",
            "uu_classroom_test",
            "mysql",
            "uu_cv_restore_bad",
            "uu_cv_restore_aaaaaaaaaaaa;DROP DATABASE mysql",
        ):
            with self.assertRaises(ValueError):
                self.module.restore_name(name)
        self.assertEqual(
            "uu_cv_restore_a01234567890",
            self.module.restore_name("uu_cv_restore_a01234567890"),
        )

    def test_corrupted_backup_is_rejected_before_any_database_call(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "data.sql").write_bytes(b"changed")
            with self.assertRaises(ValueError):
                self.module.verify_inventory(
                    root, {"data.sql": {"sha256": "0" * 64, "size": 7}}
                )

    def test_inventory_cannot_read_outside_backup_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                self.module.verify_inventory(
                    Path(directory), {"../private": {"sha256": "0" * 64, "size": 1}}
                )
