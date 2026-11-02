import hashlib
import json
import shutil
import time
from pathlib import Path
from uuid import uuid4

from classroom_vision.core.settings import Settings
from classroom_vision.domain.models import DetectionRequest
from classroom_vision.domain.results import InferenceResult
from classroom_vision.inference.onnx_pose import OnnxPoseEngine

ROOT = Path(__file__).resolve().parents[2]
settings = Settings(
    app_env="test",
    service_token="model-verification-only-token-0000000000",
    engine="onnx",
    model_lock=ROOT / "models/demo-assets.lock.json",
    models_root=ROOT / "runtime/models",
    asset_cache=ROOT / "runtime/edge/assets",
    result_root=ROOT / "runtime/demo-results",
)
settings.asset_cache.mkdir(parents=True, exist_ok=True)
started = time.monotonic()
engine = OnnxPoseEngine(settings)
results = []
for filename, kind in [("image.jpg", "IMAGE"), ("upstream-demo.mp4", "VIDEO")]:
    source = ROOT / "runtime/demo" / filename
    asset_id = str(uuid4())
    shutil.copy2(source, settings.asset_cache / asset_id)
    with source.open("rb") as content:
        digest = hashlib.file_digest(content, "sha256").hexdigest()
    request = DetectionRequest(
        job_id=str(uuid4()),
        asset_id=asset_id,
        input_sha256=digest,
        model_version=engine.version,
        media_kind=kind,
    )
    result = engine.detect(request)
    InferenceResult.model_validate(result)
    assert result["person_instances"] > 0, "示例必须有真实人物检出，不能将空结果算演示成功"
    assert all(
        len(person["keypoints"]) == 17 for frame in result["frames"] for person in frame["persons"]
    )
    assert engine.detect(request) == result, "同一作业重试应复用相同计算结果"
    results.append(
        {
            "kind": kind,
            "job_id": result["job_id"],
            "sampled_frames": result["sampled_frames"],
            "person_instances": result["person_instances"],
            "elapsed_ms": result["elapsed_ms"],
            "artifact_sha256": result["artifact_sha256"],
            "model_hashes": result["model_hashes"],
        }
    )
    print(json.dumps(results[-1], ensure_ascii=False), flush=True)
(ROOT / "runtime/demo-results/verification.json").write_text(
    json.dumps(
        {"total_elapsed_seconds": round(time.monotonic() - started, 3), "results": results},
        ensure_ascii=False,
        indent=2,
    )
    + "\n",
    encoding="utf-8",
)
