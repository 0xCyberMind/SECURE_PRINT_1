import uuid
import secrets
from datetime import datetime, timedelta, timezone
from typing import Optional
from fastapi import APIRouter, Depends, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.exceptions import PrivPrintException, ErrorCode
from app.models.base import get_db_session
from app.models.entities import Session
from app.models.enums import SessionStatus, UserRole
from app.repositories.shop_repo import ShopRepository
from app.repositories.session_repo import SessionRepository
from app.schemas.shop import SessionCreateRequest, SessionRevokeRequest, SessionResponse
from app.api.deps import get_current_user, AuthPrincipal
from app.api.deps_rate_limit import RateLimiter
from app.services.redis_service import get_redis_service
from app.core.config import settings

router = APIRouter()


@router.post(
    "",
    response_model=SessionResponse,
    status_code=status.HTTP_201_CREATED,
    dependencies=[Depends(RateLimiter("session_create", settings.RATE_LIMIT_SESSION_CREATE_MAX, settings.RATE_LIMIT_SESSION_CREATE_WINDOW_SECONDS))]
)
async def create_session(
    payload: SessionCreateRequest,
    db: AsyncSession = Depends(get_db_session),
    principal: Optional[AuthPrincipal] = Depends(get_current_user),
) -> SessionResponse:
    # Validate shop existence
    shop_repo = ShopRepository(db)
    shop = await shop_repo.get_by_id(payload.shop_id)
    if not shop:
        raise PrivPrintException(
            status_code=status.HTTP_404_NOT_FOUND,
            code=ErrorCode.NOT_FOUND,
            message=f"Shop {payload.shop_id} does not exist"
        )

    now = datetime.now(timezone.utc)
    # Ephemeral session expiration: 15 minutes
    expires_at = now + timedelta(minutes=15)

    # Cryptographically secure random identifier, token, and nonce
    session_id = f"SES-{uuid.uuid4().hex[:10].upper()}"
    pairing_token = f"tok_{secrets.token_urlsafe(32)}"
    nonce = secrets.token_hex(16)

    session_repo = SessionRepository(db)
    session = await session_repo.create(
        id=session_id,
        user_id=principal.user_id if principal else None,
        shop_id=shop.id,
        token=pairing_token,
        nonce=nonce,
        status=SessionStatus.ACTIVE.value,
        expires_at=expires_at
    )
    await db.commit()

    return SessionResponse.model_validate(session)


@router.post("/{session_id}/revoke")
async def revoke_session(
    session_id: str,
    payload: Optional[SessionRevokeRequest] = None,
    db: AsyncSession = Depends(get_db_session),
    principal: Optional[AuthPrincipal] = Depends(get_current_user),
) -> dict:
    """
    Emergency revocation endpoint used by the customer's Privacy Center.
    Only the session owner (or an ADMIN) may revoke a session.
    """
    session_repo = SessionRepository(db)
    session = await session_repo.get_by_id(session_id)
    if not session:
        raise PrivPrintException(
            status_code=status.HTTP_404_NOT_FOUND,
            code=ErrorCode.NOT_FOUND,
            message=f"Session {session_id} not found"
        )

    if session.user_id and principal and principal.user_id != session.user_id and principal.role != UserRole.ADMIN:
        raise PrivPrintException(
            status_code=status.HTTP_403_FORBIDDEN,
            code=ErrorCode.FORBIDDEN,
            message="Access denied: you do not own this session"
        )

    await session_repo.revoke_session(session_id)
    await db.commit()

    return {
        "success": True,
        "sessionId": session_id,
        "status": SessionStatus.REVOKED.value,
        "reason": payload.reason if payload and payload.reason else "User emergency revocation"
    }
