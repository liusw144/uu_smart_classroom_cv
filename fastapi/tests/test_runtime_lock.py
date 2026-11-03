from uuid import uuid4

import pytest


def test_startup_reclaims_crash_scratch_only_with_exclusive_runtime_lock(tmp_path):
    from classroom_vision.core.runtime_lock import owned_results

    crashed = tmp_path / f"video-{uuid4()}-stale"
    crashed.mkdir()
    (crashed / "partial.mp4").write_bytes(b"crashed")
    final = tmp_path / f"{uuid4()}.mp4"
    final.write_bytes(b"confirmed")
    with owned_results(tmp_path):
        assert not crashed.exists()
        assert final.read_bytes() == b"confirmed"
        with pytest.raises(RuntimeError):
            with owned_results(tmp_path):
                pass
    with owned_results(tmp_path):
        pass
