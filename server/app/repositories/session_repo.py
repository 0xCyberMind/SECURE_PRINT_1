from typing import Optional, List
from datetime import datetime, timezone
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from app.models.entities import Session, RefreshToken, AuditLog
from app.models.enums import SessionStatus
from app.repositories.base import BaseRepository


class SessionRepository(BaseRepository[Session]):
    def __init__(self, session: AsyncSession):
        super().__init__(Session, session)

    async def get_by_token(self, token: str) -> Optional[Session]:
        result = await self.session.execute(
            select(Session).where(
                Session.token == token,
                Session.status == SessionStatus.ACTIVE.value,
                Session.expires_at > datetime.now(timezone.utc)
            )
        )
        return result.scalars().first()

    async def revoke_session(self, session_id: str, shop_id: Optional[str] = None) -> bool:
        stmt = select(Session).where(Session.id == session_id)
        if shop_id:
            stmt = stmt.where(Session.shop_id == shop_id)
        result = await self.session.execute(stmt)
        sess = result.scalars().first()
        if not sess:
            return False
        sess.status = SessionStatus.REVOKED.value
        await self.session.flush()
        return True


class RefreshTokenRepository(BaseRepository[RefreshToken]):
    def __init__(self, session: AsyncSession):
        super().__init__(RefreshToken, session)

    async def get_by_jti(self, jti: str) -> Optional[RefreshToken]:
        result = await self.session.execute(select(RefreshToken).where(RefreshToken.jti == jti))
        return result.scalars().first()

    async def get_by_token_hash(self, token_hash: str) -> Optional[RefreshToken]:
        result = await self.session.execute(
            select(RefreshToken).where(RefreshToken.token_hash == token_hash)
        )
        return result.scalars().first()

    async def revoke_jti(self, jti: str) -> bool:
        token = await self.get_by_jti(jti)
        if token and not token.is_revoked:
            token.is_revoked = True
            token.revoked_at = datetime.now(timezone.utc)
            await self.session.flush()
            return True
        return False

    async def revoke_by_token_hash(self, token_hash: str) -> bool:
        token = await self.get_by_token_hash(token_hash)
        if token and not token.is_revoked:
            token.is_revoked = True
            token.revoked_at = datetime.now(timezone.utc)
            await self.session.flush()
            return True
        return False

    async def revoke_all_for_user(self, user_id: str) -> int:
        now = datetime.now(timezone.utc)
        result = await self.session.execute(
            select(RefreshToken).where(
                RefreshToken.user_id == user_id,
                RefreshToken.is_revoked == False
            )
        )
        tokens = list(result.scalars().all())
        for t in tokens:
            t.is_revoked = True
            t.revoked_at = now
        await self.session.flush()
        return len(tokens)


class AuditLogRepository(BaseRepository[AuditLog]):
    def __init__(self, session: AsyncSession):
        super().__init__(AuditLog, session)

    async def log_event(
        self,
        event_type: str,
        details: str,
        severity: str = "INFO",
        user_id: Optional[str] = None,
        shop_id: Optional[str] = None,
        job_id: Optional[str] = None,
        ip_address: Optional[str] = None
    ) -> AuditLog:
        return await self.create(
            event_type=event_type,
            details=details,
            severity=severity,
            user_id=user_id,
            shop_id=shop_id,
            job_id=job_id,
            ip_address=ip_address
        )
