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

`/health/live` 返回 200；`/health/ready` 当前返回 503，因为真实模型未提供。合法内部检测请求也返回 503 MODEL_UNAVAILABLE；匿名请求 401，未知字段 422。接口只接收素材 ID 与任务信息，不读取任意用户路径。

当前环境只有 development/test 的内部凭据认证，明确禁止 production 模式启动。真实模型、节点 mTLS、Agent、持久 Outbox、资源并发与媒体解码由后续任务接入。不会用 mock 结果代替实际检测。
