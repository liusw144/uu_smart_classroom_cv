# 课堂教务快照与生命周期验证

日期：2026-11-04。源码基线98aee354c49e8ac3c8d6ab56b79f1915f52ca3f0加本批未提交修改。

## 已交付

- 创建时持久教务快照、计划结束/教室名称、实际课堂时间、归档、版本及事件。
- 原课堂资料变更不会通过当前课程反向改变历史班级；教务报告使用快照。
- 已使用课堂永久冻结；删除全部分析/报告也不能重新编辑或删除课堂。
- 归档与新增/删除作业、教务报告、AI报告锁定同一课堂行；归档后保持只读。
- 页面固定打开编辑表单时的版本，过滤归档课堂的新增操作选项，提供状态操作及历史详情。
- 旧资料明确标历史初始化，独立打印的报告正文也保留来源与记录时间。

## 验证结果

| 范围 | 结果 | 说明 |
| --- | --- | --- |
| Java单元 | 16通过 | 既有领域/登录/证书/模型及资源基础回归 |
| 专用MySQL/Redis集成 | 64通过 | 含9项课堂历史测试；uu_classroom_test/专用Redis测试库 |
| 新课堂历史HTTP测试 | 9通过 | 快照稳定、同/跨校权限、状态/时间、并发开始、版本冲突、课程重选、活动作业、归档删除及永久冻结 |
| 前端 | 5项通过、类型/格式通过、双构建通过 | npm run verify |
| Chrome界面 | 通过 | 合成API响应；编辑版本0在刷新列表后仍传0、开始/结束/归档、归档选项过滤，无页面异常 |
| 合成历史迁移 | 通过 | 新随机库重放V1至V12，验证回填来源/时间、视图稳定、无实际结束时间不得完成及冻结初值；临时库已移除 |
| 契约 | 通过 | business.openapi.json全量OpenAPI验证 |
| 根验证 | 27项通过，foundation通过 | git diff --check通过 |

后端命令通过根local_dev.environment(load_values(), test=True)注入专用账号，不向命令/日志输出凭据：

```text
mvn -o -s .mvn/settings.xml -B -Pintegration com.diffplug.spotless:spotless-maven-plugin:3.10.4:check test-compile surefire:test failsafe:integration-test failsafe:verify
```

此命令执行编译、单元、完整数据库回归和格式检查；没有打包替换当前正在运行且可能被Windows锁定的JAR。

前端界面脚本可在启动独立教师端测试服务后复做，全部业务请求均被合成响应拦截：

```text
# frontend目录，终端一
npm exec -- vite apps/teacher --host 127.0.0.1 --port 15173 --strictPort
# frontend目录，终端二；需已安装Chrome
node e2e/lesson-history.cjs
```

本机证据位于Git忽略的.harness：lesson-history-maven.log、lesson-history-migration.json、lesson-history-ui.json、lesson-history-ui.png及lesson-history-summary.json。

## 独立复核及修复

独立只读代码复核发现：表单读取刷新后的版本、归档后旧删除接口未受保护、回填来源在报告中丢失。分别先补回归观察失败，再修复。额外自查并复现“删除分析后重新编辑/删除课堂”的路径，以独立V12保存永久冻结事实。最终全套回归64项通过，未通过删除测试或降门禁规避。

## 验证边界

Chrome使用合成契约响应，不能称作新版本已在本机业务数据库完成浏览器端到端验收；真实HTTP/数据库逻辑由独立集成测试验证。没有对归档与新增报告进行数据库屏障式交错实验，相关共同锁顺序经代码复核，另有并发状态变更回归。

本机业务库未升级，旧服务/JAR与现有候选镜像未被替换。业务升级/回滚须维护窗口备份并整体切换前后端；新旧后端不能混跑写入V11/V12课堂表。本批不计生产发布通过。

多人授课、真实教室/设备绑定、完整录像、分析运行配置快照、真实课堂精度、加速硬件与容量、人工复核和报告发布仍为后续任务。
