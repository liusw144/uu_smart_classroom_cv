# 可执行验证命令

根目录：`python scripts/quality_gate.py` 检查结构、依赖边界与中文说明性注释；`python -m unittest discover -s tests -v` 验证门禁本身。`python scripts/verify.py` 顺序运行全部本机基础检查，任一步失败即停止。

backend：在 Java 21 环境运行 `mvn -s .mvn/settings.xml -B verify`。本机真实数据库测试推荐从项目根目录运行 `python scripts/local_dev.py test`，它配置专用uu_classroom_test账号和Redis测试库。直接执行 `mvn -s .mvn/settings.xml -B -Pintegration verify` 时也必须注入这些环境配置；测试类拒绝其他库，遇占用的预留学校编号不会清理覆盖。

Java 格式由固定版本 Spotless 检查，修改后可运行 `mvn -s .mvn/settings.xml spotless:apply`。前端 `npm run format` 使用固定版本 Prettier，`npm run verify` 包含格式检查。格式化不能改变业务含义或替代测试。

frontend：`npm ci` 后 `npm run verify`，同时验证共享测试、类型和教师/管理双构建。

fastapi：`uv sync --frozen` 后运行 `uv run ruff check .`、`uv run ruff format --check .`、`uv run pytest`。从 contracts 读取协议样例验证，不访问数据库。

edge：独立执行同一组uv/ruff/pytest命令，不共享视觉服务环境。真实模型另在fastapi运行`uv run python scripts/verify_models.py`；服务完整启动后在frontend运行`node e2e/real-model.cjs`。离线验收先停普通backend和edge，再在根运行`python scripts/verify_edge_offline.py`，只控制脚本自己创建的管理端进程。详见真实模型本机演示指南。

Java集成测试按类使用独立JVM，隔离Sa-Token静态管理器与不同Spring上下文的连接生命周期；不能以复用进程导致的已停止Redis工厂错误代替真实业务验收。

视觉接口变更时，在 fastapi 运行 `uv run python scripts/export_openapi.py`，审查并提交 contracts/vision.openapi.json。CI 会检查 OpenAPI 合法性与实际应用契约一致；不能跳过变更审查直接覆盖快照。

容器：`docker compose --env-file deploy/.env -f deploy/compose.yaml config --quiet` 只检查配置；`up --build` 才实际构建启动。没有 Docker Engine 时，不把前者写为容器运行成功。

生产：`python scripts/quality_gate.py --production` 当前明确失败关闭，即使提供文件和正确哈希也不会放行，因为可信生产证据验证器尚未实现。后续必须交付受控构建、来源验证和实际生产验收；不能把本机业务测试当作生产发布授权。
