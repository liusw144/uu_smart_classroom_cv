import os
import sys
import time
from pathlib import Path

import pytest

from classroom_vision.inference.engine import ModelUnavailable


def test_invalid_child_metadata_never_publishes_success(monkeypatch, tmp_path):
    from types import SimpleNamespace
    from uuid import uuid4

    from classroom_vision.core.settings import Settings
    from classroom_vision.domain.models import DetectionRequest
    from classroom_vision.inference import video_isolation

    settings = Settings(service_token="isolated-test-no-network-token-2026", result_root=tmp_path)
    request = DetectionRequest(
        job_id=uuid4(),
        asset_id=uuid4(),
        input_sha256="a" * 64,
        model_version="test",
        media_kind="VIDEO",
    )
    engine = SimpleNamespace(
        settings=settings, version="test", hashes={}, cached_result=lambda _: ("b" * 64, None)
    )

    def invalid_worker(command, payload, *_, **__):
        result = Path(payload["settings"]["result_root"]) / f"{request.job_id}.json"
        result.write_text('{"unexpected":true}')
        return 0

    monkeypatch.setattr(video_isolation, "run_bounded", invalid_worker)
    with pytest.raises(ModelUnavailable):
        video_isolation.isolated_video(engine, request)
    assert not list(tmp_path.iterdir())


def run_code(code, *, timeout=3, memory_mb=256, payload=None):
    from classroom_vision.inference.video_isolation import run_bounded

    return run_bounded(
        [sys.executable, "-c", code], payload or {}, timeout, memory_mb, cpu_seconds=5
    )


def test_hung_decoder_has_hard_deadline_and_next_job_runs():
    started = time.monotonic()
    with pytest.raises(ModelUnavailable, match="超时"):
        run_code("import sys,time; sys.stdin.readline(); time.sleep(60)", timeout=0.3)
    assert time.monotonic() - started < 5
    assert run_code("import sys; sys.stdin.readline()") == 0


def test_native_memory_allocation_cannot_kill_parent():
    # 触碰真实内存页，避免仅保留虚拟地址导致资源限制测试失真。
    code = "import sys; sys.stdin.readline(); data=bytearray(1024*1024*1024)"
    assert run_code(code, memory_mb=128) != 0
    assert run_code("import sys; sys.stdin.readline()") == 0


def test_encoder_descendant_is_terminated_with_job(tmp_path):
    pid_file = tmp_path / "encoder.pid"
    code = (
        "import sys,json,subprocess,time; p=json.loads(sys.stdin.readline()); "
        "child=subprocess.Popen([sys.executable,'-c','import time; time.sleep(60)']); "
        "open(p['file'],'w').write(str(child.pid)); time.sleep(60)"
    )
    with pytest.raises(ModelUnavailable):
        run_code(code, timeout=1, payload={"file": str(pid_file)})
    pid = int(pid_file.read_text())
    if os.name == "nt":
        import ctypes

        api = ctypes.WinDLL("kernel32", use_last_error=True)
        api.OpenProcess.restype = ctypes.c_void_p
        api.WaitForSingleObject.argtypes = [ctypes.c_void_p, ctypes.c_uint32]
        api.CloseHandle.argtypes = [ctypes.c_void_p]
        process = api.OpenProcess(0x1000 | 0x100000, False, pid)
        if process:
            try:
                assert api.WaitForSingleObject(process, 3000) == 0
            finally:
                api.CloseHandle(process)
    else:
        # 已退出但尚未被init回收的僵尸进程也不再消耗解码资源。
        status = Path(f"/proc/{pid}/stat")
        assert not status.exists() or status.read_text().split()[2] == "Z"


def test_windows_assignment_failure_never_starts_decoder(monkeypatch, tmp_path):
    if os.name != "nt":
        pytest.skip("仅验证Windows作业对象分配失败边界")
    from classroom_vision.inference import video_isolation

    def reject(*_):
        raise OSError("注入作业对象分配失败")

    monkeypatch.setattr(video_isolation.WindowsJob, "assign", reject)
    marker = tmp_path / "started"
    code = "import sys; sys.stdin.readline(); open(" + repr(str(marker)) + ",'w').write('x')"
    with pytest.raises(ModelUnavailable):
        run_code(code)
    assert not marker.exists()


def test_windows_real_interpreter_inherits_limits_before_handshake(monkeypatch, tmp_path):
    if os.name != "nt":
        pytest.skip("仅验证Windows虚拟环境启动器的作业继承边界")
    import concurrent.futures
    import ctypes

    from classroom_vision.inference.video_isolation import WindowsJob

    original = WindowsJob.assign
    captured = {}

    def delayed_assign(self, pid):
        # 放大启动器提前派生解释器的时间窗口，并保留目标Job用于精确核验。
        time.sleep(0.2)
        original(self, pid)
        captured["job"] = self

    monkeypatch.setattr(WindowsJob, "assign", delayed_assign)
    pid_file, release_file = tmp_path / "worker.pid", tmp_path / "release"
    code = (
        "import sys,json,os,time; from pathlib import Path; "
        "data=json.loads(sys.stdin.readline()); "
        "Path(data['pid']).write_text(str(os.getpid())); "
        "release=Path(data['release']); "
        "exec('while not release.exists(): time.sleep(0.01)')"
    )
    with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
        future = pool.submit(
            run_code,
            code,
            timeout=5,
            memory_mb=128,
            payload={"pid": str(pid_file), "release": str(release_file)},
        )
        try:
            deadline = time.monotonic() + 3
            while not pid_file.exists() and time.monotonic() < deadline:
                if future.done():
                    future.result()
                    pytest.fail("工作进程未上报身份")
                time.sleep(0.01)
            actual_pid = int(pid_file.read_text())
            job = captured["job"]
            api = job.api
            api.IsProcessInJob.argtypes = [
                ctypes.c_void_p,
                ctypes.c_void_p,
                ctypes.POINTER(ctypes.c_int),
            ]
            api.IsProcessInJob.restype = ctypes.c_int
            process = api.OpenProcess(0x1000, False, actual_pid)
            assert process
            try:
                belongs = ctypes.c_int()
                assert api.IsProcessInJob(process, job.handle, ctypes.byref(belongs))
                assert belongs.value == 1
            finally:
                api.CloseHandle(process)
        finally:
            release_file.write_text("done")
        assert future.result(timeout=5) == 0
