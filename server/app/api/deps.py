from typing import List, Optional
from fastapi import Depends, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy.ext.asyncio import AsyncSession
from app.core.exceptions import ErrorCode, PrivPrintException
from app.core.security import decode_access_token
from app.models.enums import UserRole
from app.models.entities import User
from app.models.base import get_db_session
from app.repositories.user_repo import UserRepository
from app.repositories.shop_repo import ShopRepository, DeviceRepository

security = HTTPBearer(auto_error=False)


class AuthPrincipal:
    def __init__(
        self,
        user: User,
        role: UserRole,
        shop_id: Optional[str] = None,
        device_id: Optional[str] = None
    ):
        self.user = user
        self.user_id = user.id
        self.role = role
        self.shop_id = shop_id
        self.device_id = device_id


async def get_principal_from_token_str(token: str, db: AsyncSession) -> AuthPrincipal:
    payload = decode_access_token(token)
    user_id = payload.get("sub")

    if not user_id:
        raise PrivPrintException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            code=ErrorCode.AUTH_INVALID,
            message="Malformed token claims: missing subject"
        )

    user_repo = UserRepository(db)
    user = await user_repo.get_by_id(user_id)
    if not user:
        raise PrivPrintException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            code=ErrorCode.AUTH_INVALID,
            message="User account not found"
        )

    if not user.is_active:
        raise PrivPrintException(
            status_code=status.HTTP_403_FORBIDDEN,
            code=ErrorCode.FORBIDDEN,
            message="User account is deactivated"
        )

    try:
        server_role = UserRole(user.role)
    except ValueError:
        raise PrivPrintException(
            status_code=status.HTTP_403_FORBIDDEN,
            code=ErrorCode.FORBIDDEN,
            message="Invalid server-side user role"
        )

    shop_id: Optional[str] = None
    device_id: Optional[str] = None

    if server_role == UserRole.SHOP_OPERATOR:
        from sqlalchemy import select
        from app.models.entities import Shop
        res = await db.execute(select(Shop).where(Shop.owner_id == user.id))
        shop = res.scalars().first()
        if shop:
            shop_id = shop.id

    elif server_role == UserRole.PRINT_DEVICE:
        device_repo = DeviceRepository(db)
        dev = await device_repo.get_by_id(user.id)
        if dev:
            device_id = dev.id
            shop_id = dev.shop_id

    return AuthPrincipal(
        user=user,
        role=server_role,
        shop_id=shop_id,
        device_id=device_id
    )


async def get_current_user(
    credentials: Optional[HTTPAuthorizationCredentials] = Depends(security),
    db: AsyncSession = Depends(get_db_session),
) -> AuthPrincipal:
    if not credentials:
        raise PrivPrintException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            code=ErrorCode.AUTH_INVALID,
            message="Bearer token required"
        )

    return await get_principal_from_token_str(credentials.credentials, db)


def require_roles(allowed_roles: List[UserRole]):
    """
    Factory for role-based authorization guard.
    ADMIN role has automatic superuser access.
    """
    def role_checker(principal: AuthPrincipal = Depends(get_current_user)) -> AuthPrincipal:
        if principal.role not in allowed_roles and principal.role != UserRole.ADMIN:
            raise PrivPrintException(
                status_code=status.HTTP_403_FORBIDDEN,
                code=ErrorCode.FORBIDDEN,
                message=f"Access denied for role: {principal.role.value}. Required: {[r.value for r in allowed_roles]}"
            )
        return principal

    return role_checker
