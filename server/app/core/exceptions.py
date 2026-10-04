from enum import Enum
from typing import Any, Optional
from fastapi import Request, status
from fastapi.responses import JSONResponse
from fastapi.exceptions import RequestValidationError
from starlette.exceptions import HTTPException as StarletteHTTPException


class ErrorCode(str, Enum):
    AUTH_INVALID = "AUTH_INVALID"
    TOKEN_EXPIRED = "TOKEN_EXPIRED"
    TOKEN_REVOKED = "TOKEN_REVOKED"
    TOKEN_REPLAY_DETECTED = "TOKEN_REPLAY_DETECTED"
    FORBIDDEN = "FORBIDDEN"
    NOT_FOUND = "NOT_FOUND"
    CONFLICT = "CONFLICT"
    VALIDATION_ERROR = "VALIDATION_ERROR"
    RATE_LIMIT_EXCEEDED = "RATE_LIMIT_EXCEEDED"
    COPY_LIMIT_REACHED = "COPY_LIMIT_REACHED"
    JOB_EXPIRED = "JOB_EXPIRED"
    INVALID_STATE_TRANSITION = "INVALID_STATE_TRANSITION"
    STORAGE_ERROR = "STORAGE_ERROR"
    NETWORK_ERROR = "NETWORK_ERROR"
    PRINTER_OFFLINE = "PRINTER_OFFLINE"
    HARDWARE_ERROR = "HARDWARE_ERROR"
    INTERNAL_SERVER_ERROR = "INTERNAL_SERVER_ERROR"


class PrivPrintException(Exception):
    def __init__(
        self,
        status_code: int = status.HTTP_400_BAD_REQUEST,
        code: ErrorCode = ErrorCode.VALIDATION_ERROR,
        message: str = "An error occurred",
        details: Optional[Any] = None
    ):
        self.status_code = status_code
        self.code = code
        self.message = message
        self.details = details
        super().__init__(message)


def build_error_response(
    status_code: int,
    code: str,
    message: str,
    details: Optional[Any] = None,
    request_id: Optional[str] = None
) -> JSONResponse:
    content = {
        "error": {
            "code": code,
            "message": message,
            "details": details,
            "request_id": request_id
        }
    }
    return JSONResponse(status_code=status_code, content=content)


async def privprint_exception_handler(request: Request, exc: PrivPrintException) -> JSONResponse:
    request_id = getattr(request.state, "request_id", None)
    return build_error_response(
        status_code=exc.status_code,
        code=exc.code.value,
        message=exc.message,
        details=exc.details,
        request_id=request_id
    )


async def http_exception_handler(request: Request, exc: StarletteHTTPException) -> JSONResponse:
    request_id = getattr(request.state, "request_id", None)
    code = ErrorCode.NOT_FOUND.value if exc.status_code == 404 else "HTTP_ERROR"
    return build_error_response(
        status_code=exc.status_code,
        code=code,
        message=str(exc.detail),
        details=None,
        request_id=request_id
    )


async def validation_exception_handler(request: Request, exc: RequestValidationError) -> JSONResponse:
    request_id = getattr(request.state, "request_id", None)
    return build_error_response(
        status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
        code=ErrorCode.VALIDATION_ERROR.value,
        message="Request validation failed",
        details=exc.errors(),
        request_id=request_id
    )


async def global_exception_handler(request: Request, exc: Exception) -> JSONResponse:
    request_id = getattr(request.state, "request_id", None)
    return build_error_response(
        status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
        code=ErrorCode.INTERNAL_SERVER_ERROR.value,
        message="Internal server error",
        details=str(exc) if getattr(request.app.state, "debug", False) else None,
        request_id=request_id
    )
