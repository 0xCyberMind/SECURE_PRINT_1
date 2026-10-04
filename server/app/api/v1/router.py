from fastapi import APIRouter
from app.api.v1.endpoints import (
    health,
    auth,
    shops,
    devices,
    sessions,
    documents,
    jobs,
    print_spooler,
    realtime,
    printers,
    cleanup
)

api_v1_router = APIRouter()

# Register endpoint routers
api_v1_router.include_router(health.router, prefix="", tags=["health"])
api_v1_router.include_router(auth.router, prefix="/auth", tags=["auth"])
api_v1_router.include_router(shops.router, prefix="/shops", tags=["shops"])
api_v1_router.include_router(printers.router, prefix="/printers", tags=["printers"])
api_v1_router.include_router(devices.router, prefix="/devices", tags=["devices"])
api_v1_router.include_router(sessions.router, prefix="/sessions", tags=["sessions"])
api_v1_router.include_router(documents.router, prefix="/documents", tags=["documents"])
api_v1_router.include_router(jobs.router, prefix="/jobs", tags=["jobs"])
api_v1_router.include_router(print_spooler.router, prefix="/print", tags=["print"])
api_v1_router.include_router(realtime.router, prefix="/realtime", tags=["realtime"])
api_v1_router.include_router(cleanup.router, prefix="/cleanup", tags=["cleanup"])

