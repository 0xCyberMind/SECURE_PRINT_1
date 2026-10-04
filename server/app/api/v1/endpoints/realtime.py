import asyncio
import json
import logging
import re
import uuid
from datetime import datetime, timezone
from typing import Optional, Dict, Any

from fastapi import APIRouter, WebSocket, WebSocketDisconnect, Depends, status, Query
from sqlalchemy.ext.asyncio import AsyncSession

from app.services.realtime_gateway import connection_manager
from app.api.deps import AuthPrincipal, decode_access_token, get_principal_from_token_str
from app.models.base import get_db_session
from app.repositories.session_repo import SessionRepository
from app.repositories.shop_repo import ShopRepository, DeviceRepository
from app.repositories.print_job_repo import PrintJobRepository
from app.repositories.user_repo import UserRepository
from app.models.entities import UserRole

logger = logging.getLogger("privprint.realtime_api")

router = APIRouter()

CHANNEL_PATTERN = re.compile(r"^(user|shop|job|device):([a-zA-Z0-9_\-]+)$")


async def authorize_subscription(
    channel: str,
    principal: AuthPrincipal,
    db: AsyncSession
) -> bool:
    """
    Enforces strict subscription authorization rules for channels:
    - user:{id}: principal.user_id == id or ADMIN
    - shop:{id}: principal.shop_id == id or SHOP_OPERATOR/ADMIN
    - job:{id}: principal is job owner, shop operator, assigned print device, or ADMIN
    - device:{id}: principal is device itself, shop operator, or ADMIN
    """
    match = CHANNEL_PATTERN.match(channel)
    if not match:
        return False

    prefix, target_id = match.groups()

    if principal.role == UserRole.ADMIN.value:
        return True

    if prefix == "user":
        return principal.user_id == target_id

    elif prefix == "shop":
        if principal.shop_id == target_id:
            return True
        if principal.role == UserRole.SHOP_OPERATOR.value:
            shop_repo = ShopRepository(db)
            shop = await shop_repo.get_by_id(target_id)
            return shop is not None and shop.owner_id == principal.user_id
        return False

    elif prefix == "job":
        job_repo = PrintJobRepository(db)
        job = await job_repo.get_by_id(target_id)
        if not job:
            return False
        # Owner user check
        if job.user_id == principal.user_id:
            return True
        # Shop operator or assigned device check
        if principal.shop_id and job.shop_id == principal.shop_id:
            return True
        return False

    elif prefix == "device":
        if principal.device_id == target_id:
            return True
        device_repo = DeviceRepository(db)
        device = await device_repo.get_by_id(target_id)
        if not device:
            return False
        if principal.shop_id and device.shop_id == principal.shop_id:
            return True
        return False

    return False


@router.websocket("/ws")
async def websocket_realtime_endpoint(
    websocket: WebSocket,
    token: Optional[str] = Query(None),
    db: AsyncSession = Depends(get_db_session)
):
    """
    Authenticated WebSocket gateway endpoint for PrivPrint realtime notifications.
    Query Param: ?token=<jwt_access_token>
    Supported Actions:
      - subscribe: {"type": "subscribe", "channel": "job:xyz"}
      - unsubscribe: {"type": "unsubscribe", "channel": "job:xyz"}
      - ping: {"type": "ping"}
    """
    socket_id = f"sock_{uuid.uuid4().hex[:12]}"
    auth_token = token

    # Check Authorization header if query param missing
    if not auth_token:
        headers = dict(websocket.headers)
        auth_header = headers.get("authorization", "")
        if auth_header.startswith("Bearer "):
            auth_token = auth_header.split(" ")[1]

    if not auth_token:
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION, reason="Missing authentication token")
        return

    try:
        principal = await get_principal_from_token_str(auth_token, db)
    except Exception as e:
        logger.warning(f"WebSocket auth failed: {e}")
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION, reason="Invalid or expired token")
        return

    await connection_manager.connect(websocket, socket_id, principal)

    # Start periodic heartbeat & token expiration check task
    async def heartbeat_and_expiration_task():
        try:
            while True:
                await asyncio.sleep(15)
                # Check token expiration
                try:
                    decode_access_token(auth_token)
                except Exception:
                    await connection_manager.send_personal_message(socket_id, {
                        "type": "error",
                        "code": "TOKEN_EXPIRED",
                        "message": "Authentication token expired. Re-authenticate required."
                    })
                    await websocket.close(code=status.WS_1008_POLICY_VIOLATION, reason="Token expired")
                    break

                # Send heartbeat
                await connection_manager.send_personal_message(socket_id, {
                    "type": "heartbeat",
                    "timestamp": datetime.now(timezone.utc).isoformat()
                })
        except asyncio.CancelledError:
            pass
        except Exception as e:
            logger.debug(f"Heartbeat loop stopped for {socket_id}: {e}")

    heartbeat_task = asyncio.create_task(heartbeat_and_expiration_task())

    try:
        # Initial Connection Welcome
        await connection_manager.send_personal_message(socket_id, {
            "type": "connected",
            "socket_id": socket_id,
            "user_id": principal.user_id,
            "role": principal.role,
            "timestamp": datetime.now(timezone.utc).isoformat()
        })

        while True:
            raw_text = await websocket.receive_text()
            try:
                msg = json.loads(raw_text)
            except Exception:
                await connection_manager.send_personal_message(socket_id, {
                    "type": "error",
                    "code": "BAD_REQUEST",
                    "message": "Invalid JSON format"
                })
                continue

            msg_type = msg.get("type")

            if msg_type == "ping":
                await connection_manager.send_personal_message(socket_id, {
                    "type": "pong",
                    "timestamp": datetime.now(timezone.utc).isoformat()
                })

            elif msg_type == "subscribe":
                channel = msg.get("channel", "")
                # 1. Validate Channel Pattern
                if not CHANNEL_PATTERN.match(channel):
                    await connection_manager.send_personal_message(socket_id, {
                        "type": "error",
                        "code": "FORBIDDEN_CHANNEL",
                        "message": f"Channel '{channel}' is invalid or arbitrary subscription forbidden.",
                        "channel": channel
                    })
                    continue

                # 2. Authorize Subscription
                is_auth = await authorize_subscription(channel, principal, db)
                if not is_auth:
                    await connection_manager.send_personal_message(socket_id, {
                        "type": "error",
                        "code": "FORBIDDEN",
                        "message": f"Access denied for channel '{channel}'",
                        "channel": channel
                    })
                    continue

                # 3. Subscribe
                await connection_manager.subscribe_channel(socket_id, channel)
                await connection_manager.send_personal_message(socket_id, {
                    "type": "subscribed",
                    "channel": channel,
                    "timestamp": datetime.now(timezone.utc).isoformat()
                })

            elif msg_type == "unsubscribe":
                channel = msg.get("channel", "")
                await connection_manager.unsubscribe_channel(socket_id, channel)
                await connection_manager.send_personal_message(socket_id, {
                    "type": "unsubscribed",
                    "channel": channel,
                    "timestamp": datetime.now(timezone.utc).isoformat()
                })

            else:
                await connection_manager.send_personal_message(socket_id, {
                    "type": "error",
                    "code": "UNKNOWN_ACTION",
                    "message": f"Unsupported message type '{msg_type}'"
                })

    except WebSocketDisconnect:
        logger.info(f"WebSocket client disconnected gracefully: {socket_id}")
    except Exception as e:
        logger.warning(f"Unexpected WebSocket error on socket {socket_id}: {e}")
    finally:
        heartbeat_task.cancel()
        await connection_manager.disconnect(socket_id)
