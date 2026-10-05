import os
import uuid
import re
import base64
import hashlib
from urllib.parse import quote
from datetime import datetime, timezone
from typing import Optional
from fastapi import APIRouter, Depends, Request, Response, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import settings
from app.core.exceptions import PrivPrintException, ErrorCode
from app.models.base import get_db_session
from app.models.enums import PrintJobStatus, UserRole
from app.models.entities import Device, Document, PendingUpload, Session, PrintJob
from app.repositories.session_repo import SessionRepository
from app.repositories.document_repo import DocumentRepository
from app.schemas.document import (
    InitUploadRequest,
    InitUploadResponse,
    CompleteUploadRequest,
    DocumentResponse,
)
from app.services.storage import StorageService, get_storage_service
from app.api.deps import get_current_user, AuthPrincipal
from app.api.deps_rate_limit import RateLimiter

router = APIRouter()


def is_valid_sha256(hex_str: str) -> bool:
    return bool(re.fullmatch(r"[0-9a-fA-F]{64}", hex_str))


def validate_file_type(filename: str, mime_type: str) -> None:
    ext = os.path.splitext(filename.lower())[1]
    # Allow .enc extension for client-side encrypted payloads
    if ext not in settings.ALLOWED_EXTENSIONS:
        raise PrivPrintException(
            status_code=status.HTTP_400_BAD_REQUEST,
            code=ErrorCode.VALIDATION_ERROR,
            message=f"Disallowed file extension '{ext}'. Allowed: {', '.join(settings.ALLOWED_EXTENSIONS)}"
        )


@router.post(
    "/init-upload",
    response_model=InitUploadResponse,
    status_code=status.HTTP_201_CREATED,
    dependencies=[Depends(RateLimiter("upload_init", settings.RATE_LIMIT_UPLOAD_INIT_MAX, settings.RATE_LIMIT_UPLOAD_INIT_WINDOW_SECONDS))]
)
async def init_upload(
    payload: InitUploadRequest,
    db: AsyncSession = Depends(get_db_session),
    principal: AuthPrincipal = Depends(get_current_user),
    storage: StorageService = Depends(get_storage_service)
) -> InitUploadResponse:
    # 1. Validate Authenticated User
    if not principal.user_id:
        raise PrivPrintException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            code=ErrorCode.AUTH_INVALID,
            message="Authenticated user required to upload documents"
        )

    # 2. Validate Session Ownership & Freshness
    session_repo = SessionRepository(db)
    session = await session_repo.get_by_id(payload.session_id)
    if not session:
        raise PrivPrintException(
            status_code=status.HTTP_404_NOT_FOUND,
            code=ErrorCode.NOT_FOUND,
            message=f"Session {payload.session_id} not found"
        )

    if session.user_id and session.user_id != principal.user_id:
        raise PrivPrintException(
            status_code=status.HTTP_403_FORBIDDEN,
            code=ErrorCode.FORBIDDEN,
            message="Session ownership mismatch: you can only upload to your own session"
        )

    # Check session expiration
    sess_exp = session.expires_at
    if sess_exp.tzinfo is None:
        sess_exp = sess_exp.replace(tzinfo=timezone.utc)
    if sess_exp < datetime.now(timezone.utc):
        raise PrivPrintException(
            status_code=status.HTTP_400_BAD_REQUEST,
            code=ErrorCode.VALIDATION_ERROR,
            message="Session has expired. Please rescan shop QR code."
        )

    # 3. Validate File Size Limits (Max 25MB)
    if payload.file_size_bytes <= 0 or payload.file_size_bytes > settings.MAX_FILE_SIZE_BYTES:
        raise PrivPrintException(
            status_code=status.HTTP_400_BAD_REQUEST,
            code=ErrorCode.VALIDATION_ERROR,
            message=f"Invalid file size. Must be > 0 and <= {settings.MAX_FILE_SIZE_BYTES} bytes (25MB limit)"
        )

    # 4. Validate File Type
    validate_file_type(payload.filename, payload.mime_type)

    # 5. Validate Checksum
    if not is_valid_sha256(payload.sha256_hash):
        raise PrivPrintException(
            status_code=status.HTTP_400_BAD_REQUEST,
            code=ErrorCode.VALIDATION_ERROR,
            message="Invalid SHA-256 checksum format"
        )

    if not payload.wrapped_keys and settings.ENVIRONMENT.value != "development":
        raise PrivPrintException(
            status_code=status.HTTP_400_BAD_REQUEST,
            code=ErrorCode.VALIDATION_ERROR,
            message="No registered Windows station encryption keys are available for this shop",
        )

    if payload.wrapped_keys:
        active_device_result = await db.execute(
            select(Device).where(
                Device.shop_id == session.shop_id,
                Device.is_active.is_(True),
                Device.encryption_public_key.isnot(None),
            )
        )
        active_devices = {device.id: device for device in active_device_result.scalars().all()}
        if set(payload.wrapped_keys) != set(active_devices):
            raise PrivPrintException(
                status_code=status.HTTP_400_BAD_REQUEST,
                code=ErrorCode.VALIDATION_ERROR,
                message="Document key must be wrapped for every active station in this shop",
            )
        for wrapped_key in payload.wrapped_keys.values():
            try:
                decoded_key = base64.b64decode(wrapped_key, validate=True)
            except (ValueError, TypeError) as exc:
                raise PrivPrintException(
                    status_code=status.HTTP_400_BAD_REQUEST,
                    code=ErrorCode.VALIDATION_ERROR,
                    message="Invalid encrypted document key",
                ) from exc
            if not decoded_key or len(decoded_key) > 1024:
                raise PrivPrintException(
                    status_code=status.HTTP_400_BAD_REQUEST,
                    code=ErrorCode.VALIDATION_ERROR,
                    message="Invalid encrypted document key",
                )

    upload_id = f"upl_{uuid.uuid4().hex}"
    doc_id = f"DOC-{uuid.uuid4().hex[:8].upper()}"

    # Private S3 object key (no public URL)
    storage_path = f"ciphertext/{principal.user_id}/{payload.session_id}/{doc_id}.enc"

    # Generate short-lived presigned upload URL
    presigned_url = storage.generate_presigned_upload_url(
        storage_path,
        expires_in=settings.PRESIGNED_URL_EXPIRE_SECONDS
    )

    # Persist pending upload state into PostgreSQL
    pending = PendingUpload(
        id=upload_id,
        document_id=doc_id,
        user_id=principal.user_id,
        session_id=session.id,
        filename=payload.filename,
        storage_path=storage_path,
        file_size_bytes=payload.file_size_bytes,
        mime_type=payload.mime_type,
        sha256_hash=payload.sha256_hash.lower(),
        iv_hex=payload.iv_hex,
        key_fingerprint=payload.key_fingerprint,
        wrapped_keys=payload.wrapped_keys,
        copies_authorized=payload.copies_authorized,
        expires_at=session.expires_at,
        status="PENDING"
    )
    db.add(pending)
    await db.commit()

    return InitUploadResponse(
        upload_id=upload_id,
        document_id=doc_id,
        storage_path=storage_path,
        presigned_upload_url=presigned_url,
        expires_in_seconds=settings.PRESIGNED_URL_EXPIRE_SECONDS
    )


@router.post(
    "/{upload_id}/chunk",
    status_code=status.HTTP_200_OK,
)
async def upload_ciphertext_chunk(
    upload_id: str,
    request: Request,
    db: AsyncSession = Depends(get_db_session),
    principal: AuthPrincipal = Depends(get_current_user),
    storage: StorageService = Depends(get_storage_service),
) -> dict:
    """
    Receives the raw AES-256-GCM ciphertext body produced by the client phone.
    The payload is written straight to private object storage - never parsed,
    never logged, never persisted as plaintext.
    """
    stmt = select(PendingUpload).where(PendingUpload.id == upload_id)
    result = await db.execute(stmt)
    pending = result.scalar_one_or_none()

    if not pending:
        raise PrivPrintException(
            status_code=status.HTTP_404_NOT_FOUND,
            code=ErrorCode.NOT_FOUND,
            message=f"Upload session {upload_id} not found"
        )

    if pending.status != "PENDING":
        raise PrivPrintException(
            status_code=status.HTTP_400_BAD_REQUEST,
            code=ErrorCode.VALIDATION_ERROR,
            message=f"Upload session {upload_id} already finalized"
        )

    if pending.user_id != principal.user_id:
        raise PrivPrintException(
            status_code=status.HTTP_403_FORBIDDEN,
            code=ErrorCode.FORBIDDEN,
            message="User does not own this upload"
        )

    ciphertext = await request.body()
    if not ciphertext:
        raise PrivPrintException(
            status_code=status.HTTP_400_BAD_REQUEST,
            code=ErrorCode.VALIDATION_ERROR,
            message="Empty ciphertext payload"
        )

    if len(ciphertext) > settings.MAX_FILE_SIZE_BYTES:
        raise PrivPrintException(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            code=ErrorCode.VALIDATION_ERROR,
            message="Ciphertext exceeds the 25MB size limit"
        )

    storage.put_object_data(
        pending.storage_path,
        ciphertext,
        content_type="application/octet-stream"
    )

    return {
        "uploadId": pending.id,
        "documentId": pending.document_id,
        "receivedBytes": len(ciphertext),
        "status": "RECEIVED"
    }


@router.post("/{upload_id}/complete-upload", response_model=DocumentResponse)
async def complete_upload(
    upload_id: str,
    payload: CompleteUploadRequest,
    db: AsyncSession = Depends(get_db_session),
    principal: AuthPrincipal = Depends(get_current_user),
    storage: StorageService = Depends(get_storage_service)
) -> DocumentResponse:
    # 1. Retrieve Pending Upload from PostgreSQL
    stmt = select(PendingUpload).where(PendingUpload.id == upload_id)
    result = await db.execute(stmt)
    pending = result.scalar_one_or_none()

    if not pending:
        raise PrivPrintException(
            status_code=status.HTTP_404_NOT_FOUND,
            code=ErrorCode.NOT_FOUND,
            message=f"Upload session {upload_id} not found"
        )

    if pending.status != "PENDING":
        raise PrivPrintException(
            status_code=status.HTTP_400_BAD_REQUEST,
            code=ErrorCode.VALIDATION_ERROR,
            message=f"Upload session {upload_id} already finalized"
        )

    # 2. Validate Ownership
    if pending.user_id != principal.user_id:
        raise PrivPrintException(
            status_code=status.HTTP_403_FORBIDDEN,
            code=ErrorCode.FORBIDDEN,
            message="User does not own this upload"
        )

    # 3. Checksum & Size Validation
    if payload.sha256_hash and payload.sha256_hash.lower() != pending.sha256_hash:
        raise PrivPrintException(
            status_code=status.HTTP_400_BAD_REQUEST,
            code=ErrorCode.VALIDATION_ERROR,
            message="SHA-256 checksum mismatch"
        )

    if payload.file_size_bytes and payload.file_size_bytes != pending.file_size_bytes:
        raise PrivPrintException(
            status_code=status.HTTP_400_BAD_REQUEST,
            code=ErrorCode.VALIDATION_ERROR,
            message="File size mismatch"
        )

    # 4. Storage Validation (Ensure object was received in private S3 bucket)
    if not storage.object_exists(pending.storage_path):
        if settings.ENVIRONMENT.value == "development":
            storage.put_object_data(
                pending.storage_path,
                b"[PRIVPRINT-ENCRYPTED-CIPHERTEXT-STUB]",
                content_type=pending.mime_type,
            )
        else:
            raise PrivPrintException(
                status_code=status.HTTP_400_BAD_REQUEST,
                code=ErrorCode.UPLOAD_FAILED,
                message="Ciphertext upload is missing; retry the upload before creating a print job",
            )
    elif settings.ENVIRONMENT.value != "development":
        stored_ciphertext = storage.get_object_data(pending.storage_path)
        if (
            len(stored_ciphertext) != pending.file_size_bytes
            or hashlib.sha256(stored_ciphertext).hexdigest() != pending.sha256_hash
        ):
            raise PrivPrintException(
                status_code=status.HTTP_400_BAD_REQUEST,
                code=ErrorCode.UPLOAD_FAILED,
                message="Uploaded ciphertext failed size or checksum validation",
            )

    # 5. Persist Document Metadata in PostgreSQL (Document ciphertext is in S3, NEVER in DB)
    doc_repo = DocumentRepository(db)
    doc = await doc_repo.create(
        id=pending.document_id,
        user_id=pending.user_id,
        session_id=pending.session_id,
        filename=pending.filename,
        storage_path=pending.storage_path,
        file_size_bytes=pending.file_size_bytes,
        mime_type=pending.mime_type,
        sha256_hash=pending.sha256_hash,
        encryption_algorithm="AES-256-GCM",
        iv_hex=pending.iv_hex,
        key_fingerprint=pending.key_fingerprint,
        wrapped_keys=pending.wrapped_keys,
        copies_authorized=pending.copies_authorized,
        expires_at=pending.expires_at
    )

    pending.status = "COMPLETED"
    await db.commit()

    return DocumentResponse.model_validate(doc)


@router.get("/{document_id}", response_model=DocumentResponse)
async def get_document(
    document_id: str,
    db: AsyncSession = Depends(get_db_session),
    principal: AuthPrincipal = Depends(get_current_user),
    storage: StorageService = Depends(get_storage_service)
) -> DocumentResponse:
    doc_repo = DocumentRepository(db)
    doc = await doc_repo.get_by_id(document_id)
    if not doc:
        raise PrivPrintException(
            status_code=status.HTTP_404_NOT_FOUND,
            code=ErrorCode.NOT_FOUND,
            message=f"Document {document_id} not found"
        )

    # Ownership & Authorization Validation
    is_owner = (doc.user_id == principal.user_id)
    is_admin = (principal.role == UserRole.ADMIN)

    # Check if Operator of shop processing an active job for this document
    is_authorized_operator = False
    if principal.role == UserRole.SHOP_OPERATOR and principal.shop_id:
        stmt = (
            select(PrintJob)
            .where(
                PrintJob.document_id == doc.id,
                PrintJob.shop_id == principal.shop_id
            )
        )
        res = await db.execute(stmt)
        if res.scalars().first():
            is_authorized_operator = True

    if not (is_owner or is_admin or is_authorized_operator):
        raise PrivPrintException(
            status_code=status.HTTP_403_FORBIDDEN,
            code=ErrorCode.FORBIDDEN,
            message="Access denied: you do not have permission to view or download this document"
        )

    # Generate short-lived presigned download authorization URL (Never public URL)
    download_url = storage.generate_presigned_download_url(
        doc.storage_path,
        expires_in=settings.PRESIGNED_URL_EXPIRE_SECONDS
    )

    resp = DocumentResponse.model_validate(doc)
    resp.download_url = download_url
    resp.download_expires_in = settings.PRESIGNED_URL_EXPIRE_SECONDS
    return resp


@router.get("/{document_id}/print-content")
async def get_print_content(
    document_id: str,
    job_id: str,
    db: AsyncSession = Depends(get_db_session),
    principal: AuthPrincipal = Depends(get_current_user),
    storage: StorageService = Depends(get_storage_service),
) -> Response:
    if principal.role != UserRole.PRINT_DEVICE or not principal.user_id:
        raise PrivPrintException(
            status_code=status.HTTP_403_FORBIDDEN,
            code=ErrorCode.FORBIDDEN,
            message="Only an authenticated print station can retrieve print content",
        )

    job_result = await db.execute(
        select(PrintJob).where(
            PrintJob.id == job_id,
            PrintJob.document_id == document_id,
            PrintJob.shop_id == principal.shop_id,
            PrintJob.status.in_([PrintJobStatus.AUTHORIZED.value, PrintJobStatus.PRINTING.value]),
        )
    )
    job = job_result.scalar_one_or_none()
    document = await DocumentRepository(db).get_by_id(document_id)
    if (
        not job
        or not document
        or document.session_id != job.session_id
        or document.user_id != job.user_id
    ):
        raise PrivPrintException(
            status_code=status.HTTP_404_NOT_FOUND,
            code=ErrorCode.NOT_FOUND,
            message="Authorized print content not found",
        )

    expires_at = job.expires_at
    if expires_at.tzinfo is None:
        expires_at = expires_at.replace(tzinfo=timezone.utc)
    if expires_at < datetime.now(timezone.utc):
        raise PrivPrintException(
            status_code=status.HTTP_410_GONE,
            code=ErrorCode.JOB_EXPIRED,
            message="Authorized print job has expired",
        )

    device = await db.get(Device, principal.user_id)
    wrapped_key = (document.wrapped_keys or {}).get(principal.user_id)
    if not device or not device.is_active or device.shop_id != job.shop_id or not wrapped_key:
        raise PrivPrintException(
            status_code=status.HTTP_403_FORBIDDEN,
            code=ErrorCode.FORBIDDEN,
            message="This station has no wrapped key for the requested document",
        )

    ciphertext = storage.get_object_data(document.storage_path)

    if (
        len(ciphertext) != document.file_size_bytes
        or hashlib.sha256(ciphertext).hexdigest() != document.sha256_hash.lower()
    ):
        raise PrivPrintException(
            status_code=status.HTTP_400_BAD_REQUEST,
            code=ErrorCode.VALIDATION_ERROR,
            message="Stored ciphertext failed integrity validation",
        )

    return Response(
        content=ciphertext,
        media_type="application/octet-stream",
        headers={
            "X-PrivPrint-Wrapped-Key": wrapped_key,
            "X-PrivPrint-IV": document.iv_hex,
            "X-PrivPrint-SHA256": document.sha256_hash,
            "X-PrivPrint-Filename": quote(document.filename, safe=""),
        },
    )
