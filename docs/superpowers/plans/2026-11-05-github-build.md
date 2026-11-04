# GitHub 可下载构建包实施计划

> 执行方式：使用 superpowers:executing-plans 在当前会话逐项实施；不提交或推送现有未提交业务改动。

目标：现有 GitHub 验证成功后提供有来源和摘要的完整服务构建 ZIP。

架构：复用单个完整验证任务，新增严格输入清单的标准库打包器，不引入部署权限。

技术栈：GitHub Actions、Python 标准库、现有 Maven/npm/uv。

设计：[构建设计](../specs/2026-11-05-github-build-design.md)。

## 全局约束与审查重点

- 公开仓库不能打包本机凭据和学生素材；只允许明确的构建输出和公开资料。
- 缺失产物、多版本残留、符号链接、已有目标文件应拒绝，不能给出残缺或覆盖后的成功包。
- 记录真实提交与工作区状态，CI 拒绝来源不符或脏工作区。
- 摘要用于传输核对，不代替独立签名发布授权。
- 原有测试、租户隔离、CPU 默认和生产门禁保持。

## 任务一：有来源和完整性校验的打包器

文件：`scripts/package_build.py`、`tests/test_package_build.py`。

- [x] 为真实 ZIP、摘要和上述失败方式写测试，确认功能缺失时失败。
- [x] 实现 `create_bundle(root, output_dir, commit, source_dirty=False)` 与 CLI 来源核验；只写新的 ZIP/摘要。
- [x] 运行根测试和门禁，实际构建各模块并验包，独立环境安装 wheel 验证入口。

## 任务二：工作流与使用说明

文件：`.github/workflows/verify.yml`、根 `GITHUB_ACTIONS.md`、`docs/guides/构建包安装说明.md`、README、任务和状态记录。

- [x] 全部现有验证之后增加 wheel/锁定依赖导出、安装验包、打包和成功产物上传；失败报告继续保存。
- [x] 写明 Actions 开关、运行、下载、包内容、外部依赖与生产边界。
- [x] 校验 Actions 语法和链接，记录本机证据及远端尚未运行，完成自查与独立复核。

执行裁定：用户已选择本方案，在当前共享工作区保留上轮未提交改动，不新建丢失该业务代码的干净工作树；仅Java编译隔离到忽略目录。不提交、推送或部署。前端只允许常见静态Web文件类型，陌生类型需明确审查。完整证据与跳过项见`tasks/M0-GITHUB-BUILD.md`。
