# UU 智慧课堂视觉分析平台

本仓库提供课堂视觉分析平台的工程基础，采用统一代码、业务管理与边缘推理解耦的方向。当前处于 M0 开发阶段：教学业务、真实模型、持久任务和生产节点认证尚待交付，不应直接作为生产系统上线。

仓库：https://github.com/liusw144/uu_smart_classroom_cv 。原创代码与原创文档使用 [Apache License 2.0](LICENSE)，第三方内容说明见 [NOTICE](NOTICE) 与 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

```text
git clone https://github.com/liusw144/uu_smart_classroom_cv.git
cd uu_smart_classroom_cv
```

Windows 指南以 `D:\self\uu_smart_classroom_cv` 为本地示例路径，可按实际克隆位置调整。

## 模块地图

```text
backend/                  Java 21 模块化业务后端
  domain/                 领域规则与学校资源归属
  application/            用例、仓储与密码端口
  infrastructure/         MyBatis、数据库适配
  api/                    Spring Boot、Sa-Token、HTTP、迁移
frontend/                 独立 npm workspace
  apps/teacher/           教师端 5173
  apps/admin/             管理端 5172
  packages/shared/        共享会话、类型、组件
fastapi/                  独立 Python 项目及虚拟环境
  src/classroom_vision/   api、core、domain、inference
contracts/                跨模块 OpenAPI 与 JSON Schema
harness/                  AI 开发规则清单与提示模板
tasks/                    任务模板、依赖与验收记录
scripts/                  门禁、上下文打包、环境工具
deploy/                   本地 Compose 与部署模式说明
docs/                     架构、规范、决策、验收及原始资料
.github/workflows/        CI 模块验证与隔离数据库联调
```

## 开始开发

使用 IntelliJ IDEA 时，先阅读 [IDEA 打开运行与调试指南](docs/guides/IntelliJ_IDEA使用指南.md)。

先读 [AI 入口](AGENTS.md) 和 [命令说明](docs/harness/commands.md)。Python 3.12、Java 21、Node 22.18.0、Maven 3.9 以上和 uv 0.12.18 是已验证的开发工具组合。不要使用机器当前默认的 Java 8 构建。

```text
python scripts/quality_gate.py
python -m unittest discover -s tests -v
```

三个模块分别安装与验证，不能在根目录混装依赖。具体见 [后端](backend/README.md)、[前端](frontend/README.md)、[视觉服务](fastapi/README.md)。全量本机验证可用 `python scripts/verify.py`，该命令不代替数据库、容器和真实模型验证。

## 本地运行

Docker 引擎可用时，在根目录执行 `python scripts/init_dev_env.py`，然后编辑生成的 `deploy/.env`。若需首次建立本地管理员，临时将 BOOTSTRAP_ENABLED 改为 true；密码已随机生成在该文件中。不要把文件内容发到聊天、日志或 Git。

```text
docker compose --env-file deploy/.env -f deploy/compose.yaml --profile app up --build -d
```

打开本机 5173 教师端或 5172 管理端，使用学校代码 local-school、账号 admin 和自己 .env 中的初始化密码。创建后关闭初始化配置并重建业务容器。视觉服务需另加 `--profile vision`；它当前只用于接口联调，就绪状态为未加载模型。

该 Compose 仅用于本地开发，不是生产部署模板。云端、私有、单机和混合云的边界与待验证项见 [部署说明](deploy/README.md)。本轮 Docker 容器构建及数据库联调是否已验证，以 [实际验证记录](docs/verification.md) 为准。

## 持续使用 AI 开发

每次只选择一个任务，交给 AI 的输入为任务文件、根/模块 AGENTS.md、相关契约与验收条件。示例：先完成 tasks/backlog.json 中的 M1-JOBS，再接模型与媒体，不能一次要求生成全部生产代码并跳过证据。使用 `python scripts/context_pack.py --task tasks/TEMPLATE.md --module backend` 可生成有界上下文包。

所有人工维护的注释和文档使用中文。第三方依赖、协议字段和机器生成锁文件保留其格式。当前生产门禁 `python scripts/quality_gate.py --production` 应拒绝通过；只有关闭真实阻塞并附证据后才可进入发布评审。

详细状态见 [项目状态](docs/project-status.md)，开发顺序见 [任务清单](tasks/backlog.json)。资料来源与本地归档边界见[资料索引](docs/reference/README.md)；公众号配图与含图 Word 保留在开发者本机，不随本次开源提交发布。

## 参与贡献

提交问题或改进前请阅读 [贡献指南](CONTRIBUTING.md)、[社区行为准则](CODE_OF_CONDUCT.md)与[安全问题报告方式](SECURITY.md)。新增依赖和素材须核对其独立许可；仓库许可证不代替生产验收或第三方授权。
