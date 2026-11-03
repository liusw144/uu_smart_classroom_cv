import json
import os
from types import SimpleNamespace
from uuid import uuid4

import pytest
from pydantic import ValidationError

from classroom_vision.core.settings import Settings
from classroom_vision.domain.models import DetectionRequest
from classroom_vision.inference.engine import ModelUnavailable

TOKEN = "device-test-no-network-token-2026"


def config(**values):
    return Settings(_env_file=None, service_token=TOKEN, **values)


def test_default_device_is_cpu_and_accelerator_number_is_validated():
    assert config().device == "cpu"
    assert config().device_id == 0
    assert config(device="gpu", device_id=2).device_id == 2
    assert config(device="npu", device_id=1).device == "npu"
    for values in ({"device": "auto"}, {"device_id": -1}, {"device_id": 1}):
        with pytest.raises(ValidationError):
            config(**values)


@pytest.fixture
def runtime(monkeypatch):
    import onnxruntime as ort

    calls = []

    class Session:
        def __init__(self, path, *, sess_options, providers, enable_fallback=True):
            self.providers = [item[0] if isinstance(item, tuple) else item for item in providers]
            assert enable_fallback is False
            self.fallback_disabled = False
            calls.append((path, sess_options, providers, self))

        def get_providers(self):
            return self.providers

        def disable_fallback(self):
            self.fallback_disabled = True

    monkeypatch.setattr(ort, "InferenceSession", Session)
    monkeypatch.setattr(
        ort,
        "get_available_providers",
        lambda: ["CPUExecutionProvider", "CUDAExecutionProvider", "CANNExecutionProvider"],
    )
    return calls


@pytest.mark.parametrize(
    "device,provider",
    [
        ("cpu", "CPUExecutionProvider"),
        ("gpu", "CUDAExecutionProvider"),
        ("npu", "CANNExecutionProvider"),
    ],
)
def test_requested_provider_is_used_and_accelerators_forbid_cpu_fallback(runtime, device, provider):
    from classroom_vision.inference.execution import create_session

    settings = config(device=device, device_id=0 if device == "cpu" else 2, cpu_threads=3)
    session = create_session("model.onnx", settings)
    _, options, providers, _ = runtime[0]
    assert session.get_providers() == [provider]
    assert session.fallback_disabled
    assert options.intra_op_num_threads == 3
    if device != "cpu":
        assert providers[0][1]["device_id"] == "2"
        assert options.get_session_config_entry("session.disable_cpu_ep_fallback") == "1"
    if device == "npu":
        assert providers[0][1]["enable_cann_subgraph"] == "0"
        assert providers[0][1]["dump_om_model"] == "0"


@pytest.mark.parametrize("device", ["gpu", "npu"])
def test_missing_accelerator_fails_before_creating_any_session(monkeypatch, runtime, device):
    import onnxruntime as ort

    from classroom_vision.inference.execution import create_session

    monkeypatch.setattr(ort, "get_available_providers", lambda: ["CPUExecutionProvider"])
    with pytest.raises(ModelUnavailable, match="不可用"):
        create_session("model.onnx", config(device=device))
    assert not runtime


@pytest.mark.parametrize("device", ["gpu", "npu"])
def test_unused_registered_cpu_provider_does_not_reject_accelerator(monkeypatch, runtime, device):
    import onnxruntime as ort

    from classroom_vision.inference.execution import create_session

    monkeypatch.setattr(
        ort.InferenceSession,
        "get_providers",
        lambda session: session.providers + ["CPUExecutionProvider"],
    )
    session = create_session("model.onnx", config(device=device))
    assert session.get_providers()[0] != "CPUExecutionProvider"
    assert runtime[0][1].get_session_config_entry("session.disable_cpu_ep_fallback") == "1"


@pytest.mark.parametrize("device", ["gpu", "npu"])
def test_runtime_silent_cpu_fallback_is_rejected(monkeypatch, runtime, device):
    import onnxruntime as ort

    from classroom_vision.inference.execution import create_session

    monkeypatch.setattr(ort.InferenceSession, "get_providers", lambda _: ["CPUExecutionProvider"])
    with pytest.raises(ModelUnavailable, match="回退"):
        create_session("model.onnx", config(device=device))


def test_driver_initialization_error_is_explicit_and_does_not_retry_on_cpu(monkeypatch, runtime):
    import onnxruntime as ort

    from classroom_vision.inference.execution import create_session

    attempts = []

    def reject(*args, **kwargs):
        attempts.append(kwargs["providers"])
        raise RuntimeError("driver fixture failure")

    monkeypatch.setattr(ort, "InferenceSession", reject)
    with pytest.raises(ModelUnavailable, match="初始化失败"):
        create_session("model.onnx", config(device="npu"))
    assert len(attempts) == 1
    assert attempts[0][0][0] == "CANNExecutionProvider"


def test_real_ort_constructor_does_not_retry_cpu_after_accelerator_initialization_failure(
    monkeypatch,
):
    import onnxruntime as ort

    from classroom_vision.inference.execution import create_session

    attempts = []

    def reject(self, providers, provider_options, disabled_optimizers=None):
        self._fallback_providers = ["CPUExecutionProvider"]
        attempts.append(providers)
        raise RuntimeError("driver fixture failure")

    monkeypatch.setattr(ort, "get_available_providers", lambda: ["CANNExecutionProvider"])
    monkeypatch.setattr(ort.InferenceSession, "_create_inference_session", reject)
    with pytest.raises(ModelUnavailable, match="初始化失败"):
        create_session("model.onnx", config(device="npu"))
    assert len(attempts) == 1


def test_model_adapters_use_injected_sessions_without_hidden_cpu_creation(monkeypatch):
    import numpy as np
    from rtmlib.tools.base import BaseTool

    from classroom_vision.inference.model_adapters import SessionRTMPose, SessionYOLOX

    def reject(*args, **kwargs):
        raise AssertionError("不应调用隐式会话构造器")

    monkeypatch.setattr(BaseTool, "__init__", reject)
    inputs = []

    class Session:
        def get_inputs(self):
            return [SimpleNamespace(name="input")]

        def get_outputs(self):
            return [SimpleNamespace(name="output")]

        def run(self, names, feeds):
            inputs.append(feeds["input"])
            return [np.zeros((1, 3549, 85), dtype=np.float32)]

    detector = SessionYOLOX(Session())
    boxes, classes = detector(np.zeros((300, 400, 3), dtype=np.uint8))
    assert len(boxes) == len(classes) == 0
    assert inputs[0].shape == (1, 3, 416, 416)
    pose = SessionRTMPose(Session())
    pose.inference(np.zeros((256, 192, 3), dtype=np.float32))
    assert inputs[1].shape == (1, 3, 256, 192)
    assert pose.to_openpose is False


def test_video_worker_receives_same_npu_device_without_service_secret(monkeypatch, tmp_path):
    from classroom_vision.inference import video_isolation

    settings = config(device="npu", device_id=3, result_root=tmp_path)
    request = DetectionRequest(
        job_id=uuid4(),
        asset_id=uuid4(),
        input_sha256="a" * 64,
        model_version="fixture",
        media_kind="VIDEO",
    )
    engine = SimpleNamespace(settings=settings, cached_result=lambda _: ("b" * 64, None))
    captured = {}

    def worker(command, payload, *args, **kwargs):
        captured.update(payload["settings"])
        return 2

    monkeypatch.setattr(video_isolation, "run_bounded", worker)
    with pytest.raises(ValueError):
        video_isolation.isolated_video(engine, request)
    assert captured["device"] == "npu"
    assert captured["device_id"] == 3
    assert "service_token" not in captured


def test_cpu_cached_result_cannot_masquerade_as_npu_execution(tmp_path):
    from classroom_vision.inference.onnx_pose import OnnxPoseEngine

    request = DetectionRequest(
        job_id=uuid4(), asset_id=uuid4(), input_sha256="a" * 64, model_version="fixture"
    )
    engine = OnnxPoseEngine.__new__(OnnxPoseEngine)
    engine.settings = config(result_root=tmp_path)
    engine.hashes = {"detector": "b" * 64, "pose": "c" * 64}
    fingerprint, _ = engine.cached_result(request)
    (tmp_path / f"{request.job_id}.json").write_text(
        json.dumps({"request_digest": fingerprint, "schema_version": 2}), encoding="utf-8"
    )
    assert engine.cached_result(request)[1] is not None
    engine.settings = config(result_root=tmp_path, device="npu")
    with pytest.raises(ValueError, match="作业编号"):
        engine.cached_result(request)


@pytest.mark.parametrize("device", ["cpu", "gpu", "npu"])
def test_worker_preserves_only_selected_driver_environment(monkeypatch, device):
    from classroom_vision.inference.video_isolation import worker_environment

    values = {
        "LD_LIBRARY_PATH": "/fixture/driver/lib",
        "CUDA_VISIBLE_DEVICES": "2",
        "ASCEND_RT_VISIBLE_DEVICES": "3",
        "ASCEND_OPP_PATH": "/fixture/opp",
        "TOOLCHAIN_HOME": "/fixture/toolchain",
        "PYTHONPATH": os.pathsep.join(["/fixture/cann/site-packages", "/fixture/cann/tbe"]),
        "VISION_SERVICE_TOKEN": "never-forward",
        "DB_PASSWORD": "never-forward",
    }
    for key, value in values.items():
        monkeypatch.setenv(key, value)
    env = worker_environment(device)
    assert "VISION_SERVICE_TOKEN" not in env and "DB_PASSWORD" not in env
    assert ("LD_LIBRARY_PATH" in env) == (device != "cpu")
    assert ("CUDA_VISIBLE_DEVICES" in env) == (device == "gpu")
    assert ("ASCEND_RT_VISIBLE_DEVICES" in env) == (device == "npu")
    assert ("ASCEND_OPP_PATH" in env) == (device == "npu")
    assert ("TOOLCHAIN_HOME" in env) == (device == "npu")
    for path in ("/fixture/cann/site-packages", "/fixture/cann/tbe"):
        assert (path in env["PYTHONPATH"].split(os.pathsep)) == (device == "npu")
