import os
import json
import sys
from dataclasses import dataclass, field
from typing import Optional


def _config_directory() -> str:
    if getattr(sys, "frozen", False):
        base_dir = os.path.join(
            os.environ.get("LOCALAPPDATA", os.path.expanduser("~")),
            "PrivPrintStation",
        )
        os.makedirs(base_dir, exist_ok=True)
        return base_dir
    return os.path.dirname(__file__)


CONFIG_FILE_PATH = os.path.join(_config_directory(), "shop_station_config.json")

PROD_SERVER_BASE_URL = "https://secure-print-1.onrender.com/"
DEV_SERVER_BASE_URL = "https://ais-dev-6u62dc37mqabbjyehi6umo-408539472511.asia-southeast1.run.app/"

@dataclass
class AgentConfig:
    server_base_url: str = PROD_SERVER_BASE_URL
    shop_id: str = ""
    device_name: str = "Windows Xerox Station Agent"
    device_id: Optional[str] = None
    api_key: Optional[str] = None
    access_token: Optional[str] = None
    refresh_token: Optional[str] = None
    auto_print_enabled: bool = True
    history_retention_hours: int = 4
    heartbeat_interval_sec: int = 15
    reconnect_delay_sec: int = 5
    log_level: str = "INFO"

    @classmethod
    def load(cls, path: str = CONFIG_FILE_PATH) -> "AgentConfig":
        if os.path.exists(path):
            try:
                with open(path, "r", encoding="utf-8") as f:
                    data = json.load(f)
                    configured_url = data.get("server_base_url", "")
                    if isinstance(configured_url, str) and configured_url.rstrip("/").lower() == "https://api.privprint.com":
                        data["server_base_url"] = PROD_SERVER_BASE_URL
                    return cls(**{k: v for k, v in data.items() if k in cls.__dataclass_fields__})
            except Exception:
                pass
        return cls()

    def save(self, path: str = CONFIG_FILE_PATH):
        data = {
            "server_base_url": self.server_base_url,
            "shop_id": self.shop_id,
            "device_name": self.device_name,
            "auto_print_enabled": self.auto_print_enabled,
            "history_retention_hours": self.history_retention_hours,
            "heartbeat_interval_sec": self.heartbeat_interval_sec,
            "reconnect_delay_sec": self.reconnect_delay_sec,
            "log_level": self.log_level
        }
        with open(path, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2)
