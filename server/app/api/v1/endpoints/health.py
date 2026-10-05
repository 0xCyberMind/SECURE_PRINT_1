from datetime import datetime, timezone
import logging
from fastapi import APIRouter, status
from fastapi.responses import JSONResponse
from sqlalchemy import text

from app.core.config import settings
from app.models.base import engine
from app.schemas.health import HealthResponse
from app.services.redis_service import get_redis_service

router = APIRouter()
logger = logging.getLogger("privprint.health")


async def check_dependencies() -> dict[str, str]:
    checks = {
        "database": "unavailable",
        "redis": "disabled" if not settings.REDIS_ENABLED else "unavailable",
        "storage": "not_checked",
    }

    try:
        async with engine.connect() as connection:
            await connection.execute(text("SELECT 1"))
        checks["database"] = "ready"
    except Exception:
        logger.exception("Database readiness check failed")

    if settings.REDIS_ENABLED:
        try:
            checks["redis"] = (
                "ready" if await get_redis_service().ping() else "unavailable"
            )
        except Exception:
            logger.exception("Redis readiness check failed")

    return checks


def health_response(checks: dict[str, str]) -> HealthResponse:
    database_status = checks.get("database", checks.get("database_driver"))
    return HealthResponse(
        status="healthy" if database_status == "ready" else "unhealthy",
        apiVersion="v1",
        environment=settings.ENVIRONMENT.value,
        timestamp=datetime.now(timezone.utc).isoformat(),
        checks=checks,
    )


@router.get("/health", response_model=HealthResponse, tags=["health"])
async def get_v1_health() -> JSONResponse:
    result = health_response(await check_dependencies())
    response_status = (
        status.HTTP_200_OK
        if result.status == "healthy"
        else status.HTTP_503_SERVICE_UNAVAILABLE
    )
    return JSONResponse(
        status_code=response_status,
        content=result.model_dump(),
    )
