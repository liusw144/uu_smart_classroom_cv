# Vue 双应用

Node 22.18.0，npm 10 及以上。依赖固定在 package-lock.json，两套应用共享类型、会话与基础界面，分别构建产物。

```text
npm ci
npm run verify
npm run dev:teacher
npm run dev:admin
```

教师端 5173，管理端 5172。开发代理将 /api 转到本机 Java 8089。两条 dev 命令分别在独立终端运行。当前只有真实登录表单与学校会话展示，没有模拟课堂数据。账号需由开发初始化或后续身份管理流程建立。

生产镜像经 Nginx 将 /api 同源转发到 Java，不把运行密钥打包到 Vite 环境变量。教师与管理端是两个镜像构建目标；UI 入口选择不代替后端权限。
