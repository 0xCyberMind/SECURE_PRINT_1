import asyncio
import logging
import uvicorn
from windows_agent.config import AgentConfig
from windows_agent.agent_service import WindowsAgentService
from windows_agent.dashboard import dashboard_app, init_dashboard

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s"
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
    logger.info(f"Shop ID: {config.shop_id}")
    logger.info(f"Dashboard URL: http://localhost:{config.dashboard_port}")

    # Start background agent
    await service.start()

    # Start uvicorn server for local dashboard
    uvicorn_config = uvicorn.Config(
        app=dashboard_app,
        host="0.0.0.0",
        port=config.dashboard_port,
        log_level="warning"
    )
    server = uvicorn.Server(uvicorn_config)
    await server.serve()

if __name__ == "__main__":
    asyncio.run(main())
