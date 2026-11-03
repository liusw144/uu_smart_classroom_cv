# FastAPI 独立视觉服务

Python 3.12 与 uv，虚拟环境位于当前模块 .venv，不复用前后端依赖。

```text
uv sync --frozen
uv run ruff check .
uv run ruff format --check .
uv run pytest
```

复制 .env.example 为 .env，注入不少于 32 字符的 VISION_SERVICE_TOKEN，再运行：

```text
uv run --frozen python -m classroom_vision.server
```

默认未启用模型时，`/health/ready`返回503，检测返回MODEL_UNAVAILABLE。按根目录`docs/guides/真实模型本机演示指南.md`运行setup_demo，再使用`python scripts/local_dev.py vision`可启用YOLOX/RTMPose ONNX CPU，实际加载成功后就绪为200。接口只接收素材UUID、哈希、版本和媒体类型，不接受任意路径/URL，未授权为401。

模型推理可用`VISION_DEVICE=cpu|gpu|npu`配置，默认cpu；gpu使用NVIDIA CUDA，npu使用华为昇腾CANN，`VISION_DEVICE_ID`指定设备编号。GPU/NPU使用runtimes下各自的虚拟环境和锁文件，不能把三个ONNX Runtime发行包混装。具体安装、IDEA解释器、Linux驱动及验证边界见[推理设备配置](../docs/guides/推理设备配置.md)。

development/test用于本机验证；staging入口强制原生双向TLS及凭据文件，production仍在完整生产验收前拒绝启动。不得直接用不带TLS参数的uvicorn命令替代受控staging入口。

Linux只读容器中真实YOLOX/RTMPose、headless OpenCV与12秒视频已实际运行，并验证超时、内存、文件上限及子进程清理。可用scripts/verify_linux.py在非root且具有cgroup上限的容器内重做验证。结果卷只允许一个进程，重启时在独占锁内清理崩溃暂存。课堂精度、目标硬件容量、系统依赖漏洞整改仍是生产阻塞，详见根项目状态和最新QA。实体摄像头/RTSP尚未验收。
