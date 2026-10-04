import uuid
from starlette.middleware.base import BaseHTTPMiddleware, RequestResponseEndpoint
from fastapi import Request, Response


class RequestIdMiddleware(BaseHTTPMiddleware):
    """
    Ensures every request has a correlation/request ID for end-to-end tracing.
    Accepts X-Request-ID from client or generates a fresh UUID4.
    """
    async def dispatch(self, request: Request, call_next: RequestResponseEndpoint) -> Response:
        request_id = request.headers.get("X-Request-ID") or f"req-{uuid.uuid4().hex[:12]}"
        request.state.request_id = request_id

        response = await call_next(request)
        response.headers["X-Request-ID"] = request_id
        return response
