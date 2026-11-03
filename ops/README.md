# 运维与验收工具

本模块使用Python 3.12和独立uv环境，不引用其他模块源码。先运行 `uv sync --frozen`，再运行 `uv run --frozen ruff check .`、`uv run --frozen ruff format --check .` 和 `uv run --frozen pytest`。

## 节点证书

从ops目录执行 `uv run --frozen python -m classroom_ops.pki lab --out ../.harness/pki-production-tests` 为自动化联调创建临时证书目录；目录必须尚不存在。实验CA不保存签发私钥，不能续签，也不能用于正式部署。

正式证书由学校或组织的受控CA签发，服务端SAN必须覆盖实际访问域名，节点证书必须具有clientAuth用途。`inspect --cert 路径` 检查叶证书；`enrollment-sql --node 节点编号 --cert 路径` 输出待审核绑定SQL；`revoke-sql --fingerprint 摘要` 输出吊销SQL。工具只生成SQL，不自动写业务库。先绑定新证书并验证，再吊销旧证书；保留审核和执行记录。

## 录像分段与容量

可调整批次规划参数见[容量基线](config/capacity-baseline.json)，推导与限制见[容量说明](../docs/production/capacity-baseline.md)。当前按300课堂、每间10分钟、每小时一批、30分钟完成规划，共6000个30秒片段；这是待实测假设，现有benchmark尚未自动读取该文件。

`uv run --frozen python -m classroom_ops.segments --help` 查看本地授权录像的分段选项。工具使用FFmpeg生成不超过30秒的片段，输出摘要和完整性标志；失败或中断的清单不能当作完整输入。它不接入摄像头，不自动上传录像。

`uv run --frozen python -m classroom_ops.benchmark --help` 查看真实视觉HTTP基线参数。默认必须使用CA和客户端证书，只有显式development模式可访问回环HTTP。输入素材必须已进入目标视觉服务缓存。重复3至20次，保留实际延迟、模型摘要、帧数和累计人次。学校均匀分配、70%利用率、用户指定完成窗口只用于估算执行槽，不能替代目标GPU实测或采购依据；300路入队也不能当作300路推理通过。

## 签名发布验证

根目录执行 `python scripts/quality_gate.py --production --release-key 仓库外的公钥路径`。验收信封位于被Git忽略的harness/release-evidence.json，报告建议存于runtime/release-evidence，避免把生成材料反复提交导致版本循环。发布责任人审核报告后，在仓库外签署；本工具不代替责任人签署，不生成假通过材料。

信封、签名、七类报告、镜像摘要和当前干净Git提交必须一致；详见docs/production/release.md。没有真实课堂精度、目标硬件容量和留校部署证据时，生产门禁继续拒绝。
