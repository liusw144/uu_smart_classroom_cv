# 受控候选部署

此目录用于预发布验收，不能直接宣称生产可用。教师端、管理端、Java控制面、FastAPI视觉服务和edge分别使用独立镜像；数据库与Redis由受控基础设施提供。本地开发继续使用根README快速开始。当前四模式的真实生产安装和灾备尚未全部验收。

## 准备配置

复制.env.example为.env、control.env.example为control.env、edge.env.example为edge.env，填入实际域名、数据目录和已扫描镜像摘要。两份实际env文件和秘密目录不得提交。control.env只包含非秘密参数；DB_PASSWORD、REDIS_PASSWORD、EDGE_SERVICE_TOKEN、VISION_SERVICE_TOKEN各存一个受控文件，由Compose secrets挂载。节点令牌每节点独立，数据库只保存其摘要，不在所有学校共用令牌。

准备CONTROL_STATE、EDGE_STATE两个持久卷和只读模型目录。Linux宿主机上运行身份为10001:10001，数据目录归此身份且模式0700；证书私钥仅对相应运行身份可读。模型目录包含demo-assets.lock.json和weights下经过哈希核对的两个ONNX文件。不要在启动容器时下载未经审核的权重。

证书目录的文件如下：

| 目录 | 文件及要求 |
| --- | --- |
| CONTROL_CERTS | server.crt、server.key、ca.crt；服务端SAN包含backend和实际控制面域名；ca.crt为可信节点CA |
| VISION_CERTS | server.crt、server.key、ca.crt；SAN包含vision；ca.crt为可信视觉客户端CA |
| EDGE_CERTS | control-ca.crt、node.crt、node.key、vision-ca.crt、vision-client.crt、vision-client.key；两个客户端证书均需clientAuth |
| UI_CERTS | server.crt、server.key、control-ca.crt；前端证书覆盖浏览器实际域名；control-ca.crt验证Java服务 |

正式节点通过ops工具生成待审核绑定SQL；先由受授权运维把节点令牌摘要、学校归属和证书摘要登记到数据库，再启动edge。已有节点换证先登记新证、切换验证、再吊销旧证。视觉服务原生强制客户端证书；Java允许浏览器API无客户端证书，但内部节点API同时要求原生客户端证书、数据库绑定和节点令牌，HTTP转发证书头无效。

## 启动与入口

在项目根目录执行：

```powershell
docker compose --env-file deploy/staging/.env -f deploy/staging/compose.yaml config --quiet
docker compose --env-file deploy/staging/.env -f deploy/staging/compose.yaml up -d
```

默认仅向宿主回环暴露8443控制面、8444教师端、8445管理端。正式入口的DNS、证书、网络策略及防火墙另行验收，不应直接扩大端口绑定到公网。NGINX到Java使用校验CA及backend主机名的HTTPS；浏览器仅访问同源/api，不直连视觉服务。NGINX支持34MiB请求体，Java仍限制单个文件32MiB。

控制面节点只启动backend、teacher、admin；校内节点只启动vision、edge，并把EDGE_CONTROL_URL指向受信的控制面域名。一个edge、一个视觉执行槽、一个节点编号、一个Outbox和独占数据卷；扩容用独立节点目录及凭据。当前不支持靠增加同一容器的Uvicorn workers扩容，启动锁会拒绝共用视觉结果目录。

所有服务使用非root身份、只读根目录、临时文件系统、有限内存/CPU/进程数和轮转日志。边缘health.json包含最近成功轮询时间、磁盘使用与预留；连续180秒无成功轮询会使探针失败。控制面/视觉存活分别检查/health/live；Java就绪检查/actuator/health/readiness，视觉/health/ready需使用客户端证书。容器存活或探针正常都不等于任务已完成，需同时监控作业排队年龄、失败率、Outbox待回传量和磁盘低水位。

## 恢复和资源边界

边缘默认总预算20GiB、剩余空间底线2GiB，预留包含输入替换、临时产物、最终产物和Outbox副本。预算不足不领取新任务；DELIVERED/REJECTED确认后保留1小时再回收，RESULT_READY不因磁盘不足自动删除。401/403保留待回传结果等待修复身份；产物配额413保留结果并尝试续租；服务明确返回过期/冲突时记REJECTED，不能把陈旧结果当成功。

学校默认最多300个在途任务、全局3000、每节点待执行64；待执行超过1天失败关闭。节点90秒无心跳视为离线；30秒维护周期将未领取或租约已过期的作业移到同校有容量的在线节点，三次尝试耗尽则失败。执行槽当前严格为1。旧节点无法凭原租约覆盖重新分配的任务。

Java素材和产物预算分别配置，产物支持ARTIFACT_GLOBAL_BYTES、ARTIFACT_SCHOOL_BYTES、ARTIFACT_MAX_FILES、ARTIFACT_MINIMUM_FREE_BYTES；素材支持STORAGE前缀参数。数据卷需为所有控制面实例可见且提供可靠文件锁/原子移动语义；尚未验收跨主机共享存储时仅部署一个写入控制面，不宣称高可用。长期录像先使用ops.segments分成不超过30秒、32MiB的片段，再通过当前上传流程提交；自动定时采集上传与完整录像编排尚未交付。

升级前暂停接单与edge，备份MySQL、素材、产物、Outbox和配置并验证隔离恢复，再应用新增迁移。V7至V9增加证书绑定/调度保护并移除未生效的并行槽字段，不修改旧迁移。宿主SIGKILL、断电、长断网、多节点和真实硬件须继续演练，不能仅靠SQLite单测推断全部恢复成功。

## 混合云留校边界

当前浏览器上传仍由Java控制面保存原文件，再供edge按租约下载。控制面部署在校内时原文件可留校；若把该控制面直接放云端，原文件也会进入云端。因此此编排尚不能证明“云端管理且原始录像始终留校”。在完成校内Java素材网关、跨站点元数据协议、学校隔离和故障验收之前，云端模式仅限明确允许素材上云的受控样例，正式留校场景仍为发布阻塞。

容量目标为10校、1000个摄像头来源、300间课堂的短片/录像后分析，不是持续实时分析。600秒完成窗口只是工具示例，尚未经用户确认为SLA。公开12秒样例延迟和估算执行槽不构成GPU采购或300课堂实际吞吐保证。
