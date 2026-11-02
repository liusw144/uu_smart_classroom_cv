from contextlib import asynccontextmanager
from uuid import uuid4

from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from classroom_vision.api.routes import router
from classroom_vision.core.settings import Settings
from classroom_vision.inference.engine import InferenceBusy, ModelUnavailable, UnavailableEngine


def create_app(settings: Settings | None = None) -> FastAPI:
    config = settings or Settings()

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        app.state.settings = config
        if config.engine == "onnx":
            from classroom_vision.inference.onnx_pose import OnnxPoseEngine

            app.state.engine = OnnxPoseEngine(config)
        else:
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
        return error_response(
            error.status_code,
            "UNAUTHORIZED" if error.status_code == 401 else "NOT_FOUND",
            "内部调用身份无效" if error.status_code == 401 else "结果不存在",
        )

    @app.exception_handler(RequestValidationError)
    async def validation_error(_request: Request, _error: RequestValidationError):
        return error_response(422, "INVALID_REQUEST", "请求字段或格式无效")

    @app.exception_handler(ValueError)
    async def invalid_asset(_request: Request, _error: ValueError):
        return error_response(422, "INVALID_ASSET", "素材、模型版本或作业输入无效")

    @app.exception_handler(InferenceBusy)
    async def busy(_request: Request, _error: InferenceBusy):
        return error_response(429, "INFERENCE_BUSY", "推理资源正忙，请稍后重试")

    @app.exception_handler(Exception)
    async def inference_error(_request: Request, _error: Exception):
        return error_response(500, "INFERENCE_FAILED", "推理执行失败，请检查服务端运行日志")

    return app
