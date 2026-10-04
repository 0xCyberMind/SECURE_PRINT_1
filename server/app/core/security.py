import hashlib
import uuid
import jwt
import bcrypt
from datetime import datetime, timedelta, timezone
from typing import Any, Dict, Optional
from app.core.config import settings
from app.core.exceptions import PrivPrintException, ErrorCode
from app.models.enums import UserRole


def verify_password(plain_password: str, hashed_password: str) -> bool:
    try:
        pwd_bytes = plain_password.encode("utf-8")[:72]
        return bcrypt.checkpw(pwd_bytes, hashed_password.encode("utf-8"))
    except Exception:
        return False


def get_password_hash(password: str) -> str:
    pwd_bytes = password.encode("utf-8")[:72]
    salt = bcrypt.gensalt()
    return bcrypt.hashpw(pwd_bytes, salt).decode("utf-8")


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def create_access_token(
    subject: str,
    role: str,
    expires_delta: Optional[timedelta] = None
) -> str:
    now = datetime.now(timezone.utc)
    if expires_delta:
        expire = now + expires_delta
    else:
        expire = now + timedelta(minutes=settings.ACCESS_TOKEN_EXPIRE_MINUTES)

    to_encode: Dict[str, Any] = {
        "sub": subject,
        "role": role,
        "type": "access",
        "jti": f"acc_{uuid.uuid4().hex}",
        "iat": now,
        "exp": expire,
    }
    return jwt.encode(to_encode, settings.SECRET_KEY, algorithm=settings.ALGORITHM)


def create_refresh_token(
    subject: str,
    role: str,
    jti: Optional[str] = None,
    expires_delta: Optional[timedelta] = None
) -> tuple[str, str, datetime]:
    now = datetime.now(timezone.utc)
    if expires_delta:
        expire = now + expires_delta
    else:
        expire = now + timedelta(days=settings.REFRESH_TOKEN_EXPIRE_DAYS)

    token_jti = jti or f"refr_{uuid.uuid4().hex}"
    to_encode: Dict[str, Any] = {
        "sub": subject,
        "role": role,
        "type": "refresh",
        "jti": token_jti,
        "iat": now,
        "exp": expire,
    }
    token_str = jwt.encode(to_encode, settings.SECRET_KEY, algorithm=settings.ALGORITHM)
    return token_str, token_jti, expire


def decode_token(token: str) -> Dict[str, Any]:
    try:
        payload = jwt.decode(token, settings.SECRET_KEY, algorithms=[settings.ALGORITHM])
        return payload
    except jwt.ExpiredSignatureError:
        raise PrivPrintException(
            status_code=401,
            code=ErrorCode.TOKEN_EXPIRED,
            message="Token has expired"
        )
    except jwt.PyJWTError:
        raise PrivPrintException(
            status_code=401,
            code=ErrorCode.AUTH_INVALID,
            message="Invalid authentication credentials"
        )


def decode_access_token(token: str) -> Dict[str, Any]:
    payload = decode_token(token)
    if payload.get("type") != "access":
        raise PrivPrintException(
            status_code=401,
            code=ErrorCode.AUTH_INVALID,
            message="Invalid token type: expected access token"
        )
    return payload


def decode_refresh_token(token: str) -> Dict[str, Any]:
    payload = decode_token(token)
    if payload.get("type") != "refresh":
        raise PrivPrintException(
            status_code=401,
            code=ErrorCode.AUTH_INVALID,
            message="Invalid token type: expected refresh token"
        )
    return payload
