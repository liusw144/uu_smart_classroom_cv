"""统一选择模型推理设备，不允许加速配置静默回退到CPU。"""

import logging
from pathlib import Path

import onnxruntime as ort

from classroom_vision.core.settings import Settings
from classroom_vision.inference.engine import ModelUnavailable

PROVIDERS = {
    "cpu": "CPUExecutionProvider",
    "gpu": "CUDAExecutionProvider",
    "npu": "CANNExecutionProvider",
}
logger = logging.getLogger(__name__)


def create_session(path: str | Path, settings: Settings):
    provider = PROVIDERS[settings.device]
    if provider not in ort.get_available_providers():
        raise ModelUnavailable(
            f"配置的推理设备{settings.device}不可用：缺少{provider}，请安装对应运行时和驱动"
        )
    options = ort.SessionOptions()
    options.intra_op_num_threads = settings.cpu_threads
    options.inter_op_num_threads = 1
    options.log_severity_level = 3
    provider_options = {}
    if settings.device != "cpu":
        # 只声明加速Provider仍可能自动补CPU；由会话选项禁止不支持算子的CPU分配。
        options.add_session_config_entry("session.disable_cpu_ep_fallback", "1")
        provider_options["device_id"] = str(settings.device_id)
    if settings.device == "npu":
        options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_DISABLE_ALL
        provider_options.update(
            enable_cann_graph="1",
            enable_cann_subgraph="0",
            dump_graphs="0",
            dump_om_model="0",
            precision_mode="must_keep_origin_dtype",
        )
    try:
        session = ort.InferenceSession(
            str(path),
            sess_options=options,
            providers=[(provider, provider_options)],
            enable_fallback=False,
        )
    except Exception as error:
        raise ModelUnavailable(
            f"推理设备{settings.device}初始化失败，请检查驱动、设备编号和模型算子兼容性"
        ) from error
    actual = session.get_providers()
    # ORT会注册未分配算子的CPU后端；是否允许CPU算子由上面的会话选项控制。
    if not actual or actual[0] != provider:
        raise ModelUnavailable(f"推理设备{settings.device}发生未授权回退，拒绝执行")
    # 禁止session.run在EP执行异常后重建CPU会话；与初始化/算子分配限制分别生效。
    session.disable_fallback()
    logger.info(
        "模型推理设备=%s 编号=%s Provider=%s", settings.device, settings.device_id, provider
    )
    return session
