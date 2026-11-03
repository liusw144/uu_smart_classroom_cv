from pathlib import Path
from typing import Literal

from pydantic import Field, SecretStr, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="VISION_", env_file=".env", extra="ignore")

    app_env: Literal["development", "test", "staging", "production"] = "development"
    service_token: SecretStr
    engine: Literal["unavailable", "onnx"] = "unavailable"
    model_lock: Path = Path("../models/demo-assets.lock.json")
    models_root: Path = Path("../runtime/models")
    asset_cache: Path = Path("../runtime/edge/assets")
    result_root: Path = Path("../runtime/edge/results")
    device: Literal["cpu", "gpu", "npu"] = "cpu"
    device_id: int = Field(default=0, ge=0, le=255)
    cpu_threads: int = Field(default=2, ge=1, le=8)
    sample_interval_ms: int = Field(default=250, ge=100, le=1000)
    max_frames: int = Field(default=32, ge=2, le=32)
    max_video_seconds: int = Field(default=30, ge=1, le=30)
    video_timeout_seconds: int = Field(default=90, ge=5, le=110)
    video_memory_mb: int = Field(default=2048, ge=256, le=8192)
    ffmpeg: str = "ffmpeg"
    tls_ca: Path | None = None
    tls_cert: Path | None = None
    tls_key: Path | None = None

    @model_validator(mode="after")
    def validate_identity(self):
        if self.device == "cpu" and self.device_id != 0:
            raise ValueError("CPU模式的设备编号必须为0")
        if len(self.service_token.get_secret_value()) < 32:
            raise ValueError("内部服务凭据至少需要 32 个字符")
        if self.app_env == "production":
            raise ValueError("生产节点认证与课堂验收尚未完成，禁止使用开发认证启动生产服务")
        if self.app_env == "staging" and not all(
            path is not None and path.is_file()
            for path in (self.tls_ca, self.tls_cert, self.tls_key)
        ):
            raise ValueError("预发布视觉服务必须配置原生双向TLS证书")
        return self
