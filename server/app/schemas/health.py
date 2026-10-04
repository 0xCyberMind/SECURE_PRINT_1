from typing import Dict
from pydantic import BaseModel


class HealthResponse(BaseModel):
    status: str
    apiVersion: str
    environment: str
    timestamp: str
    checks: Dict[str, str]
