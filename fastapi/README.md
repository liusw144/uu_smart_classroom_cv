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
uv run uvicorn classroom_vision.main:create_app --factory --host 127.0.0.1 --port 5000
```

默认未启用模型时，`/health/ready`返回503，检测返回MODEL_UNAVAILABLE。按根目录`docs/guides/真实模型本机演示指南.md`运行setup_demo，再使用`python scripts/local_dev.py vision`可启用YOLOX/RTMPose ONNX CPU，实际加载成功后就绪为200。接口只接收素材UUID、哈希、版本和媒体类型，不接受任意路径/URL，未授权为401。

当前仅development/test内部凭据，明确禁止production启动。图片/短MP4解码、17点姿态、并发限额及独立edge Outbox已本机验证；生产mTLS、实体摄像头/RTSP、容器真实模型运行及容量未验收。默认不可用模式不导入OpenCV；Linux ONNX部署还需配置对应OpenCV系统库、FFmpeg、权重和缓存挂载，不能直接以当前本机结果宣称容器已可运行真实模型。
