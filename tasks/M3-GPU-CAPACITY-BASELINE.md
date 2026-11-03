# M3-GPU-CAPACITY-BASELINE 容量口径记录

状态：implemented_documentation，GPU容量任务仍为pending。

需求来源与目的：用户对容量示例回复“可以”，先将其保存为可调整压测假设，避免把300课堂误当300片段。

涉及模块与允许修改范围：ops配置、容量说明、运维导航与任务备注；不修改应用执行逻辑或生产门禁。

前置任务：M0-FOUNDATION。

输入输出契约与兼容策略：新增ops/config/capacity-baseline.json，状态planning_assumption，contractual_sla=false，实测值及GPU数量为空；现有benchmark尚未接入此配置。

租户、角色、资源归属与数据保留：10校负载均分仅为规划假设；不访问真实摄像头、录像或身份数据，保留期未设定。

失败、重试、取消与幂等：本轮没有作业执行。后续父任务编排需单独实现片段重试、取消及幂等聚合。

验收用例及验证命令：JSON解析及独立算术核对；根quality_gate和git diff --check。

执行记录：2026-11-03，JSON解析及独立算术断言通过，python scripts/quality_gate.py退出0，git diff --check退出0。未因文档和规划参数变更重跑应用联调，也未运行GPU负载或访问实体设备。

完成内容、未验证项、后续任务：每批6000片、平均每校600片；理论下限约3.33片/秒、按70%利用率规划约4.76片/秒。未测GPU、未生成容量通过结论，尚需完整录像调度、GPU执行与真实负载验收。
