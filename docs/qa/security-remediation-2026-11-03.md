# 安全修复验收记录

日期：2026-11-03。工作区：D:\self\uu_smart_classroom_cv。基础提交：a69dfc0c7514ad1198a05bc3a7fbfad48776ed30。当前改动未提交或推送。原始扫描为部分覆盖，原报告与其扫描工件保持不变。

结论：原扫描三项问题已实现修复并通过注明的本机验收；不具备整体生产发布资格。参数和升级约束见../security/2026-11-03-安全修复与上线边界.md。

## 修复内容

| 原问题 | 修复 | 主要验证 |
| --- | --- | --- |
| 匿名登录可无限执行BCrypt | Redis原子账号/来源/全局预算、真实账号编号补充预算、本机并发槽及集群租约；异常失败关闭 | 无效账号429、Retry-After、跨实例共享槽、别名真实编号、伪造转发头不能分散来源、Redis故障不泄漏本机槽、拒绝后不执行密码校验、未知账号虚拟哈希 |
| 累积上传无限占用磁盘 | V6多级字节/数量账本、PENDING不可见、排他文件锁、原子发布、持久删除回收、物理容量水位 | 4个并发请求只接受2个，学校/全局及字节/数量独立限制，管理员代传计入教师，引用409不减账，写后失败、删除失败、锁内回收和重复回收 |
| 视频解码没有可终止的资源边界 | 全作业子进程、Windows挂起创建后分配Job并恢复、Linux进程组/资源限制、墙钟期限、结果核验后发布 | 超时与模拟编码后代终止、实际解释器属于目标Job、内存拒绝、分配失败不开始处理、无效元数据不发布、真实模型与幂等重放 |

新增429、413、503、507业务契约。同步修正原心跳/失败回调引用不存在的nodeToken安全方案为已有edgeToken；认证行为不变，业务OpenAPI完整校验通过。根启动器新增安全参数白名单，并验证仅传给对应模块。

## 失败复现与独立复核

修复前真实HTTP登录测试期望429而收到401；配额测试期望2个成功却有4个成功。两者日志保存在.harness/security-login-red.log、security-quota-red.log。视频隔离新增测试先因模块缺失失败，再实现限制。

一次修复前独立调查核对调用方、引用约束、暂停写入和回收竞争；一次最终独立只读复核发现Windows虚拟环境启动器可能在Job分配前派生实际解释器。已改为CREATE_SUSPENDED→AssignProcessToJobObject→恢复唯一初始线程→输入握手。

精确回归在父进程持目标Job句柄，对真实解释器PID执行IsProcessInJob；延迟分配放大旧窗口。模拟旧启动时序时回归失败，当前实现通过。QueryInformationJobObject(NULL)只查询直接关联的嵌套Job，曾导致测试误判，已移除该判据。独立复核以相同的精确归属检查确认新路径有效，其他已审范围未发现具体剩余绕过。复核的其他结论限静态代码，没有冒充执行数据库、模型或Linux测试。

另先观察本地启动器不转发安全参数、无效子进程元数据被归为输入异常的失败，再补白名单及503异常归类并验证通过。

## 实际检查

| 检查 | 结果 |
| --- | --- |
| 工程边界与中文注释门禁 | 通过 |
| 根unittest | 23项通过 |
| Java21完整verify、Spotless、打包 | 通过；随后新增测试后再次执行完整单元/集成组 |
| Java单元 | 12项通过 |
| MySQL8.4、Redis真实HTTP集成 | 45项通过；专用uu_classroom_test与Redis15，不使用业务库清理夹具 |
| 前端 | 格式、类型、5项测试和双应用构建通过 |
| FastAPI | 冻结依赖、Ruff、格式、30项pytest通过 |
| edge | 冻结依赖、Ruff、格式、8项测试通过 |
| Chrome真实页面 | 17项通过，包含真实视觉模式的作业执行与业务清理 |
| 业务OpenAPI | 完整规范校验通过 |
| 真实YOLOX/RTMPose | 短片4帧31累计人次，12秒片32帧259累计人次；缓存重放一致 |
| 异常后恢复 | 损坏视频拒绝、256MiB不足预算拒绝、无成功元数据；随后正常图片检测5人；暂存目录已清理 |
| 完整本机链路 | Java上传/排队→edge→FastAPI隔离视频→结果回传→本地Qwen报告成功；结果4帧31累计人次，报告来源LOCAL_QWEN3 |
| 生产门禁 | 继续拒绝；不能用本轮修复代替生产证据 |

各组有重复，不累计为产品完成率。累计人次不是独立人数，不表示课堂行为准确率。首轮浏览器回归遇到维护期间边缘节点离线503；恢复自有edge进程后17项通过，失败测试留下的唯一测试账号及素材已校验归属并通过业务API清除，没有裸SQL删账。

## 本机升级与服务

先核实项目进程归属并停止Java、edge写入，创建并校验升级前备份：runtime/backups/20261103-020506-bb6442。升级前29条登记素材与29个物理文件一致，无缺失、孤儿或大小不符。新后端Flyway已从V5迁移V6，回填总字节与文件计数核对一致；业务素材保留。

本项目Java、视觉和edge进程已恢复，仅监听原有本机端口；MySQL、Redis、Qwen及双前端保留。新增公开样例视频和AI报告保留为可审阅的本机结果，编号见.harness/security-live-video.json。没有访问实体摄像头或RTSP，没有更改系统全局配置。

## 重跑及证据位置

按根README准备独立环境与本机配置后，停止运行中的本项目Java以避免Windows打包文件占用，运行python scripts/local_dev.py test；该脚本使用专用测试库。根python -m unittest discover -s tests -v及python scripts/quality_gate.py验证结构，生产检查另加--production且目前必须失败。

FastAPI运行uv sync --frozen、uv run ruff check .、uv run ruff format --check .、uv run pytest；edge同样执行其模块命令；前端npm run verify；已启动本机全链路时运行node e2e/local-mvp.cjs。不要在已有其他边缘作业的环境运行会主动领取任务的历史离线演练脚本。

本机原始证据：.harness/security-backend-verify.log（打包与首轮完整组）、security-backend-final.log（最终12/45）、security-real-video.json、security-live-video.json、security-migration.json、security-pre-migration-inventory.json、local-e2e/results.json。pytest及根/前端/edge命令的执行结果也已在本次任务中实际读取确认；不把旧报告数量当成本轮执行证据。

## 未覆盖与上线阻塞

Windows边界已实测；Linux实现未在本轮Linux主机执行，RLIMIT是每进程限制，仍需容器cgroup总量限制和真实Linux回归。模拟编码后代用于进程树超时回收，真实FFmpeg正常编码已通过，未注入真实编码器挂死。素材配额只覆盖原始素材，边缘缓存、产物、报告、日志、备份的总量与保留期仍待实现。多副本必须共享可靠锁语义的同一素材卷，独立本地卷/对象存储不在本轮适配范围。

未执行在线全依赖漏洞核查、远端GitHub CI、容器构建、生产mTLS/证书轮换、真实校园设备、课堂精度、多节点容量及生产部署/灾备回滚。本机Maven插件前缀解析曾出现全局镜像HTTP元数据和校验警告；发布构建还需隔离机器级Maven配置并核验依赖供应链，本轮没有更改用户全局配置。生产模式拒绝启动及harness生产门禁继续保留。
