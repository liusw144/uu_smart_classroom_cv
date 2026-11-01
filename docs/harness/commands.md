# 可执行验证命令

根目录：`python scripts/quality_gate.py` 检查结构、依赖边界与中文说明性注释；`python -m unittest discover -s tests -v` 验证门禁本身。`python scripts/verify.py` 顺序运行全部本机基础检查，任一步失败即停止。

backend：在 Java 21 环境运行 `mvn -s .mvn/settings.xml -B verify`。需要独立数据库时运行 `mvn -s .mvn/settings.xml -B -Pintegration verify`；必须配置 DB_URL 指向 classroom_test，脚本在启动测试类时阻止其他库。

Java 格式由固定版本 Spotless 检查，修改后可运行 `mvn -s .mvn/settings.xml spotless:apply`。前端 `npm run format` 使用固定版本 Prettier，`npm run verify` 包含格式检查。格式化不能改变业务含义或替代测试。

frontend：`npm ci` 后 `npm run verify`，同时验证共享测试、类型和教师/管理双构建。

fastapi：`uv sync --frozen` 后运行 `uv run ruff check .`、`uv run ruff format --check .`、`uv run pytest`。从 contracts 读取协议样例验证，不访问数据库。

视觉接口变更时，在 fastapi 运行 `uv run python scripts/export_openapi.py`，审查并提交 contracts/vision.openapi.json。CI 会检查 OpenAPI 合法性与实际应用契约一致；不能跳过变更审查直接覆盖快照。

容器：`docker compose --env-file deploy/.env -f deploy/compose.yaml config --quiet` 只检查配置；`up --build` 才实际构建启动。没有 Docker Engine 时，不把前者写为容器运行成功。

生产：`python scripts/quality_gate.py --production` 需要无阻塞、完整证据清单与文件哈希。该检查只能确认材料齐全与未变，不能确认材料真实性或取代人工发布评审。
