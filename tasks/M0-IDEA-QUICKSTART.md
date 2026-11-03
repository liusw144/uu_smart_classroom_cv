# M0-IDEA-QUICKSTART 统一 IDEA 快速开始

状态：implemented，文档整合已完成。

需求来源与目的：按用户要求，把Spring Boot、Vue双前端、FastAPI及edge的IDEA导入和启动说明合并到根目录Markdown。

涉及模块与允许修改范围：README.md、快速开始.md、原IDEA指南，以及任务与项目状态记录；不修改应用代码、数据库或运行中的服务。

前置任务：M0-FOUNDATION；使用现有本机启动工具及已建立的独立模块。

输入输出契约与兼容策略：README快速开始作为完整说明；原指南及根目录快速开始保留导航入口。接口、源码边界与依赖锁文件均无变化。

租户、角色、资源归属与数据保留：仅说明本机配置来源，不记录真实凭据；浏览器仍通过Java访问业务API。

失败、重试、取消与幂等：文档涵盖缺少环境变量、SDK与源码根错误、重复启动、端口及JAR占用。实际进程操作由开发者明确执行。

验收用例及验证命令：本地Markdown链接、代码块、模块路径、启动动作和npm脚本静态核对；python scripts/quality_gate.py；git diff --check；在fastapi执行uv run python -c导入create_app。

执行记录：2026-11-02，基础门禁退出0、passed=true；FastAPI实际导入退出0；文档检查与git diff --check退出0。检查结果保存在Git忽略的.harness/idea-quickstart/checks.json。

完成内容：根README统一导入、独立SDK、首次初始化、六服务启动、原生调试与常见问题；旧指南指向统一入口。

未验证项：本轮不重新启动所有服务，不宣称IDEA界面索引或断点调试已完成验收；文档整合不改变生产就绪状态。
