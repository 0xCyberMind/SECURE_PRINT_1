import json
import pytest
from fastapi.testclient import TestClient
from app.main import app
from app.services.redis_service import get_redis_service, RedisService
from app.core.config import settings

client = TestClient(app)


@pytest.mark.asyncio
async def test_redis_connectivity_and_cache_operations():
    """Verify Redis ping and cache set/get/delete operations."""
    redis_svc = get_redis_service()
    redis_svc.set_simulation_failure(False)

    # 1. Connectivity Ping
    is_alive = await redis_svc.ping()
    assert is_alive is True

    # 2. Cache Set / Get / Delete
    test_key = "test_cache_key_999"
    test_data = {"user_id": "usr_123", "theme": "dark", "count": 42}

    set_ok = await redis_svc.set_cache(test_key, test_data, ttl_seconds=60)
    assert set_ok is True

    cached_val = await redis_svc.get_cache(test_key)
    assert cached_val == test_data

    del_ok = await redis_svc.delete_cache(test_key)
    assert del_ok is True

    cached_after_del = await redis_svc.get_cache(test_key)
    assert cached_after_del is None


@pytest.mark.asyncio
async def test_rate_limiting_unit_and_isolation():
    """Verify atomic rate limiting counter, remaining allowance calculation, and cutoff."""
    redis_svc = get_redis_service()
    redis_svc.set_simulation_failure(False)

    rate_key = "unit_test_ip_127.0.0.1"
    max_limit = 3
    window = 10

    # Request 1
    allowed, remaining, retry_after = await redis_svc.check_rate_limit(rate_key, max_limit, window)
    assert allowed is True
    assert remaining == 2
    assert retry_after == 0

    # Request 2
    allowed, remaining, retry_after = await redis_svc.check_rate_limit(rate_key, max_limit, window)
    assert allowed is True
    assert remaining == 1

    # Request 3 (Limit reached)
    allowed, remaining, retry_after = await redis_svc.check_rate_limit(rate_key, max_limit, window)
    assert allowed is True
    assert remaining == 0

    # Request 4 (Exceeded)
    allowed, remaining, retry_after = await redis_svc.check_rate_limit(rate_key, max_limit, window)
    assert allowed is False
    assert remaining == 0
    assert retry_after > 0


@pytest.mark.asyncio
async def test_api_rate_limiting_enforcement():
    """Verify HTTP 429 RATE_LIMIT_EXCEEDED returned on rate limited endpoints."""
    redis_svc = get_redis_service()
    redis_svc.set_simulation_failure(False)

    # Trigger rate limit on login endpoint
    login_payload = {
        "email": "ratelimit_target@example.com",
        "password": "WrongPassword123!"
    }

    exceeded_response = None
    # Send requests up to limit + 1
    for i in range(settings.RATE_LIMIT_LOGIN_MAX + 2):
        res = client.post("/api/v1/auth/login", json=login_payload)
        if res.status_code == 429:
            exceeded_response = res
            break

    assert exceeded_response is not None, "Expected HTTP 429 Too Many Requests when rate limit exceeded"
    data = exceeded_response.json()
    assert data["error"]["code"] == "RATE_LIMIT_EXCEEDED"
    assert exceeded_response.status_code == 429


@pytest.mark.asyncio
async def test_pubsub_channels_broadcasting():
    """
    Verify Pub/Sub event broadcasting across required channel topologies:
    job:{job_id}, user:{user_id}, shop:{shop_id}, device:{device_id}
    """
    redis_svc = get_redis_service()
    redis_svc.set_simulation_failure(False)

    job_id = "job_test_pubsub_88"
    user_id = "usr_test_pubsub_88"
    shop_id = "shop_test_pubsub_88"
    device_id = "dev_test_pubsub_88"

    # Subscribe to job channel
    pubsub = await redis_svc.subscribe(f"job:{job_id}")
    assert pubsub is not None

    # Publish events
    published_count = await redis_svc.publish_job_event(
        job_id=job_id,
        event_type="JOB_CREATED",
        data={"status": "CREATED", "pages": 5}
    )
    assert published_count >= 1

    # Read message from subscriber
    msg = None
    for _ in range(10):
        m = await pubsub.get_message(ignore_subscribe_messages=True, timeout=1.0)
        if m and m.get("type") == "message":
            msg = m
            break

    assert msg is not None, "Expected PubSub message on channel job:{job_id}"
    payload = json.loads(msg["data"])
    assert payload["event"] == "JOB_CREATED"
    assert payload["job_id"] == job_id
    assert payload["data"]["pages"] == 5

    await pubsub.unsubscribe()

    # Also verify publish_user_event, publish_shop_event, publish_device_event executed cleanly
    assert await redis_svc.publish_user_event(user_id, "USER_LOGIN", {"ip": "127.0.0.1"}) >= 0
    assert await redis_svc.publish_shop_event(shop_id, "SHOP_ONLINE", {"status": "ACTIVE"}) >= 0
    assert await redis_svc.publish_device_event(device_id, "HEARTBEAT", {"paper": "A4"}) >= 0


@pytest.mark.asyncio
async def test_graceful_redis_failure():
    """
    Verify system resilience during simulated Redis outages:
    - Ping returns False without crashing
    - Rate limiter fails open (allows requests)
    - Pub/Sub logs/ignores error without crashing
    - User authentication API continues functioning
    """
    redis_svc = get_redis_service()

    try:
        # Simulate Redis outage
        redis_svc.set_simulation_failure(True)

        # 1. Ping test during failure
        is_alive = await redis_svc.ping()
        assert is_alive is False

        # 2. Rate Limiter fails open
        allowed, remaining, retry_after = await redis_svc.check_rate_limit("failure_key", 5, 60)
        assert allowed is True
        assert remaining == 5
        assert retry_after == 0

        # 3. Pub/Sub fails gracefully
        pub_res = await redis_svc.publish("job:failure_job", {"event": "TEST"})
        assert pub_res == 0

        # 4. Token revocation cache returns None during outage
        is_revoked = await redis_svc.is_token_revoked_cached("some_jti")
        assert is_revoked is None

        # 5. API registration continues to work even with Redis down
        reg_payload = {
            "email": "redis_down_user@example.com",
            "password": "ValidPass123!",
            "full_name": "Resilient User"
        }
        res = client.post("/api/v1/auth/register", json=reg_payload)
        assert res.status_code in [201, 409]

    finally:
        # Restore normal operation
        redis_svc.set_simulation_failure(False)
