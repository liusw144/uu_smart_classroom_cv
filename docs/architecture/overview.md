# 架构与代码边界

统一控制面管理学校、身份、教务、作业和统计，校内边缘执行面处理素材和模型。单机将它们部署在同机；校内私有环境部署完整控制面；纯云把执行节点部署在云端；混合云让校内节点主动出站联系云端控制面。各模式共用代码，不做数据库双主写入。

当前工程实现了身份基础、资源归属领域规则、双前端入口、视觉契约与不可用状态。边缘 Agent、mTLS、持久作业、媒体与真实模型尚未实现。详细目标设计见 hybrid-cloud-design.md；其中“待审定”是先前归档状态，本轮用户指令已授权创建工程基础，当前实际范围以本文及 project-status.md 为准。

## 依赖方向

Java：api → infrastructure → application → domain，api 可依赖应用/领域做 HTTP 映射；domain 与 application 不引入框架。每层内部按 identity、teaching、detection、reporting 等业务能力建包，初期小模块先保持聚焦，不为了套层增加空接口。

前端：teacher/admin → shared。禁止互相引用应用入口，不通过磁盘路径导入后端类型；跨语言类型从 contracts 建立并随接口测试更新。后续具备真实需求时引入路由、状态库、组件库与图表，当前不安装未使用依赖。

Python：api → domain/inference，core 提供配置；模型适配器实现 inference 端口。后续 Agent 为独立进程/包，负责任务租约和本地 Outbox，不与 Web 请求共生命周期。Python 不连接业务 MySQL，不直接处理人类用户会话。

## 权限与数据

Sa-Token 会话位于 Redis。账户由学校代码＋用户名查询，tenant_id 从数据库得到；当前一个账号绑定一个学校，跨学校账号联邦留后续。每次权限查询重新核验启用状态；平台运维不默认获得课堂素材访问权。

业务资源同时校验动作权限、学校和所有权。租户 SQL 插件不是替代手写 SQL 审计的捷径。asset_id 代替共享磁盘路径；路径、URL、请求中的 tenant_id 不能作为信任来源。

## 可靠性目标

后续作业持久化于 MySQL，用租约与 attempt 防止旧执行覆盖，以结果 ID 与哈希实现至少一次交付的去重。节点断网可缓存已领取作业结果，重连后重新校验租约；断网不承诺新登录与完整教务写入。需要完整离线业务的学校部署校内控制面。

FastAPI 当前刻意不就绪且拒绝生产启动，原因是模型与生产节点身份未交付。进程存活、模型就绪、作业执行成功与生产就绪是四种不同状态，不能互相代替。

## 版本与依赖

候选版本已能实际构建：Java 21、Spring Boot 4.1.2、Sa-Token 1.46.0、MyBatis-Plus 3.5.18。前端用 npm 锁文件，Python 用 uv.lock。Java 由父 POM/BOM、插件和直接依赖固定版本；构建使用项目 HTTPS 仓库配置，不修改全局 Maven 设置。

官方参考：[Spring Boot](https://docs.spring.io/spring-boot/system-requirements.html)、[Sa-Token](https://sa-token.com/start/example.html)、[Redis 集成](https://sa-token.com/up/integ-redis.html)、[MyBatis-Plus](https://baomidou.com/getting-started/install/)、[FastAPI 生命周期](https://fastapi.tiangolo.com/advanced/events/)。
