# 真实模型、视频、AI与边缘离线本机验收

日期：2026-11-02。项目：`D:\self\uu_smart_classroom_cv`。结论：真实开源模型的本机闭环可运行；不代表课堂算法精度或生产验收。

## 实际运行

| 场景 | 结果 | 证据 |
| --- | --- | --- |
| 公开图片→Vue上传→Java→edge→FastAPI→Java→页面 | 实际检出5个人物，17点姿态；标注PNG已查看 | runtime/real-demo-evidence/image-result.json、annotated.png、image-page.png |
| MMPose公开视频MP4→真实推理与视频播放 | 1秒视频采样4帧，累计31人次，标注H.264 MP4在Chrome可解码播放 | video-result.json、annotated.mp4、video-page.png |
| Qwen3本地实际生成 | 通过本机llama.cpp生成中文，保存LOCAL_QWEN3报告与作业/模型/权重来源 | ai-report.txt、browser-evidence.json |
| 摄像头链路 | Chrome公开视频虚拟设备，实际getUserMedia和Canvas采样，5次真实作业全部成功 | virtual-camera-preview.png、camera-result-page.png、browser-evidence.json |
| 断网与重启 | 领取并缓存→本脚本管理端真实停机→模型计算→边缘新进程断网回传失败但结果保留→管理端恢复→回传成功→重复回传原结果不变 | offline-evidence.json |
| 极端尺寸输入与队列恢复 | 合法20×8000合成PNG在视觉端明确拒绝并保存FAILED；下一项公开图片成功检出5人 | input-failure-evidence.json |

上述证据均在项目`runtime/real-demo-evidence/`，不含模型/学生素材；上游公开视频与球场图片只作本机测试。自动测试没有打开实体摄像头，没有伪造模型成功输出或拦截浏览器业务API。

## 检查结果

| 命令/范围 | 结果 |
| --- | --- |
| 根结构与中文注释门禁 | 通过 |
| 根unittest | 12通过，含凭据不得传给Python/前端及Java清单副本一致性 |
| Java Maven verify和真实MySQL/Redis联调 | 单元10通过，HTTP/Redis集成25通过 |
| Vue格式、类型、单元、双入口构建 | 通过，单元5通过 |
| FastAPI Ruff和pytest | 通过，18通过 |
| edge Ruff和pytest | 通过，4通过 |
| 两份OpenAPI验证及视觉快照测试 | 通过 |
| 核心真实Chrome回归 | 17通过，无失败 |
| 新增真实模型Chrome场景 | 图片、视频、Qwen、5帧虚拟摄像头通过，无pageerror |
| 最新代码独立真实权重推理 | 图片5人、视频4帧31人次；哈希、17点明细及相同作业缓存核验通过 |
| 生产门禁 | 正确拒绝，退出码1 |

组间存在范围重叠，不累加作为产品完成率。日志保存在`.harness/final-integration.log`、`final-frontend.log`、`final-vision.log`、`final-edge.log`、`final-root-tests.log`、`final-real-browser.log`、`final-regression-browser.log`、`offline-real.log`等。

Java集成测试按类隔离JVM，原因是Sa-Token静态管理器与多个Spring测试上下文不能共享已停止的Redis连接工厂；复现了失败后修正测试隔离，再完整重跑25项，未删减测试或放宽断言。Windows运行中的JAR会阻止重新打包，构建前已停止本任务启动的后端。

## 权限和结果校验

覆盖了匿名/普通用户不能领取节点任务、跨学校节点不能领取/下载/回传、伪造及旧租约拒绝、过期重新领取与attempt递增、用户取消撤销租约、跨教师/学校结果不可见。结果校验绑定job/asset/输入SHA/版本/模型SHA/媒体类型，核对帧/人物明细与累计统计、产物大小与SHA。正常重复回传接受，篡改产物重放和缺失字段拒绝。

数据库只使用原有本机专用业务库和测试库；测试清理仅限本测试成功创建的保留学校，预留ID被占用时拒绝覆盖。没有清空Redis实例，也没有停止机器上原有8088服务。

## 独立审查和修复

独立只读复核发现三处重要问题，均已修复并验证：极端图片缩放后产生零维的模型预处理问题（缩放前后检查长宽比，横竖20×8000与2×1280/8000测试）；Java容器缺少外部模型清单问题（公共清单打包classpath，实际JAR与根副本核对）；切换作业时旧AI响应串位/按钮卡住（请求代次保护并重置busy）。边缘增加租约过期的REJECTED终态，避免异常任务永久占据本节点。

另以RED/GREEN测试修正成功回调绕过产物校验和缺失数值字段被默认0接受的问题。真实异常输入测试进一步确认失败任务不会阻止后续真实模型作业。

## 当前限制

- 模型是通用人物/17点姿态；HAND_UP_CANDIDATE仅是关键点阈值规则，尚无课堂标注集、准确率、独立人数或专注度结论。
- Qwen0.6B实际生成中出现数字含义表述偏差；固定结构化统计是数值依据，AI内容需人工核对，不能用于正式评价。
- 实体摄像头、RTSP、24小时运行、长视频完整覆盖、批量并发和多节点容量尚未验收。
- 本次离线为600秒租约内的已缓存任务；不支持长期离线新任务、离线登录或无限续租。
- 开发节点共享令牌只用于本机，生产mTLS、证书轮换、限流、磁盘配额/保留期、数据库恢复和四模式生产安装仍有阻塞。
- Docker真实模型与Linux系统依赖没有实机容器验收。Java清单已打包验证，但不宣称Docker完整运行通过。
- 混合云原始素材留校的独立存储域尚未部署验证；本次所有业务和素材都在同一台本机。

操作与可行性说明见`docs/guides/真实模型本机演示指南.md`。本次保留用户既有CI暂存删除，未自动提交或推送。
