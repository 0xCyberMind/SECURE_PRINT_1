import pytest
from pydantic import ValidationError
from app.core.config import Settings, EnvironmentType


def test_default_configuration(monkeypatch):
    # The intent is to validate the class defaults, so neutralize any ambient
    # PORT (and friends) inherited from the host environment.
    monkeypatch.delenv("PORT", raising=False)
    s = Settings()
    assert s.API_V1_STR == "/api/v1"
    assert s.PORT in (8000, 8080)
    assert s.ACCESS_TOKEN_EXPIRE_MINUTES == 15
    assert len(s.SECRET_KEY) >= 32
    assert isinstance(s.CORS_ORIGINS, list)


def test_cors_origins_parsing():
    s = Settings(CORS_ORIGINS="https://foo.com, https://bar.com")
    assert "https://foo.com" in s.CORS_ORIGINS
    assert "https://bar.com" in s.CORS_ORIGINS


def test_environment_validation():
    s_dev = Settings(ENVIRONMENT="development")
    assert s_dev.ENVIRONMENT == EnvironmentType.DEVELOPMENT

    s_prod = Settings(**_valid_production_settings())
    assert s_prod.ENVIRONMENT == EnvironmentType.PRODUCTION

    s_stage = Settings(ENVIRONMENT="staging")
    assert s_stage.ENVIRONMENT == EnvironmentType.STAGING

    with pytest.raises(ValidationError, match="ENVIRONMENT must be"):
        Settings(ENVIRONMENT="prod", _env_file=None)


def _valid_production_settings():
    return {
        "_env_file": None,
        "ENVIRONMENT": "production",
        "DEBUG": False,
        "DEVELOPMENT_OTP_ENABLED": False,
        "SECRET_KEY": "a" * 64,
        "DATABASE_URL": "postgresql+asyncpg://privprint:dbpass@db.example.com/privprint",
        "REDIS_URL": "rediss://:redispass@redis.example.com:6380/0",
        "STORAGE_ENDPOINT": "https://storage.example.com",
        "STORAGE_USE_SSL": True,
        "STORAGE_ACCESS_KEY": "production-storage-user",
        "STORAGE_SECRET_KEY": "production-storage-secret",
        "TWILIO_ACCOUNT_SID": "AC-test",
        "TWILIO_AUTH_TOKEN": "twilio-test-token",
        "TWILIO_VERIFY_SERVICE_SID": "VA-test",
    }


def test_production_configuration_accepts_secure_remote_dependencies():
    settings = Settings(**_valid_production_settings())
    assert settings.ENVIRONMENT == EnvironmentType.PRODUCTION


@pytest.mark.parametrize(
    "database_url",
    [
        "postgres://privprint:dbpass@db.example.com/privprint",
        "postgresql://privprint:dbpass@db.example.com/privprint",
    ],
)
def test_database_provider_urls_use_asyncpg_driver(database_url):
    settings = Settings(DATABASE_URL=database_url, _env_file=None)
    assert settings.DATABASE_URL.startswith("postgresql+asyncpg://")


@pytest.mark.parametrize(
    "override",
    [
        {"SECRET_KEY": "REPLACE_WITH_A_SECRET"},
        {"DEBUG": True},
        {"DEVELOPMENT_OTP_ENABLED": True},
        {"DATABASE_URL": "postgresql+asyncpg://privprint:password@localhost/privprint"},
        {"REDIS_URL": "redis://localhost:6379/0"},
        {"STORAGE_ENDPOINT": "http://storage.example.com"},
        {"STORAGE_USE_SSL": False},
        {"TWILIO_AUTH_TOKEN": ""},
    ],
)
def test_production_configuration_rejects_unsafe_values(override):
    values = _valid_production_settings()
    values.update(override)
    with pytest.raises(ValidationError, match="Invalid production configuration"):
        Settings(**values)
