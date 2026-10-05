from contextlib import asynccontextmanager
from fastapi import FastAPI, Request, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.core.config import settings
from app.core.logging import setup_logging, logger
from app.core.exceptions import (
    PrivPrintException,
    privprint_exception_handler,
    http_exception_handler,
    validation_exception_handler,
    global_exception_handler,
)
from app.api.middleware import RequestIdMiddleware
from app.api.v1.router import api_v1_router
from app.api.v1.endpoints.health import check_dependencies, health_response
from app.schemas.health import HealthResponse


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Startup
    setup_logging(debug=settings.DEBUG)
    logger.info(
        f"Starting {settings.PROJECT_NAME} in [{settings.ENVIRONMENT.value}] mode on port {settings.PORT}"
    )
    yield
    # Shutdown
    logger.info(f"Shutting down {settings.PROJECT_NAME}")


def create_application() -> FastAPI:
    app = FastAPI(
        title=settings.PROJECT_NAME,
        version="1.0.0",
        description="PrivPrint Enterprise Zero-Knowledge Spooler & Relay Cloud API",
        openapi_url=f"{settings.API_V1_STR}/openapi.json" if settings.ENVIRONMENT.value != "production" else None,
        docs_url=f"{settings.API_V1_STR}/docs" if settings.ENVIRONMENT.value != "production" else None,
        redoc_url=f"{settings.API_V1_STR}/redoc" if settings.ENVIRONMENT.value != "production" else None,
        lifespan=lifespan,
    )

    # Middleware: Request ID / Correlation ID (must be outer)
    app.add_middleware(RequestIdMiddleware)

    # Middleware: CORS
    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.CORS_ORIGINS if isinstance(settings.CORS_ORIGINS, list) else [settings.CORS_ORIGINS],
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
        expose_headers=["X-Request-ID"],
    )

    # Register Exception Handlers
    app.add_exception_handler(PrivPrintException, privprint_exception_handler)
    app.add_exception_handler(StarletteHTTPException, http_exception_handler)
    app.add_exception_handler(RequestValidationError, validation_exception_handler)
    app.add_exception_handler(Exception, global_exception_handler)

    # Mount API v1 router
    app.include_router(api_v1_router, prefix=settings.API_V1_STR)

    # Root health probe: /healthz
    @app.get("/healthz", response_model=HealthResponse, tags=["health"])
    async def root_healthz() -> JSONResponse:
        checks = await check_dependencies()
        result = health_response({
            "api": "healthy",
            "database_driver": checks["database"],
            "redis_client": checks["redis"],
            "storage_client": checks["storage"],
        })
        response_status = (
            status.HTTP_200_OK
            if result.status == "healthy"
            else status.HTTP_503_SERVICE_UNAVAILABLE
        )
        return JSONResponse(
            status_code=response_status,
            content=result.model_dump(),
        )

    return app


app = create_application()
