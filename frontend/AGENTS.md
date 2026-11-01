# 前端模块约束

apps/teacher 与 apps/admin 是独立入口，packages/shared 只放双方共享的组件、类型和 API 能力。按 features、pages、components 逐步扩展业务；页面中不拼接任意服务 URL。

只访问相对路径 /api，经开发代理或同源网关到 Java。禁止引用 backend、fastapi 源码或直接调用视觉服务。令牌只保存在内存；401 清理，退出清理；不写 localStorage，不把用户权限判断当服务端授权。

在 frontend 使用 `npm ci` 与 `npm run verify`。依赖修改提交 package-lock.json；新增组件库、状态库与路由库按实际需求接入，不为目录完整而提前放置空实现。代码注释全部中文。
