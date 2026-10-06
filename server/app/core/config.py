import os
from enum import Enum
from typing import List, Union
from urllib.parse import urlsplit
from pydantic import Field, field_validator, model_validator
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

    @field_validator("DATABASE_URL", mode="before")
    @classmethod
    def normalize_database_url(cls, value: str) -> str:
        if not isinstance(value, str):
            raise ValueError("DATABASE_URL must be a PostgreSQL connection URL")
        parsed = urlsplit(value)
        if parsed.scheme in {"postgres", "postgresql"}:
            return parsed._replace(scheme="postgresql+asyncpg").geturl()
        return value

    # Redis
    REDIS_URL: str = Field(
        default="redis://localhost:6379/0",
        description="Redis connection URL"
    )
    REDIS_ENABLED: bool = True
    REDIS_SOCKET_TIMEOUT: float = 2.0

    # Development OTP backdoor (fixed code 123456).
    # NEVER enable on a publicly reachable deployment: anyone could log in as any phone number.
    DEVELOPMENT_OTP_ENABLED: bool = Field(
        default_factory=lambda data: data.get("ENVIRONMENT") != EnvironmentType.PRODUCTION,
        description="Enable the fixed development OTP only outside production",
    )

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
        "https://secure-print-1.onrender.com",
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
        if isinstance(v, EnvironmentType):
            return v
        if isinstance(v, str):
            v_lower = v.lower().strip()
            for env in EnvironmentType:
                if env.value == v_lower:
                    return env
        raise ValueError("ENVIRONMENT must be development, staging, or production")

    @model_validator(mode="after")
    def validate_production_configuration(self) -> "Settings":
        if self.ENVIRONMENT != EnvironmentType.PRODUCTION:
            return self

        problems: List[str] = []
        if (
            len(self.SECRET_KEY) < 32
            or self.SECRET_KEY == Settings.model_fields["SECRET_KEY"].default
            or self.SECRET_KEY.upper().startswith("REPLACE_WITH")
        ):
            problems.append("SECRET_KEY must be a unique value with at least 32 characters")
        if self.SECRET_KEY.lower().startswith("change_this"):
            problems.append("SECRET_KEY must not be the example placeholder")
        if self.DEBUG:
            problems.append("DEBUG must be false")
        if self.DEVELOPMENT_OTP_ENABLED:
            problems.append("DEVELOPMENT_OTP_ENABLED must be false")

        database = urlsplit(self.DATABASE_URL)
        if (
            database.scheme != "postgresql+asyncpg"
            or not database.hostname
            or database.hostname in {"localhost", "127.0.0.1", "::1"}
            or not database.username
            or not database.password
            or "REPLACE_WITH" in database.password.upper()
        ):
            problems.append("DATABASE_URL must target a remote PostgreSQL service with credentials")

        redis = urlsplit(self.REDIS_URL)
        if (
            redis.scheme not in {"redis", "rediss"}
            or not redis.hostname
            or redis.hostname in {"localhost", "127.0.0.1", "::1"}
            or not redis.password
            or "REPLACE_WITH" in redis.password.upper()
        ):
            problems.append("REDIS_URL must target a remote Redis service with authentication")

        storage = urlsplit(self.STORAGE_ENDPOINT)
        if (
            storage.scheme != "https"
            or not storage.hostname
            or storage.hostname in {"localhost", "127.0.0.1", "::1"}
            or "REPLACE_WITH" in storage.hostname.upper()
            or not self.STORAGE_USE_SSL
        ):
            problems.append("storage must use an HTTPS endpoint and TLS outside localhost")
        if (
            not self.STORAGE_ACCESS_KEY
            or self.STORAGE_ACCESS_KEY == "minioadmin"
            or self.STORAGE_ACCESS_KEY.upper().startswith("REPLACE_WITH")
        ):
            problems.append("STORAGE_ACCESS_KEY must be set to a non-default value")
        if (
            not self.STORAGE_SECRET_KEY
            or self.STORAGE_SECRET_KEY == "minioadmin"
            or self.STORAGE_SECRET_KEY.upper().startswith("REPLACE_WITH")
        ):
            problems.append("STORAGE_SECRET_KEY must be set to a non-default value")

        if not all((
            self.TWILIO_ACCOUNT_SID,
            self.TWILIO_AUTH_TOKEN,
            self.TWILIO_VERIFY_SERVICE_SID,
        )) or any(
            value.upper().startswith(("AC_REPLACE", "VA_REPLACE", "REPLACE_WITH"))
            for value in (
                self.TWILIO_ACCOUNT_SID,
                self.TWILIO_AUTH_TOKEN,
                self.TWILIO_VERIFY_SERVICE_SID,
            )
        ):
            problems.append("all Twilio Verify credentials must be set")

        if problems:
            raise ValueError("Invalid production configuration: " + "; ".join(problems))
        return self

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=True,
        extra="ignore"
    )


settings = Settings()
