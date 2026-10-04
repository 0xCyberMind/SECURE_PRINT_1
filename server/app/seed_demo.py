"""One-shot demo seed: creates verified shops with real coordinates so
nearby discovery and QR pairing work immediately on a fresh database.

Run inside the API container:
    docker compose exec api python -m app.seed_demo

Or on the host against a local database:
    python -m app.seed_demo   (from the server/ directory)
"""

import asyncio

from sqlalchemy import select

from app.models.base import AsyncSessionLocal, engine
from app.models.entities import Shop

SHOPS = [
    {
        "id": "SHOP-101",
        "name": "Apex Campus Xerox & Print",
        "address": "Student Center, Ahmedabad",
        "lat": 23.0225,
        "lng": 72.5714,
    },
    {
        "id": "SHOP-102",
        "name": "Metro Secure QuickPrint",
        "address": "SG Highway, Ahmedabad",
        "lat": 23.0520,
        "lng": 72.5020,
    },
    {
        "id": "SHOP-103",
        "name": "City Hall Documentation Desk",
        "address": "Danapith, Ahmedabad",
        "lat": 23.0250,
        "lng": 72.5800,
    },
]


async def seed() -> None:
    created = 0
    async with AsyncSessionLocal() as db:
        for s in SHOPS:
            existing = (
                (await db.execute(select(Shop).where(Shop.id == s["id"])))
                .scalars()
                .first()
            )
            if existing:
                continue
            db.add(
                Shop(
                    id=s["id"],
                    name=s["name"],
                    owner_id=None,
                    address=s["address"],
                    latitude=s["lat"],
                    longitude=s["lng"],
                    status="ACTIVE",
                    is_verified=True,
                    is_online=True,
                    supports_color=True,
                    supports_duplex=True,
                    permanent_qr_payload=f"privprint://shop?id={s['id']}",
                )
            )
            created += 1
        await db.commit()
    await engine.dispose()
    print(f"Seed complete: {created} shop(s) created, {len(SHOPS) - created} already present.")


if __name__ == "__main__":
    asyncio.run(seed())
