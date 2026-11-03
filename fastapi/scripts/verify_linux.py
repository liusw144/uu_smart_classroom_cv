"""在受限Linux容器内验证真实视频和异常进程边界，不生成生产容量结论。"""

import argparse
import hashlib
import importlib.metadata
import json
import os
import shutil
import sys
import tempfile
import time
from pathlib import Path
from uuid import uuid4

from classroom_vision.core.runtime_lock import owned_results
from classroom_vision.core.settings import Settings
from classroom_vision.domain.models import DetectionRequest
from classroom_vision.domain.results import InferenceResult
from classroom_vision.inference.engine import ModelUnavailable
from classroom_vision.inference.onnx_pose import OnnxPoseEngine
from classroom_vision.inference.video_isolation import run_bounded


def main():
    if not __debug__:
        raise RuntimeError("资源验收禁止使用优化模式")
    parser = argparse.ArgumentParser(description="Linux进程与真实视频验证")
    parser.add_argument("--lock", type=Path, required=True)
    parser.add_argument("--models", type=Path, required=True)
    parser.add_argument("--sample", type=Path, required=True)
    parser.add_argument("--state", type=Path, required=True)
    args = parser.parse_args()
    if sys.platform != "linux" or os.getuid() == 0:
        raise RuntimeError("必须在非root Linux进程中执行")
    args.state.mkdir(parents=True, exist_ok=True)
    report = {"passed": False, "uid": os.getuid(), "production_capacity_passed": False}
    try:
        for name in ("memory.max", "pids.max"):
            limit = Path("/sys/fs/cgroup", name).read_text().strip()
            if limit == "max" or int(limit) <= 0:
                raise RuntimeError("缺少容器资源上限：" + name)
            report[name] = int(limit)

        def bounded(code, payload=None, timeout=2, memory=128):
            return run_bounded(
                [sys.executable, "-c", code], payload or {}, timeout, memory, cpu_seconds=5
            )

        started = time.monotonic()
        try:
            bounded("import sys,time; sys.stdin.readline(); time.sleep(60)", timeout=0.3)
            raise AssertionError("挂起进程没有超时")
        except ModelUnavailable:
            assert time.monotonic() - started < 5
        assert bounded("import sys; sys.stdin.readline(); data=bytearray(1024**3)") != 0
        with tempfile.TemporaryDirectory(dir=args.state) as temporary:
            target = Path(temporary) / "large.bin"
            code = (
                "import sys,json; p=json.loads(sys.stdin.readline()); "
                "open(p['file'],'wb').write(b'x'*(40*1024**2))"
            )
            assert bounded(code, {"file": str(target)}, memory=256) != 0
            assert not target.exists() or target.stat().st_size <= 32 * 1024**2
            pid_file = Path(temporary) / "descendant.pid"
            code = (
                "import sys,json,subprocess,time; p=json.loads(sys.stdin.readline()); "
                "child=subprocess.Popen([sys.executable,'-c','import time; time.sleep(60)']); "
                "open(p['file'],'w').write(str(child.pid)); time.sleep(60)"
            )
            try:
                bounded(code, {"file": str(pid_file)}, timeout=1)
                raise AssertionError("子进程树没有超时")
            except ModelUnavailable:
                pass
            status = Path("/proc", pid_file.read_text(), "stat")
            assert not status.exists() or status.read_text().split()[2] == "Z"
        assert bounded("import sys; sys.stdin.readline()") == 0
        report["timeout_memory_file_and_descendant_limits"] = True

        distributions = {
            entry.metadata["Name"]
            for entry in importlib.metadata.distributions()
            if entry.metadata["Name"].startswith("opencv-")
        }
        assert distributions == {"opencv-python-headless"}
        report["opencv_distributions"] = sorted(distributions)
        settings = Settings(
            app_env="test",
            service_token="linux-local-verification-only-0000000000",
            engine="onnx",
            model_lock=args.lock,
            models_root=args.models,
            asset_cache=args.state / "assets",
            result_root=args.state / "results",
        )
        settings.asset_cache.mkdir(exist_ok=True)
        asset, job = str(uuid4()), str(uuid4())
        shutil.copyfile(args.sample, settings.asset_cache / asset)
        with args.sample.open("rb") as source:
            digest = hashlib.file_digest(source, "sha256").hexdigest()
        with owned_results(settings.result_root):
            engine = OnnxPoseEngine(settings)
            request = DetectionRequest(
                job_id=job,
                asset_id=asset,
                input_sha256=digest,
                model_version=engine.version,
                media_kind="VIDEO",
            )
            started = time.monotonic()
            result = engine.detect(request)
            InferenceResult.model_validate(result)
            assert result["person_instances"] > 0
            assert engine.detect(request) == result
            report["video"] = {
                "seconds": round(time.monotonic() - started, 3),
                "sampled_frames": result["sampled_frames"],
                "person_instances": result["person_instances"],
                "model_hashes": result["model_hashes"],
                "artifact_sha256": result["artifact_sha256"],
            }
        report["passed"] = True
    finally:
        (args.state / "linux-verification.json").write_text(
            json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        print(json.dumps(report, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
