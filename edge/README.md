# 本机边缘执行器

Python 3.12 独立进程、独立 `.venv` 和 `uv.lock`。只通过节点凭据访问 Java；不连接业务 MySQL。FastAPI 是本机推理服务，端口不面向浏览器。

在项目根目录完成 `python scripts/setup_demo.py` 后，分别启动 backend、vision，再运行 `python scripts/local_dev.py edge`。执行器每2秒推进一个阶段，同一节点有界执行一项任务。

状态：CLAIMED → CACHED → RESULT_READY → DELIVERED。每阶段写 SQLite WAL、synchronous=FULL；文件先写临时文件再替换。管理端不可达时仍可对已缓存输入运行模型。回传确认前保留结果；重复回传由 Java 校验作业、节点、租约、模型、统计和产物哈希。失效、取消、租约过期记录进入REJECTED；管理端可重新发放新租约，最多3次。

租约为600秒；推理前先续租，推理期间每30秒续租并持久化。在线自动续租不等于无限离线续租；超过租约的未接受结果仍由Java拒绝。已接受但确认丢失的结果会重试幂等确认。不能离线创建登录会话、领取新任务或进行云端教务修改。

诊断命令：`python scripts/local_dev.py edge --once --phase cache`、`--phase infer`、`--phase deliver`。自动验收见 `scripts/verify_edge_offline.py`，它只停止自己创建的Java进程，运行前需停用普通backend和edge进程。

SQLite和所有输入输出在 `runtime/edge/`，被Git忽略。当前共享开发令牌仅限本机；生产mTLS、证书轮换、磁盘配额及保留期仍未验收。

系统worker.lock限制同一Outbox一个执行器，并与备份维护工具互斥。节点最近90秒有认证活动才接受新作业。完整操作见docs/guides/试点基础完善与运维指南.md。
