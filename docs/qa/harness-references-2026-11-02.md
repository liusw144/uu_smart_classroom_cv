# Harness参考项目与改进建议

核查日期：2026-11-02。目标项目：UU智慧课堂，Vue双端、Java/Sa-Token控制面、FastAPI独立视觉服务、混合云边缘执行。本文是审计后的采用建议，没有安装、初始化或迁移任何框架。

## 推荐取舍

优先组合是 **Spec Kit的规格追踪方法＋可执行测试工具＋现有模块边界**。业务基础重点参考RuoYi-Vue-Plus；长期代理的工作节奏参考Anthropic和OpenAI的官方实践。暂时不要先上复杂的多代理调度平台。

没有公开证据能证明某个Harness可直接把本项目自动生成到生产。本次按具体机制、当前维护情况、栈匹配和许可核对选择参考，不按Star排名，不把上游案例成绩套成本项目产能或质量承诺。

## 逐项比较

| 参考 | 借鉴机制 | 本项目用途 | 限制与建议 |
| --- | --- | --- | --- |
| [GitHub Spec Kit](https://github.com/github/spec-kit) | 规格、计划、任务、项目原则及一致性收敛 | 将C/T/A、AC、混合云要求连接到任务和验收 | 不是测试执行器；先选一个小功能试用，不在现有根目录直接覆盖初始化 |
| [Anthropic长期代理实践](https://www.anthropic.com/research/effective-harnesses-for-long-running-agents) | 初始化环境、增量任务、功能清单、跨会话进展与真实端到端验证 | 防止上下文切换后重做；逐项交付可验证功能 | 是方法参考，不能用代理自己填写passed代替执行结果 |
| [OpenAI Harness Engineering](https://openai.com/index/harness-engineering/) | 仓库知识作为记录、可机械约束的架构、运行反馈与可观测性 | 缩短AGENTS入口，给代理可运行命令和可诊断失败证据 | 经验文章不是可安装框架；文中案例不能当作我们的生产保证 |
| [RuoYi-Vue-Plus](https://github.com/dromara/RuoYi-Vue-Plus) | Sa-Token、多租户、MyBatis-Plus和后台基础能力 | 参考账号、租户、数据权限、操作审计及后台业务结构 | 是业务底座，不是AI Harness；没有课堂模型、边缘作业和断网协议；不能整仓无评估替换 |
| [FastAPI官方全栈模板](https://github.com/fastapi/full-stack-fastapi-template) | Pytest、Playwright、API客户端及容器/CI组织方式 | 补视觉服务集成测试及跨端验收方法 | 模板使用React、PostgreSQL/SQLModel等，与本项目Vue、MySQL及Sa-Token身份不同；借测试模式，不照搬认证和数据库 |
| [OpenAI Symphony](https://github.com/openai/symphony) | 事项驱动、隔离工作区、仓库工作流、运行状态及重试 | 待验收闭环稳定后，考虑自动领取任务与PR流程 | 官方仍称可信环境中的工程预览；参考实现为Elixir，演示使用Linear；不应假设当前环境已支持GitHub Issues，也不应取代确定性测试 |

以上是不同层次的参考：Spec Kit管需求组织，测试工具提供判定，业务底座提供可研究的实现，Symphony管代理调度；不能把它们作为互相替换的同类产品。

## 直接对应本次失败的工具

| 缺口 | 参考工具及理由 | 本项目采用位置 |
| --- | --- | --- |
| Java正则架构检查被绕过 | [ArchUnit](https://www.archunit.org/userguide/html/000_Index.html)分析Java字节码和类/包依赖 | backend架构测试；确保没有空扫描、排除真实模块或只测临时类 |
| 缺少真实浏览器验收及失败轨迹 | [Playwright Trace Viewer](https://playwright.dev/docs/trace-viewer)用于查看执行轨迹和失败上下文 | 独立E2E测试目录，连接真实Java、数据库和浏览器；fixture测试单独标记 |
| 数据库联调依赖本机状态 | [Testcontainers for Java](https://java.testcontainers.org/)提供临时容器依赖 | backend隔离MySQL/Redis集成；仍需可用Docker，不能解决当前本机引擎故障 |

OpenAPI需同时校验“schema自身有效”和“真实响应符合schema”；单纯重新生成并比较快照不能发现本次422错误模型漂移。发布证据需从上述执行器的真实产物生成，并绑定构建身份；不存在装一个工具就自动获得生产资格的路径。

## 适合本项目的最小改进顺序

1. **先修门禁可信度和4组复现。** 修凭据异常及OpenAPI错误声明；用ArchUnit覆盖框架依赖；改证据数据结构和生成来源；明确CI文件删除如何被发现。本轮用户的CI暂存删除保持原样，不代替用户恢复或提交。
2. **把功能矩阵变成验收输入。** 保留原C/T/A及AC编号，补租户和混合云验收。每个功能列出真实用户行为、权限、失败行为、依赖、测试命令和证据。未实现用not_implemented；环境失败用unverified；只有指定测试成功才可记录通过。
3. **从学校隔离的班级管理开始试点。** 完成真实数据库迁移、Java权限和归属、Vue页面及两个学校的互访拒绝测试。用这条小闭环评价Spec Kit/现有流程是否减少返工，而非立即生成全部页面。
4. **再交付图片检测纵向闭环。** 素材授权→持久作业→节点身份/领取→真实模型→幂等结果→教师查看。Fake只在测试隔离使用，正式视觉服务缺模型仍必须拒绝就绪。
5. **最后扩展视频、离线与生产证据。** 为目标CPU/GPU、素材、学校并发数定义性能与算法阈值，再实际测量；完成恢复、证书轮换、取消重试、离线回传、原始视频留校及四部署模式验收后才讨论生产发布。

这些是本次评估的建议采用顺序，尚未执行的整改不能当成当前功能。当前三模块隔离和Java负责Sa-Token、FastAPI负责节点推理的方向可保留，主要问题在交付覆盖及反馈闭环。

## 试点是否有效的判断

用同一小功能记录：需求遗漏数、被验收发现的失败数、返工次数、跨会话重复工作、真实E2E是否可重现、耗时和费用。上线质量取决于功能正确和故障恢复，不用文档数量、生成代码行数或代理数量作质量指标。未实际跑过工具前，不给它们主观生产评分。

## 许可与版本快照

2026-11-02通过GitHub公开API核对如下提交及根许可证，仅用于复查此次调研，正式引入时应选择合适发布版本并重新检查子目录、资产和依赖许可。未向本项目复制上游代码。

| 仓库 | 核查分支 | 核查提交 | 根许可 |
| --- | --- | --- | --- |
| [github/spec-kit](https://github.com/github/spec-kit/tree/7370e592f3cd9c06e5b698983957e227c75b30e2) | main | `7370e592f3cd9c06e5b698983957e227c75b30e2` | MIT |
| [openai/symphony](https://github.com/openai/symphony/tree/be10a1b79df723d6d7612b5651c8522704dafb2e) | main | `be10a1b79df723d6d7612b5651c8522704dafb2e` | Apache-2.0 |
| [anthropics/claude-quickstarts](https://github.com/anthropics/claude-quickstarts/tree/1a18c340ca42b9137d991637afe867bdcf70a9ce) | main | `1a18c340ca42b9137d991637afe867bdcf70a9ce` | MIT |
| [fastapi/full-stack-fastapi-template](https://github.com/fastapi/full-stack-fastapi-template/tree/f4bcfaba7c85be3c6332cc74a20819f586dcdbc6) | master | `f4bcfaba7c85be3c6332cc74a20819f586dcdbc6` | MIT |
| [dromara/RuoYi-Vue-Plus](https://github.com/dromara/RuoYi-Vue-Plus/tree/9227f626a3a37e1546d6c291ed398183e94df92b) | 6.X | `9227f626a3a37e1546d6c291ed398183e94df92b` | MIT |

重要例外：[Anthropic autonomous-coding示例README](https://github.com/anthropics/claude-quickstarts/blob/main/autonomous-coding/README.md) 的License段写有“Internal Anthropic use.”，与API所列仓库根MIT存在差异。这里只参考官方文章的方法；在许可范围澄清前，不把该示例代码复制进本Apache仓库。

Spec Kit当前README的命令及集成方式会演进；应按选定版本文档试用，不能直接套旧教程。Symphony同样需要核对所选实现的tracker支持、凭据边界和工作区隔离，再决定是否采用。
