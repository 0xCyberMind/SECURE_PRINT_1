import pytest
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

    s_prod = Settings(ENVIRONMENT="production")
    assert s_prod.ENVIRONMENT == EnvironmentType.PRODUCTION

    s_stage = Settings(ENVIRONMENT="staging")
    assert s_stage.ENVIRONMENT == EnvironmentType.STAGING
