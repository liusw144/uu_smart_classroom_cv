# 智慧课堂生产工程基础实施计划

> 执行方式：依照 superpowers:executing-plans 在当前会话逐项实施，使用测试验证关键行为，结束时进行独立代码检查。用户已明确授权生成工程文件，不再重复申请创建许可。

**目标：** 在指定根目录建立可持续由 AI 开发的工程基础，提供可执行质量门禁、三个隔离模块及真实验证记录。

**架构：** Java 模块化业务后端、Vue 教师与管理双应用、FastAPI 内部推理服务分别构建和部署。跨模块只通过版本化契约通信；控制面、节点身份、业务数据归属分开处理。

**技术栈：** Java 21、Spring Boot 4.1.2、Sa-Token 1.46.0、MyBatis-Plus 3.5.18；Vue 3、TypeScript、Vite；Python 3.12、FastAPI、Pydantic 2；MySQL 8.4 与 Redis。

**设计依据：** ../../architecture/hybrid-cloud-design.md；用户要求所有人工编写的代码注释使用中文，前后端及 FastAPI 依赖隔离。

## 全局约束

- 工程根目录固定为 D:\self\uu_smart_classroom_cv，不修改全局 Java/Python 环境。
- backend、frontend、fastapi 分别有构建描述、锁定策略、测试、容器及模块 AGENTS.md。
- Java 领域与应用层不依赖 Spring 或持久化实现；Python 不连接 Java 业务库；浏览器只访问 Java 业务接口。
- Sa-Token 负责用户会话及动作权限；租户与教师资源归属由业务代码约束。
- 没有真实模型时，推理接口明确返回不可用；不伪造算法能力、检测结果或生产验收。
- 注释、文档字符串和维护文档使用中文；机器标识、协议字段、库名和自动生成的第三方文件保留标准格式。
- 当前范围是生产工程基础，不等同于课堂业务全部完成或可直接上线。

## 重点检查

- 跨租户及教师访问其他教师资源必须拒绝。
- FastAPI 未配置模型时就绪检查失败，受保护接口不能匿名调用。
- 非法素材路径/URL不能混入素材 ID 契约。
- 门禁能发现跨模块引用、英文注释和缺失生产证据。
- 两个前端分别构建；会话不写入浏览器永久存储。

## 任务 1 工程地图与 Harness

文件：根 AGENTS.md、harness/manifest.json、docs/harness/*.md、scripts/quality_gate.py、tests/test_quality_gate.py、任务模板与 CI。

接口：门禁命令 `python scripts/quality_gate.py` 返回 0 表示工程结构检查通过；`--production` 在生产证据不齐时返回非零。

- [x] 写入模块地图、开发规则、完成定义、任务模板与生产缺口。
- [x] 先测试非法跨层引用及英文注释能被发现，再实现检查器。
- [x] 运行根测试与门禁，记录结果。

## 任务 2 Java 业务基础

文件：backend/pom.xml 及 domain、application、infrastructure、api 四模块，Flyway V1、权限与认证测试。

接口：`/health/live`、`/actuator/health/readiness`、`/api/v1/auth/login`、`/api/v1/auth/me`、`/api/v1/auth/logout`。成功 envelope 为 data，错误 envelope 为 error，字段使用 snake_case。

- [x] 先写所有权拒绝、登录失败、正确登录及不启用账户的测试。
- [x] 实现纯 Java 领域策略、认证用例、MyBatis 仓储、BCrypt、Sa-Token Redis 与 HTTP 适配。
- [x] 提供无固定密码的开发初始化配置，非开发环境禁止启用。
- [x] 使用项目级 JDK 21 运行 `mvn verify`，记录真实结果。

## 任务 3 FastAPI 推理边界

文件：fastapi/pyproject.toml、uv.lock、src/classroom_vision/{api,core,domain,inference}、tests。

接口：`/health/live` 为进程健康，`/health/ready` 反映真实模型；`/internal/v1/detections` 接收 job_id、asset_id、输入哈希与模型版本。

- [x] 先写匿名拒绝、未知字段拒绝、模型不可用返回 503、生产模式拒绝开发认证的测试。
- [x] 使用 lifespan 管理资源，定义推理端口与明确不可用的初始适配器。
- [x] 锁依赖，运行 lint、pytest 与契约验证。

## 任务 4 Vue 双应用与共享契约

文件：frontend/apps/{teacher,admin}、frontend/packages/shared、根 npm workspace、package-lock.json。

接口：共享 API 客户端只访问相对路径 /api；令牌保存在内存，会话错误清除状态。

- [x] 先写会话存储与失败响应测试。
- [x] 实现双应用、登录表单、会话页面、统一样式与类型契约。
- [x] 运行共享测试、类型检查与两个应用构建。

## 任务 5 部署与交付验证

文件：deploy/compose.yaml、四模式说明、各模块 Dockerfile、CI、README、验证记录。

- [x] 提供本地仅监听 loopback 的开发 Compose，凭据由环境注入。
- [x] 导入现有需求文档和全部源图，标明历史方案的优先级。
- [x] 执行全部本机可运行验证；Docker/真实模型等不能运行的检查明确列出。
- [x] 独立审查并修复重要发现；更新状态与任务清单。

## 明确留待后续

真实模型与规则、持久作业租约、边缘 mTLS 注册和轮换、媒体存储、完整教学业务、视频与摄像头、容量与灾备验收分别建立任务。本轮不以空实现冒充这些能力。

实际完成范围与未执行的外部依赖验收见 ../../verification.md；M0 不标记生产版本。
