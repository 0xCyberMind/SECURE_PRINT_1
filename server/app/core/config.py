import os
from enum import Enum
from typing import List, Union
from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class EnvironmentType(str, Enum):
    DEVELOPMENT = "development"
    STAGING = "staging"
    PRODUCTION = "production"


class Settings(BaseSettings):
    # App
    PROJECT_NAME: str = "PrivPrint Cloud Backend"
    API_V1_STR: str = "/api/v1"
    ENVIRONMENT: EnvironmentType = EnvironmentType.DEVELOPMENT
    DEBUG: bool = False
    PORT: int = 8000
    HOST: str = "0.0.0.0"

    # Security
    SECRET_KEY: str = Field(
        default="privprint_dev_insecure_secret_key_minimum_32_bytes_long_12345",
        description="JWT HMAC signing key"
    )
    ALGORITHM: str = "HS256"
    ACCESS_TOKEN_EXPIRE_MINUTES: int = 15
    REFRESH_TOKEN_EXPIRE_DAYS: int = 7

    # Database
    DATABASE_URL: str = Field(
        default="postgresql+asyncpg://privprint:privprint_pass@localhost:5432/privprint_db",
        description="Async PostgreSQL connection string"
    )
    DATABASE_POOL_SIZE: int = 20
    DATABASE_MAX_OVERFLOW: int = 10

    # Redis
    REDIS_URL: str = Field(
        default="redis://localhost:6379/0",
        description="Redis connection URL"
    )
    REDIS_ENABLED: bool = True
    REDIS_SOCKET_TIMEOUT: float = 2.0

    # Development OTP backdoor (fixed code 123456).
    # NEVER enable on a publicly reachable deployment: anyone could log in as any phone number.
    DEVELOPMENT_OTP_ENABLED: bool = True

    # Twilio Verify (credentials are supplied by the deployment secret store)
    TWILIO_ACCOUNT_SID: str = ""
    TWILIO_AUTH_TOKEN: str = ""
    TWILIO_VERIFY_SERVICE_SID: str = ""

    # Rate Limiting (Configurable windows and limits)
    RATE_LIMIT_LOGIN_MAX: int = 5
    RATE_LIMIT_LOGIN_WINDOW_SECONDS: int = 60
    RATE_LIMIT_REGISTER_MAX: int = 5
    RATE_LIMIT_REGISTER_WINDOW_SECONDS: int = 60
    RATE_LIMIT_SESSION_CREATE_MAX: int = 10
    RATE_LIMIT_SESSION_CREATE_WINDOW_SECONDS: int = 60
    RATE_LIMIT_UPLOAD_INIT_MAX: int = 10
    RATE_LIMIT_UPLOAD_INIT_WINDOW_SECONDS: int = 60
    RATE_LIMIT_JOB_CREATE_MAX: int = 15
    RATE_LIMIT_JOB_CREATE_WINDOW_SECONDS: int = 60

    # Storage (S3 / MinIO)
    STORAGE_ENDPOINT: str = "http://localhost:9000"
    STORAGE_ACCESS_KEY: str = "minioadmin"
    STORAGE_SECRET_KEY: str = "minioadmin"
    STORAGE_BUCKET_NAME: str = "privprint-documents"
    STORAGE_REGION: str = "us-east-1"
    STORAGE_USE_SSL: bool = False
    PRESIGNED_URL_EXPIRE_SECONDS: int = 300
    MAX_FILE_SIZE_BYTES: int = 25 * 1024 * 1024  # 25 MB max limit
    ALLOWED_MIME_TYPES: List[str] = [
        "application/pdf",
        "image/png",
        "image/jpeg",
        "application/octet-stream"
    ]
    ALLOWED_EXTENSIONS: List[str] = [".pdf", ".png", ".jpg", ".jpeg", ".enc"]

    # CORS
    CORS_ORIGINS: Union[List[str], str] = [
        "http://localhost:3000",
        "http://localhost:8888",
        "https://api.privprint.com"
    ]

    @field_validator("CORS_ORIGINS", mode="before")
    @classmethod
    def assemble_cors_origins(cls, v: Union[str, List[str]]) -> List[str]:
        if isinstance(v, str) and not v.startswith("["):
            return [i.strip() for i in v.split(",") if i.strip()]
        elif isinstance(v, (list, str)):
            return v
        raise ValueError(v)

    @field_validator("ENVIRONMENT", mode="before")
    @classmethod
    def validate_environment(cls, v: str) -> EnvironmentType:
        if isinstance(v, str):
            v_lower = v.lower().strip()
            for env in EnvironmentType:
                if env.value == v_lower:
                    return env
        return EnvironmentType.DEVELOPMENT

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=True,
        extra="ignore"
    )


settings = Settings()
