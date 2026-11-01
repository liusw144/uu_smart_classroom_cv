import secrets
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import JSONResponse
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from classroom_vision.domain.models import DetectionRequest

router = APIRouter()
bearer = HTTPBearer(auto_error=False)


def require_service(
    request: Request, credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(bearer)]
):
    expected = request.app.state.settings.service_token.get_secret_value()
    if credentials is None or not secrets.compare_digest(credentials.credentials, expected):
        raise HTTPException(status_code=401, detail="内部调用身份无效")


@router.get("/health/live")
def live():
    return {"status": "alive", "version": "0.1.0"}


@router.get("/health/ready")
def ready(request: Request):
    available = request.app.state.engine.ready
    return JSONResponse(status_code=200 if available else 503, content={"ready": available})


@router.post("/internal/v1/detections", dependencies=[Depends(require_service)])
def detect(body: DetectionRequest, request: Request):
    return {"data": request.app.state.engine.detect(body)}
