# Vue 双应用

Node 22.18.0，npm 10 及以上。依赖固定在 package-lock.json，两套应用共享类型、会话与基础界面，分别构建产物。

```text
npm ci
npm run verify
npm run dev:teacher
npm run dev:admin
```

教师端 5173，管理端 5172。开发代理将 /api 转到本机 Java 8089。两条 dev 命令分别在独立终端运行。当前提供教务、素材、图片提交/失败记录、教务模板、公告、资料与管理页面，均调用真实Java API；没有模拟课堂检测数据。账号由本机seed或学校管理员维护。详细运行与凭据位置见项目根目录docs/guides/本机最小版本运行指南.md。

四个本机服务运行后执行 `npm run test:e2e`，使用真实Chrome及后端完成业务验收。检查依赖前先停止Vite，再执行npm ci，避免Windows原生模块文件锁。PDF通过浏览器打印导出，真实模型未加载时不能出现检测成功。

生产镜像经 Nginx 将 /api 同源转发到 Java，不把运行密钥打包到 Vite 环境变量。教师与管理端是两个镜像构建目标；UI 入口选择不代替后端权限。
