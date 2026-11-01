# Java 模块约束

领域层 domain 不依赖 Spring、Sa-Token、SQL 和 HTTP。application 定义用例及仓储端口；infrastructure 实现数据库/密码等端口；api 是唯一启动与框架装配入口。

按业务能力在层内建包，不创建无边界的 common、utils、BaseService 大杂烩。控制器不直接拼接业务 SQL；数据库查询必须绑定学校和用户资源归属。新接口先更新 contracts/business.openapi.json，再补验证与实现。查询禁用学校与账号的检查不能由缓存会话替代。

使用 Java 21，在 backend 运行 `mvn -s .mvn/settings.xml -B verify`。完整 MySQL/Redis 联调另运行 `mvn -s .mvn/settings.xml -B -Pintegration verify`，数据库必须为隔离测试实例。禁止向生产库运行集成测试。Flyway 只添加新版本迁移。密码和请求体不能写日志。所有注释中文。
