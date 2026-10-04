import uuid
from datetime import datetime, timezone
from typing import Optional
from fastapi import APIRouter, Depends, Header, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.exceptions import PrivPrintException, ErrorCode
from app.models.base import get_db_session
from app.models.enums import UserRole, PrintJobStatus
from app.models.entities import PrintJob
from app.repositories.print_job_repo import PrintJobRepository
from app.repositories.session_repo import SessionRepository
from app.repositories.document_repo import DocumentRepository
from app.repositories.shop_repo import ShopRepository
from app.schemas.print_job import (
    JobCreateRequest,
    JobAuthorizeRequest,
    JobCancelRequest,
    JobResponse
)
from app.api.deps import get_current_user, AuthPrincipal
from app.api.deps_rate_limit import RateLimiter
from app.services.redis_service import get_redis_service
from app.core.config import settings

router = APIRouter()


@router.post(
    "",
    response_model=JobResponse,
    status_code=status.HTTP_201_CREATED,
    dependencies=[Depends(RateLimiter("job_create", settings.RATE_LIMIT_JOB_CREATE_MAX, settings.RATE_LIMIT_JOB_CREATE_WINDOW_SECONDS))]
)
async def create_job(
    payload: JobCreateRequest,
    idempotency_key_header: Optional[str] = Header(None, alias="Idempotency-Key"),
    db: AsyncSession = Depends(get_db_session),
    principal: AuthPrincipal = Depends(get_current_user)
) -> JobResponse:
    job_repo = PrintJobRepository(db)
    session_repo = SessionRepository(db)
    doc_repo = DocumentRepository(db)
    shop_repo = ShopRepository(db)

    # 1. Resolve Idempotency Key
    idem_key = idempotency_key_header or payload.idempotency_key
    if idem_key:
        existing_job = await job_repo.get_by_idempotency_key(principal.user_id, idem_key)
        if existing_job:
            return JobResponse.model_validate(existing_job)

    # 2. Validate Session Ownership & State
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
            message="Session ownership mismatch: you can only create jobs for your own session"
        )

    # Check expiration
    now_utc = datetime.now(timezone.utc)
    sess_exp = session.expires_at if session.expires_at.tzinfo else session.expires_at.replace(tzinfo=timezone.utc)
    if sess_exp < now_utc:
        raise PrivPrintException(
            status_code=status.HTTP_400_BAD_REQUEST,
            code=ErrorCode.JOB_EXPIRED,
            message="Session has expired"
        )

    # 3. Validate Shop Matching
    if payload.shop_id != session.shop_id:
        raise PrivPrintException(
            status_code=status.HTTP_400_BAD_REQUEST,
            code=ErrorCode.VALIDATION_ERROR,
            message=f"Shop mismatch: session is bound to shop {session.shop_id}, not {payload.shop_id}"
        )
    shop = await shop_repo.get_by_id(payload.shop_id)
    if not shop or shop.status != "ACTIVE":
        raise PrivPrintException(
            status_code=status.HTTP_400_BAD_REQUEST,
            code=ErrorCode.VALIDATION_ERROR,
            message="Target shop is not active or verified"
        )

    # 4. Validate Document Ownership & Copies Limit
    doc = await doc_repo.get_by_id(payload.document_id)
    if not doc:
        raise PrivPrintException(
            status_code=status.HTTP_404_NOT_FOUND,
            code=ErrorCode.NOT_FOUND,
            message=f"Document {payload.document_id} not found"
        )
    if doc.user_id != principal.user_id:
        raise PrivPrintException(
            status_code=status.HTTP_403_FORBIDDEN,
            code=ErrorCode.FORBIDDEN,
            message="Document ownership mismatch"
        )

    # Check authorized copies
    if payload.requested_copies > doc.copies_authorized:
        raise PrivPrintException(
            status_code=status.HTTP_400_BAD_REQUEST,
            code=ErrorCode.COPY_LIMIT_REACHED,
            message=f"Requested copies ({payload.requested_copies}) exceeds authorized document copies ({doc.copies_authorized})"
        )

    # 5. Create Authoritative Print Job (Starts in CREATED state)
    job_id = f"PRV-{uuid.uuid4().hex[:8].upper()}"
    job = await job_repo.create(
        id=job_id,
        user_id=principal.user_id,
        shop_id=payload.shop_id,
        session_id=session.id,
        document_id=doc.id,
        printer_id=payload.printer_id,
        page_count=payload.page_count,
        requested_copies=payload.requested_copies,
        completed_copies=0,
        status=PrintJobStatus.CREATED.value,
        idempotency_key=idem_key,
        color_mode=payload.color_mode,
        paper_size=payload.paper_size,
        orientation=payload.orientation,
        duplex_mode=payload.duplex_mode,
        expires_at=session.expires_at
    )
    await db.commit()

    # Redis Pub/Sub: broadcast job created event across channels
    redis_service = get_redis_service()
    event_payload = {
        "job_id": job.id,
        "shop_id": job.shop_id,
        "user_id": job.user_id,
        "status": job.status,
        "requested_copies": job.requested_copies
    }
    await redis_service.publish_job_event(job.id, "JOB_CREATED", event_payload)
    await redis_service.publish_user_event(job.user_id, "JOB_CREATED", event_payload)
    await redis_service.publish_shop_event(job.shop_id, "JOB_CREATED", event_payload)
    if job.printer_id:
        await redis_service.publish_device_event(job.printer_id, "JOB_CREATED", event_payload)

    return JobResponse.model_validate(job)


@router.get("/{job_id}", response_model=JobResponse)
async def get_job(
    job_id: str,
    db: AsyncSession = Depends(get_db_session),
    principal: AuthPrincipal = Depends(get_current_user)
) -> JobResponse:
    job_repo = PrintJobRepository(db)
    job = await job_repo.get_by_id(job_id)
    if not job:
        raise PrivPrintException(
            status_code=status.HTTP_404_NOT_FOUND,
            code=ErrorCode.NOT_FOUND,
            message=f"Print job {job_id} not found"
        )

    # Ownership & Isolation Check
    if principal.role == UserRole.USER:
        if job.user_id != principal.user_id:
            raise PrivPrintException(
                status_code=status.HTTP_403_FORBIDDEN,
                code=ErrorCode.FORBIDDEN,
                message="Access denied: you do not own this print job"
            )
    elif principal.role in [UserRole.SHOP_OPERATOR, UserRole.PRINT_DEVICE]:
        if principal.shop_id and job.shop_id != principal.shop_id:
            raise PrivPrintException(
                status_code=status.HTTP_403_FORBIDDEN,
                code=ErrorCode.FORBIDDEN,
                message="Access denied: job belongs to another shop"
            )

    # Check for expiration transition
    now_utc = datetime.now(timezone.utc)
    job_exp = job.expires_at if job.expires_at.tzinfo else job.expires_at.replace(tzinfo=timezone.utc)
    if job_exp < now_utc and job.status in [PrintJobStatus.CREATED.value, PrintJobStatus.QUEUED.value, PrintJobStatus.AUTHORIZED.value]:
        job.status = PrintJobStatus.EXPIRED.value
        await db.commit()

    return JobResponse.model_validate(job)


@router.post("/{job_id}/authorize", response_model=JobResponse)
async def authorize_job(
    job_id: str,
    payload: Optional[JobAuthorizeRequest] = None,
    idempotency_key_header: Optional[str] = Header(None, alias="Idempotency-Key"),
    db: AsyncSession = Depends(get_db_session),
    principal: AuthPrincipal = Depends(get_current_user)
) -> JobResponse:
    job_repo = PrintJobRepository(db)
    job = await job_repo.get_by_id(job_id)
    if not job:
        raise PrivPrintException(
            status_code=status.HTTP_404_NOT_FOUND,
            code=ErrorCode.NOT_FOUND,
            message=f"Print job {job_id} not found"
        )

    # User / Operator Authorization
    if principal.role == UserRole.USER:
        if job.user_id != principal.user_id:
            raise PrivPrintException(
                status_code=status.HTTP_403_FORBIDDEN,
                code=ErrorCode.FORBIDDEN,
                message="Access denied: cannot authorize another user's job"
            )
    elif principal.role in [UserRole.SHOP_OPERATOR, UserRole.PRINT_DEVICE]:
        if principal.shop_id and job.shop_id != principal.shop_id:
            raise PrivPrintException(
                status_code=status.HTTP_403_FORBIDDEN,
                code=ErrorCode.FORBIDDEN,
                message="Access denied: job belongs to another shop"
            )

    # Expiration check
    now_utc = datetime.now(timezone.utc)
    job_exp = job.expires_at if job.expires_at.tzinfo else job.expires_at.replace(tzinfo=timezone.utc)
    if job_exp < now_utc:
        job.status = PrintJobStatus.EXPIRED.value
        await db.commit()
        raise PrivPrintException(
            status_code=status.HTTP_400_BAD_REQUEST,
            code=ErrorCode.JOB_EXPIRED,
            message="Print job has expired and cannot be authorized"
        )

    # Idempotent return if already AUTHORIZED
    if job.status == PrintJobStatus.AUTHORIZED.value:
        return JobResponse.model_validate(job)

    # State Machine Validation
    job_repo.validate_transition(job.status, PrintJobStatus.AUTHORIZED.value)

    if payload and payload.printer_id:
        job.printer_id = payload.printer_id

    job.status = PrintJobStatus.AUTHORIZED.value
    await db.commit()

    redis_service = get_redis_service()
    auth_payload = {"job_id": job.id, "status": job.status, "printer_id": job.printer_id}
    await redis_service.publish_job_event(job.id, "JOB_AUTHORIZED", auth_payload)
    await redis_service.publish_user_event(job.user_id, "JOB_AUTHORIZED", auth_payload)
    await redis_service.publish_shop_event(job.shop_id, "JOB_AUTHORIZED", auth_payload)
    if job.printer_id:
        await redis_service.publish_device_event(job.printer_id, "JOB_AUTHORIZED", auth_payload)

    return JobResponse.model_validate(job)


@router.post("/{job_id}/cancel", response_model=JobResponse)
async def cancel_job(
    job_id: str,
    payload: Optional[JobCancelRequest] = None,
    db: AsyncSession = Depends(get_db_session),
    principal: AuthPrincipal = Depends(get_current_user)
) -> JobResponse:
    job_repo = PrintJobRepository(db)
    job = await job_repo.get_by_id(job_id)
    if not job:
        raise PrivPrintException(
            status_code=status.HTTP_404_NOT_FOUND,
            code=ErrorCode.NOT_FOUND,
            message=f"Print job {job_id} not found"
        )

    # Authorization check
    if principal.role == UserRole.USER:
        if job.user_id != principal.user_id:
            raise PrivPrintException(
                status_code=status.HTTP_403_FORBIDDEN,
                code=ErrorCode.FORBIDDEN,
                message="Access denied: cannot cancel another user's job"
            )
    elif principal.role in [UserRole.SHOP_OPERATOR, UserRole.PRINT_DEVICE]:
        if principal.shop_id and job.shop_id != principal.shop_id:
            raise PrivPrintException(
                status_code=status.HTTP_403_FORBIDDEN,
                code=ErrorCode.FORBIDDEN,
                message="Access denied: job belongs to another shop"
            )

    # Idempotent return if already CANCELLED
    if job.status == PrintJobStatus.CANCELLED.value:
        return JobResponse.model_validate(job)

    # Cannot cancel if already COMPLETED
    if job.status == PrintJobStatus.COMPLETED.value:
        raise PrivPrintException(
            status_code=status.HTTP_400_BAD_REQUEST,
            code=ErrorCode.INVALID_STATE_TRANSITION,
            message="Cannot cancel an already completed print job"
        )

    # Cannot cancel if EXPIRED
    if job.status == PrintJobStatus.EXPIRED.value:
        raise PrivPrintException(
            status_code=status.HTTP_400_BAD_REQUEST,
            code=ErrorCode.INVALID_STATE_TRANSITION,
            message="Cannot cancel an expired print job"
        )

    # Validate transition
    job_repo.validate_transition(job.status, PrintJobStatus.CANCELLED.value)

    job.status = PrintJobStatus.CANCELLED.value
    if payload and payload.reason:
        job.failure_reason = payload.reason
    await db.commit()

    redis_service = get_redis_service()
    cancel_payload = {"job_id": job.id, "status": job.status, "reason": job.failure_reason}
    await redis_service.publish_job_event(job.id, "JOB_CANCELLED", cancel_payload)
    await redis_service.publish_user_event(job.user_id, "JOB_CANCELLED", cancel_payload)
    await redis_service.publish_shop_event(job.shop_id, "JOB_CANCELLED", cancel_payload)

    return JobResponse.model_validate(job)
