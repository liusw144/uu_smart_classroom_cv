"""受控视觉服务入口；预发布要求原生双向TLS。"""

import os
import ssl
from pathlib import Path

import uvicorn

from classroom_vision.core.settings import Settings


def main():
    if os.environ.get("VISION_SERVICE_TOKEN_FILE"):
        with Path(os.environ["VISION_SERVICE_TOKEN_FILE"]).open(encoding="utf-8") as source:
            token = source.read(4097).strip()
        if not 32 <= len(token) <= 512 or not token.isascii():
            raise ValueError("视觉服务凭据文件格式无效")
        os.environ["VISION_SERVICE_TOKEN"] = token
    elif os.environ.get("VISION_APP_ENV") == "staging":
        raise ValueError("预发布视觉服务必须挂载凭据文件")
    settings = Settings()
    tls = {}
    if settings.app_env == "staging":
        tls = {
            "ssl_certfile": str(settings.tls_cert),
            "ssl_keyfile": str(settings.tls_key),
            "ssl_ca_certs": str(settings.tls_ca),
            "ssl_cert_reqs": ssl.CERT_REQUIRED,
        }
    uvicorn.run(
        "classroom_vision.main:create_app",
        factory=True,
        host=os.environ.get("VISION_HOST", "127.0.0.1"),
        port=int(os.environ.get("VISION_PORT", "5000")),
        workers=1,
        limit_concurrency=16,
        timeout_keep_alive=5,
        **tls,
    )


if __name__ == "__main__":
    main()
