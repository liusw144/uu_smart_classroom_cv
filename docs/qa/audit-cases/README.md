# 审计复现说明

本目录保留2026-11-02的审计检查，不表示缺陷已修复，也没有自动加入当前CI。

- `test_runtime_boundaries.py`：从项目根目录按上级报告的命令运行；当前预期16通过、6失败，整改后应迁入各模块的常规测试并变为通过。
- `browser-audit.cjs`：需要可用的Playwright包及Windows Chrome；先在frontend分别启动teacher/admin的Vite到15173/15172端口，再运行脚本。API全部由fixture拦截，不是后端E2E。脚本截图和结果写在自身目录；归档初始结果在上级evidence内。不要在业务模块混装审计依赖。
- `AuditJavaBoundaries.java`：JDK21源文件模式，类路径需包括backend/domain及application的target/classes和backend/api/target/classroom-api-0.1.0-SNAPSHOT.jar中BOOT-INF/lib的依赖。只使用内存仓储及低成本BCrypt测试参数，不用于SQL或性能评估。
- `evidence`内日志保留首次执行位置，可能包含本机绝对路径；迁机后的新结果应记录新提交和环境，不能复用旧日志宣称新版本通过。

测试数据均为人工构造，无真实用户凭据或课堂素材。审计中生产证据反例只写入pytest临时目录，没有生成真实release-evidence.json。
