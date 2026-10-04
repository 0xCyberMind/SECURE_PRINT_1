from typing import Optional
from datetime import datetime
from pydantic import BaseModel, EmailStr, Field, ConfigDict
from app.models.enums import UserRole


class UserRegisterRequest(BaseModel):
    email: EmailStr
    password: str = Field(..., min_length=8, description="Password with minimum 8 characters")
    full_name: Optional[str] = None
    phone_number: Optional[str] = None
    role: Optional[UserRole] = Field(default=UserRole.USER)


class UserLoginRequest(BaseModel):
    email: EmailStr
    password: str


class PhoneOtpRequest(BaseModel):
    phone_number: str = Field(..., min_length=7, max_length=32)
    role: UserRole = UserRole.USER
    shop_id: Optional[str] = None


class PhoneOtpVerifyRequest(BaseModel):
    phone_number: str = Field(..., min_length=7, max_length=32)
    otp: str = Field(..., min_length=6, max_length=6)
    role: UserRole = UserRole.USER
    shop_id: Optional[str] = None


class OtpRequestResponse(BaseModel):
    message: str
    expires_in: int
    development_otp: Optional[str] = None


class UserResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    email: str
    full_name: Optional[str] = None
    phone_number: Optional[str] = None
    role: str
    is_active: bool
    is_verified: bool
    created_at: datetime


class TokenResponse(BaseModel):
    access_token: str
    refresh_token: str
    token_type: str = "Bearer"
    expires_in: int
    user: UserResponse


class TokenRefreshRequest(BaseModel):
    refresh_token: str


class LogoutRequest(BaseModel):
    refresh_token: Optional[str] = None


class RevokeTokenRequest(BaseModel):
    token: Optional[str] = None
    jti: Optional[str] = None
    all_sessions: bool = False
