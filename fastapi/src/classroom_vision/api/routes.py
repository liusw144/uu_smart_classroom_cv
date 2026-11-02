import hashlib
import json
import secrets
from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import FileResponse, JSONResponse
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from classroom_vision.domain.errors import ErrorEnvelope
from classroom_vision.domain.models import DetectionRequest
from classroom_vision.domain.results import DetectionEnvelope

router = APIRouter()
bearer = HTTPBearer(auto_error=False)


def require_service(
    request: Request, credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(bearer)]
):
    expected = request.app.state.settings.service_token.get_secret_value()
    supplied = credentials.credentials if credentials else ""
    if (
        not supplied
        or not supplied.isascii()
        or len(supplied) > 512
        or not secrets.compare_digest(supplied.encode("ascii"), expected.encode("utf-8"))
    ):
        raise HTTPException(status_code=401, detail="内部调用身份无效")


@router.get("/health/live")
def live():
    return {"status": "alive", "version": "0.1.0"}


@router.get("/health/ready")
def ready(request: Request):
    available = request.app.state.engine.ready
    return JSONResponse(status_code=200 if available else 503, content={"ready": available})


@router.post(
    "/internal/v1/detections",
    dependencies=[Depends(require_service)],
    response_model=DetectionEnvelope,
    responses={
        401: {"model": ErrorEnvelope, "description": "内部身份无效"},
        422: {"model": ErrorEnvelope, "description": "请求字段无效"},
        503: {"model": ErrorEnvelope, "description": "模型尚不可用"},
        429: {"model": ErrorEnvelope, "description": "推理资源正忙"},
        500: {"model": ErrorEnvelope, "description": "推理执行失败"},
    },
)
def detect(body: DetectionRequest, request: Request):
    return {"data": request.app.state.engine.detect(body)}


@router.get("/internal/v1/detections/{job}/artifact", dependencies=[Depends(require_service)])
def artifact(job: UUID, request: Request):
    root = request.app.state.settings.result_root.resolve()
    metadata = root / f"{job}.json"
    if not metadata.is_file() or metadata.is_symlink():
        raise HTTPException(status_code=404, detail="结果不存在")
    result = json.loads(metadata.read_text(encoding="utf-8"))
    suffix = ".mp4" if result["artifact_content_type"] == "video/mp4" else ".png"
    target = root / f"{job}{suffix}"
    if not target.is_file() or target.is_symlink():
        raise HTTPException(status_code=404, detail="结果不存在")
    with target.open("rb") as source:
        if hashlib.file_digest(source, "sha256").hexdigest() != result["artifact_sha256"]:
            raise ValueError("结果哈希校验失败")
    return FileResponse(
        target, media_type=result["artifact_content_type"], headers={"Cache-Control": "no-store"}
    )
