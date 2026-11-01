# 贡献指南

欢迎通过 Issue 和 Pull Request 改进工程基础、业务功能、测试与文档。当前为 M0 开发阶段，完整课堂业务与真实模型尚未交付，请先查阅 [项目状态](docs/project-status.md)和[任务清单](tasks/backlog.json)。

## 开始开发

1. 阅读根 [AGENTS.md](AGENTS.md) 和所修改模块的 AGENTS.md。
2. 从 main 创建聚焦单项需求的分支；较大变更先说明问题、接口和验收方式。
3. 前后端与 FastAPI 保持独立依赖，不跨模块引用源码，不提交本机环境。
4. 为关键行为添加正反用例；涉及学校和教师资源时覆盖越权情况。
5. 运行相应模块验证与根门禁，说明未验证环境。

## 验证命令

- 根目录：`python scripts/quality_gate.py` 和 `python -m unittest discover -s tests -v`。
- backend：Java 21 环境下 `mvn -s .mvn/settings.xml -B verify`。
- frontend：`npm ci` 和 `npm run verify`。
- fastapi：`uv sync --frozen`、`uv run ruff check .`、`uv run ruff format --check .`、`uv run pytest`。

完整说明见[验证命令](docs/harness/commands.md)和[IDEA 使用指南](docs/guides/IntelliJ_IDEA使用指南.md)。数据库集成测试只连接独立测试库，不能用于生产数据。CI 通过不等于生产验收完成。

## 提交与检查

提交描述应说明具体问题和行为变化；PR 附验证命令、结果、契约/迁移兼容性与发布影响。注释和维护文档使用中文，代码标识、协议字段和第三方许可原文保持标准格式。

AI 生成的改动同样需要贡献者检查、运行与承担维护责任。不得编造模型结果、准确率、运行证据或以删除门禁修复失败。新增依赖须说明用途、版本和许可，更新锁文件；不要为了未实现的页面预先安装大量依赖。

## 贡献许可与数据

请只提交你有权贡献的内容，原创贡献按本项目 Apache License 2.0 接纳，贡献者保留各自版权。第三方内容必须说明来源与许可，不能把来源文章、配图、权重或数据集直接声明为原创。

不要提交真实凭据、.env、私钥、课堂视频、学生信息和未获再分发授权的素材。安全问题请按 [SECURITY.md](SECURITY.md) 处理，社区交流遵循 [行为准则](CODE_OF_CONDUCT.md)。
