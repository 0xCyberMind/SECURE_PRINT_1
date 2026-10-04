import asyncio
import json
import logging
import uuid
from datetime import datetime, timezone
from typing import Dict, Set, Optional, Any
from fastapi import WebSocket, status
from collections import OrderedDict

from app.services.redis_service import RedisService, get_redis_service
from app.api.deps import AuthPrincipal, decode_access_token
from app.repositories.user_repo import UserRepository
from app.repositories.session_repo import SessionRepository
from app.repositories.shop_repo import ShopRepository, DeviceRepository
from app.repositories.print_job_repo import PrintJobRepository

logger = logging.getLogger("privprint.realtime")


class ConnectionManager:
    """
    Manages active WebSocket connections, subscription routing,
    and Redis Pub/Sub bridge.
    """

    def __init__(self):
        # socket_id -> WebSocket
        self.active_connections: Dict[str, WebSocket] = {}
        # socket_id -> AuthPrincipal
        self.connection_principals: Dict[str, AuthPrincipal] = {}
        # socket_id -> Set[channel_name]
        self.connection_channels: Dict[str, Set[str]] = {}
        # channel_name -> Set[socket_id]
        self.channel_subscribers: Dict[str, Set[str]] = {}
        # Redis PubSub listener tasks per channel: channel_name -> asyncio.Task
        self.redis_tasks: Dict[str, asyncio.Task] = {}
        # Event deduplication cache: socket_id -> OrderedDict[event_id, timestamp]
        self.seen_event_ids: Dict[str, OrderedDict] = {}

    async def connect(self, websocket: WebSocket, socket_id: str, principal: AuthPrincipal) -> None:
        await websocket.accept()
        self.active_connections[socket_id] = websocket
        self.connection_principals[socket_id] = principal
        self.connection_channels[socket_id] = set()
        self.seen_event_ids[socket_id] = OrderedDict()
        logger.info(f"WebSocket client connected: socket_id={socket_id}, user={principal.user_id}, role={principal.role}")

    async def disconnect(self, socket_id: str) -> None:
        if socket_id in self.active_connections:
            del self.active_connections[socket_id]
        if socket_id in self.connection_principals:
            del self.connection_principals[socket_id]

        # Unsubscribe from all channels
        if socket_id in self.connection_channels:
            channels = list(self.connection_channels[socket_id])
            for ch in channels:
                await self.unsubscribe_channel(socket_id, ch)
            del self.connection_channels[socket_id]

        if socket_id in self.seen_event_ids:
            del self.seen_event_ids[socket_id]

        logger.info(f"WebSocket client disconnected: socket_id={socket_id}")

    def is_event_duplicate(self, socket_id: str, event_id: str) -> bool:
        """Checks and updates LRU event_id cache for deduplication per connection."""
        if socket_id not in self.seen_event_ids:
            return False
        cache = self.seen_event_ids[socket_id]
        if event_id in cache:
            return True
        cache[event_id] = datetime.now(timezone.utc).timestamp()
        if len(cache) > 200:
            cache.popitem(last=False)
        return False

    async def subscribe_channel(self, socket_id: str, channel: str) -> bool:
        if socket_id not in self.active_connections:
            return False

        if channel not in self.channel_subscribers:
            self.channel_subscribers[channel] = set()
            # Start Redis PubSub bridge task for this channel if first subscriber
            task = asyncio.create_task(self._listen_redis_channel(channel))
            self.redis_tasks[channel] = task

        self.channel_subscribers[channel].add(socket_id)
        if socket_id in self.connection_channels:
            self.connection_channels[socket_id].add(channel)

        logger.info(f"Socket {socket_id} subscribed to {channel}")
        return True

    async def unsubscribe_channel(self, socket_id: str, channel: str) -> None:
        if channel in self.channel_subscribers:
            self.channel_subscribers[channel].discard(socket_id)
            if not self.channel_subscribers[channel]:
                del self.channel_subscribers[channel]
                # Cancel Redis PubSub bridge task if no subscribers left
                if channel in self.redis_tasks:
                    task = self.redis_tasks.pop(channel)
                    task.cancel()

        if socket_id in self.connection_channels:
            self.connection_channels[socket_id].discard(channel)

    async def send_personal_message(self, socket_id: str, message: Dict[str, Any]) -> bool:
        if socket_id not in self.active_connections:
            return False
        ws = self.active_connections[socket_id]
        try:
            # Ensure event_id and timestamp present
            if "event_id" not in message and message.get("type") == "event":
                message["event_id"] = f"evt_{uuid.uuid4().hex[:12]}"
            if "timestamp" not in message:
                message["timestamp"] = datetime.now(timezone.utc).isoformat()

            await ws.send_text(json.dumps(message))
            return True
        except Exception as e:
            logger.warning(f"Failed to send message to socket {socket_id}: {e}")
            return False

    async def broadcast_to_channel(self, channel: str, message: Dict[str, Any]) -> None:
        subscribers = self.channel_subscribers.get(channel, set()).copy()
        if not subscribers:
            return

        event_id = message.get("event_id") or f"evt_{uuid.uuid4().hex[:12]}"
        message["event_id"] = event_id
        if "timestamp" not in message:
            message["timestamp"] = datetime.now(timezone.utc).isoformat()

        for socket_id in subscribers:
            if not self.is_event_duplicate(socket_id, event_id):
                await self.send_personal_message(socket_id, message)

    async def _listen_redis_channel(self, channel: str) -> None:
        redis_svc = get_redis_service()
        pubsub = await redis_svc.subscribe(channel)
        if not pubsub:
            logger.warning(f"Unable to subscribe to Redis channel {channel}")
            return

        try:
            while True:
                msg = await pubsub.get_message(ignore_subscribe_messages=True, timeout=1.0)
                if msg and msg.get("type") == "message":
                    raw_data = msg.get("data")
                    try:
                        parsed = json.loads(raw_data) if isinstance(raw_data, str) else raw_data
                        event_payload = {
                            "type": "event",
                            "channel": channel,
                            "event_id": parsed.get("event_id") or f"evt_{uuid.uuid4().hex[:12]}",
                            "event": parsed.get("event", "MESSAGE"),
                            "timestamp": parsed.get("timestamp") or datetime.now(timezone.utc).isoformat(),
                            "data": parsed.get("data", parsed)
                        }
                        await self.broadcast_to_channel(channel, event_payload)
                    except Exception as pe:
                        logger.warning(f"Error parsing Redis message on channel {channel}: {pe}")
                await asyncio.sleep(0.05)
        except asyncio.CancelledError:
            logger.debug(f"Redis listener task cancelled for channel {channel}")
        except Exception as e:
            logger.warning(f"Error in Redis listener loop for channel {channel}: {e}")
        finally:
            try:
                await pubsub.unsubscribe(channel)
            except Exception:
                pass


# Global connection manager instance
connection_manager = ConnectionManager()
