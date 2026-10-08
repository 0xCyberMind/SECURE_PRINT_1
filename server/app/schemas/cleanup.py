from typing import Optional, List, Dict, Any
from datetime import datetime
from pydantic import BaseModel, ConfigDict


class CleanupResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    job_id: Optional[str] = None
    document_id: str
    cleanup_state: str  # SHREDDED, PENDING, VERIFIED, RETENTION_REQUIRED, FAILED
    storage_verified_deleted: bool
    retention_required: bool
    reason: str
    storage_path: Optional[str] = None
    storage_backend: str = "S3_COMPATIBLE_OBJECT_STORE"
    guarantee_level: str = "OBJECT_DELETION_VERIFIED"  # Discloses object unlinking vs physical hardware magnetic overwrite
    cleaned_at: datetime


class CleanupStatusResponse(BaseModel):
    document_id: str
    cleanup_state: str
    is_deleted: bool
    retention_required: bool
    active_job_count: int
    copies_authorized: int
    copies_consumed: int
    storage_exists: bool
    guarantee_level: str
    expires_at: datetime


class CleanupSweepResponse(BaseModel):
    scanned_documents: int
    shredded_documents: int
    retained_documents: int
    failed_documents: int
    details: List[CleanupResponse]


class HistoryCleanupResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    shop_id: str
    deleted_job_ids: List[str]
    deleted_count: int
    retained_count: int
    retention_hours: int
    cleaned_at: datetime


class HistoryCleanupSweepResponse(BaseModel):
    total_scanned_jobs: int
    total_deleted_jobs: int
    total_retained_jobs: int
    details_by_shop: Dict[str, HistoryCleanupResponse]
