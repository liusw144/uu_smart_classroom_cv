import importlib.util
import hashlib
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location(
    "classroom_dataset",
    Path(__file__).resolve().parents[1] / "scripts/classroom_dataset.py",
)


class DatasetManifestTest(unittest.TestCase):
    def setUp(self):
        self.module = importlib.util.module_from_spec(SPEC)
        SPEC.loader.exec_module(self.module)

    def sample(self, root, index, split="train", session=None):
        content = ("仅供清单协议测试" + str(index)).encode("utf-8")
        (root / f"{index}.jpg").write_bytes(content)
        return {
            "sample_id": f"fixture_{index}",
            "school_id": "fixture_school",
            "session_id": session or f"fixture_session_{index}",
            "media_path": f"{index}.jpg",
            "sha256": hashlib.sha256(content).hexdigest(),
            "width": 100,
            "height": 100,
            "annotation_status": "human-reviewed",
            "annotator_id": "protocol_fixture",
            "authorization_ref": "protocol_fixture",
            "split": split,
            "frames": [
                {
                    "timestamp_ms": 0,
                    "boxes": [{"bbox": [0, 0, 10, 10], "category": "hand_up"}],
                }
            ],
        }

    def test_same_recording_cannot_leak_across_training_and_test(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = {
                "schema_version": 1,
                "categories": ["hand_up"],
                "samples": [
                    self.sample(root, 1, session="same"),
                    self.sample(root, 2, "test", session="same"),
                ],
            }
            with self.assertRaisesRegex(ValueError, "分组"):
                self.module.validate(manifest, root)

    def test_paths_hashes_and_box_bounds_are_checked(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            sample = self.sample(root, 1)
            manifest = {
                "schema_version": 1,
                "categories": ["hand_up"],
                "samples": [sample],
            }
            self.module.validate(manifest, root)
            sample["frames"][0]["boxes"][0]["bbox"] = [0, 0, 101, 10]
            with self.assertRaises(ValueError):
                self.module.validate(manifest, root)
            sample["media_path"] = "../private"
            with self.assertRaises(ValueError):
                self.module.validate(manifest, root)

    def test_grouped_split_is_deterministic_and_keeps_recordings_together(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = {
                "schema_version": 1,
                "categories": ["hand_up"],
                "samples": [self.sample(root, i) for i in range(6)],
            }
            first = self.module.split(manifest, "fixed-seed")
            self.assertEqual(first, self.module.split(manifest, "fixed-seed"))
            self.assertEqual(
                {"train", "val", "test"},
                {sample["split"] for sample in first["samples"]},
            )
