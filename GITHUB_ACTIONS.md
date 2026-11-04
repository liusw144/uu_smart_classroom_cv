# GitHub 自动构建使用指南

本项目已配置 [自动验证与构建包](.github/workflows/verify.yml)。本轮选择可下载服务 ZIP，不推送镜像、不创建 Release、不自动部署。

## 1. 在 GitHub 开启

1. 将本次配置、打包脚本、文档和相关业务源码一起提交到仓库。GitHub 只能构建已提交的文件；IDEA 中本机未提交的修改不会进入构建。
2. 打开仓库 `Settings → Actions → General`，启用 Actions。Actions 策略需允许本工作流所用的 `actions/*` 与 `astral-sh/setup-uv` 固定提交；受组织策略管理时遵守对应限制。
3. `Workflow permissions` 使用只读。当前流程只有 `contents: read`，**无需配置 GitHub 账号密码、PAT、服务器密码或生产数据库密码**。MySQL/Redis 是每次运行的新临时服务，CI 自建临时应用账号与随机服务凭据。
4. 代码推送及 PR 会自动运行。手动运行入口：仓库 `Actions → 自动验证与构建包 → Run workflow`，选择分支；手动入口要求工作流已存在于默认分支。

仓库入口：[Actions](https://github.com/liusw144/uu_smart_classroom_cv/actions)。工作流沿用所有分支的 push / pull_request；PR 分支推送可能出现两次运行，二者验证的提交上下文不同。后续可按团队分支策略缩小 push 范围。

## 2. 实际执行内容

| 阶段 | 行为 |
| --- | --- |
| 固定环境 | Ubuntu 22.04、Java 21、Node 22.18.0、Python 3.12、uv 0.12.18；Action 引用固定 SHA |
| 根检查 | 工程结构、模块边界、中文说明性注释及根测试 |
| 五模块 | Java 格式/单元/真实 MySQL Redis 集成；双前端格式/类型/测试/构建；FastAPI、edge、ops 的格式与测试 |
| 浏览器 | 临时 Java、视觉服务与双前端下的基础业务页面回归；不访问现场摄像头 |
| 安装验包 | 三个独立空环境按哈希安装生产依赖及 wheel，检查视觉导入和 edge/ops 命令入口 |
| 打包 | 三个独立 wheel、锁定生产依赖、后端可执行 JAR、两个前端 dist、模型/节点协议清单、授权与安装说明 |
| 上传 | 所有步骤成功才上传构建包；失败时仍尝试保存已有测试报告 |

使用依赖锁文件，打包不会临时改项目依赖版本。wheel 的构建后端依照各模块 build-system 安装；这不是字节级可复现构建或外部签名供应链证明。

## 3. 下载与核验

1. 打开成功运行，确认提交号是要测试的版本。
2. 在该运行底部 `Artifacts` 下载 `uu-smart-classroom-build-完整提交号`。GitHub 下载附件可能有外层 ZIP，先解开它。
3. 得到 `uu-smart-classroom-cv-提交号前12位.zip` 与同名 `.zip.sha256`。构建附件保存 **14 天**，测试报告保存 **7 天**，同时受仓库保留策略上限约束。
4. 在 PowerShell 核对摘要：

```powershell
$buildZip = @(Get-ChildItem -File 'uu-smart-classroom-cv-*.zip')
if ($buildZip.Count -ne 1) { throw '请在只包含目标构建包的目录核验' }
$buildZip = $buildZip[0]
$expectedHash = ((Get-Content -LiteralPath ($buildZip.FullName + '.sha256')) -split '\s+')[0]
$actualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $buildZip.FullName).Hash
if ($actualHash -ine $expectedHash) { throw '构建包摘要不一致' }
Expand-Archive -LiteralPath $buildZip.FullName -DestinationPath '.\uu-classroom-build'
```

解压后的 `build-manifest.json` 记录完整提交号、逐文件大小与 SHA-256、CPU 默认值和 `production_ready: false`。摘要用于传输完整性核对，不能代替发布签名。PR 产物可能对应 GitHub 合并测试提交；以运行页面和清单中的完整 SHA 为准。

安装见 [构建包安装说明](docs/guides/构建包安装说明.md)，该说明同时放在 ZIP 根目录。没有重新编译 Java/Vue 的需求，但需要外部运行环境及下载 Python 依赖。

## 4. 构建失败从哪里看

- `Actions → 对应运行 → verify → 失败步骤` 查看日志；同次运行的 `开发验证报告` 保存已生成的 Java XML 与浏览器结果。早期失败时可能还没有报告。
- `npm ci`、`uv sync --frozen` 失败：修正依赖声明与锁文件，不能改成忽略锁文件。GitHub 上不使用本机 `.venv` 或 `node_modules`。
- Java 联调失败：确认 CI 隔离 MySQL/Redis 正常及本次迁移/测试一起提交；不能上传本机 `.env.local` 代替临时环境。
- 打包缺失产物或发现多个旧 JAR/wheel：修正构建输出。打包器不会猜测使用哪一份，也不会上传残缺成功包。
- 前端 dist 只允许常见静态 Web 文件；录像、模型、表格数据及未知类型会拒绝打包，应移出构建目录。新增必要静态类型需审查打包清单，不能将业务素材塞入前端。
- `Run workflow` 不出现：确认默认分支已有工作流、Actions 开启且有运行权限。
- GPU/昇腾测试需要匹配驱动的独立硬件 runner 和专门验收。普通 CPU CI 通过不代表这些设备已验证；不把公开 PR 自动交给连接校内设备的 runner。

## 5. 当前验证与发布边界

本机已验证哪些内容及远端运行状态，见 [本轮任务记录](tasks/M0-GITHUB-BUILD.md)。本次未替用户提交/推送代码或修改 GitHub 仓库设置，因此没有宣称远端构建已经成功。

构建包需要自行准备 MySQL、Redis、FFmpeg、模型/AI 服务及学校配置，不是 Windows EXE 或离线依赖全集。现有课堂精度、录像留校、镜像依赖及硬件容量阻塞继续存在。生产仍执行 `docs/production/release.md` 的独立验收与授权，不能用本工作流绿色状态替代。

官方说明：[工作流触发](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/trigger-a-workflow)、[构建产物与保留期](https://docs.github.com/en/actions/tutorials/store-and-share-data)、[Actions 启用与权限](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/enabling-features-for-your-repository/managing-github-actions-settings-for-a-repository)。
