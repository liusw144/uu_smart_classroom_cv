# UU 智慧课堂 IntelliJ IDEA 使用指南

更新日期：2026-11-02。适用环境：Windows、PowerShell、IntelliJ IDEA，项目根目录为 `D:\self\uu_smart_classroom_cv`。

本指南说明如何在 IDEA 中打开整个仓库，分别配置、运行和调试 Java 后端、Vue 双前端与 FastAPI。推荐开发方式是 **Docker 只运行 MySQL 和 Redis，三个应用在本机运行**，便于断点调试。

当前项目为 M0 工程基础：已有登录、会话、权限边界、双前端入口及视觉接口基础，尚未接入真实模型和完整教学业务。FastAPI 返回“模型未就绪”是当前真实状态，不能为了演示而改成成功。

本文依据仓库实际配置及 JetBrains 官方资料编写，未代替你修改 IDEA 设置，也未在 IDEA 界面中完成全套启动验证。项目已有验证记录见[工程验证记录](../verification.md)。

## 1 首次使用的操作顺序

1. 在 IDEA 中打开项目根目录，而不是只打开 frontend 或 backend/api。
2. 将 backend/pom.xml 加载为 Maven 项目，等待四个子模块导入。
3. 设置项目 SDK、Maven 导入 JDK、Maven 运行 JRE、后端运行 JRE 为 Java 21。
4. 在 frontend 运行 npm ci，在 fastapi 运行 uv sync --frozen。
5. 生成 deploy/.env，只启动 MySQL 和 Redis。
6. 创建 Java 后端运行配置，填入环境变量，首次运行时初始化本地管理员。
7. 启动管理端或教师端的 npm 配置，通过页面验证登录。
8. 配置 FastAPI 的独立 Python 解释器和 Uvicorn 运行配置。
9. 运行本指南中的模块检查与根门禁，再继续开发。

如果只想阅读代码或运行不依赖数据库的测试，可以先完成前四步，不必立即启动 Docker。

## 2 IDEA 版本与插件

本机检测到 IntelliJIdea2025.3 配置目录；实际正在使用的版本，请以 Help → About 为准。IDEA 从 2025.3 起采用统一发行版，专业功能仍会因订阅和插件不同而有所区别。本文不假定所有运行模板都可用。[JetBrains 2025.3 说明](https://www.jetbrains.com/idea/whatsnew/2025-3/)

在 File → Settings → Plugins 中按需要确认：

| 能力 | 用途 | 缺少该能力时 |
| --- | --- | --- |
| Java 与 Maven | 导入和调试 Java 后端 | 先检查插件和 Maven 导入状态 |
| Spring 支持 | Spring 导航与专用运行模板 | 使用普通 Application 配置启动主类 |
| JavaScript、TypeScript 与 Vue | 前端提示与界面支持 | 仍可在 IDEA Terminal 中运行 npm 命令 |
| Python | Python SDK、运行与调试 | 在 Terminal 使用 uv；也可单独用 PyCharm 打开 fastapi |
| Docker | 可视化查看本地容器 | 使用 Terminal 的 docker compose 命令 |
| JavaScript Debugger | 在 IDEA 中调试浏览器代码 | 使用浏览器开发者工具 |

Python 功能需要已安装并启用相应 Python 插件。[Python 官方说明](https://www.jetbrains.com/help/idea/python.html) 浏览器调试功能依插件和订阅而定，缺少时不影响 Vite 通过终端运行。[JavaScript 调试说明](https://www.jetbrains.com/help/idea/debugging-javascript-in-chrome.html)

以下菜单名称使用英文，中文界面对应“设置、项目结构、编辑配置”等。不同版本入口略有变化时，可用 Ctrl+Shift+A 搜索相应设置或操作名称。

## 3 本机工具与目录

以下为原开发机器的工具路径示例。Python 路径使用本机 LOCALAPPDATA；其他机器需按实际安装路径调整。

| 工具或目录 | 本机路径或版本 |
| --- | --- |
| 项目根目录 | D:\self\uu_smart_classroom_cv |
| JDK 21 | D:\software\java\jdk21 |
| Maven | D:\software\apache-maven-3.9.12 |
| Node.js | C:\nvm4w\nodejs\node.exe，22.18.0 |
| Python 3.12 | %LOCALAPPDATA%\Programs\Python\Python312\python.exe |
| FastAPI 独立解释器 | D:\self\uu_smart_classroom_cv\fastapi\.venv\Scripts\python.exe |
| uv | 0.12.18，通过 PATH 调用 |

项目要求 Java 21、Node 22.12 至 22.x、Python 3.12。机器默认 Java 可能仍是 Java 8，IDEA 的 SDK 与 Terminal 的 JAVA_HOME 也互不等同，不要只修改其中一处。

在 IDEA Terminal 中检查：

```powershell
Set-Location 'D:\self\uu_smart_classroom_cv'
java -version
mvn.cmd -version
node --version
uv --version
```

如果 Terminal 仍使用 Java 8，仅为当前终端设置：

```powershell
$env:JAVA_HOME = 'D:\software\java\jdk21'
$env:Path = $env:JAVA_HOME + '\bin;' + $env:Path
mvn.cmd -version
```

此操作不会自动改变 IDEA 运行配置的 JRE。下面还需要在 IDEA 设置 Java 21。

## 4 打开整个项目并导入 Maven

1. 选择 File → Open，打开 `D:\self\uu_smart_classroom_cv`。
2. 确认 Project 窗口中同时可见 backend、frontend、fastapi、contracts、docs 和 harness。
3. 找到 backend/pom.xml，右键选择 Add as Maven Project，或在 Maven 工具窗口点击添加项目并选择该文件。
4. 等待依赖同步，必要时点击 Reload All Maven Projects。
5. Maven 项目中应包含 classroom-domain、classroom-application、classroom-infrastructure、classroom-api。

根目录没有 pom.xml，这是单仓库的正常结构；不要为了让 IDEA 识别而在根目录新建聚合 POM。Java 的聚合入口已经在 backend/pom.xml。IDEA 支持将 Maven 项目加入现有工程。[Maven 官方说明](https://www.jetbrains.com/help/idea/maven-support.html)

如果已经只打开了 backend，也可以继续开发 Java；需要统一查看其他模块时，重新打开项目根目录并加载相同 Maven POM 即可。

## 5 设置 Java 与 Maven

### 5.1 项目 SDK

打开 File → Project Structure：

- Project SDK：选择或添加 `D:\software\java\jdk21`。
- Language level：选择 21，或跟随项目 SDK，不启用 Preview。
- Java 子模块 SDK：继承项目 Java 21。

不要把整个项目的 SDK 改成 Python；Python 解释器单独分配给 FastAPI 模块或 Python 运行配置。

### 5.2 Maven 设置

打开 Settings → Build, Execution, Deployment → Build Tools → Maven，按当前版本界面设置：

| 配置项 | 本项目建议值 |
| --- | --- |
| Maven home path | D:\software\apache-maven-3.9.12 |
| User settings file | D:\self\uu_smart_classroom_cv\backend\.mvn\settings.xml，启用 Override |
| Importing → JDK for importer | Java 21 或 Project JDK |
| Runner → JRE | Java 21 或 Project JDK |
| Skip tests | 不勾选 |
| Work offline | 首次下载依赖时不勾选 |

项目使用自己的 HTTPS Maven 仓库配置，不需要改写机器全局 settings.xml。各 IDEA 版本会将部分选项放在独立页签，用设置搜索框查找 Runner、Importer 或 User settings 即可。

### 5.3 建立 Maven 检查配置

在 Run → Edit Configurations 添加 Maven 配置：

| 字段 | 值 |
| --- | --- |
| Name | 后端工程检查 |
| Working directory / Maven project | D:\self\uu_smart_classroom_cv\backend |
| Run / Command line | -s .mvn/settings.xml -B verify |
| JRE | Java 21 |

字段中填写 Maven 参数和目标，不再额外填写 mvn.exe。命令应从 backend 聚合工程执行，否则内部模块依赖可能无法在一次构建中解析。[Maven 运行配置](https://www.jetbrains.com/help/idea/run-debug-configuration-maven.html)

## 6 安装两个独立依赖环境

在 IDEA Terminal 分别执行：

```powershell
Set-Location 'D:\self\uu_smart_classroom_cv\frontend'
npm.cmd ci
```

```powershell
Set-Location 'D:\self\uu_smart_classroom_cv\fastapi'
uv sync --frozen
```

前端只在 frontend 安装依赖；教师端与管理端是同一 npm workspace 的两个应用，不要分别生成新的锁文件。Python 只使用 fastapi/.venv；不要把依赖装到全局 Python 或项目根目录。

若 uv 没有选到 Python 3.12，可显式指定已安装的解释器：

```powershell
uv sync --frozen --python (Join-Path $env:LOCALAPPDATA 'Programs\Python\Python312\python.exe')
```

最后这条命令仍需在 fastapi 目录运行。已有 .venv 时，IDEA 直接选择它，不必再创建另一个虚拟环境。

## 7 准备数据库与环境变量

### 7.1 只启动基础设施

先启动 Docker Desktop，并确认 Docker Engine 可用：

```powershell
docker version
```

然后在项目根目录执行：

```powershell
Set-Location 'D:\self\uu_smart_classroom_cv'
python scripts/init_dev_env.py
docker compose --env-file deploy/.env -f deploy/compose.yaml up -d mysql redis
docker compose --env-file deploy/.env -f deploy/compose.yaml ps
```

如果 deploy/.env 已存在，初始化脚本会拒绝覆盖；直接使用已有文件。本指南采用“基础设施在容器、应用在 IDEA”的方式，此时不要再启动 app profile，否则会与本机应用争用 8089、5172 和 5173 端口。

| 服务 | 本机访问地址 | 用途 |
| --- | --- | --- |
| MySQL | 127.0.0.1:3307 | 数据库 classroom，用户 classroom |
| Redis | 127.0.0.1:6380 | Sa-Token 会话 |
| Java | 127.0.0.1:8089 | 业务 API |
| 管理端 | 127.0.0.1:5172 | 学校管理入口 |
| 教师端 | 127.0.0.1:5173 | 教师入口 |
| FastAPI | 127.0.0.1:5000 | 内部视觉服务 |

这些是本机访问地址。只有 Docker 内的应用才使用 mysql:3306、redis:6379；不要把容器内部主机名直接填进本机 IDEA 配置。

### 7.2 Java 运行配置需要的变量

在 Java 运行配置的 Environment variables 中填入：

| 变量 | 值或来源 |
| --- | --- |
| DB_PASSWORD | deploy/.env 中的 DB_PASSWORD |
| REDIS_PASSWORD | deploy/.env 中的 REDIS_PASSWORD |
| DB_URL | 可省略，默认连接 127.0.0.1:3307/classroom |
| DB_USER | 可省略，默认 classroom |
| REDIS_HOST | 可省略，默认 127.0.0.1 |
| REDIS_PORT | 可省略，默认 6380 |
| BOOTSTRAP_ENABLED | 首次初始化 true，之后 false |
| BOOTSTRAP_PASSWORD | 首次初始化时填写 deploy/.env 中对应值 |

Java 不会自动加载 deploy/.env，Docker 的 --env-file 也不会把变量写进 IDEA。默认做法是在运行配置中逐项填写；如果你的版本提供显式环境文件加载入口，也可以自行配置后核对变量。

带密码的运行配置保留为本机配置，不启用 Store as project file 后提交到仓库。项目忽略 .idea 和实际 .env 文件；共享配置只能保留非敏感参数。[Application 配置与存储位置](https://www.jetbrains.com/help/idea/run-debug-configuration-java-application.html)

### 7.3 Terminal 方式加载开发变量

如果不使用 IDEA 的 Java 运行按钮，可以在同一个 PowerShell Terminal 中加载脚本生成的简单 KEY=VALUE 文件：

```powershell
Set-Location 'D:\self\uu_smart_classroom_cv'
$envFile = Join-Path (Get-Location) 'deploy\.env'
Get-Content -LiteralPath $envFile | ForEach-Object {
    $entry = $_.Trim()
    if ($entry -and -not $entry.StartsWith('#')) {
        $pair = $entry.Split('=', 2)
        if ($pair.Length -eq 2) {
            Set-Item -LiteralPath ('Env:' + $pair[0]) -Value $pair[1]
        }
    }
}
```

该示例只用于本项目初始化脚本生成的文件，不是通用 dotenv 解析器。它不输出凭据；加载结果仅影响当前终端及其子进程，不会传给已经运行的 IDEA 配置。

## 8 在 IDEA 运行 Java 后端

### 8.1 创建 Application 配置

打开以下文件，使用 main 方法旁的运行图标创建配置，再选择 Edit Configurations：

```text
backend/api/src/main/java/cn/uusmart/classroom/api/ClassroomApplication.java
```

| 字段 | 值 |
| --- | --- |
| Name | 业务后端 dev |
| 类型 | Application；有 Spring Boot 模板时也可使用 |
| Main class | cn.uusmart.classroom.api.ClassroomApplication |
| Use classpath of module | classroom-api，或包含主类的对应导入模块 |
| JRE | Java 21 |
| Working directory | $PROJECT_DIR$/backend |
| Program arguments | --spring.profiles.active=dev |
| Environment variables | 按上一节填写 |

使用 Spring Boot 专用模板时，可在 Active profiles 中填 dev，代替相同的命令行参数；不要配置互相冲突的 profile。$PROJECT_DIR$ 是 IDEA 宏，前提是打开本指南指定的项目根目录；不确定时使用完整绝对路径。

### 8.2 首次初始化与登录

1. 首次运行设置 BOOTSTRAP_ENABLED=true，并填写 12 至 64 字符的 BOOTSTRAP_PASSWORD；初始化脚本生成的值可直接使用。
2. 启动后，Flyway 创建身份基础表，开发初始化流程创建学校 local-school 和账号 admin。
3. 初始化账号的角色为学校管理员，不会自动创建教师账号。
4. 首次创建成功后，将本机运行配置中的 BOOTSTRAP_ENABLED 改为 false，再正常启动。

如果已有管理员，初始化流程不会重置其密码；修改 .env 中密码也不会修改数据库中的既有密码。初始化仅允许 dev 环境，不能用于生产开通。

### 8.3 确认服务状态

浏览器或 Terminal 访问：

```powershell
curl.exe -i http://127.0.0.1:8089/health/live
curl.exe -i http://127.0.0.1:8089/actuator/health/readiness
```

存活接口应反映进程状态；就绪接口还检查数据库和 Redis。就绪失败时先检查容器、密码与端口，不要删除健康检查项。

### 8.4 Terminal 启动后端

在已经设置 Java 21、加载环境变量的同一终端中执行：

```powershell
Set-Location 'D:\self\uu_smart_classroom_cv\backend'
mvn.cmd -s .mvn/settings.xml -B verify
java -jar api/target/classroom-api-0.1.0-SNAPSHOT.jar --spring.profiles.active=dev
```

不要同时运行这个 JAR 与 IDEA 中同端口的后端配置。

## 9 运行 Vue 教师端和管理端

### 9.1 Node 运行时

在 Settings 中搜索 Node.js 或 JavaScript Runtime，选择本机 Node 22.18.0。较新版本统一在 JavaScript Runtime 页面管理，较早版本菜单名称可能不同。[运行时说明](https://www.jetbrains.com/help/idea/javascript-runtime.html)

### 9.2 两个 npm 配置

在 Run → Edit Configurations 添加两个 npm 配置：

| 字段 | 教师端 | 管理端 |
| --- | --- | --- |
| Name | 教师端 dev | 管理端 dev |
| package.json | $PROJECT_DIR$/frontend/package.json | 同左 |
| Command | run 或 run-script | 同左 |
| Scripts | dev:teacher | dev:admin |
| Node runtime | 本机 Node 22 | 同左 |
| 访问地址 | http://127.0.0.1:5173 | http://127.0.0.1:5172 |

两个配置都使用 frontend 根 package.json，不要把工作目录设为项目根目录。启动后保持进程运行；Vite 会处理前端更新。[npm 运行配置](https://www.jetbrains.com/help/idea/run-debug-configuration-npm.html)

没有 npm 配置模板时，在两个 Terminal 标签分别运行：

```powershell
Set-Location 'D:\self\uu_smart_classroom_cv\frontend'
npm.cmd run dev:teacher
```

```powershell
Set-Location 'D:\self\uu_smart_classroom_cv\frontend'
npm.cmd run dev:admin
```

前端 /api 通过 Vite 转发到 127.0.0.1:8089，不直接调用 FastAPI。管理端可使用 local-school、admin 和初始化密码验证登录。页面刷新会清除内存令牌，重新登录符合当前设计。

## 10 配置与运行 FastAPI

### 10.1 选择独立解释器

确认已在 fastapi 运行 uv sync --frozen。启用 Python 插件后，在 File → Project Structure → SDKs 中添加已有 Python SDK，指向：

```text
D:\self\uu_smart_classroom_cv\fastapi\.venv\Scripts\python.exe
```

将该 SDK 分配给 FastAPI 的 Python 模块，或直接在 Python 运行配置中选择它。保持 Java 项目的 SDK 为 Java 21。需要时将 fastapi/src 标记为对应 Python 模块的 Sources Root，不要标记整个仓库为 Python 源码。[Python SDK 设置](https://www.jetbrains.com/help/idea/configuring-python-sdk.html)

### 10.2 准备视觉服务变量

在 fastapi 目录中首次复制 .env.example 为 .env；已有文件时直接编辑，不覆盖原配置。填写：

```dotenv
VISION_APP_ENV=development
VISION_SERVICE_TOKEN=请替换为不少于32字符的开发凭据
```

上面是格式示例，必须替换占位值。可使用 deploy/.env 中已生成的 VISION_SERVICE_TOKEN。视觉配置只需要自己的 VISION_ 变量，不复制数据库密码；此服务不连接业务 MySQL。

Pydantic 从工作目录的 .env 读取配置，因此运行目录必须设为 fastapi。也可在运行配置中显式填写两个变量。当前 production 模式会拒绝启动，这是尚未接入节点认证和真实模型的明确边界。

### 10.3 Python 模块运行配置

在 Run → Edit Configurations 中添加 Python 配置：

| 字段 | 值 |
| --- | --- |
| Name | 视觉服务 dev |
| Target / Run kind | Module name |
| Module name | uvicorn |
| Parameters | classroom_vision.main:create_app --factory --host 127.0.0.1 --port 5000 |
| Python interpreter | fastapi/.venv/Scripts/python.exe |
| Working directory | $PROJECT_DIR$/fastapi |

这是 Python 的模块启动方式，不是执行名为 uvicorn.py 的脚本。入口为 create_app 工厂，本项目没有 classroom_vision.main:app 全局对象。Python 配置可独立指定解释器、工作目录及环境变量。[Python 运行配置](https://www.jetbrains.com/help/pycharm/run-debug-configuration-python.html)

日常自动重载可在参数末尾加 --reload；断点调试先去掉 --reload，并保持单个 worker，避免重载子进程导致断点位置混乱。

没有 Python 配置模板时，在 Terminal 运行：

```powershell
Set-Location 'D:\self\uu_smart_classroom_cv\fastapi'
uv run uvicorn classroom_vision.main:create_app --factory --host 127.0.0.1 --port 5000
```

### 10.4 当前可验证的行为

| 请求 | 当前预期 |
| --- | --- |
| GET /health/live | 200，进程存活 |
| GET /health/ready | 503，真实模型未接入 |
| GET /docs | 查看内部接口说明，不代表模型可用 |
| 匿名 POST /internal/v1/detections | 401 |
| 身份有效且请求合法的检测调用 | 503 MODEL_UNAVAILABLE |
| 身份有效但请求带未知路径字段 | 422 |

这些是当前代码的行为边界；真实推理、Agent 和节点 mTLS 留在后续任务中实现。

## 11 管理多个运行配置

建议保留以下名称，便于日常操作：

- 后端工程检查：Maven verify。
- 业务后端 dev：Application 或 Spring Boot。
- 教师端 dev：npm dev:teacher。
- 管理端 dev：npm dev:admin。
- 视觉服务 dev：Python 模块 uvicorn。

首次调通时按“MySQL/Redis → 后端 → 一个前端 → 视觉服务”的顺序逐个启动。都能独立运行后，可以创建 Compound 配置组合本机应用；Compound 不负责等待数据库就绪，仍须先启动并检查基础设施。

运行配置中的工作目录、解释器与环境变量各自独立。IDEA Terminal 切换目录或设置变量，不会自动修改这些配置。[运行配置说明](https://www.jetbrains.com/help/idea/run-debug-configuration.html)

## 12 断点调试

### 12.1 Java

在 AuthController.login、LoginService.login 或 OwnershipPolicy.requireAccess 设置断点，以 Debug 启动“业务后端 dev”，再从页面发起请求。重点观察学校上下文、权限判断和调用链；不要将密码、令牌或整个请求体复制到日志和任务记录中。

Windows 默认键位通常为：Ctrl+F8 切换断点、F8 单步跳过、F7 单步进入、F9 继续、Shift+F9 调试；实际以 Settings → Keymap 为准。

前端 API 超时为 15 秒，长时间暂停会让页面提示失败。可在检查完变量后及时继续，或使用独立 HTTP 调试请求设置更合适的超时，不要为调试永久关闭超时保护。

### 12.2 Vue

浏览器开发者工具可查看 Network 中的 /api 请求、HTTP 状态和组件对应源码。需要 IDEA 集成调试时，使用可用的 JavaScript Debugger，连接相应 Vite 地址；前提是前端服务已启动。不要直接打开 apps/teacher/index.html 的 file:// 地址运行应用。

### 12.3 Python

在 api/routes.py 或 inference/engine.py 设置断点，通过“视觉服务 dev”的 Debug 启动。使用正确开发身份和合法请求才会进入检测处理；当前最终会抛出模型不可用异常，这是预期结果。

## 13 在 IDEA 执行测试和门禁

| 范围 | 工作目录 | 命令 |
| --- | --- | --- |
| 根工程门禁 | 项目根目录 | python scripts/quality_gate.py |
| 门禁回归 | 项目根目录 | python -m unittest discover -s tests -v |
| Java 单元、格式与打包 | backend | mvn.cmd -s .mvn/settings.xml -B verify |
| 前端格式、类型、测试与双构建 | frontend | npm.cmd run verify |
| Python 静态检查 | fastapi | uv run ruff check . |
| Python 格式检查 | fastapi | uv run ruff format --check . |
| Python 与契约测试 | fastapi | uv run pytest |
| 全部本机基础验证 | 项目根目录 | python scripts/verify.py |

Java 测试可从测试类旁图标运行；正式记录仍执行聚合 Maven verify。前端固定使用项目 Vitest 版本，若 IDEA 测试面板未识别，以 npm test 结果为准，不为适配 IDE 单独降低项目依赖版本。

全量验证由当前 Terminal 启动，需先设置 Java 21，并确保 Maven、Node 和 uv 在 PATH。它不包含 Docker 镜像、数据库 HTTP 联调或生产验收。

### 独立数据库集成测试

AuthFlowIT 会删除并重建测试身份数据。只有准备好独立 MySQL 数据库 classroom_test 和测试 Redis 后，才为单独 Maven 配置填写测试 DB_URL、DB_USER、DB_PASSWORD 与 Redis 变量，并执行：

```text
-s .mvn/settings.xml -B -Pintegration verify
```

该配置的工作目录仍为 backend。不要复用日常课堂数据库，更不要连接生产库。integration 是 Maven profile，不是 Spring 的 dev/prod profile。隔离服务参考 [.github/workflows/ci.yaml](../../.github/workflows/ci.yaml)。

## 14 编码、格式与索引

项目使用 UTF-8 和 .editorconfig，注释统一中文。Settings 中可搜索 File Encodings，将项目编码设为 UTF-8；不要为了修正控制台显示直接把源码转为 GBK。

Java 使用固定版本 Spotless，在 backend 运行：

```text
mvn.cmd -s .mvn/settings.xml spotless:apply
```

前端使用固定版本 Prettier，在 frontend 运行 npm.cmd run format。IDEA 中可选择项目安装的 Prettier；保存时自动格式化前，确认使用仓库配置，避免内置格式化器与 CI 反复冲突。

Python 在 fastapi 运行 uv run ruff format .，必要时安装支持当前版本的 Ruff 插件。即使不装 IDE 插件，命令行检查仍是验收依据。

索引缓慢时，可在 Project 窗口将 frontend/node_modules、各 target/dist、fastapi/.venv、.harness、.git 等生成目录标记 Excluded；源码和契约不要排除。Python 解释器仍指向 .venv，排除其文件树不等于删除环境。

## 15 Git 与 AI 开发协作

先读根 [AGENTS.md](../../AGENTS.md)、当前模块的 AGENTS.md，再按 [任务清单](../../tasks/backlog.json) 选择小任务。IDEA 中负责导航、断点、差异检查；AI 生成的改动仍经过相同测试和门禁。

建议一次任务覆盖一个可验证行为：明确契约、学校隔离、失败处理 → 修改与回归 → 模块检查 → 根门禁 → 更新任务记录。提交前在 IDEA Commit 窗口检查文件列表与 diff，确保没有 .env、凭据、模型权重和视频素材。

项目目前已初始化 Git，是否存在远端与提交以实际 Git 状态为准。本指南不代你提交、推送或配置 IDE 密钥。若需要共享运行配置，仅共享不含敏感信息的参数文件。

## 16 常见问题

| 现象 | 优先检查与处理 |
| --- | --- |
| Project SDK 已是 21，但 Maven 报 Java 8 | 单独检查 Maven Runner、Importer 与 Terminal 的 JAVA_HOME |
| Maven 窗口没有项目 | 将 backend/pom.xml 加为 Maven 项目，然后重新同步 |
| 找不到内部模块依赖 | 从 backend 聚合入口运行；不要只构建 api 且漏掉其余模块 |
| 找不到主类或启动后缺业务类 | 运行 classpath 选择 classroom-api，确认 Maven 四模块已加载 |
| Java 启动提示 DB_PASSWORD 无法解析 | 在对应运行配置注入变量；deploy/.env 不会自动进入 IDEA |
| MySQL 或 Redis 连接失败 | 检查容器健康、本机 3307/6380、密码；本机不用 mysql/redis 主机名 |
| Docker npipe 或 Engine 连接失败 | 启动 Docker Desktop，先用 docker version 确认服务端可连接 |
| admin 无法登录 | 学校代码是否为 local-school，是否完成首次初始化，是否误以为重跑初始化会重置密码 |
| /api 返回代理错误 | Java 是否已在 8089 启动；前端需从 Vite 页面访问 |
| 端口被占用 | 检查是否同时启动了容器 app profile 与 IDEA 应用，停止重复实例 |
| PowerShell 提示 npm.ps1 不允许运行 | 使用 npm.cmd；本指南命令无需全局修改执行策略 |
| Python 提示缺包或无法导入 classroom_vision | 解释器是否为 fastapi/.venv；在 fastapi 重新执行 uv sync --frozen |
| Uvicorn 找不到 app | 使用 classroom_vision.main:create_app 与 --factory |
| VISION_SERVICE_TOKEN 缺失 | 检查 fastapi/.env、工作目录及显式环境变量 |
| FastAPI ready 返回 503 | 当前真实模型未接入，符合预期，不能改成伪就绪 |
| FastAPI production 启动被拒绝 | 生产节点身份与模型未交付，先完成对应任务 |
| 断点不命中 | 确认是 Debug、选择了正确进程/解释器，Python 调试先关闭 --reload |
| Java 或前端格式检查失败 | 分别运行 Spotless / Prettier，再检查变更；不要关闭检查 |

## 17 进一步阅读与验证范围

- [项目 README](../../README.md)、[实际状态](../project-status.md)、[验证命令](../harness/commands.md)。
- [资料来源与本地归档说明](../reference/README.md)、[架构说明](../architecture/overview.md)。
- [生产发布要求](../production/release.md)、[部署模式边界](../../deploy/README.md)。

本文中的目录、主类、端口、命令和变量已按当前源码核对；IDE 菜单参考编写时的 JetBrains 官方文档。未将“写好了指南”或“能在 IDEA 打开”视为数据库联调、真实模型或生产验收通过，相关证据仍以项目验证记录为准。
