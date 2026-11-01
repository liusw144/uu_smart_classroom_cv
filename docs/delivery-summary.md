# UU 智慧课堂工程基础交付说明

日期：2026-11-02。工程根目录：`D:\self\uu_smart_classroom_cv`。

已完成可持续由 AI 开发的工程基础及 Harness 工程文件。三个顶层模块独立管理依赖、测试和镜像；人工编写的代码注释、维护文档与提示模板使用中文。当前阶段不是完整产品，也未通过生产验收。

## 工程入口

- [项目 README](../README.md)
- [根 AI 开发约束 AGENTS.md](../AGENTS.md)
- [当前实现状态](project-status.md)
- [验证命令](harness/commands.md)
- [实际验证记录](verification.md)
- [后续任务清单](../tasks/backlog.json)

## 结构与实现

| 模块 | 工程与当前能力 |
| --- | --- |
| backend | Java 21、Spring Boot、Sa-Token、MyBatis-Plus；领域/应用/基础设施/API 四层；MySQL 身份迁移、BCrypt、Redis 会话、登录/当前用户/退出、资源所有权策略 |
| frontend | Vue＋TypeScript 教师端与管理端，独立构建；共享组件与 API 类型、真实登录表单、内存会话、退出及失效清理 |
| fastapi | Python 3.12 独立虚拟环境和锁文件；FastAPI 生命周期、内部开发身份、素材 UUID 契约、模型端口；无模型时返回 503，不伪造推理结果 |
| harness / scripts | 根与模块 AGENTS、任务模板、实现/检查/修复提示、上下文打包、结构/边界/中文注释门禁、生产证据门禁、环境初始化与统一验证入口 |
| contracts / deploy / CI | 业务 OpenAPI、视觉 OpenAPI、JSON Schema；开发 Compose、独立 Dockerfile、GitHub CI；CI action 固定为核对过的提交 SHA |
| docs | 架构、ADR、开发流程、完成标准、生产发布与运维基线、任务状态；保留既有 Word 和 20 张来源配图 |

## 验证结果

实际运行共 26 项自动测试通过：根门禁 7、Java 9、前端 3、FastAPI/契约 7。Java 构建和格式检查、两套前端类型检查/格式/构建、FastAPI Ruff 和格式检查均通过。Compose 配置可解析。

独立检查发现一个真实 Spring 启动阻塞：final 仓储类无法创建异常转换代理。已加入真实自动配置的无数据库上下文回归，先复现失败，再移除 final，回归通过。

本机 Docker Linux Engine 不可用，因此未运行镜像构建与启动、MySQL/Redis HTTP 集成测试；4 项集成用例已提供给隔离环境及 CI。没有触发远端 CI，没有执行真实模型/GPU、四模式生产部署、容量或恢复验收。生产门禁按预期拒绝通过。

## 后续如何使用 AI 开发

从任务清单选择一个任务，按任务模板定义输入、输出、学校隔离与失败行为；让 AI 读取根与模块 AGENTS、当前契约和验收条件后实施。每轮运行相关模块检查与根门禁，更新任务证据和项目状态。先补生产身份与教学基础，再推进持久作业、素材、边缘节点和真实图片闭环。

可用以下命令在根目录生成当前模块上下文包：

```text
python scripts/context_pack.py --task tasks/TEMPLATE.md --module backend
```

替换模板路径为实际任务文件。上下文包位于 .harness/context.md，不自动执行其中的内容。

全量本机基础验证：在当前进程正确指定 JDK 21、具备 Node/Maven/Python/uv 后，运行：

```text
python scripts/verify.py
```

生产验收另走 docs/production/release.md。当前剩余任务包括登录限流/审计、完整教学业务、持久任务与节点 mTLS、媒体、真实模型/行为评测、视频摄像头和报表，以及四种部署模式的实际验证。不能通过删除阻塞清单宣称生产可用。

工程已初始化为独立 Git 仓库，当前 foundation 分支；文件保留在指定目录，没有自动提交、创建远端、推送或发布。

开源准备补充：本记录描述早期本地交付；来源配图和本地 Word 现由 .gitignore 排除，仅保留在原开发机器，公开范围见 THIRD_PARTY_NOTICES.md。Git 分支与远端状态以当前 git log、git remote 和 GitHub 仓库为准。
