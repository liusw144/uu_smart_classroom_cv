# 工程验证记录

日期：2026-11-02。阶段：工程基础检查完成，生产就绪为否。

| 检查 | 实际结果 |
| --- | --- |
| 根结构、边界与中文注释门禁 | 通过 |
| 根门禁回归测试 | 7 项通过 |
| Java 21 Maven verify | 通过；领域 5、认证用例 3、Spring 仓储代理回归 1，共 9 项 |
| Java 格式检查 | Spotless 通过 |
| Vue 两应用 | Prettier、类型检查、共享测试 3 项及双构建通过 |
| FastAPI | Ruff、格式检查、7 项服务/契约测试通过 |
| OpenAPI 与 JSON Schema | 文档合法性、模型请求及视觉契约漂移检查通过 |
| Compose 配置 | 使用 --no-interpolate 解析通过，未启动容器 |
| 独立代码检查 | 确认 1 项仓储代理启动阻塞；已复现失败并修复，回归通过 |
| 生产门禁 | 按预期拒绝：存在明确阻塞与缺失验收证据 |

合计实际运行 26 项自动测试通过。Java 的 4 项 MySQL/Redis HTTP 集成用例是另外的测试组，不包含在上述数量中，也没有冒充执行通过。

验证命令：根目录 python scripts/verify.py；后端另运行 mvn -s .mvn/settings.xml -B spotless:apply verify；Compose 运行 docker compose --env-file .env.example -f compose.yaml config --no-interpolate --quiet。后端命令在显式指定 JDK 21 的当前进程执行，没有改变全局 Java 配置。

本地完整输出位于忽略提交的 .harness/verify.log、.harness/java-format.log、.harness/repository-red.log 与 repository-green.log。重跑命令可以重新生成，不能只依据本文件推定其他机器或后续版本同样通过。

Git 状态：新建独立仓库，foundation 分支；未建立远端，未自动提交、推送或发布。

当前已知未验证项：本机 Docker Linux Engine 不可用，因此未执行容器镜像构建、MySQL/Redis HTTP 集成、真实模型/GPU、完整四模式部署、容量或恢复演练。GitHub CI 尚未触发，因为没有发布远端仓库。
