"""视频作业的进程资源边界；不接受来自HTTP的命令或路径。"""

import ctypes
import hashlib
import json
import os
import signal
import subprocess
import sys
import tempfile
import time
from pathlib import Path

from classroom_vision.domain.results import InferenceResult
from classroom_vision.inference.engine import ModelUnavailable


class WindowsJob:
    def __init__(self, memory_mb):
        from ctypes import wintypes as w

        class Basic(ctypes.Structure):
            _fields_ = [
                ("process_time", ctypes.c_int64),
                ("job_time", ctypes.c_int64),
                ("flags", w.DWORD),
                ("minimum", ctypes.c_size_t),
                ("maximum", ctypes.c_size_t),
                ("active", w.DWORD),
                ("affinity", ctypes.c_size_t),
                ("priority", w.DWORD),
                ("scheduling", w.DWORD),
            ]

        class Counters(ctypes.Structure):
            _fields_ = [
                (name, ctypes.c_uint64)
                for name in (
                    "reads",
                    "writes",
                    "others",
                    "read_bytes",
                    "write_bytes",
                    "other_bytes",
                )
            ]

        class Extended(ctypes.Structure):
            _fields_ = [
                ("basic", Basic),
                ("io", Counters),
                ("process_memory", ctypes.c_size_t),
                ("job_memory", ctypes.c_size_t),
                ("peak_process", ctypes.c_size_t),
                ("peak_job", ctypes.c_size_t),
            ]

        self.api = ctypes.WinDLL("kernel32", use_last_error=True)
        self.api.CreateJobObjectW.argtypes = [ctypes.c_void_p, w.LPCWSTR]
        self.api.CreateJobObjectW.restype = w.HANDLE
        self.api.SetInformationJobObject.argtypes = [
            w.HANDLE,
            ctypes.c_int,
            ctypes.c_void_p,
            w.DWORD,
        ]
        self.api.SetInformationJobObject.restype = w.BOOL
        self.api.AssignProcessToJobObject.argtypes = [w.HANDLE, w.HANDLE]
        self.api.AssignProcessToJobObject.restype = w.BOOL
        self.api.OpenProcess.argtypes = [w.DWORD, w.BOOL, w.DWORD]
        self.api.OpenProcess.restype = w.HANDLE
        self.api.CloseHandle.argtypes = [w.HANDLE]
        self.api.CloseHandle.restype = w.BOOL
        self.handle = self.api.CreateJobObjectW(None, None)
        if not self.handle:
            raise ctypes.WinError(ctypes.get_last_error())
        limits = Extended()
        # 作业内存总量、有限进程数、句柄关闭时终止整个进程树。
        # Windows虚拟环境重定向器也占一个进程槽，需要为解释器和编码器留出槽位。
        limits.basic.flags = 0x200 | 0x8 | 0x2000
        limits.basic.active = 4
        limits.job_memory = memory_mb * 1024 * 1024
        if not self.api.SetInformationJobObject(
            self.handle, 9, ctypes.byref(limits), ctypes.sizeof(limits)
        ):
            self.close()
            raise ctypes.WinError(ctypes.get_last_error())

    def assign(self, pid):
        process = self.api.OpenProcess(0x100 | 0x1, False, pid)
        if not process:
            raise ctypes.WinError(ctypes.get_last_error())
        try:
            if not self.api.AssignProcessToJobObject(self.handle, process):
                raise ctypes.WinError(ctypes.get_last_error())
        finally:
            self.api.CloseHandle(process)

    def close(self):
        if self.handle:
            self.api.CloseHandle(self.handle)
            self.handle = None

    def resume(self, pid):
        from ctypes import wintypes as w

        class ThreadEntry(ctypes.Structure):
            _fields_ = [
                ("size", w.DWORD),
                ("usage", w.DWORD),
                ("id", w.DWORD),
                ("owner", w.DWORD),
                ("priority", w.LONG),
                ("delta", w.LONG),
                ("flags", w.DWORD),
            ]

        self.api.CreateToolhelp32Snapshot.argtypes = [w.DWORD, w.DWORD]
        self.api.CreateToolhelp32Snapshot.restype = w.HANDLE
        for name in ("Thread32First", "Thread32Next"):
            function = getattr(self.api, name)
            function.argtypes = [w.HANDLE, ctypes.POINTER(ThreadEntry)]
            function.restype = w.BOOL
        self.api.OpenThread.argtypes = [w.DWORD, w.BOOL, w.DWORD]
        self.api.OpenThread.restype = w.HANDLE
        self.api.ResumeThread.argtypes = [w.HANDLE]
        self.api.ResumeThread.restype = w.DWORD
        snapshot = self.api.CreateToolhelp32Snapshot(0x4, 0)
        if not snapshot or snapshot == ctypes.c_void_p(-1).value:
            raise ctypes.WinError(ctypes.get_last_error())
        threads = []
        try:
            entry = ThreadEntry()
            entry.size = ctypes.sizeof(entry)
            available = self.api.Thread32First(snapshot, ctypes.byref(entry))
            while available:
                if entry.owner == pid:
                    threads.append(entry.id)
                available = self.api.Thread32Next(snapshot, ctypes.byref(entry))
        finally:
            self.api.CloseHandle(snapshot)
        if len(threads) != 1:
            raise OSError("无法确认挂起进程的唯一初始线程")
        thread = self.api.OpenThread(0x2, False, threads[0])
        if not thread:
            raise ctypes.WinError(ctypes.get_last_error())
        try:
            if self.api.ResumeThread(thread) != 1:
                raise OSError("视频初始线程恢复失败")
        finally:
            self.api.CloseHandle(thread)


def worker_environment(device):
    allowed = {"PATH", "SYSTEMROOT", "WINDIR", "TEMP", "TMP", "LANG", "LC_ALL"}
    if device == "gpu":
        allowed.update(
            {"LD_LIBRARY_PATH", "CUDA_PATH", "CUDA_VISIBLE_DEVICES", "NVIDIA_VISIBLE_DEVICES"}
        )
    if device == "npu":
        allowed.update(
            {
                "LD_LIBRARY_PATH",
                "ASCEND_HOME_PATH",
                "ASCEND_OPP_PATH",
                "ASCEND_AICPU_PATH",
                "ASCEND_CUSTOM_OPP_PATH",
                "ASCEND_TOOLKIT_HOME",
                "ASCEND_RT_VISIBLE_DEVICES",
                "ASCEND_VISIBLE_DEVICES",
                "TOOLCHAIN_HOME",
            }
        )
    # 只继承目标驱动需要的变量；服务令牌、用户会话、数据库配置不传给解码子进程。
    env = {key: value for key, value in os.environ.items() if key.upper() in allowed}
    python_paths = [str(Path(__file__).resolve().parents[2])]
    if device == "npu":
        # CANN的GE/TBE编译依赖set_env.sh所配置的Python算子路径，保留运维加载的目录。
        python_paths.extend(
            path for path in os.environ.get("PYTHONPATH", "").split(os.pathsep) if path
        )
    env["PYTHONPATH"] = os.pathsep.join(dict.fromkeys(python_paths))
    env["OPENBLAS_NUM_THREADS"] = "1"
    env["OMP_NUM_THREADS"] = "1"
    return env


def run_bounded(command, payload, timeout, memory_mb, *, cpu_seconds):
    """先建立资源边界，再通过标准输入授权子进程开始处理。"""
    started = time.monotonic()
    process = None
    job = None
    try:
        if os.name == "nt":
            job = WindowsJob(memory_mb)
        elif sys.platform != "linux":
            raise OSError("当前系统没有已实现的视频资源边界")
        env = worker_environment(payload.get("settings", {}).get("device", "cpu"))
        process = subprocess.Popen(
            command,
            stdin=subprocess.PIPE,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            env=env,
            start_new_session=os.name != "nt",
            # 挂起创建，先分配Job再运行，堵住虚拟环境启动器提前派生解释器的窗口。
            creationflags=(subprocess.CREATE_NO_WINDOW | 0x4) if os.name == "nt" else 0,
        )
        if job:
            job.assign(process.pid)
            job.resume(process.pid)
        else:
            import resource

            memory = memory_mb * 1024 * 1024
            # Linux限额按进程生效，子进程继承；部署仍需cgroup限制整个容器。
            resource.prlimit(process.pid, resource.RLIMIT_AS, (memory, memory))
            resource.prlimit(process.pid, resource.RLIMIT_CPU, (cpu_seconds, cpu_seconds))
            resource.prlimit(
                process.pid, resource.RLIMIT_FSIZE, (32 * 1024 * 1024, 32 * 1024 * 1024)
            )
        remaining = timeout - (time.monotonic() - started)
        if remaining <= 0:
            raise subprocess.TimeoutExpired(command, timeout)
        process.communicate(
            (json.dumps(payload, ensure_ascii=False) + "\n").encode("utf-8"), timeout=remaining
        )
        return process.returncode
    except subprocess.TimeoutExpired as error:
        raise ModelUnavailable("视频作业超时，已终止处理进程") from error
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        raise ModelUnavailable("无法建立视频资源隔离") from error
    finally:
        if job:
            job.close()
        if process:
            if os.name == "nt":
                if process.poll() is None:
                    process.kill()
            else:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
            process.wait(timeout=5)
            if process.stdin:
                process.stdin.close()


def isolated_video(engine, request):
    settings = engine.settings
    data = request.model_dump(mode="json")
    fingerprint, cached = engine.cached_result(request)
    if cached is not None:
        return cached
    settings.result_root.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(
        prefix=f"video-{request.job_id}-", dir=settings.result_root
    ) as temporary:
        root = Path(temporary)
        config = settings.model_dump(mode="json", exclude={"service_token"})
        for key in ("model_lock", "models_root", "asset_cache"):
            config[key] = str(Path(config[key]).resolve())
        config["result_root"] = str(root.resolve())
        status = run_bounded(
            [sys.executable, "-m", "classroom_vision.inference.video_worker"],
            {"settings": config, "request": data},
            settings.video_timeout_seconds,
            settings.video_memory_mb,
            cpu_seconds=settings.video_timeout_seconds,
        )
        if status == 2:
            raise ValueError("视频输入无法安全解码或超过允许范围")
        if status != 0:
            raise ModelUnavailable("视频处理异常或超出资源预算")
        metadata = root / f"{request.job_id}.json"
        artifact = root / f"{request.job_id}.mp4"
        if (
            not metadata.is_file()
            or metadata.is_symlink()
            or metadata.stat().st_size > 2 * 1024 * 1024
        ):
            raise ModelUnavailable("视频结果元数据无效")
        try:
            result = InferenceResult.model_validate_json(metadata.read_bytes()).model_dump(
                mode="json"
            )
        except (ValueError, OSError) as error:
            raise ModelUnavailable("视频结果元数据无效") from error
        if any(
            result[k] != v
            for k, v in {
                "job_id": str(request.job_id),
                "asset_id": str(request.asset_id),
                "input_sha256": request.input_sha256,
                "model_version": engine.version,
                "model_hashes": engine.hashes,
                "media_kind": "VIDEO",
                "request_digest": fingerprint,
                "schema_version": 2,
                "artifact_content_type": "video/mp4",
            }.items()
        ):
            raise ModelUnavailable("视频结果与请求不一致")
        if (
            not artifact.is_file()
            or artifact.is_symlink()
            or not 0 < artifact.stat().st_size <= 32 * 1024 * 1024
            or artifact.stat().st_size != result["artifact_size"]
        ):
            raise ModelUnavailable("视频产物大小无效")
        with artifact.open("rb") as source:
            if hashlib.file_digest(source, "sha256").hexdigest() != result["artifact_sha256"]:
                raise ModelUnavailable("视频产物校验失败")
        # 元数据最后发布；超时、内存拒绝和校验失败不会留下可下载的成功结果。
        artifact.replace(settings.result_root / artifact.name)
        metadata.replace(settings.result_root / metadata.name)
        return result
