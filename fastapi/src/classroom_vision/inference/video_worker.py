"""资源隔离完成前只等待父进程握手，不导入解码与模型运行库。"""

import json
import sys


def main():
    message = sys.stdin.buffer.readline(65537)
    if not message.endswith(b"\n") or len(message) > 65536:
        return 3
    payload = json.loads(message)
    from classroom_vision.core.settings import Settings
    from classroom_vision.domain.models import DetectionRequest
    from classroom_vision.inference.onnx_pose import OnnxPoseEngine

    settings = Settings(
        **payload["settings"], service_token="isolated-worker-no-network-token-2026"
    )
    engine = OnnxPoseEngine(settings)
    try:
        engine._detect(DetectionRequest.model_validate(payload["request"]))
    except ValueError:
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
