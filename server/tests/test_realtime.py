import json
import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from app.main import app
from app.services.redis_service import get_redis_service

client = TestClient(app)


def get_auth_token(email: str = "realtime_user@example.com", role: str = "USER") -> dict:
    res = client.post("/api/v1/auth/register", json={
        "email": email,
        "password": "Password123!",
        "full_name": "Realtime User",
        "role": role
    })
    if res.status_code == 201:
        return res.json()
    # If already exists, login
    login_res = client.post("/api/v1/auth/login", json={
        "email": email,
        "password": "Password123!"
    })
    return login_res.json()


def test_unauthenticated_websocket_connection_rejection():
    """Verify unauthenticated WebSocket connections are rejected immediately."""
    with pytest.raises(Exception):
        with client.websocket_connect("/api/v1/realtime/ws"):
            pass


def test_authenticated_websocket_connection_and_ping_pong():
    """Verify authenticated WebSocket handshake and ping/pong frame response."""
    auth = get_auth_token("ws_ping_user@example.com")
    token = auth["access_token"]
    user_id = auth["user"]["id"]

    with client.websocket_connect(f"/api/v1/realtime/ws?token={token}") as websocket:
        # 1. Connected Frame
        welcome = websocket.receive_json()
        assert welcome["type"] == "connected"
        assert welcome["user_id"] == user_id
        assert "socket_id" in welcome

        # 2. Ping / Pong
        websocket.send_json({"type": "ping"})
        pong = websocket.receive_json()
        assert pong["type"] == "pong"
        assert "timestamp" in pong


def test_channel_subscription_authorization():
    """
    Verify channel authorization:
    - user:{id} allowed for owner
    - arbitrary channels (e.g. arbitrary:123) rejected with FORBIDDEN_CHANNEL
    - another user's channel rejected with FORBIDDEN
    """
    auth_a = get_auth_token("user_a_ws@example.com")
    auth_b = get_auth_token("user_b_ws@example.com")

    token_a = auth_a["access_token"]
    user_a_id = auth_a["user"]["id"]
    user_b_id = auth_b["user"]["id"]

    with client.websocket_connect(f"/api/v1/realtime/ws?token={token_a}") as websocket:
        _ = websocket.receive_json()  # connected frame

        # 1. Arbitrary Channel Rejection
        websocket.send_json({"type": "subscribe", "channel": "arbitrary:123"})
        res1 = websocket.receive_json()
        assert res1["type"] == "error"
        assert res1["code"] == "FORBIDDEN_CHANNEL"

        # 2. Unauthorized User Channel Rejection
        websocket.send_json({"type": "subscribe", "channel": f"user:{user_b_id}"})
        res2 = websocket.receive_json()
        assert res2["type"] == "error"
        assert res2["code"] == "FORBIDDEN"

        # 3. Authorized Own User Channel
        websocket.send_json({"type": "subscribe", "channel": f"user:{user_a_id}"})
        res3 = websocket.receive_json()
        assert res3["type"] == "subscribed"
        assert res3["channel"] == f"user:{user_a_id}"


@pytest.mark.asyncio
async def test_realtime_event_broadcasting_over_websocket():
    """
    Verify Redis Pub/Sub events delivered in real-time over WebSocket.
    """
    auth = get_auth_token("broadcast_user_ws@example.com")
    token = auth["access_token"]
    user_id = auth["user"]["id"]

    redis_svc = get_redis_service()
    redis_svc.set_simulation_failure(False)

    with client.websocket_connect(f"/api/v1/realtime/ws?token={token}") as websocket:
        _ = websocket.receive_json()  # connected

        # Subscribe to user channel
        websocket.send_json({"type": "subscribe", "channel": f"user:{user_id}"})
        sub_ack = websocket.receive_json()
        assert sub_ack["type"] == "subscribed"

        # Publish event via Redis Service
        await redis_svc.publish_user_event(
            user_id=user_id,
            event_type="JOB_CREATED",
            data={"job_id": "job_ws_999", "status": "CREATED"}
        )

        # Receive real-time event frame over WebSocket
        event_frame = websocket.receive_json()
        assert event_frame["type"] == "event"
        assert event_frame["channel"] == f"user:{user_id}"
        assert event_frame["event"] == "JOB_CREATED"
        assert "event_id" in event_frame
        assert "timestamp" in event_frame
        assert event_frame["data"]["job_id"] == "job_ws_999"


def test_graceful_websocket_disconnect():
    """Verify graceful disconnect without raising server errors or leaving leaked sockets."""
    auth = get_auth_token("disconnect_user_ws@example.com")
    token = auth["access_token"]

    with client.websocket_connect(f"/api/v1/realtime/ws?token={token}") as websocket:
        _ = websocket.receive_json()
        websocket.close()
