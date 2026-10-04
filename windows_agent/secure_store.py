import os
import sys
import json
import base64
from typing import Optional

class SecureCredentialStore:
    """
    Secure Credential Storage for Windows Agent.
    Uses Windows DPAPI (win32crypt) on Windows platform,
    with base64/file security on Linux test runners.
    """

    def __init__(self, storage_path: Optional[str] = None):
        if storage_path is None:
            base_dir = os.path.dirname(__file__)
            storage_path = os.path.join(base_dir, ".secure_credentials.dat")
        self.storage_path = storage_path

    def _is_windows(self) -> bool:
        return sys.platform == "win32"

    def store_credentials(self, device_id: str, api_key: str, access_token: str, refresh_token: str):
        payload = {
            "device_id": device_id,
            "api_key": api_key,
            "access_token": access_token,
            "refresh_token": refresh_token
        }
        raw_bytes = json.dumps(payload).encode("utf-8")

        if self._is_windows():
            try:
                import win32crypt
                protected = win32crypt.CryptProtectData(raw_bytes, "PrivPrintDeviceKey", None, None, None, 0)
                with open(self.storage_path, "wb") as f:
                    f.write(protected)
                return
            except Exception:
                pass

        # Fallback obfuscated storage for cross-platform/test environments
        encoded = base64.b64encode(raw_bytes)
        with open(self.storage_path, "wb") as f:
            f.write(encoded)

    def retrieve_credentials(self) -> Optional[dict]:
        if not os.path.exists(self.storage_path):
            return None

        try:
            with open(self.storage_path, "rb") as f:
                content = f.read()

            if self._is_windows():
                try:
                    import win32crypt
                    _, decrypted = win32crypt.CryptUnprotectData(content, None, None, None, 0)
                    return json.loads(decrypted.decode("utf-8"))
                except Exception:
                    pass

            decoded = base64.b64decode(content)
            return json.loads(decoded.decode("utf-8"))
        except Exception:
            return None

    def clear(self):
        if os.path.exists(self.storage_path):
            try:
                os.remove(self.storage_path)
            except Exception:
                pass
