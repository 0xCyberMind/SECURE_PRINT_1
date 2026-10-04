from typing import Callable
from fastapi import Request, Depends, status

from app.core.config import settings
from app.core.exceptions import PrivPrintException, ErrorCode
from app.services.redis_service import RedisService, get_redis_service


def RateLimiter(action: str, max_requests: int, window_seconds: int) -> Callable:
    """
    FastAPI dependency factory enforcing rate limits via Redis.
    Attaches to endpoints, building unique keys per client IP and action.
    """
    async def dependency(
        request: Request
    ) -> None:
        redis_service = get_redis_service()
        client_ip = "127.0.0.1"
        if request.client and request.client.host:
            client_ip = request.client.host

        # Include forwarded IP if behind proxy
        forwarded_for = request.headers.get("X-Forwarded-For")
        if forwarded_for:
            client_ip = forwarded_for.split(",")[0].strip()

        key = f"{action}:{client_ip}"

        allowed, remaining, retry_after = await redis_service.check_rate_limit(
            key=key,
            max_requests=max_requests,
            window_seconds=window_seconds
        )

        if not allowed:
            raise PrivPrintException(
                status_code=status.HTTP_429_TOO_MANY_REQUESTS,
                code=ErrorCode.RATE_LIMIT_EXCEEDED,
                message=f"Rate limit exceeded for {action}. Retry in {retry_after} seconds.",
                details={"retry_after": retry_after, "action": action}
            )

    return dependency
