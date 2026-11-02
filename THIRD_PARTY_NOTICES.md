# 第三方组件与资料说明

本项目原创代码和原创文档使用 [Apache License 2.0](LICENSE)。依赖软件、运行镜像、模型和来源资料分别保留自身许可；本文件不将它们统一改为 Apache 2.0。

## 当前直接依赖

以下为 2026-11-02 根据当前安装包元数据、锁文件和上游许可整理的主要组件。它不是全部传递依赖或最终二进制分发的完整许可清单，构建发行物时还需收集其实际依赖的 LICENSE/NOTICE。

| 组件 | 当前版本 | 许可与来源 |
| --- | --- | --- |
| Spring Boot | 4.1.2 | [Apache-2.0](https://github.com/spring-projects/spring-boot/blob/main/LICENSE.txt) |
| Sa-Token | 1.46.0 | [Apache-2.0](https://github.com/dromara/Sa-Token/blob/master/LICENSE) |
| MyBatis-Plus | 3.5.18 | [Apache-2.0](https://github.com/baomidou/mybatis-plus/blob/3.0/LICENSE) |
| Vue | 3.5.44 | MIT，见安装包及[上游](https://github.com/vuejs/core) |
| Axios | 1.21.0 | MIT，见安装包及[上游](https://github.com/axios/axios) |
| Vite、Vue 插件、vue-tsc、Vitest、Prettier | 见 frontend/package-lock.json | MIT，见相应安装包许可 |
| TypeScript | 5.9.3 | Apache-2.0，见安装包及[上游](https://github.com/microsoft/TypeScript) |
| Playwright | 1.62.1 | Apache-2.0，见安装包及[上游](https://github.com/microsoft/playwright)；仅作浏览器验收工具 |
| FastAPI | 0.162.0 | MIT，见安装包及[上游](https://github.com/fastapi/fastapi) |
| Uvicorn | 0.54.0 | BSD-3-Clause，见安装包及[上游](https://github.com/encode/uvicorn) |
| Pydantic、Pydantic Settings | 见 fastapi/uv.lock | MIT，见相应安装包许可 |
| HTTPX、HTTPX2 | 见 fastapi/uv.lock | BSD-3-Clause，见相应安装包许可 |
| pytest、Ruff、jsonschema | 见 fastapi/uv.lock | MIT，见相应安装包许可 |
| openapi-spec-validator | 0.9.0 | Apache-2.0，见安装包及[上游](https://github.com/python-openapi/openapi-spec-validator) |

Java 的其他直接和传递依赖以 Maven 依赖树及对应制品许可为准；开发工具与运行依赖应分别登记。不得删除随依赖附带的版权声明或 NOTICE。

## 外部运行镜像

Compose 和 Dockerfile 引用的 MySQL、Redis、JDK、Node、Python 与 Nginx 镜像不是由本仓库重新授权的软件。源码仓库提供构建配置，不在本次提交中嵌入这些镜像。

特别是开发配置使用的 Redis 7.4，对应上游 RSALv2 或 SSPLv1 许可，不能按 Apache-2.0 或旧版 Redis 的许可处理；Redis 不同版本的许可存在区别。正式选择、托管或分发镜像前，应按具体版本与实际用途核对。[Redis 官方许可说明](https://redis.io/legal/licenses/)

## 文章与配图

早期需求参考了源码营地公众号的《基于SpringBoot+Vue+YOLOX+RTMPose课堂行为检测与分析系统（源码+数据库脚本）》。[原文链接](https://mp.weixin.qq.com/s/BRqaM_yw_MaIDtcKDA-2NA)

文章配图、含图 Word 和其他本地参考归档保留在开发者本机 docs/reference，未纳入公开 Git 提交。没有确认可再分发的素材不声明为本项目 Apache 授权内容。公开仓库中的架构与接口以本项目自行编写的说明和代码为准，不声称取得原项目源码。

## 模型与课堂数据

源码仓库不分发权重、训练数据或课堂录像。已在本机runtime目录下载YOLOX-tiny、RTMPose-s、Qwen3-0.6B GGUF、llama.cpp CPU运行时及上游演示素材；这些文件被Git忽略。来源、体积及SHA256见models/demo-assets.lock.json，运行方法见docs/guides/真实模型本机演示指南.md。

YOLOX、MMPose、RTMLib的仓库代码分别遵循其上游Apache-2.0许可；模型权重和训练数据的授权须独立检查。Qwen官方GGUF页面声明Apache-2.0；llama.cpp为MIT。上游视频/图片仅用于本机验证，不因位于开源仓库就声明取得本项目Apache再分发许可。本次公开文档不附这些原始媒体。

视觉新增依赖：RTMLib 0.0.15、ONNX Runtime 1.31.1、OpenCV Python及Contrib 4.14.0.94、NumPy 2.5.4；实际版本以fastapi/uv.lock为准。edge独立依赖HTTPX、jsonschema，版本见edge/uv.lock。依赖包附带的LICENSE/NOTICE应随实际发行物收集。FFmpeg使用本机已有可执行程序，不随源码嵌入其二进制；发布其二进制前需按具体构建选项核对许可。

不得将学生素材、可识别身份的信息或未经授权的图片作为公开测试数据提交。
