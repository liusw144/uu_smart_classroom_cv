# M0-GITHUB-BUILD：GitHub 可下载构建包

状态：本机实现与验证通过；GitHub远端待提交后验收。

需求来源：用户希望使用 GitHub 自动构建，并选择生成可下载安装包。

允许范围：工作流、标准库打包脚本、根测试及构建/安装文档。沿用现有五模块验证，不改业务接口、数据库迁移或运行服务。

输入输出：已验证的 JAR、前端 dist、三个 wheel 与锁定依赖 → 带提交号的 ZIP、包内逐文件摘要、外部 SHA-256。

权限与数据：只读 GitHub contents；不使用个人账号密码或生产秘密。明确清单打包，不收入环境配置、录像、模型和私钥。

失败：产物缺失、多个候选、敏感文件/符号链接、来源不符或已有同名输出均失败；不生成可下载成功标记。新提交重新构建。

验收：打包行为根测试、真实构建/验包、wheel 独立环境安装与入口导入、工作流语法及根门禁。命令与结果完成后补充；本机结果不等于 GitHub 远端成功或生产验收。

## 执行记录（2026-11-05）

- TDD：打包器未实现时8项失败；增加edge协议随包断言先失败后修复；独立复核发现dist仍能携带mp4/onnx，6类录像/模型/表格拒绝用例先全部失败，再用静态Web文件类型清单修复。
- `python -m unittest discover -s tests -v`：37项，36通过、1跳过；跳过原因是本机Windows不允许创建符号链接，保留该用例供Linux CI执行。根 `python scripts/quality_gate.py` 通过。
- 新增脚本与测试 `ruff check`、`ruff format --check` 通过；`git diff --check` 通过，原有文件存在CRLF提示。
- 在 `.harness/github-build-check/backend` 复制当前Java源码，执行 `mvn -o -s .mvn/settings.xml -B verify`：16单元测试及可执行JAR构建通过，无集成profile、不连接业务数据库，不覆盖正在运行的JAR。
- `frontend` 的 `npm run verify`：5测试、格式/类型、teacher/admin双构建通过。
- 三模块执行 `uv build 模块 --wheel --out-dir 模块/dist` 及 `uv export --project 模块 --frozen --no-dev --no-emit-project --output-file 模块/dist/requirements.txt --quiet`，全部通过。
- 真实ZIP约40.9MB、26个载荷文件及1个构建清单；CRC、逐文件大小/摘要、外部摘要一致。JAR含正确入口和V12迁移，三份wheel分别在空Python 3.12环境按哈希同步依赖后用 `--no-deps` 安装，视觉入口/CPU运行时、edge与ops命令入口检查通过；没有访问摄像头。
- 官方actionlint v1.7.12经官方校验和验证后，运行 `actionlint -shellcheck= -pyflakes= .github/workflows/verify.yml` 通过；本机没有ShellCheck/Pyflakes，该结果是Actions结构/表达式检查，不声称完整Shell静态检查。
- 独立只读复核：无Critical，1项Important为前端dist打包边界，已按上述失败用例修复；本次未扩大到上轮业务功能复核。

证据（Git忽略）：`.harness/github-build-check/java-build.log`、`install.log`、`summary.json`、`downloads-final/`；校验器位于`.harness/actionlint-1.7.12`。本机包名含 `-local`，清单明确当前源码尚有未提交改动，不能冒充HEAD提交产物。

未验证：GitHub托管runner真实全链路、Linux符号链接用例及目标机整套安装。尝试检查本机Docker时Linux Engine未运行，因此未启动或伪称完成Linux容器测试。本轮没有重跑业务数据库集成/真实模型整链路，没有改业务库、运行服务、GitHub设置、分支保护或生产门禁。

后续：一起提交本轮配置及需要交付的业务代码，在Actions首轮运行检查失败日志并验收可下载Artifact。GPU/昇腾、模型精度、留校网关及正式发布独立验收。
