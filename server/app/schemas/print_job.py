from typing import List, Optional
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
    batch_id: Optional[str] = None
    file_index: int = Field(default=0, ge=0)
    total_files: int = Field(default=1, ge=1)
    retention_hours: int = Field(default=2, ge=1, le=24)


class BatchJobCreateItem(BaseModel):
    document_id: str
    requested_copies: int = Field(default=1, ge=1, le=100)
    page_count: int = Field(default=1, ge=1)
    color_mode: str = Field(default="MONOCHROME")
    paper_size: str = Field(default="A4")
    orientation: str = Field(default="PORTRAIT")
    duplex_mode: str = Field(default="SIMPLEX")
    idempotency_key: Optional[str] = None


class BatchJobCreateRequest(BaseModel):
    shop_id: str
    session_id: str
    batch_id: Optional[str] = None
    printer_id: Optional[str] = None
    retention_hours: int = Field(default=2, ge=1, le=24)
    items: List[BatchJobCreateItem] = Field(..., min_length=1, max_length=10)


class JobAuthorizeRequest(BaseModel):
    printer_id: Optional[str] = None
    idempotency_key: Optional[str] = None


class JobCancelRequest(BaseModel):
    reason: Optional[str] = Field(default="User cancelled")


class JobProgressUpdateRequest(BaseModel):
    pages_printed: int = Field(..., ge=0)
    completed_copies: Optional[int] = Field(None, ge=0)
    status: Optional[str] = None


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
    pages_printed: int = 0
    batch_id: Optional[str] = None
    file_index: int = 0
    total_files: int = 1
    retention_hours: int = 2
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


class BatchJobResponse(BaseModel):
    batch_id: str
    total_files: int
    total_pages: int
    jobs: List[JobResponse]

