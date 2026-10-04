import httpx
from typing import Optional, List, Dict, Any
from windows_agent.config import AgentConfig

class WindowsAgentApiClient:
    """
    Outbound HTTPS REST Client for Windows Shop Station Agent.
    Strictly outbound communication (no inbound listening ports on shop network).
    """

    def __init__(self, config: AgentConfig):
        self.config = config
        self.base_url = config.server_base_url.rstrip("/")
        self.access_token: Optional[str] = config.access_token
        self.refresh_token: Optional[str] = config.refresh_token

    def _headers(self) -> Dict[str, str]:
        headers = {"Content-Type": "application/json"}
        if self.access_token:
            headers["Authorization"] = f"Bearer {self.access_token}"
        return headers

    async def register_device(self, shop_id: str, device_name: str, operator_token: Optional[str] = None) -> Dict[str, Any]:
        url = f"{self.base_url}/api/v1/devices/register"
        payload = {
            "shop_id": shop_id,
            "name": device_name,
            "os_info": "Windows 11 Pro 64-bit Build 22631",
            "app_version": "1.0.0",
            "hardware_fingerprint": "WIN-SPOOLER-HW-FP-998"
        }
        headers = {"Content-Type": "application/json"}
        if operator_token:
            headers["Authorization"] = f"Bearer {operator_token}"
        elif self.access_token:
            headers["Authorization"] = f"Bearer {self.access_token}"

        async with httpx.AsyncClient(timeout=10.0) as client:
            res = await client.post(url, json=payload, headers=headers)
            res.raise_for_status()
            data = res.json()
            self.config.device_id = data.get("device_id")
            self.config.api_key = data.get("api_key")
            return data

    async def authenticate_device(self, device_id: str, api_key: str) -> Dict[str, Any]:
        """
        Exchanges the device api_key (issued at registration) for a JWT pair
        carrying the PRINT_DEVICE role, via POST /api/v1/devices/authenticate.
        """
        url = f"{self.base_url}/api/v1/devices/authenticate"
        payload = {
            "device_id": device_id,
            "api_key": api_key,
        }
        async with httpx.AsyncClient(timeout=10.0) as client:
            res = await client.post(url, json=payload)
            res.raise_for_status()
            data = res.json()
            self.access_token = data.get("accessToken") or data.get("access_token")
            self.refresh_token = data.get("refreshToken") or data.get("refresh_token")
            self.config.access_token = self.access_token
            self.config.refresh_token = self.refresh_token
            return data

    async def get_print_queue(self, shop_id: str) -> List[Dict[str, Any]]:
        url = f"{self.base_url}/api/v1/print/jobs?shopId={shop_id}"
        async with httpx.AsyncClient(timeout=10.0) as client:
            res = await client.get(url, headers=self._headers())
            res.raise_for_status()
            return res.json()

    async def start_printing(self, job_id: str) -> Dict[str, Any]:
        url = f"{self.base_url}/api/v1/print/jobs/{job_id}/start"
        async with httpx.AsyncClient(timeout=10.0) as client:
            res = await client.post(url, headers=self._headers())
            res.raise_for_status()
            return res.json()

    async def increment_copy(self, job_id: str, delta: int = 1) -> Dict[str, Any]:
        url = f"{self.base_url}/api/v1/print/jobs/{job_id}/increment-copy?delta={delta}"
        async with httpx.AsyncClient(timeout=10.0) as client:
            res = await client.post(url, headers=self._headers())
            res.raise_for_status()
            return res.json()

    async def fail_job(self, job_id: str, reason: str) -> Dict[str, Any]:
        url = f"{self.base_url}/api/v1/print/jobs/{job_id}/fail?reason={reason}"
        async with httpx.AsyncClient(timeout=10.0) as client:
            res = await client.post(url, headers=self._headers())
            res.raise_for_status()
            return res.json()

    async def execute_cleanup(self, job_id: str) -> Dict[str, Any]:
        url = f"{self.base_url}/api/v1/cleanup/execute/{job_id}"
        async with httpx.AsyncClient(timeout=10.0) as client:
            res = await client.post(url, headers=self._headers())
            res.raise_for_status()
            return res.json()

    async def get_shop_printers(self, shop_id: str) -> List[Dict[str, Any]]:
        url = f"{self.base_url}/api/v1/shops/{shop_id}/printers"
        async with httpx.AsyncClient(timeout=10.0) as client:
            res = await client.get(url, headers=self._headers())
            res.raise_for_status()
            return res.json()

    async def select_printer(self, shop_id: str, printer_id: str, session_id: Optional[str] = None) -> Dict[str, Any]:
        url = f"{self.base_url}/api/v1/printers/select"
        payload = {
            "shop_id": shop_id,
            "printer_id": printer_id,
            "session_id": session_id,
            "device_id": self.config.device_id
        }
        async with httpx.AsyncClient(timeout=10.0) as client:
            res = await client.post(url, json=payload, headers=self._headers())
            res.raise_for_status()
            return res.json()

    async def sync_printers(self, shop_id: str, printers: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        url = f"{self.base_url}/api/v1/printers/sync"
        payload = {
            "shop_id": shop_id,
            "printers": printers
        }
        async with httpx.AsyncClient(timeout=10.0) as client:
            res = await client.post(url, json=payload, headers=self._headers())
            res.raise_for_status()
            return res.json()

    async def send_heartbeat(self, device_id: str) -> Dict[str, Any]:
        url = f"{self.base_url}/api/v1/devices/{device_id}/heartbeat"
        async with httpx.AsyncClient(timeout=10.0) as client:
            res = await client.post(url, headers=self._headers())
            res.raise_for_status()
            return res.json()

    async def get_device_state(self, device_id: str) -> Dict[str, Any]:
        url = f"{self.base_url}/api/v1/devices/{device_id}"
        async with httpx.AsyncClient(timeout=10.0) as client:
            res = await client.get(url, headers=self._headers())
            res.raise_for_status()
            return res.json()

    async def refresh_auth_tokens(self) -> Dict[str, Any]:
        if not self.refresh_token:
            raise ValueError("No refresh token available")
        url = f"{self.base_url}/api/v1/auth/refresh"
        payload = {"refresh_token": self.refresh_token}
        async with httpx.AsyncClient(timeout=10.0) as client:
            res = await client.post(url, json=payload)
            res.raise_for_status()
            data = res.json()
            self.access_token = data.get("accessToken") or data.get("access_token")
            self.refresh_token = data.get("refreshToken") or data.get("refresh_token")
            self.config.access_token = self.access_token
            self.config.refresh_token = self.refresh_token
            return data


