# M1-LOCAL-MVP 本机最小业务闭环

状态：本机最小范围已完成验证；不具备生产发布条件。

目的：实现审核中缺失的可本机验证业务，使用本机MySQL/Redis。

范围：backend、frontend、fastapi边界修复、contracts、scripts、测试与说明。设计和步骤见docs/implementation/local-mvp-design.md及local-mvp-plan.md。

权限：学校管理员只管理本校；教师只管理自己的教务和素材；平台运维不自动获得课堂数据。所有请求以服务端活跃身份为准。

失败：无模型不生成成功记录；有引用的数据删除返回冲突；跨范围资源返回不可见；不可用环境不标记通过。

验收及执行记录：随本轮实际验证补充，不以预期代替结果。

执行结果：根10、Java单元9、真实数据库集成21、前端5、FastAPI11、真实浏览器17、旧审计反例22通过。详细命令与范围见docs/qa/local-mvp-verification-2026-11-02.md。未交付算法/视频/AI/边缘等继续留在任务清单，本轮未提交或推送。
