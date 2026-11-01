from typing import Protocol

from classroom_vision.domain.models import DetectionRequest


class ModelUnavailable(RuntimeError):
    pass


class InferenceEngine(Protocol):
    @property
    def ready(self) -> bool: ...

    def detect(self, request: DetectionRequest) -> dict: ...


class UnavailableEngine:
    @property
    def ready(self) -> bool:
        return False

    def detect(self, request: DetectionRequest) -> dict:
        raise ModelUnavailable("尚未接入经过验证的模型与行为规则")
