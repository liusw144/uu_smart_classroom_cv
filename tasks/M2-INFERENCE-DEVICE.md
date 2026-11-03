# M2-INFERENCE-DEVICE：CPU、GPU 与昇腾 NPU 推理配置

状态：配置能力及本机CPU验证完成；GPU/NPU硬件验收未完成

需求来源与目的：用户要求抽帧检测可选择 GPU、CPU、NPU，默认 CPU；已明确 NPU 为华为昇腾。

涉及模块与允许修改范围：fastapi 的配置、ONNX 会话装配、rtmlib 适配与测试；根本机启动工具的参数转发、配置示例及说明。保留当前工作区已有容量规划修改。

前置任务：M1-MODELS、M2-VIDEO。

## 设计与边界

- 使用服务/节点级 VISION_DEVICE=cpu|gpu|npu、VISION_DEVICE_ID=0；cpu 对应 CPUExecutionProvider，gpu 对应 NVIDIA CUDAExecutionProvider，npu 对应华为 CANNExecutionProvider。默认 cpu；CPU 只允许编号 0。
- 沿用 ONNX、YOLOX 与 RTMPose 的前后处理，统一创建受控会话并注入适配器，避免第三方构造器先创建隐含 CPU 会话。不采用按浏览器请求切设备或并行引入另一套模型框架，减少契约与运行环境变动。
- GPU/NPU 缺失、驱动不兼容、会话实际回退或模型算子不支持时明确失败；禁止自动切回 CPU。关闭加速会话的 CPU 算子回退及运行时重试回退。
- 抽帧、解码、前后处理和视频编码仍在 CPU；设备配置控制两个人物/姿态模型的推理。原有帧数、人数、时长、资源及生产门禁不放宽。
- 视频子进程继承相同设备配置；结果缓存绑定设备设置，旧 CPU 缓存保持兼容，加速设备不能返回旧 CPU 结果。
- CPU 锁文件和默认镜像保持不变。GPU/CANN 需独立平台环境、相应运行时、驱动和设备映射；不在当前 CPU 环境混装多个共享 onnxruntime 命名空间的发行包。

输入输出契约与兼容策略：现有 HTTP 请求/结果字段不变，新增部署环境变量；跨学校权限和用户身份仍由原链路控制。

租户、角色、资源归属与数据保留：不变；不读取真实课堂或实体摄像头，不输出凭据。

失败、重试、取消与幂等：错误沿用 ModelUnavailable；视频资源隔离、持久 Outbox、租约等原机制不变。换设备后的同一作业缓存冲突要求新建作业，防止误认硬件执行证据。

## 实施计划

- [x] 先写配置、Provider 选择/拒绝回退、会话注入、子进程透传及启动参数隔离测试，确认新增行为失败。
- [x] 实现受控会话工厂及两个轻量 rtmlib 适配器，接入 OnnxPoseEngine 和缓存。
- [x] 补充本机/IDEA、独立 GPU/昇腾环境说明与配置示例，更新 backlog、项目状态。
- [x] 运行 FastAPI 全套、根测试和门禁；用本机真实 CPU 模型验证图片及隔离短视频；审查差异。

验收用例及验证命令：FastAPI 中 uv run --frozen pytest、ruff check/format；根 python -m unittest discover -s tests -v、python scripts/quality_gate.py；真实模型仅本机授权示例。

## 执行记录

- 2026-11-04：新增首轮12项FastAPI测试全部因缺失行为失败；根参数转发测试失败。随后新增驱动环境和独立启动环境测试也确认失败，再实现。
- 最终FastAPI全套49项通过，ruff check及format通过；根测试27项通过，foundation门禁通过，git diff --check通过。
- GPU/NPU各自uv lock --check通过；GPU Windows及CANN Linux x86_64/aarch64 frozen sync dry-run通过，解析结果只有对应运行时和headless OpenCV，无CPU发行包；没有安装GPU/NPU环境或修改默认CPU锁文件。
- 真实CPU模型：示例图片5人次，框与17点坐标和原rtmlib适配器在0.001绝对误差内一致；图片检测和隔离子进程12秒视频成功，视频32帧259累计人次；本机缺失CUDA/CANN均明确拒绝。证据.harness/inference-device-cpu.json，不计独立学生人数或课堂精度。
- 当前机器可用Provider仅AzureExecutionProvider、CPUExecutionProvider，不能生成GPU/NPU成功或性能结论。保留原视频资源限制，未构建/部署加速镜像、访问实体设备或执行生产发布。
- 独立代码复核发现并关闭：ORT自动注册未使用CPU Provider导致误拒绝、CANN视频子进程丢失Python算子路径。另核对真实ORT构造器后关闭构造期CPU重试，补充其内部失败路径回归。上述问题均先复现测试失败再修复，通过49项全套回归。复核未验证目标硬件/设备内存/加速镜像，均保留为验收边界。
- 最后一次真实CPU回归：图片1帧5人次、12秒视频32帧259累计人次，原适配器框和关键点对齐；证据记录本次源码SHA256。未提交、推送或重启已有服务，修改配置后由操作者重启vision生效。

完成内容、未验证项、后续任务：GPU/CANN 配置和错误路径使用替身验证；实际硬件、算子兼容、加速隔离限制与吞吐仍须在目标设备验收，不代表生产容量通过。
