import pytest
import pytest_asyncio
import sys
import os
import asyncio
from typing import AsyncGenerator
from sqlalchemy.ext.asyncio import create_async_engine, async_sessionmaker, AsyncSession
from sqlalchemy.pool import StaticPool

# Add server and repository root directories to sys.path for test collection
repo_root = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
server_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
sys.path.insert(0, repo_root)
sys.path.insert(0, server_dir)

from fastapi.testclient import TestClient
from app.main import app as fastapi_app
from app.models.base import Base, get_db_session
from app.models.entities import (
    User, Shop, Device, Printer, Session, Document, PrintJob, RefreshToken, AuditLog
)
from app.services.redis_service import get_redis_service
from app.core.security import create_access_token, get_password_hash
from app.models.enums import UserRole
from app.repositories.user_repo import UserRepository

TEST_DB_URL = "sqlite+aiosqlite:///:memory:"

test_engine = create_async_engine(
    TEST_DB_URL,
    connect_args={"check_same_thread": False},
    poolclass=StaticPool,
)

TestAsyncSessionLocal = async_sessionmaker(
    bind=test_engine,
    class_=AsyncSession,
    expire_on_commit=False,
    autocommit=False,
    autoflush=False
)


async def override_get_db_session() -> AsyncGenerator[AsyncSession, None]:
    async with TestAsyncSessionLocal() as session:
        yield session


fastapi_app.dependency_overrides[get_db_session] = override_get_db_session


@pytest_asyncio.fixture(autouse=True, scope="function")
async def setup_test_database(monkeypatch):
    from app.api.v1.endpoints import health as health_endpoint

    redis_svc = get_redis_service()
    redis_svc.set_simulation_failure(False)
    redis_client = await redis_svc.get_client()
    if redis_client:
        try:
            await redis_client.flushdb()
        except Exception:
            pass

    async with test_engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    monkeypatch.setattr(health_endpoint, "engine", test_engine)
    yield
    async with test_engine.begin() as conn:
        await conn.run_sync(Base.metadata.drop_all)


@pytest.fixture(scope="session")
def client() -> TestClient:
    with TestClient(fastapi_app) as test_client:
        yield test_client


@pytest_asyncio.fixture(scope="function")
async def db_session() -> AsyncGenerator[AsyncSession, None]:
    async with TestAsyncSessionLocal() as session:
        yield session


@pytest_asyncio.fixture
async def admin_headers(db_session: AsyncSession) -> dict[str, str]:
    admin = await UserRepository(db_session).create(
        email="test_admin@example.com",
        hashed_password=get_password_hash("AdminPassword123!"),
        role=UserRole.ADMIN.value,
        is_active=True,
        is_verified=True,
    )
    await db_session.commit()
    token = create_access_token(subject=admin.id, role=admin.role)
    return {"Authorization": f"Bearer {token}"}
