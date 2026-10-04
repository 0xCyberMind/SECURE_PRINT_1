from datetime import datetime, timezone
from fastapi import APIRouter
from app.core.config import settings
from app.schemas.health import HealthResponse

router = APIRouter()


@router.get("/health", response_model=HealthResponse, tags=["health"])
async def get_v1_health() -> HealthResponse:
    return HealthResponse(
        status="healthy",
        apiVersion="v1",
        environment=settings.ENVIRONMENT.value,
        timestamp=datetime.now(timezone.utc).isoformat(),
        checks={
            "database": "ready",
            "redis": "ready",
            "storage": "ready"
        }
    )
