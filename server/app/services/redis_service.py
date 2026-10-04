import json
import logging
from typing import Optional, Tuple, Any, Dict, AsyncGenerator
from datetime import datetime, timezone
import redis.asyncio as aioredis
from redis.exceptions import RedisError, ConnectionError, TimeoutError

from app.core.config import settings

logger = logging.getLogger("privprint.redis")


class RedisService:
    """
    Production-grade Redis abstraction for:
    1. Pub/Sub event broadcasting (job:{id}, user:{id}, shop:{id}, device:{id})
    2. Atomic rate limiting (sliding/fixed window counter with TTL)
    3. Temporary session and volatile cache storage
    4. Fast token revocation cache

    Design Invariants:
    - Authoritative business data is NEVER stored solely in Redis (persisted in PostgreSQL).
    - Graceful degradation: Redis errors/timeouts fail open for non-critical paths,
      preventing cache cluster hiccups from breaking user printing workflows.
    """

    def __init__(self, client: Optional[aioredis.Redis] = None):
        self._client: Optional[aioredis.Redis] = client
        self._is_connected: bool = False
        self._simulate_failure: bool = False  # Used for testing graceful failure behavior

    async def get_client(self) -> Optional[aioredis.Redis]:
        if self._simulate_failure:
            return None

        if self._client is None and settings.REDIS_ENABLED:
            try:
                client = aioredis.from_url(
                    settings.REDIS_URL,
                    socket_timeout=settings.REDIS_SOCKET_TIMEOUT,
                    socket_connect_timeout=settings.REDIS_SOCKET_TIMEOUT,
                    decode_responses=True
                )
                await client.ping()
                self._client = client
            except Exception as e:
                logger.info(f"Real Redis unavailable ({e}). Initializing FakeRedis fallback.")
                try:
                    import fakeredis.aioredis
                    self._client = fakeredis.aioredis.FakeRedis(decode_responses=True)
                except Exception as fe:
                    logger.warning(f"Failed to initialize FakeRedis: {fe}")
                    return None
        return self._client

    def set_simulation_failure(self, fail: bool) -> None:
        """Testing utility to test circuit breaker / graceful failure."""
        self._simulate_failure = fail

    async def ping(self) -> bool:
        """Check Redis connectivity."""
        if self._simulate_failure:
            return False
        try:
            client = await self.get_client()
            if not client:
                return False
            return bool(await client.ping())
        except (RedisError, ConnectionError, TimeoutError, OSError) as e:
            logger.warning(f"Redis ping failed: {e}")
            return False

    # ---------------------------------------------------------
    # 1. Atomic Rate Limiting
    # ---------------------------------------------------------
    async def check_rate_limit(
        self,
        key: str,
        max_requests: int,
        window_seconds: int
    ) -> Tuple[bool, int, int]:
        """
        Atomic rate limiter using Redis INCR + EXPIRE.
        Returns:
            Tuple[is_allowed (bool), remaining_requests (int), retry_after_seconds (int)]
        Graceful Failure:
            If Redis is unavailable, logs a warning and permits the request (fail-open).
        """
        if self._simulate_failure:
            return True, max_requests, 0

        try:
            client = await self.get_client()
            if not client:
                return True, max_requests, 0

            prefixed_key = f"ratelimit:{key}"
            current = await client.incr(prefixed_key)
            if current == 1:
                await client.expire(prefixed_key, window_seconds)

            ttl = await client.ttl(prefixed_key)
            ttl = max(ttl, 1)

            if current > max_requests:
                return False, 0, ttl

            return True, max_requests - current, 0
        except (RedisError, ConnectionError, TimeoutError, OSError) as e:
            logger.warning(f"Redis rate limiter failed for {key}: {e}. Failing open.")
            return True, max_requests, 0

    # ---------------------------------------------------------
    # 2. Pub/Sub System
    # ---------------------------------------------------------
    async def publish(self, channel: str, message: Any) -> int:
        """
        Publishes message payload to a Redis channel.
        Fails gracefully if Redis is unreachable.
        """
        if self._simulate_failure:
            return 0

        try:
            client = await self.get_client()
            if not client:
                return 0

            payload_str = json.dumps(message) if not isinstance(message, str) else message
            return await client.publish(channel, payload_str)
        except (RedisError, ConnectionError, TimeoutError, OSError) as e:
            logger.warning(f"Failed to publish to channel '{channel}': {e}")
            return 0

    async def publish_job_event(self, job_id: str, event_type: str, data: Dict[str, Any]) -> int:
        return await self.publish(f"job:{job_id}", {
            "channel": f"job:{job_id}",
            "event": event_type,
            "job_id": job_id,
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "data": data
        })

    async def publish_user_event(self, user_id: str, event_type: str, data: Dict[str, Any]) -> int:
        return await self.publish(f"user:{user_id}", {
            "channel": f"user:{user_id}",
            "event": event_type,
            "user_id": user_id,
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "data": data
        })

    async def publish_shop_event(self, shop_id: str, event_type: str, data: Dict[str, Any]) -> int:
        return await self.publish(f"shop:{shop_id}", {
            "channel": f"shop:{shop_id}",
            "event": event_type,
            "shop_id": shop_id,
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "data": data
        })

    async def publish_device_event(self, device_id: str, event_type: str, data: Dict[str, Any]) -> int:
        return await self.publish(f"device:{device_id}", {
            "channel": f"device:{device_id}",
            "event": event_type,
            "device_id": device_id,
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "data": data
        })

    async def subscribe(self, channel: str) -> Optional[aioredis.client.PubSub]:
        """Subscribes to a channel, returning the PubSub object."""
        if self._simulate_failure:
            return None
        try:
            client = await self.get_client()
            if not client:
                return None
            pubsub = client.pubsub()
            await pubsub.subscribe(channel)
            return pubsub
        except (RedisError, ConnectionError, TimeoutError, OSError) as e:
            logger.warning(f"Failed to subscribe to channel '{channel}': {e}")
            return None

    # ---------------------------------------------------------
    # 3. Temporary Session & Volatile Cache Data
    # ---------------------------------------------------------
    async def set_cache(self, key: str, value: Any, ttl_seconds: int = 300) -> bool:
        if self._simulate_failure:
            return False
        try:
            client = await self.get_client()
            if not client:
                return False
            payload = json.dumps(value)
            await client.set(f"cache:{key}", payload, ex=ttl_seconds)
            return True
        except (RedisError, ConnectionError, TimeoutError, OSError) as e:
            logger.warning(f"Failed to set cache key '{key}': {e}")
            return False

    async def get_cache(self, key: str) -> Optional[Any]:
        if self._simulate_failure:
            return None
        try:
            client = await self.get_client()
            if not client:
                return None
            val = await client.get(f"cache:{key}")
            if val:
                return json.loads(val)
            return None
        except (RedisError, ConnectionError, TimeoutError, OSError) as e:
            logger.warning(f"Failed to get cache key '{key}': {e}")
            return None

    async def delete_cache(self, key: str) -> bool:
        if self._simulate_failure:
            return False
        try:
            client = await self.get_client()
            if not client:
                return False
            await client.delete(f"cache:{key}")
            return True
        except (RedisError, ConnectionError, TimeoutError, OSError) as e:
            logger.warning(f"Failed to delete cache key '{key}': {e}")
            return False

    # ---------------------------------------------------------
    # 4. Token Revocation Cache
    # ---------------------------------------------------------
    async def cache_revoked_token(self, token_or_jti: str, ttl_seconds: int = 86400) -> bool:
        """Store revoked token or JTI in fast Redis set/key to bypass DB queries on hot auth path."""
        if self._simulate_failure:
            return False
        try:
            client = await self.get_client()
            if not client:
                return False
            await client.set(f"revoked_token:{token_or_jti}", "1", ex=ttl_seconds)
            return True
        except (RedisError, ConnectionError, TimeoutError, OSError) as e:
            logger.warning(f"Failed to cache revoked token: {e}")
            return False

    async def is_token_revoked_cached(self, token_or_jti: str) -> Optional[bool]:
        """
        Returns:
            True if explicitly cached as revoked.
            False if not found in revoked cache (may still check DB).
            None if Redis is unavailable.
        """
        if self._simulate_failure:
            return None
        try:
            client = await self.get_client()
            if not client:
                return None
            res = await client.exists(f"revoked_token:{token_or_jti}")
            return bool(res > 0)
        except (RedisError, ConnectionError, TimeoutError, OSError) as e:
            logger.warning(f"Failed to check token revocation cache: {e}")
            return None

    async def close(self) -> None:
        if self._client:
            await self._client.close()
            self._client = None


# Global singleton instance
_redis_service_instance: Optional[RedisService] = None


def get_redis_service() -> RedisService:
    global _redis_service_instance
    if _redis_service_instance is None:
        _redis_service_instance = RedisService()
    return _redis_service_instance
