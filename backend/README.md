# Java 业务后端

Java 21 与 Maven 3.9 以上。四层 Maven 模块均独立编译，api 生成启动 JAR。Spring Boot、Sa-Token、MyBatis-Plus 与插件版本由父 POM 固定；不导入 Sa-Token 的旧 Spring Boot BOM 覆盖本项目版本。

```powershell
$env:JAVA_HOME='D:\software\java\jdk21'
$env:Path=$env:JAVA_HOME+'\bin;'+$env:Path
mvn -s .mvn/settings.xml -B verify
```

使用自己的 JDK 21 路径，不修改全局环境变量。数据库与 Redis 启动后，在当前进程设置 DB_PASSWORD、REDIS_PASSWORD 以及可选 DB_URL，再运行：

```text
java -jar api/target/classroom-api-0.1.0-SNAPSHOT.jar --spring.profiles.active=dev
```

本地首次创建管理员时显式设置 BOOTSTRAP_ENABLED=true 与 12 至 64 字符的 BOOTSTRAP_PASSWORD；学校代码 local-school，账号 admin。密码无默认值，首次创建后关闭初始化；已有账号不会被重置。生产禁止启用该功能。

`GET /health/live` 仅代表进程运行；`GET /actuator/health/readiness` 同时检查数据库与 Redis。当前已实现本校账号、个人资料/头像/改密、教务CRUD、公告、目录档案、素材、失败作业和教务模板报告。接口见contracts/business.openapi.json。登录限流、完整安全审计、真实推理与生产初始化仍是上线前阻塞。

本机使用项目根目录的 `python scripts/local_dev.py init`、`build`、`test`、`backend` 子命令自动注入对应专用账号和环境。集成测试只接受显式配置的 `uu_classroom_test`，不要针对业务库运行；仅单元构建通过不能替代数据库联调。运行与本次实际证据见docs/guides/本机最小版本运行指南.md及docs/qa/local-mvp-verification-2026-11-02.md（路径相对项目根目录）。
