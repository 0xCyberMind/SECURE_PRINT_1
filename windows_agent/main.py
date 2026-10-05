import asyncio
import logging
import os
import webbrowser
import uvicorn
from logging.handlers import RotatingFileHandler
from windows_agent.config import AgentConfig
from windows_agent.agent_service import WindowsAgentService
from windows_agent.dashboard import dashboard_app, init_dashboard

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

    # Initialize dashboard with agent reference
    init_dashboard(service)

    logger.info("==================================================")
    logger.info(" PRIVPRINT WINDOWS SHOP STATION AGENT v1.0.0 ")
    logger.info(" Zero-Knowledge Cross-Platform Hardware Spooler ")
    logger.info("==================================================")
    logger.info(f"Target Server: {config.server_base_url}")
    dashboard_url = f"http://127.0.0.1:{config.dashboard_port}"
    logger.info(f"Dashboard URL: {dashboard_url}")

    # Start background agent
    await service.start()

    # Start uvicorn server for local dashboard
    uvicorn_config = uvicorn.Config(
        app=dashboard_app,
        host="127.0.0.1",
        port=config.dashboard_port,
        log_level="warning"
    )
    server = uvicorn.Server(uvicorn_config)

    if os.environ.get("PRIVPRINT_OPEN_DASHBOARD", "1") != "0":
        async def open_dashboard_when_ready():
            while not server.started:
                await asyncio.sleep(0.1)
            webbrowser.open(dashboard_url)

        asyncio.create_task(open_dashboard_when_ready())
    await server.serve()

if __name__ == "__main__":
    asyncio.run(main())
