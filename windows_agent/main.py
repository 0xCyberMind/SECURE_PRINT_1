import asyncio
import logging
import os
from logging.handlers import RotatingFileHandler
from windows_agent.config import AgentConfig
from windows_agent.agent_service import WindowsAgentService

log_directory = os.path.join(
    os.environ.get("LOCALAPPDATA", os.path.expanduser("~")),
    "PrivPrintStation",
)
os.makedirs(log_directory, exist_ok=True)
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    handlers=[
        RotatingFileHandler(
            os.path.join(log_directory, "station.log"),
            maxBytes=2 * 1024 * 1024,
            backupCount=3,
            encoding="utf-8",
        )
    ],
)
logger = logging.getLogger("WindowsAgentMain")

async def main():
    config = AgentConfig.load()
    service = WindowsAgentService(config)

    logger.info("==================================================")
    logger.info(" PRIVPRINT WINDOWS SHOP STATION AGENT v1.0.0 ")
    logger.info(" Zero-Knowledge Cross-Platform Hardware Spooler ")
    logger.info("==================================================")
    logger.info(f"Target Server: {config.server_base_url}")
    await service.start()
    await asyncio.Event().wait()

if __name__ == "__main__":
    asyncio.run(main())
