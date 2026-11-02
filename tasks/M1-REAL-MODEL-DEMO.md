# M1-REAL-MODEL-DEMO 真实开源模型本机演示

状态：implemented_local，本机演示验收完成；不标记生产可用。

目的：用可追溯的开源模型和公开视频落实真实图片/视频、摄像头输入、AI辅助分析及边缘离线最小闭环。

范围：backend、frontend、fastapi与独立边缘进程、模型/服务启动脚本、contracts、迁移、测试和验证文档。设计与计划见docs/implementation/real-model-demo-design.md和real-model-demo-plan.md。

约束：源码与注释中文；三模块依赖隔离；Sa-Token认证及学校/资源归属不削弱；不上传原始视频到外部模型站；不提交权重、录像、凭据。生产身份和容量未验收前保持生产门禁拒绝。

完成标准：实际模型输出可查看、真实AI生成有来源、离线结果重启不丢失，测试、命令和产物记录完整；实体摄像头未测则明确说明。

交付：锁定公开来源与哈希；真实YOLOX/RTMPose ONNX CPU；图片/MP4与有界摄像头采样；Qwen3-0.6B本地报告；Java持久队列、租约、取消、幂等回传；独立edge和SQLite Outbox。浏览器通过真实链路验证图片5人、视频4帧31人次、5帧公开视频虚拟摄像头和AI；管理端真实停机、边缘重启及重连已验收。异常20×8000图片明确失败后下一项真实图片仍成功。

检查：根12、Java单元10/数据库集成25、前端5、FastAPI18、edge4、核心Chrome17及真实模型场景通过；生产门禁正确拒绝。细节、独立审查与未验收范围见docs/qa/real-model-verification-2026-11-02.md。来源和启动见docs/guides/真实模型本机演示指南.md。
