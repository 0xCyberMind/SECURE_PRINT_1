import uuid
import secrets
from datetime import datetime, timezone
from typing import List, Optional
from fastapi import APIRouter, Depends, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import settings
from app.core.exceptions import PrivPrintException, ErrorCode
from app.core.security import hash_token, get_password_hash, create_access_token, create_refresh_token
from app.models.base import get_db_session
from app.models.enums import UserRole
from app.models.entities import Device, User
from app.repositories.shop_repo import ShopRepository, DeviceRepository
from app.repositories.user_repo import UserRepository
from app.repositories.session_repo import RefreshTokenRepository
from app.schemas.shop import (
    DeviceRegisterRequest,
    DeviceRegisterResponse,
    DeviceAuthenticateRequest,
    DeviceResponse,
)
from app.api.deps import get_current_user, require_roles, AuthPrincipal

router = APIRouter()


@router.post("/register", response_model=DeviceRegisterResponse, status_code=status.HTTP_201_CREATED)
async def register_device(
    payload: DeviceRegisterRequest,
    db: AsyncSession = Depends(get_db_session),
    principal: AuthPrincipal = Depends(require_roles([UserRole.SHOP_OPERATOR, UserRole.ADMIN]))
) -> DeviceRegisterResponse:
    shop_repo = ShopRepository(db)
    shop = await shop_repo.get_by_id(payload.shop_id)
    if not shop:
        raise PrivPrintException(
            status_code=status.HTTP_404_NOT_FOUND,
            code=ErrorCode.NOT_FOUND,
            message=f"Shop {payload.shop_id} not found"
        )

    # Operator authorization check: operator can only register devices for their owned shop
    if principal.role != UserRole.ADMIN and shop.owner_id != principal.user_id:
        raise PrivPrintException(
            status_code=status.HTTP_403_FORBIDDEN,
            code=ErrorCode.FORBIDDEN,
            message="Operator is not authorized to register devices for another shop"
        )

    device_id = f"dev_win_{uuid.uuid4().hex[:10]}"
    raw_api_key = f"ppdev_{secrets.token_urlsafe(32)}"
    api_key_hash = hash_token(raw_api_key)

    device_repo = DeviceRepository(db)
    device = await device_repo.create(
        id=device_id,
        shop_id=shop.id,
        name=payload.name,
        device_type="WINDOWS_STATION",
        os_info=payload.os_info,
        app_version=payload.app_version,
        auth_state="AUTHENTICATED",
        api_key_hash=api_key_hash,
        status="ONLINE",
        hardware_fingerprint=payload.hardware_fingerprint,
        is_active=True,
        last_seen_at=datetime.now(timezone.utc),
        last_heartbeat_at=datetime.now(timezone.utc)
    )
    await db.commit()

    return DeviceRegisterResponse(
        device_id=device.id,
        shop_id=device.shop_id,
        name=device.name,
        os_info=device.os_info,
        app_version=device.app_version,
        auth_state=device.auth_state,
        status=device.status,
        api_key=raw_api_key
    )


@router.post("/authenticate")
async def authenticate_device(
    payload: DeviceAuthenticateRequest,
    db: AsyncSession = Depends(get_db_session),
) -> dict:
    """
    Exchanges a device's `api_key` (issued at registration) for a JWT pair
    carrying the PRINT_DEVICE role. This is the Windows agent's login endpoint.
    """
    device_repo = DeviceRepository(db)
    device = await device_repo.get_by_id(payload.device_id)
    if not device or not device.api_key_hash:
        raise PrivPrintException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            code=ErrorCode.AUTH_INVALID,
            message="Unknown device"
        )

    provided_hash = hash_token(payload.api_key)
    if provided_hash != device.api_key_hash:
        raise PrivPrintException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            code=ErrorCode.AUTH_INVALID,
            message="Invalid device credentials"
        )

    if not device.is_active:
        raise PrivPrintException(
            status_code=status.HTTP_403_FORBIDDEN,
            code=ErrorCode.FORBIDDEN,
            message="Device is deactivated"
        )

    # The auth dependency resolves PRINT_DEVICE principals from a User row whose
    # id matches the device id, so materialize that identity on first login.
    user_repo = UserRepository(db)
    device_user = await user_repo.get_by_id(device.id)
    if not device_user:
        device_user = await user_repo.create(
            id=device.id,
            email=f"{device.id}@device.privprint.local",
            hashed_password=get_password_hash(secrets.token_urlsafe(32)),
            role=UserRole.PRINT_DEVICE.value,
            is_active=True,
            is_verified=True,
        )

    now = datetime.now(timezone.utc)
    device.auth_state = "AUTHENTICATED"
    device.status = "ONLINE"
    device.is_active = True
    device.last_seen_at = now
    device.last_heartbeat_at = now

    access_token = create_access_token(subject=device.id, role=UserRole.PRINT_DEVICE.value)
    refresh_token_str, refresh_jti, refresh_exp = create_refresh_token(
        subject=device.id, role=UserRole.PRINT_DEVICE.value
    )
    await RefreshTokenRepository(db).create(
        user_id=device.id,
        token_hash=hash_token(refresh_token_str),
        jti=refresh_jti,
        expires_at=refresh_exp,
        is_revoked=False,
    )
    await db.commit()

    # Both key casings are returned so every client (Android, Windows agent)
    # can consume the payload without transformation.
    return {
        "access_token": access_token,
        "accessToken": access_token,
        "refresh_token": refresh_token_str,
        "refreshToken": refresh_token_str,
        "token_type": "Bearer",
        "expires_in": settings.ACCESS_TOKEN_EXPIRE_MINUTES * 60,
        "device_id": device.id,
        "shop_id": device.shop_id,
        "role": UserRole.PRINT_DEVICE.value,
    }


@router.get("", response_model=List[DeviceResponse])
async def list_shop_devices(
    shop_id: Optional[str] = None,
    db: AsyncSession = Depends(get_db_session),
    principal: AuthPrincipal = Depends(require_roles([UserRole.SHOP_OPERATOR, UserRole.ADMIN]))
) -> List[DeviceResponse]:
    target_shop_id = shop_id or principal.shop_id
    if not target_shop_id:
        if principal.role == UserRole.ADMIN:
            # Admin without shop_id returns all devices
            from sqlalchemy import select
            result = await db.execute(select(Device))
            return [DeviceResponse.model_validate(d) for d in result.scalars().all()]
        raise PrivPrintException(
            status_code=status.HTTP_400_BAD_REQUEST,
            code=ErrorCode.VALIDATION_ERROR,
            message="shop_id required"
        )

    # Operator cannot view devices of another shop
    if principal.role != UserRole.ADMIN and principal.shop_id and target_shop_id != principal.shop_id:
        raise PrivPrintException(
            status_code=status.HTTP_403_FORBIDDEN,
            code=ErrorCode.FORBIDDEN,
            message="Operator cannot access devices belonging to another shop"
        )

    device_repo = DeviceRepository(db)
    devices = await device_repo.list_by_shop(target_shop_id)
    return [DeviceResponse.model_validate(d) for d in devices]


@router.get("/{device_id}", response_model=DeviceResponse)
async def get_device(
    device_id: str,
    db: AsyncSession = Depends(get_db_session),
    principal: AuthPrincipal = Depends(require_roles([UserRole.SHOP_OPERATOR, UserRole.ADMIN, UserRole.PRINT_DEVICE]))
) -> DeviceResponse:
    device_repo = DeviceRepository(db)
    device = await device_repo.get_by_id(device_id)
    if not device:
        raise PrivPrintException(
            status_code=status.HTTP_404_NOT_FOUND,
            code=ErrorCode.NOT_FOUND,
            message=f"Device {device_id} not found"
        )

    # Check shop boundary
    if principal.role == UserRole.SHOP_OPERATOR and principal.shop_id != device.shop_id:
        raise PrivPrintException(
            status_code=status.HTTP_403_FORBIDDEN,
            code=ErrorCode.FORBIDDEN,
            message="Operator cannot access a device from another shop"
        )

    if principal.role == UserRole.PRINT_DEVICE and principal.device_id != device.id:
        raise PrivPrintException(
            status_code=status.HTTP_403_FORBIDDEN,
            code=ErrorCode.FORBIDDEN,
            message="Device cannot inspect other devices"
        )

    return DeviceResponse.model_validate(device)


@router.post("/{device_id}/heartbeat")
async def device_heartbeat(
    device_id: str,
    db: AsyncSession = Depends(get_db_session),
    principal: AuthPrincipal = Depends(require_roles([UserRole.PRINT_DEVICE, UserRole.SHOP_OPERATOR, UserRole.ADMIN]))
):
    """
    Heartbeat endpoint for Windows Agent. Updates last_seen_at, last_heartbeat_at, and sets status to ONLINE.
    """
    device_repo = DeviceRepository(db)
    device = await device_repo.get_by_id(device_id)
    if not device:
        raise PrivPrintException(
            status_code=status.HTTP_404_NOT_FOUND,
            code=ErrorCode.NOT_FOUND,
            message=f"Device {device_id} not found"
        )

    now = datetime.now(timezone.utc)
    device.last_seen_at = now
    device.last_heartbeat_at = now
    device.status = "ONLINE"
    device.is_active = True
    await db.commit()

    return {
        "device_id": device.id,
        "status": device.status,
        "last_heartbeat_at": device.last_heartbeat_at.isoformat()
    }


@router.post("/check-stale")
async def check_stale_devices(
    timeout_seconds: int = 60,
    db: AsyncSession = Depends(get_db_session),
    principal: AuthPrincipal = Depends(require_roles([UserRole.ADMIN, UserRole.SHOP_OPERATOR]))
):
    """
    Marks devices as OFFLINE if their last_heartbeat_at is older than the timeout threshold.
    """
    from datetime import timedelta
    from sqlalchemy import select
    cutoff = datetime.now(timezone.utc) - timedelta(seconds=timeout_seconds)

    stmt = select(Device).where(Device.status == "ONLINE")
    if principal.role == UserRole.SHOP_OPERATOR and principal.shop_id:
        stmt = stmt.where(Device.shop_id == principal.shop_id)

    result = await db.execute(stmt)
    online_devices = result.scalars().all()
    marked_stale = []

    for dev in online_devices:
        hb = dev.last_heartbeat_at
        if not hb:
            dev.status = "OFFLINE"
            marked_stale.append(dev.id)
        else:
            if hb.tzinfo is None:
                hb = hb.replace(tzinfo=timezone.utc)
            if hb <= cutoff:
                dev.status = "OFFLINE"
                marked_stale.append(dev.id)

    if marked_stale:
        await db.commit()

    return {
        "marked_stale_count": len(marked_stale),
        "stale_device_ids": marked_stale
    }

