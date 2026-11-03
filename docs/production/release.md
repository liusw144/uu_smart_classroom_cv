# 生产发布约束

当前仍不允许生产放行：harness/manifest.json中有尚未完成的验收。工程测试、300次HTTP入队、公开样例推理与候选容器构建各自证明有限范围，不能替代课堂精度、目标硬件和混合云留校验收。

发布前完成业务与异常验收、逐类课堂评测、租户隔离、节点证书轮换与吊销、恢复与保留策略、目标硬件容量、监控告警、数据库迁移及回滚。镜像经漏洞扫描并固定digest，保存依赖和模型来源；禁止将开发Compose暴露公网。候选部署的配置和边界见deploy/staging/README.md。

## 版本和材料

先完成代码评审并提交候选版本，构建并验收该版本，再由独立发布责任人签署。生成报告放在被Git忽略的runtime/release-evidence，信封放在被Git忽略的harness/release-evidence.json，避免生成材料引起Git版本循环。正式公钥从仓库外受控配置提供，签署私钥不交给应用、不进入仓库。

信封为JSON，包含payload和signature两个标准Base64字符串。payload是签署前的原始UTF-8 JSON字节；验证器直接验证这些字节，不自行重新序列化。signature是Ed25519签名。payload字段如下：

| 字段 | 要求 |
| --- | --- |
| schema_version | 1 |
| revision | 当前干净工作区HEAD的40位提交摘要 |
| issued_at、expires_at | 含时区的ISO日期；当前有效且授权不超过7天 |
| target | schools至少10、cameras至少1000、concurrent_analysis至少300 |
| reports | model、tenant_isolation、recovery、capacity、deployment、residency、approval七类，缺一拒绝 |
| 每类报告 | passed必须为true，path为仓库内文件相对路径，sha256与实际文件一致 |
| images | backend、teacher、admin、vision、edge五个镜像，均使用仓库地址@sha256:摘要 |

根目录运行 `python scripts/quality_gate.py --production --release-key 仓库外的Ed25519公钥文件`。ops独立环境必须已用uv sync --frozen安装。门禁同时检查未解决阻塞、外部签名、有效期、目标规模、当前版本、材料摘要和镜像摘要格式；它不自动清空阻塞或签署发布。只有真实验收并经责任人批准后才可更新阻塞清单。签名有效说明责任人授权了材料，不等于软件能自动证明材料事实。

## 密钥与数据库

生产配置由密钥系统或受控文件挂载注入，不复用开发账号和令牌，关闭bootstrap。TLS必须验证服务端主机名和客户端证书，不信任HTTP转发证书头。数据库只应用已评审新增迁移；备份并验证恢复后升级。回滚应用前先确认其兼容当前数据库版本，不能通过删除Flyway记录或修改已部署迁移回滚。

FastAPI目前保留production环境拒绝启动；staging用于受控候选验收。不得把环境名称改成development或取消拒绝逻辑来宣称生产通过。真正的生产放行需要完成剩余验收并评审解除此限制。
