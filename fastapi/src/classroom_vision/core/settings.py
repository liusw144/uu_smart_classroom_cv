from typing import Literal

from pydantic import SecretStr, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="VISION_", env_file=".env", extra="ignore")

    app_env: Literal["development", "test", "production"] = "development"
    service_token: SecretStr

    @model_validator(mode="after")
    def validate_identity(self):
        if len(self.service_token.get_secret_value()) < 32:
            raise ValueError("内部服务凭据至少需要 32 个字符")
        if self.app_env == "production":
            raise ValueError("生产节点认证及真实模型尚未接入，禁止使用开发认证启动生产服务")
        return self
