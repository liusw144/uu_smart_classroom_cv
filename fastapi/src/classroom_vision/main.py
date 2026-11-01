from contextlib import asynccontextmanager
from uuid import uuid4

from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from classroom_vision.api.routes import router
from classroom_vision.core.settings import Settings
from classroom_vision.inference.engine import ModelUnavailable, UnavailableEngine


def create_app(settings: Settings | None = None) -> FastAPI:
    config = settings or Settings()

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        app.state.settings = config
        app.state.engine = UnavailableEngine()
        yield

    app = FastAPI(title="智慧课堂内部视觉服务", version="0.1.0", lifespan=lifespan)
    app.include_router(router)

    def error_response(status: int, code: str, message: str):
        return JSONResponse(
            status_code=status,
            content={"error": {"code": code, "message": message, "request_id": str(uuid4())}},
        )

    @app.exception_handler(ModelUnavailable)
    async def model_unavailable(_request: Request, _error: ModelUnavailable):
        return error_response(503, "MODEL_UNAVAILABLE", "模型尚未通过接入验证")

    @app.exception_handler(HTTPException)
    async def http_error(_request: Request, error: HTTPException):
        return error_response(error.status_code, "UNAUTHORIZED", "内部调用身份无效")

    @app.exception_handler(RequestValidationError)
    async def validation_error(_request: Request, _error: RequestValidationError):
        return error_response(422, "INVALID_REQUEST", "请求字段或格式无效")

    return app
