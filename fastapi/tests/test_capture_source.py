import importlib.util
import json
import subprocess
import sys
from pathlib import Path

import pytest

SPEC = importlib.util.spec_from_file_location(
    "capture_source", Path(__file__).resolve().parents[1] / "scripts/capture_source.py"
)
capture = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(capture)


def test_device_access_requires_explicit_authorization():
    with pytest.raises(ValueError, match="显式"):
        capture.resolve_source("DEVICE", 0, False, {})
    with pytest.raises(ValueError, match="显式"):
        capture.resolve_source(
            "RTSP", "CAMERA_RTSP_URL", False, {"CAMERA_RTSP_URL": "rtsp://example.test/live"}
        )


def test_rtsp_cannot_read_unrelated_secret_or_http_endpoint():
    with pytest.raises(ValueError):
        capture.resolve_source(
            "RTSP", "DB_PASSWORD", True, {"DB_PASSWORD": "rtsp://example.test/live"}
        )


def test_capture_cli_file_path_produces_bounded_video(tmp_path):
    import cv2
    import numpy as np

    source = tmp_path / "protocol.mp4"
    writer = cv2.VideoWriter(str(source), cv2.VideoWriter_fourcc(*"mp4v"), 5, (16, 16))
    assert writer.isOpened()
    for _ in range(2):
        writer.write(np.zeros((16, 16, 3), dtype=np.uint8))
    writer.release()
    completed = subprocess.run(
        [
            sys.executable,
            str(Path(capture.__file__)),
            "--input-file",
            str(source),
            "--seconds",
            "1",
            "--max-frames",
            "2",
        ],
        capture_output=True,
        text=True,
        encoding="utf-8",
        timeout=20,
    )
    assert completed.returncode == 0, completed.stderr
    result = json.loads(completed.stdout)
    assert result["source_kind"] == "FILE" and result["frames"] == 2
    target = Path(result["file"]).resolve()
    assert target.is_relative_to(capture.ROOT / "runtime/capture")
    target.unlink()
    target.with_suffix(".json").unlink()
    with pytest.raises(ValueError):
        capture.resolve_source(
            "RTSP", "CAMERA_RTSP_URL", True, {"CAMERA_RTSP_URL": "https://example.test/live"}
        )
