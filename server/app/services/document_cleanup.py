import asyncio
import logging

from app.models.base import AsyncSessionLocal
from app.services.cleanup_service import DocumentCleanupService

logger = logging.getLogger("privprint.cleanup_worker")


async def run() -> None:
    while True:
        try:
            async with AsyncSessionLocal() as db:
                result = await DocumentCleanupService(db).run_cleanup_sweep()
                await db.commit()
                logger.info(
                    "Cleanup sweep completed: scanned=%s shredded=%s retained=%s failed=%s",
                    result.scanned_documents,
                    result.shredded_documents,
                    result.retained_documents,
                    result.failed_documents,
                )
        except Exception:
            logger.exception("Cleanup sweep failed; retrying on the next interval")
        await asyncio.sleep(60)


if __name__ == "__main__":
    asyncio.run(run())
