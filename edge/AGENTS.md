# 边缘执行器约束

独立进程、独立虚拟环境；禁止访问业务 MySQL、引用其他模块源码或接受浏览器用户身份。仅向配置好的管理端领取本节点租约；输入按 UUID 缓存并校验哈希。SQLite Outbox 必须在推理与回传之前提交事务。断网只处理已缓存且有租约的任务；过期回传必须被管理端拒绝，不伪装成功。不得自动打开实体摄像头。所有注释使用中文。

验证：`uv sync --frozen`、`uv run ruff check .`、`uv run ruff format --check .`、`uv run pytest`。
