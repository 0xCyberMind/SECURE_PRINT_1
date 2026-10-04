from typing import Optional
from datetime import datetime
from pydantic import BaseModel, Field, ConfigDict


class InitUploadRequest(BaseModel):
    session_id: str
    filename: str = Field(..., min_length=1, max_length=255)
    file_size_bytes: int = Field(..., gt=0)
    mime_type: str = Field(default="application/pdf")
    sha256_hash: str = Field(..., min_length=64, max_length=64)
    iv_hex: str = Field(..., min_length=16)
    key_fingerprint: str = Field(..., min_length=8)
    copies_authorized: int = Field(default=1, ge=1, le=100)


class InitUploadResponse(BaseModel):
    upload_id: str
    document_id: str
    storage_path: str
    presigned_upload_url: str
    expires_in_seconds: int = 300


class CompleteUploadRequest(BaseModel):
    document_id: str
    session_id: str
    sha256_hash: Optional[str] = None
    file_size_bytes: Optional[int] = None


class DocumentResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    user_id: str
    session_id: str
    filename: str
    file_size_bytes: int
    mime_type: str
    sha256_hash: str
    encryption_algorithm: str
    iv_hex: str
    key_fingerprint: str
    copies_authorized: int
    copies_consumed: int
    expires_at: datetime
    cleanup_state: str
    download_url: Optional[str] = None
    download_expires_in: Optional[int] = None
    created_at: datetime
