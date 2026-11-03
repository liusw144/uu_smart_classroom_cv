# UU 智慧课堂视觉分析平台

本仓库提供课堂视觉分析平台，采用统一代码、业务管理与边缘推理解耦。当前具备本机教务、YOLOX/RTMPose真实图片与短视频、本地Qwen、持久作业和边缘短时离线回传；节点双向TLS、磁盘预算、故障重分配及Linux容器已有受控验证。课堂精度、目标硬件容量、混合云录像留校及镜像依赖风险仍阻塞生产，不能直接上线。

仓库：https://github.com/liusw144/uu_smart_classroom_cv 。原创代码与原创文档使用 [Apache License 2.0](LICENSE)，第三方内容说明见 [NOTICE](NOTICE) 与 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

```text
git clone https://github.com/liusw144/uu_smart_classroom_cv.git
cd uu_smart_classroom_cv
```

Windows 指南以 `D:\self\uu_smart_classroom_cv` 为本地示例路径，可按实际克隆位置调整。

## 快速开始

本节合并 IDEA 导入、独立运行环境、首次初始化及调试步骤。以 Windows/PowerShell 为例，**IDEA 打开整个项目根目录，Java、前端、FastAPI、edge 分别使用自己的构建环境**。菜单名称因版本与插件有所不同，可用 `Ctrl+Shift+A` 搜索操作。

### 1. 工具与插件

| 项目 | 配置 |
| --- | --- |
| Java | JDK 21；本机示例 `D:\software\java\jdk21` |
| Maven | 3.9 以上；本机示例 `D:\software\apache-maven-3.9.12` |
| Node.js | 22.x，至少22.12；本机已验证22.18.0 |
| Python | FastAPI与edge各自使用Python 3.12虚拟环境 |
| uv | 已验证0.12.18，能够在终端执行 `uv --version` |
| 基础设施 | 本机MySQL 8.4、Redis；本机方案默认3306、6379 |
| 视频工具 | 真实视频演示需要FFmpeg，可在终端执行 `ffmpeg -version` |

在 IDEA 的 `Settings → Plugins` 确认 Java/Maven、Python、JavaScript/TypeScript、Vue 支持可用。Spring 专用运行模板不可用时，可以使用普通 Application 配置；前端运行也可使用 IDEA Terminal。如果没有Python模块或运行配置选项，先检查Python插件是否可用；仍不可用时，可按第7节在Terminal启动。

### 2. 打开仓库并安装依赖

1. `File → Open`，选择 `D:\self\uu_smart_classroom_cv`。
2. 使用根目录作为同一个 IDEA 项目，随后分别配置下列模块。
3. 在 IDEA Terminal 执行一次依赖安装；已完成安装时可跳过。

```powershell
Set-Location 'D:\self\uu_smart_classroom_cv\frontend'
npm.cmd ci

Set-Location 'D:\self\uu_smart_classroom_cv\fastapi'
uv sync --frozen

Set-Location 'D:\self\uu_smart_classroom_cv\edge'
uv sync --frozen
```

`uv`依据各模块的`.python-version`创建Python 3.12环境；缺少该版本时可先运行 `uv python install 3.12`。FastAPI和edge分别生成自己的`.venv`，前端依赖只安装在frontend工作区。

### 3. 导入 Spring Boot 后端

1. 在项目树找到 `backend/pom.xml`，右键选择 `Add as Maven Project`，或在 Maven 工具窗口添加该文件。
2. 等待 Maven 导入并刷新，确认出现 `domain`、`application`、`infrastructure`、`api` 四个子模块。
3. `File → Project Structure → Project`：Project SDK选择Java 21，语言级别21；Java子模块继承该SDK。
4. `Settings → Build, Execution, Deployment → Build Tools → Maven`：Maven home选择本机Maven，User settings file选择 `backend/.mvn/settings.xml`；Importer JDK与Runner JRE均选择Java 21。
5. 可在 Maven 面板刷新依赖，或创建下表中的 Maven 检查配置。

| IDEA运行配置字段 | 值 |
| --- | --- |
| 类型 | Maven |
| 名称 | 后端构建检查 |
| Working directory / Maven project | `$PROJECT_DIR$/backend` |
| Command line | `-s .mvn/settings.xml -B verify` |
| JRE | Java 21 |

Spring Boot入口是 `backend/api/src/main/java/cn/uusmart/classroom/api/ClassroomApplication.java`，主类为 `cn.uusmart.classroom.api.ClassroomApplication`。构建应从backend聚合工程执行，确保四个模块一起解析。

### 4. 导入 Vue 教师端和管理端

frontend是一个npm workspace，教师端和管理端共用根锁文件与shared包。

1. 保持 `frontend` 在当前项目中，打开 `frontend/package.json`。
2. 在 `Settings` 中搜索 `Node.js`，选择Node 22解释器和对应npm；本机Node路径示例为 `C:\nvm4w\nodejs\node.exe`。
3. 在npm工具窗口找到脚本，或用 `Run → Edit Configurations → + → npm` 创建两份配置。

| 配置 | package.json | Command | Scripts | 地址 |
| --- | --- | --- | --- | --- |
| 教师端 | `$PROJECT_DIR$/frontend/package.json` | run | dev:teacher | http://127.0.0.1:5173 |
| 管理端 | `$PROJECT_DIR$/frontend/package.json` | run | dev:admin | http://127.0.0.1:5172 |

在frontend终端的等价命令为 `npm.cmd run dev:teacher` 和 `npm.cmd run dev:admin`。日常统一启动脚本见第7节，带有固定端口检查。浏览器的`/api`由Vite代理到Java 8089，业务请求不直连Python。

### 5. 导入 FastAPI 与 edge Python 模块

FastAPI必须以整个 `fastapi` 文件夹作为一个Python模块。IDEA已经识别该模块时，直接检查配置；未识别时，在 `File → Project Structure → Modules → +` 中导入现有源码/创建Python模块，选择 `fastapi` 文件夹，保留已有源码与pyproject配置。

| 设置 | FastAPI | edge |
| --- | --- | --- |
| Content root | `$PROJECT_DIR$/fastapi` | `$PROJECT_DIR$/edge` |
| 独立解释器 | `fastapi/.venv/Scripts/python.exe` | `edge/.venv/Scripts/python.exe` |
| Sources | `fastapi/src` | `edge/src` |
| Test Sources | `fastapi/tests` | `edge/tests` |
| Excluded | 各自的`.venv`、`.pytest_cache`、`.ruff_cache` | 各自的`.venv`、`.pytest_cache`、`.ruff_cache` |

在 `Project Structure → SDKs` 添加Python SDK，选择Existing environment/已有解释器；再在对应模块的Dependencies/Module SDK中选中该SDK。FastAPI本机解释器完整路径为：

```text
D:\self\uu_smart_classroom_cv\fastapi\.venv\Scripts\python.exe
```

本机FastAPI SDK名称为 `Python 3.12 (uu_smart_classroom_cv)`；新机器名称可自定，解释器路径和版本必须匹配。edge应添加自己的独立SDK。

新增ops运维工具模块也使用自己的Python 3.12环境：在ops运行uv sync --frozen，IDEA选择ops/.venv/Scripts/python.exe，标记ops/src为Sources。证书工具、分段与容量命令见ops/README.md；候选容器操作见deploy/staging/README.md，不与FastAPI共用解释器。

**`src`才是源码根目录，`classroom_vision`是包。** `.venv`、`Scripts`、`Lib/site-packages`不能分别导入成项目模块。模块写着某个SDK名称但SDK列表没有对应解释器，也会导致导入标红。修正后重开项目，让IDEA重新加载与索引。全仓库的Project SDK仍为Java 21，Python SDK配置在各Python模块上。

检查实际导入可在FastAPI目录运行：

```powershell
Set-Location 'D:\self\uu_smart_classroom_cv\fastapi'
uv run python -c "from classroom_vision.main import create_app; print('FastAPI导入正常')"
```

### 6. 本机数据库、配置与首次初始化

当前机器已有配置和数据时，保留现有 `.env.local`，直接进行第7节。新机器先启动本机MySQL/Redis，在项目根目录新建Git忽略的`.env.local`，把以下占位值改为自己的本机配置：

```dotenv
DB_HOST=127.0.0.1
DB_PORT=3306
DB_ADMIN_USER=填写本机MySQL管理账号
DB_ADMIN_PASSWORD=填写本机MySQL管理密码
REDIS_HOST=127.0.0.1
REDIS_PORT=6379
REDIS_PASSWORD=
REDIS_DATABASE=14
TEST_REDIS_DATABASE=15
LOCAL_JAVA_HOME=D:\software\java\jdk21
```

Redis密码与端口按自己的实例填写，14和15应作为项目业务/测试用途。然后从根目录执行：

```powershell
Set-Location 'D:\self\uu_smart_classroom_cv'
python scripts/local_dev.py init
python scripts/local_dev.py build
```

初始化准备 `uu_classroom_local` 和 `uu_classroom_test`，生成随机业务账号、初始化密码与服务令牌并保存到`.env.local`；Java启动时执行Flyway迁移。`DB_ADMIN_*`只供初始化工具使用，不作为业务后端数据库账号。根启动工具是标准库脚本，终端默认Python版本可以与业务虚拟环境不同。MySQL客户端应在PATH中，或在`.env.local`配置`MYSQL_EXE`指向自己的mysql.exe。

需要真实图片、视频和本地AI时，在启动服务之前执行：

```powershell
python scripts/setup_demo.py
# 已下载后只核对文件完整性：
python scripts/setup_demo.py --verify-only
```

setup_demo下载并校验模型/便携运行时、准备公开视频，写入真实演示开关及节点/AI开发凭据。文件位于Git忽略的runtime；首次准备需要网络和磁盘空间。

### 7. 在 IDEA 中启动整套项目

在IDEA Terminal为各服务分别开终端标签，工作目录均为项目根目录。以下命令会持续运行，逐条放到不同终端中。

| 顺序 | 服务 | 根目录命令 | 地址/作用 |
| --- | --- | --- | --- |
| 1 | Spring Boot | `python scripts/local_dev.py backend` | Java业务API，8089 |
| 2 | 本地Qwen | `python scripts/local_dev.py ai` | 本地AI，18188；真实演示已准备时使用 |
| 3 | FastAPI | `python scripts/local_dev.py vision` | 视觉服务，5000 |
| 4 | edge | `python scripts/local_dev.py edge` | 出站领取任务；真实推理需要此进程 |
| 5 | 教师端 | `python scripts/local_dev.py teacher` | http://127.0.0.1:5173 |
| 6 | 管理端 | `python scripts/local_dev.py admin` | http://127.0.0.1:5172 |

Java启动成功后，在另一个根目录终端执行一次 `python scripts/local_dev.py seed`，准备本机教师及对应模式的模型档案。仅体验教务基础模式时，可只运行backend和两个前端；真实推理需完成setup_demo并运行vision、edge，本地AI报告还需要ai。

学校代码为`local-school`。管理员账号`admin`，密码从`.env.local`的`BOOTSTRAP_PASSWORD`查看；教师账号/密码分别从`TEACHER_USERNAME`、`TEACHER_PASSWORD`查看。配置文件不随Git公开。

Java健康检查为 http://127.0.0.1:8089/actuator/health/readiness ，视觉就绪检查为 http://127.0.0.1:5000/health/ready 。未启用真实模型时视觉返回503是预期；真实演示需实际加载模型后才返回200。节点开始领取任务后，管理端“边缘节点”页可看到在线状态。

`.env.local`由本项目启动工具读取，IDEA的普通Application、Python运行配置和Spring Boot不会自动把该文件完整转换为环境变量。首次运行优先使用上表统一命令。

### 8. IDEA 原生断点调试

**Java：** 添加Spring Boot配置或普通Application配置，Main class填 `cn.uusmart.classroom.api.ClassroomApplication`，Use classpath of module选`classroom-api`，JRE选21，Working directory填`$PROJECT_DIR$/backend`。环境变量按本机模式填写：

| 环境变量 | 值或来源 |
| --- | --- |
| SPRING_PROFILES_ACTIVE | dev |
| SERVER_ADDRESS | `127.0.0.1`，本机调试仅监听回环地址 |
| DB_URL | `jdbc:mysql://127.0.0.1:3306/uu_classroom_local?serverTimezone=UTC&characterEncoding=UTF-8`，端口按本机配置调整 |
| DB_USER、DB_PASSWORD | `.env.local`的业务账号字段 |
| REDIS_HOST、REDIS_PORT、REDIS_PASSWORD | `.env.local`的同名字段；密码为空时也需提供空值 |
| REDIS_DATABASE | `.env.local`的业务库编号，通常14 |
| CLASSROOM_REDIS_COMPATIBILITY_MODE | 本机Redis5使用true |
| BOOTSTRAP_ENABLED、BOOTSTRAP_PASSWORD | 本机首次初始化设true，密码取同名字段 |
| CLASSROOM_ASSETS_ROOT | `D:\self\uu_smart_classroom_cv\runtime\assets` |
| CLASSROOM_ARTIFACT_ROOT | `D:\self\uu_smart_classroom_cv\runtime\artifacts` |
| MODEL_LOCK_PATH | `D:\self\uu_smart_classroom_cv\models\demo-assets.lock.json` |
| VISION_URL、VISION_SERVICE_TOKEN | `http://127.0.0.1:5000`及`.env.local`同名令牌 |
| CLASSROOM_PIPELINE_ENABLED | 真实演示true，基础教务模式false |
| EDGE_NODE_ID、EDGE_SERVICE_TOKEN | 真实演示使用`.env.local`同名字段 |
| AI_SERVICE_URL、AI_SERVICE_TOKEN | 本地AI报告使用`.env.local`同名字段 |

原生调试配置与终端启动的同一服务择一运行，避免占用相同端口。Java参数对应 `backend/api/src/main/resources/application.yaml`，本机路径需与其他服务的存储位置一致。

**FastAPI：** 添加Python配置，选择Module name模式，配置如下，然后点击Debug。

| 字段 | 值 |
| --- | --- |
| Module name | uvicorn |
| Parameters | `classroom_vision.main:create_app --factory --host 127.0.0.1 --port 5000` |
| Python interpreter | FastAPI自己的Python 3.12 `.venv` |
| Working directory | `$PROJECT_DIR$/fastapi` |
| Environment variables | `VISION_APP_ENV=development`；`VISION_SERVICE_TOKEN`取`.env.local`同名值；真实模式`VISION_ENGINE=onnx`，基础模式`VISION_ENGINE=unavailable` |
| PYTHONPATH选项 | 勾选添加content roots/source roots |

调试使用单进程，可在`classroom_vision/api/routes.py`打断点；自动重载会产生子进程，需确认调试器实际连接到处理请求的进程。图片/视频作业通过浏览器提交到Java，再由edge调用FastAPI。

**edge：** Python配置选择Module name `classroom_edge.worker`，解释器选edge自己的`.venv`，工作目录`$PROJECT_DIR$/edge`。环境需要`EDGE_STATE_ROOT`指向根runtime/edge、`EDGE_LEASE_SCHEMA`指向根contracts/edge-lease.schema.json、`EDGE_CONTROL_URL=http://127.0.0.1:8089`、`VISION_URL=http://127.0.0.1:5000`，以及`.env.local`中的`EDGE_NODE_ID`、`EDGE_SERVICE_TOKEN`、`VISION_SERVICE_TOKEN`。

**前端：** 使用浏览器开发者工具或IDEA的JavaScript调试能力；两个前端由各自npm运行配置启动。给`local_dev.py`打断点只调试启动工具本身，业务断点应使用上述对应服务的原生配置。

### 9. 常见问题与检查

| 现象 | 处理 |
| --- | --- |
| `classroom_vision`整段标红 | 检查fastapi模块、Python 3.12 SDK真实注册、src源码根以及`.venv`排除；重开项目索引 |
| 能在终端导入，IDEA仍报错 | 对照模块SDK与实际`.venv/Scripts/python.exe`，避免IDEA使用另一套全局Python |
| Java提示版本不匹配 | 同时检查Project SDK、Maven Importer/Runner与运行配置JRE是否均为21 |
| Spring缺少DB_PASSWORD或Python缺少VISION_SERVICE_TOKEN | 统一启动脚本会读本地配置；原生运行配置要显式传入所需环境变量 |
| 模型已就绪但作业等待 | 检查edge是否启动、节点是否在线，确认只运行一个Outbox执行器 |
| 端口已占用 | 确认是否已从终端启动同一服务，停止该项目已有实例后再Debug |
| Windows构建无法替换JAR | 先停止正在运行的本项目backend，再重新构建 |
| 新机器找不到已修复的IDE模块 | `.idea`和`.iml`是本机配置且被Git忽略，按第3—5节重新导入 |

文档中的首次依赖准备完成后，可以分别检查：

```powershell
# 项目根目录
python scripts/quality_gate.py
python -m unittest discover -s tests -v

# backend构建/真实数据库联调，构建前停止本项目backend
python scripts/local_dev.py build
python scripts/local_dev.py test
```

前端在frontend运行 `npm.cmd run verify`；Python在各自模块运行 `uv run ruff check .`、`uv run ruff format --check .`、`uv run pytest`。真实模型、设备工具、离线回传与备份恢复见[真实模型指南](docs/guides/真实模型本机演示指南.md)和[试点运维指南](docs/guides/试点基础完善与运维指南.md)。

IDE菜单参考：[JetBrains Maven](https://www.jetbrains.com/help/idea/maven-support.html)、[Python SDK](https://www.jetbrains.com/help/idea/configuring-python-sdk.html)、[内容根目录](https://www.jetbrains.com/help/idea/content-roots.html)。本快速开始以仓库当前脚本和本机验证范围为准，生产就绪仍以[项目状态](docs/project-status.md)与验收记录为准。

详细验收证据见[真实模型验收](docs/qa/real-model-verification-2026-11-02.md)与[试点基础验收](docs/qa/trial-readiness-verification-2026-11-02.md)。

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
edge/                     独立 Python 执行器、SQLite Outbox及独立虚拟环境
models/                   公共下载清单、哈希与来源，不包含权重
contracts/                跨模块 OpenAPI 与 JSON Schema
harness/                  AI 开发规则清单与提示模板
tasks/                    任务模板、依赖与验收记录
scripts/                  门禁、上下文打包、环境工具
deploy/                   本地 Compose 与部署模式说明
docs/                     架构、规范、决策、验收及原始资料
.github/workflows/        CI 模块验证与隔离数据库联调
```

## 开始开发

使用 IntelliJ IDEA 时，按上方[快速开始](#快速开始)导入并运行。原IDEA指南保留为兼容入口。

先读 [AI 入口](AGENTS.md) 和 [命令说明](docs/harness/commands.md)。Python 3.12、Java 21、Node 22.18.0、Maven 3.9 以上和 uv 0.12.18 是已验证的开发工具组合。不要使用机器当前默认的 Java 8 构建。

```text
python scripts/quality_gate.py
python -m unittest discover -s tests -v
```

各模块分别安装与验证，不能在根目录混装依赖。具体见 [后端](backend/README.md)、[前端](frontend/README.md)、[视觉服务](fastapi/README.md)、[边缘执行器](edge/README.md)和[运维工具](ops/README.md)。全量基础验证可用 `python scripts/verify.py`，该命令不代替数据库、容器和真实模型验证。

首次跑Java完整数据库测试前，在ops运行uv sync --frozen和 `uv run --frozen python -m classroom_ops.pki lab --out ../.harness/pki-production-tests` 准备独立TLS测试证书，再回根运行python scripts/local_dev.py test。已有且未过期证书目录保持不变；过期后归档再重建，禁止覆盖生产PKI。

最新实测及剩余上线条件见[候选验收报告](docs/qa/production-candidate-2026-11-03.md)；受控容器、TLS、升级恢复及文件配置见[候选部署指南](deploy/staging/README.md)。

## 可选：Docker 开发编排

本节使用`deploy/.env`和Docker映射端口MySQL 3307、Redis 6380，与快速开始的`.env.local`本机方案分开配置。

Docker 引擎可用时，在根目录执行 `python scripts/init_dev_env.py`，然后编辑生成的 `deploy/.env`。若需首次建立本地管理员，临时将 BOOTSTRAP_ENABLED 改为 true；密码已随机生成在该文件中。不要把文件内容发到聊天、日志或 Git。

```text
docker compose --env-file deploy/.env -f deploy/compose.yaml --profile app up --build -d
```

打开本机 5173 教师端或 5172 管理端，使用学校代码 local-school、账号 admin 和自己 .env 中的初始化密码。创建后关闭初始化配置并重建业务容器。视觉服务需另加 `--profile vision`；它当前只用于接口联调，就绪状态为未加载模型。

该 Compose 仅用于本地开发，不是生产部署模板。云端、私有、单机和混合云的边界与待验证项见 [部署说明](deploy/README.md)。本轮 Docker 容器构建及数据库联调是否已验证，以 [实际验证记录](docs/verification.md) 为准。

## 持续使用 AI 开发

每次从 tasks/backlog.json 选择一个尚未完成的范围，交给 AI 的输入为任务文件、根/模块 AGENTS.md、相关契约与验收条件。已完成的本机能力与生产阻塞以项目状态为准，避免重复实现。使用 `python scripts/context_pack.py --task tasks/TEMPLATE.md --module backend` 可生成有界上下文包。

所有人工维护的注释和文档使用中文。第三方依赖、协议字段和机器生成锁文件保留其格式。当前生产门禁 `python scripts/quality_gate.py --production` 应拒绝通过；只有关闭真实阻塞并附证据后才可进入发布评审。

详细状态见 [项目状态](docs/project-status.md)，开发顺序见 [任务清单](tasks/backlog.json)。资料来源与本地归档边界见[资料索引](docs/reference/README.md)；公众号配图与含图 Word 保留在开发者本机，不随本次开源提交发布。

## 安全修复与上线边界

2026-11-03已补登录尝试预算、素材累计配额与失败回收、视频进程隔离，并完成本机回归。配置及升级要求见[安全修复与上线边界](docs/security/2026-11-03-安全修复与上线边界.md)，实际证据见[本轮验收记录](docs/qa/security-remediation-2026-11-03.md)。项目仍需生产节点认证、容量和部署验收，生产门禁继续拒绝放行。

## 参与贡献

提交问题或改进前请阅读 [贡献指南](CONTRIBUTING.md)、[社区行为准则](CODE_OF_CONDUCT.md)与[安全问题报告方式](SECURITY.md)。新增依赖和素材须核对其独立许可；仓库许可证不代替生产验收或第三方授权。
