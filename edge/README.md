# 本机边缘执行器

Python 3.12 独立进程、独立 `.venv` 和 `uv.lock`。只通过节点凭据访问 Java；不连接业务 MySQL。FastAPI 是本机推理服务，端口不面向浏览器。

在项目根目录完成 `python scripts/setup_demo.py` 后，分别启动 backend、vision，再运行 `python scripts/local_dev.py edge`。执行器每2秒推进一个阶段，同一节点有界执行一项任务。

状态：CLAIMED → CACHED → RESULT_READY → DELIVERED。每阶段写 SQLite WAL、synchronous=FULL；文件先写临时文件再替换。管理端不可达时仍可对已缓存输入运行模型。回传确认前保留结果；重复回传由 Java 校验作业、节点、租约、模型、统计和产物哈希。失效、取消、租约过期记录进入REJECTED；管理端可重新发放新租约，最多3次。

租约为600秒，当前没有长期续租策略；超过租约的离线结果不得作为有效作业结果回传。不能离线创建登录会话、领取新任务或进行云端教务修改。

诊断命令：`python scripts/local_dev.py edge --once --phase cache`、`--phase infer`、`--phase deliver`。自动验收见 `scripts/verify_edge_offline.py`，它只停止自己创建的Java进程，运行前需停用普通backend和edge进程。

SQLite和所有输入输出在 `runtime/edge/`，被Git忽略。当前共享开发令牌仅限本机；生产mTLS、证书轮换、磁盘配额及保留期仍未验收。
