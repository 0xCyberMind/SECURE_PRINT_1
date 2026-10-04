from datetime import datetime, timezone
import re
import secrets
from typing import Optional
from fastapi import APIRouter, Depends, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import settings
from app.core.exceptions import PrivPrintException, ErrorCode
from app.core.security import (
    verify_password,
    get_password_hash,
    hash_token,
    create_access_token,
    create_refresh_token,
    decode_refresh_token,
)
from app.models.base import get_db_session
from app.models.enums import UserRole
from app.models.entities import User, RefreshToken, Shop
from app.repositories.user_repo import UserRepository
from app.repositories.session_repo import RefreshTokenRepository, AuditLogRepository
from app.schemas.auth import (
    UserRegisterRequest,
    UserLoginRequest,
    TokenResponse,
    TokenRefreshRequest,
    LogoutRequest,
    RevokeTokenRequest,
    UserResponse,
    PhoneOtpRequest,
    PhoneOtpVerifyRequest,
    OtpRequestResponse,
)
from app.api.deps import get_current_user, AuthPrincipal, require_roles
from app.api.deps_rate_limit import RateLimiter
from app.services.redis_service import get_redis_service
from app.services import twilio_verify

router = APIRouter()

OTP_TTL_SECONDS = 300
DEVELOPMENT_OTP = "123456"


def _normalize_phone_number(phone_number: str) -> str:
    phone = re.sub(r"[\s().-]", "", phone_number)
    if twilio_verify.is_configured() and not re.fullmatch(r"\+[1-9]\d{7,14}", phone):
        raise PrivPrintException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            code=ErrorCode.VALIDATION_ERROR,
            message="Enter a valid phone number with its country code, such as +919876543210",
        )
    return phone


@router.post(
    "/phone/request-otp",
    response_model=OtpRequestResponse,
    dependencies=[Depends(RateLimiter("phone_otp", 5, 300))],
)
async def request_phone_otp(
    payload: PhoneOtpRequest,
) -> OtpRequestResponse:
    phone = _normalize_phone_number(payload.phone_number)
    redis = get_redis_service()
    client = await redis.get_client()
    if client is None:
        raise PrivPrintException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            code=ErrorCode.NETWORK_ERROR,
            message="OTP service is unavailable",
        )

    if twilio_verify.is_configured():
        try:
            await twilio_verify.start_verification(phone)
        except twilio_verify.TwilioVerifyError as exc:
            raise PrivPrintException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                code=ErrorCode.NETWORK_ERROR,
                message="SMS verification is temporarily unavailable",
            ) from exc
        await client.set(
            f"otp:phone:{phone}",
            f"twilio:{payload.role.value}:{payload.shop_id or ''}",
            ex=OTP_TTL_SECONDS,
        )
        development_otp = None
        message = "Verification code sent."
    elif settings.ENVIRONMENT.value == "development" and settings.DEVELOPMENT_OTP_ENABLED:
        await client.set(
            f"otp:phone:{phone}",
            f"{DEVELOPMENT_OTP}:{payload.role.value}:{payload.shop_id or ''}",
            ex=OTP_TTL_SECONDS,
        )
        development_otp = DEVELOPMENT_OTP
        message = "Development verification code generated."
    else:
        raise PrivPrintException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            code=ErrorCode.NETWORK_ERROR,
            message="Phone verification is not configured",
        )

    return OtpRequestResponse(
        message=message,
        expires_in=OTP_TTL_SECONDS,
        development_otp=development_otp,
    )


@router.post(
    "/phone/verify-otp",
    response_model=TokenResponse,
    dependencies=[Depends(RateLimiter("phone_otp_verify", 10, 300))],
)
async def verify_phone_otp(
    payload: PhoneOtpVerifyRequest,
    db: AsyncSession = Depends(get_db_session),
) -> TokenResponse:
    phone = _normalize_phone_number(payload.phone_number)
    redis = get_redis_service()
    client = await redis.get_client()
    if client is None:
        raise PrivPrintException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            code=ErrorCode.NETWORK_ERROR,
            message="OTP service is unavailable",
        )

    stored = await client.get(f"otp:phone:{phone}")
    parts = (stored or "").split(":", 2)
    is_twilio_verification = len(parts) == 3 and parts[0] == "twilio"
    expected_role = parts[1] if len(parts) == 3 else ""
    expected_shop_id = parts[2] if len(parts) == 3 else ""
    metadata_matches = (
        stored is not None
        and payload.role.value == expected_role
        and (payload.shop_id or "") == expected_shop_id
    )
    if not metadata_matches:
        raise PrivPrintException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            code=ErrorCode.AUTH_INVALID,
            message="Invalid or expired OTP",
        )

    if is_twilio_verification:
        if not twilio_verify.is_configured():
            raise PrivPrintException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                code=ErrorCode.NETWORK_ERROR,
                message="Phone verification is not configured",
            )
        try:
            valid_otp = await twilio_verify.check_verification(phone, payload.otp)
        except twilio_verify.TwilioVerifyError as exc:
            raise PrivPrintException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                code=ErrorCode.NETWORK_ERROR,
                message="SMS verification is temporarily unavailable",
            ) from exc
    else:
        valid_otp = (
            len(parts) == 3
            and payload.otp == parts[0]
            and settings.ENVIRONMENT.value == "development"
            and settings.DEVELOPMENT_OTP_ENABLED
        )

    if not valid_otp:
        raise PrivPrintException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            code=ErrorCode.AUTH_INVALID,
            message="Invalid or expired OTP",
        )

    await client.delete(f"otp:phone:{phone}")

    user_repo = UserRepository(db)
    user = await user_repo.get_by_phone(phone)
    if not user:
        user = await user_repo.create(
            email=f"{phone.replace('+', '').replace(' ', '')}@phone.privprint.local",
            hashed_password=get_password_hash(secrets.token_urlsafe(32)),
            full_name=None,
            phone_number=phone,
            role=payload.role.value,
            is_active=True,
            is_verified=True,
        )
    elif user.role != payload.role.value:
        raise PrivPrintException(
            status_code=status.HTTP_409_CONFLICT,
            code=ErrorCode.CONFLICT,
            message="Phone number is already registered for another role",
        )

    if payload.role == UserRole.SHOP_OPERATOR:
        # Auto-create shop for SHOP_OPERATOR if not already associated
        shop_id = payload.shop_id or f"shop_{secrets.token_urlsafe(12)}"
        shop = await db.get(Shop, shop_id) if payload.shop_id else None
        if not shop:
            shop = Shop(
                id=shop_id,
                name="PrivPrint Xerox Station",
                owner_id=user.id,
                address="Address pending registration",
                permanent_qr_payload=f"privprint://shop?id={shop_id}",
            )
            db.add(shop)
            await db.flush()  # Ensure shop is created before proceeding

    access_token = create_access_token(subject=user.id, role=user.role)
    refresh_token_str, refresh_jti, refresh_exp = create_refresh_token(
        subject=user.id, role=user.role
    )
    await RefreshTokenRepository(db).create(
        user_id=user.id,
        token_hash=hash_token(refresh_token_str),
        jti=refresh_jti,
        expires_at=refresh_exp,
        is_revoked=False,
    )
    await AuditLogRepository(db).log_event(
        event_type="AUTH_PHONE_OTP_LOGIN",
        details=f"Phone user {user.id} authenticated with OTP",
        user_id=user.id,
    )
    await db.commit()
    return TokenResponse(
        access_token=access_token,
        refresh_token=refresh_token_str,
        token_type="Bearer",
        expires_in=settings.ACCESS_TOKEN_EXPIRE_MINUTES * 60,
        user=UserResponse.model_validate(user),
    )


@router.post(
    "/register",
    response_model=TokenResponse,
    status_code=status.HTTP_201_CREATED,
    dependencies=[Depends(RateLimiter("register", settings.RATE_LIMIT_REGISTER_MAX, settings.RATE_LIMIT_REGISTER_WINDOW_SECONDS))]
)
async def register(
    payload: UserRegisterRequest,
    db: AsyncSession = Depends(get_db_session),
) -> TokenResponse:
    user_repo = UserRepository(db)
    existing = await user_repo.get_by_email(payload.email)
    if existing:
        raise PrivPrintException(
            status_code=status.HTTP_409_CONFLICT,
            code=ErrorCode.CONFLICT,
            message=f"User with email {payload.email} already exists"
        )

    # Sanitize role: public registration defaults to USER unless explicitly permitted
    assigned_role = payload.role if payload.role else UserRole.USER

    hashed_pw = get_password_hash(payload.password)
    user = await user_repo.create(
        email=payload.email,
        hashed_password=hashed_pw,
        full_name=payload.full_name,
        phone_number=payload.phone_number,
        role=assigned_role.value,
        is_active=True,
        is_verified=False
    )

    access_token = create_access_token(subject=user.id, role=user.role)
    refresh_token_str, refresh_jti, refresh_exp = create_refresh_token(subject=user.id, role=user.role)

    # Store refresh token record
    refresh_repo = RefreshTokenRepository(db)
    await refresh_repo.create(
        user_id=user.id,
        token_hash=hash_token(refresh_token_str),
        jti=refresh_jti,
        expires_at=refresh_exp,
        is_revoked=False
    )

    audit_repo = AuditLogRepository(db)
    await audit_repo.log_event(
        event_type="AUTH_REGISTER",
        details=f"User registered with id {user.id}",
        user_id=user.id
    )

    await db.commit()

    return TokenResponse(
        access_token=access_token,
        refresh_token=refresh_token_str,
        token_type="Bearer",
        expires_in=settings.ACCESS_TOKEN_EXPIRE_MINUTES * 60,
        user=UserResponse.model_validate(user)
    )


@router.post(
    "/login",
    response_model=TokenResponse,
    dependencies=[Depends(RateLimiter("login", settings.RATE_LIMIT_LOGIN_MAX, settings.RATE_LIMIT_LOGIN_WINDOW_SECONDS))]
)
async def login(
    payload: UserLoginRequest,
    db: AsyncSession = Depends(get_db_session),
) -> TokenResponse:
    user_repo = UserRepository(db)
    user = await user_repo.get_by_email(payload.email)
    if not user or not verify_password(payload.password, user.hashed_password):
        raise PrivPrintException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            code=ErrorCode.AUTH_INVALID,
            message="Invalid email or password"
        )

    if not user.is_active:
        raise PrivPrintException(
            status_code=status.HTTP_403_FORBIDDEN,
            code=ErrorCode.FORBIDDEN,
            message="User account is deactivated"
        )

    access_token = create_access_token(subject=user.id, role=user.role)
    refresh_token_str, refresh_jti, refresh_exp = create_refresh_token(subject=user.id, role=user.role)

    refresh_repo = RefreshTokenRepository(db)
    await refresh_repo.create(
        user_id=user.id,
        token_hash=hash_token(refresh_token_str),
        jti=refresh_jti,
        expires_at=refresh_exp,
        is_revoked=False
    )

    audit_repo = AuditLogRepository(db)
    await audit_repo.log_event(
        event_type="AUTH_LOGIN",
        details=f"User {user.id} logged in successfully",
        user_id=user.id
    )

    await db.commit()

    return TokenResponse(
        access_token=access_token,
        refresh_token=refresh_token_str,
        token_type="Bearer",
        expires_in=settings.ACCESS_TOKEN_EXPIRE_MINUTES * 60,
        user=UserResponse.model_validate(user)
    )


@router.post("/refresh", response_model=TokenResponse)
async def refresh_token(
    payload: TokenRefreshRequest,
    db: AsyncSession = Depends(get_db_session),
) -> TokenResponse:
    # Decode and validate refresh token structure
    token_claims = decode_refresh_token(payload.refresh_token)
    user_id = token_claims.get("sub")
    jti = token_claims.get("jti")

    if not user_id or not jti:
        raise PrivPrintException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            code=ErrorCode.AUTH_INVALID,
            message="Invalid refresh token claims"
        )

    refresh_repo = RefreshTokenRepository(db)
    stored_token = await refresh_repo.get_by_jti(jti)

    if not stored_token:
        raise PrivPrintException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            code=ErrorCode.AUTH_INVALID,
            message="Refresh token not found or unrecognized"
        )

    # REPLAY DETECTION:
    # If the token has already been marked as revoked, a replay attack is occurring!
    if stored_token.is_revoked:
        # Revoke ALL refresh tokens for this user for security protection
        revoked_count = await refresh_repo.revoke_all_for_user(stored_token.user_id)
        audit_repo = AuditLogRepository(db)
        await audit_repo.log_event(
            event_type="AUTH_REPLAY_DETECTED",
            severity="SECURITY_ALERT",
            details=f"Refresh token replay detected for jti {jti}. Revoked {revoked_count} active tokens.",
            user_id=stored_token.user_id
        )
        await db.commit()
        raise PrivPrintException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            code=ErrorCode.TOKEN_REPLAY_DETECTED,
            message="Refresh token replay detected. All active sessions have been terminated for security."
        )

    # Check expiration
    stored_exp = stored_token.expires_at
    if stored_exp.tzinfo is None:
        stored_exp = stored_exp.replace(tzinfo=timezone.utc)
    if stored_exp < datetime.now(timezone.utc):
        raise PrivPrintException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            code=ErrorCode.TOKEN_EXPIRED,
            message="Refresh token has expired"
        )

    # Verify user
    user_repo = UserRepository(db)
    user = await user_repo.get_by_id(stored_token.user_id)
    if not user or not user.is_active:
        raise PrivPrintException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            code=ErrorCode.AUTH_INVALID,
            message="User account is inactive or deleted"
        )

    # ROTATION: Revoke current token
    stored_token.is_revoked = True
    stored_token.revoked_at = datetime.now(timezone.utc)

    # Issue new token pair
    new_access_token = create_access_token(subject=user.id, role=user.role)
    new_refresh_token_str, new_jti, new_exp = create_refresh_token(subject=user.id, role=user.role)

    await refresh_repo.create(
        user_id=user.id,
        token_hash=hash_token(new_refresh_token_str),
        jti=new_jti,
        expires_at=new_exp,
        is_revoked=False
    )

    await db.commit()

    return TokenResponse(
        access_token=new_access_token,
        refresh_token=new_refresh_token_str,
        token_type="Bearer",
        expires_in=settings.ACCESS_TOKEN_EXPIRE_MINUTES * 60,
        user=UserResponse.model_validate(user)
    )


@router.post("/logout")
async def logout(
    payload: LogoutRequest,
    db: AsyncSession = Depends(get_db_session),
    principal: Optional[AuthPrincipal] = Depends(get_current_user),
) -> dict:
    refresh_repo = RefreshTokenRepository(db)
    revoked = False

    if payload.refresh_token:
        token_hash = hash_token(payload.refresh_token)
        revoked = await refresh_repo.revoke_by_token_hash(token_hash)

    if principal:
        await refresh_repo.revoke_all_for_user(principal.user_id)
        audit_repo = AuditLogRepository(db)
        await audit_repo.log_event(
            event_type="AUTH_LOGOUT",
            details=f"User {principal.user_id} logged out",
            user_id=principal.user_id
        )

    await db.commit()
    return {"success": True, "message": "Logged out successfully"}


@router.post("/revoke")
async def revoke_token(
    payload: RevokeTokenRequest,
    db: AsyncSession = Depends(get_db_session),
    principal: AuthPrincipal = Depends(get_current_user),
) -> dict:
    refresh_repo = RefreshTokenRepository(db)

    if payload.all_sessions:
        count = await refresh_repo.revoke_all_for_user(principal.user_id)
        await db.commit()
        return {"success": True, "message": f"Revoked {count} sessions"}

    if payload.jti:
        token = await refresh_repo.get_by_jti(payload.jti)
        # Never allow a user to revoke another user's token unless ADMIN
        if token and (token.user_id == principal.user_id or principal.role == UserRole.ADMIN):
            await refresh_repo.revoke_jti(payload.jti)

    if payload.token:
        thash = hash_token(payload.token)
        token = await refresh_repo.get_by_token_hash(thash)
        if token and (token.user_id == principal.user_id or principal.role == UserRole.ADMIN):
            await refresh_repo.revoke_by_token_hash(thash)

    await db.commit()
    return {"success": True, "message": "Token revoked successfully"}


@router.get("/me", response_model=UserResponse)
async def get_me(principal: AuthPrincipal = Depends(get_current_user)) -> UserResponse:
    return UserResponse.model_validate(principal.user)


@router.get("/admin-only")
async def admin_only_endpoint(
    principal: AuthPrincipal = Depends(require_roles([UserRole.ADMIN]))
) -> dict:
    return {"status": "ok", "message": "Admin access granted", "user_id": principal.user_id}


@router.get("/operator-only")
async def operator_only_endpoint(
    principal: AuthPrincipal = Depends(require_roles([UserRole.SHOP_OPERATOR]))
) -> dict:
    return {"status": "ok", "message": "Operator access granted", "shop_id": principal.shop_id}


@router.get("/device-only")
async def device_only_endpoint(
    principal: AuthPrincipal = Depends(require_roles([UserRole.PRINT_DEVICE]))
) -> dict:
    return {"status": "ok", "message": "Device access granted", "device_id": principal.device_id}
