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

`GET /health/live` 仅代表进程运行；`GET /actuator/health/readiness` 同时检查数据库与 Redis。当前接口为登录、查询当前身份和退出；教学业务未完成。登录限流、完整操作审计与生产初始化是上线前阻塞任务。

集成测试：配置独立 MySQL 8.4 与 Redis 后运行 `mvn -s .mvn/settings.xml -B -Pintegration verify`。不要针对生产库运行。当前 unit 构建通过不代表数据库联调已通过。
