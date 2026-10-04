from typing import Optional
from datetime import datetime
from pydantic import BaseModel, Field, ConfigDict, computed_field


class JobCreateRequest(BaseModel):
    shop_id: str
    session_id: str
    document_id: str
    printer_id: Optional[str] = None
    requested_copies: int = Field(default=1, ge=1, le=100)
    page_count: int = Field(default=1, ge=1)
    color_mode: str = Field(default="MONOCHROME")
    paper_size: str = Field(default="A4")
    orientation: str = Field(default="PORTRAIT")
    duplex_mode: str = Field(default="SIMPLEX")
    idempotency_key: Optional[str] = None


class JobAuthorizeRequest(BaseModel):
    printer_id: Optional[str] = None
    idempotency_key: Optional[str] = None


class JobCancelRequest(BaseModel):
    reason: Optional[str] = Field(default="User cancelled")


class JobResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    user_id: str
    shop_id: str
    session_id: str
    document_id: str
    printer_id: Optional[str] = None
    requested_copies: int
    completed_copies: int
    page_count: int
    status: str
    color_mode: str
    paper_size: str
    orientation: str
    duplex_mode: str
    failure_reason: Optional[str] = None
    idempotency_key: Optional[str] = None
    created_at: datetime
    updated_at: datetime
    completed_at: Optional[datetime] = None
    expires_at: datetime

    @computed_field
    @property
    def state(self) -> str:
        return self.status
