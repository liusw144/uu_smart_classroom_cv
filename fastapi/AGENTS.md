# 视觉服务模块约束

src/classroom_vision/api 只做输入输出与内部身份校验，domain 定义数据契约，inference 放模型端口与适配器，core 管配置。后续边缘 Agent 建独立 agent 包与独立进程，不把持续执行任务塞进请求生命周期。

禁止连接 Java 业务 MySQL；只接受 asset_id，不接受用户提供的绝对路径或任意远程 URL。模型在 lifespan 加载，真实推理按硬件有界执行。不能把关键点分数当行为准确率，不能将重复检测次数当学生人数。

开发凭据不可作为生产节点认证。当前生产模式明确拒绝启动，直到独立节点 mTLS 与生产验收完成。已加载通用ONNX权重不代表课堂精度验收。禁止为了让健康检查变绿而返回伪造模型状态。

在 fastapi 使用 `uv sync --frozen`、`uv run ruff check .`、`uv run ruff format --check .`、`uv run pytest`。Python 包和环境只在本模块。注释和文档字符串全部中文。
