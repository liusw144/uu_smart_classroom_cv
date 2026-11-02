import json
from uuid import uuid4

import pytest


def test_low_confidence_joints_do_not_create_hand_raise():
    from classroom_vision.inference.onnx_pose import pose_candidates

    points = [[0.0, 100.0] for _ in range(17)]
    points[9] = [0.0, 0.0]
    assert pose_candidates(points, [0.01] * 17) == []
    confidence = [0.0] * 17
    confidence[5] = confidence[9] = 0.9
    assert pose_candidates(points, confidence) == ["HAND_UP_CANDIDATE"]


def test_configured_models_cannot_load_with_wrong_hash(tmp_path):
    from classroom_vision.inference.onnx_pose import load_model_files

    for name in ["detector.onnx", "pose.onnx"]:
        (tmp_path / name).write_bytes(b"not-a-real-model")
    lock = tmp_path / "lock.json"
    lock.write_text(
        json.dumps(
            {
                "model_version": "test",
                "detector": {"filename": "detector.onnx", "sha256": "0" * 64},
                "pose": {"filename": "pose.onnx", "sha256": "0" * 64},
            }
        ),
        encoding="utf-8",
    )
    with pytest.raises(ValueError, match="哈希"):
        load_model_files(lock, tmp_path)


def test_asset_reader_rejects_traversal_and_hash_mismatch(tmp_path):
    from classroom_vision.inference.onnx_pose import read_asset

    asset_id = str(uuid4())
    (tmp_path / asset_id).write_bytes(b"wrong-content")
    with pytest.raises(ValueError, match="校验"):
        read_asset(tmp_path, asset_id, "a" * 64)
    with pytest.raises(ValueError):
        read_asset(tmp_path, "../../private", "a" * 64)


@pytest.mark.parametrize("height,width", [(1280, 2), (8000, 2), (8000, 20), (20, 8000)])
def test_narrow_legal_image_is_rejected_before_model_preprocessing(height, width):
    import numpy as np

    from classroom_vision.inference.onnx_pose import OnnxPoseEngine

    engine = OnnxPoseEngine.__new__(OnnxPoseEngine)
    engine.detector = lambda image: ([], [])
    with pytest.raises(ValueError, match="长宽比"):
        engine.frame(np.zeros((height, width, 3), dtype=np.uint8))
