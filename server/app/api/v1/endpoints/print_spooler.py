from typing import List, Optional
from fastapi import APIRouter, Depends, Query, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.exceptions import PrivPrintException, ErrorCode
from app.models.base import get_db_session
from app.models.enums import UserRole, PrintJobStatus
from app.repositories.print_job_repo import PrintJobRepository
from app.schemas.print_job import JobResponse, JobProgressUpdateRequest
from app.api.deps import require_roles, AuthPrincipal
from app.services.redis_service import get_redis_service

router = APIRouter()


@router.get("/jobs", response_model=List[JobResponse])
async def get_print_queue(
    shop_id: Optional[str] = Query(None, alias="shopId"),
    status: Optional[str] = None,
    db: AsyncSession = Depends(get_db_session),
    principal: AuthPrincipal = Depends(require_roles([UserRole.SHOP_OPERATOR, UserRole.PRINT_DEVICE, UserRole.ADMIN]))
) -> List[JobResponse]:
    if principal.role == UserRole.ADMIN:
        target_shop = shop_id or principal.shop_id
        if not target_shop:
            raise PrivPrintException(
                status_code=400,
                code=ErrorCode.VALIDATION_ERROR,
                message="Shop ID required"
            )
    elif principal.role == UserRole.PRINT_DEVICE:
        if not principal.shop_id:
            raise PrivPrintException(
                status_code=403,
                code=ErrorCode.FORBIDDEN,
                message="Print device is not bound to a valid shop"
            )
        if shop_id and shop_id != principal.shop_id:
            raise PrivPrintException(
                status_code=403,
                code=ErrorCode.FORBIDDEN,
                message="Access denied to other shop queue"
            )
        target_shop = principal.shop_id
    elif principal.role == UserRole.SHOP_OPERATOR:
        target_shop = shop_id or principal.shop_id
        if not target_shop:
            raise PrivPrintException(
                status_code=400,
                code=ErrorCode.VALIDATION_ERROR,
                message="Shop ID required"
            )
        from app.repositories.shop_repo import ShopRepository
        shop_repo = ShopRepository(db)
        shop = await shop_repo.get_by_id(target_shop)
        if not shop or shop.owner_id != principal.user_id:
            raise PrivPrintException(
                status_code=403,
                code=ErrorCode.FORBIDDEN,
                message="Access denied: operator does not own this shop"
            )
    else:
        raise PrivPrintException(
            status_code=403,
            code=ErrorCode.FORBIDDEN,
            message="Access denied"
        )

    job_repo = PrintJobRepository(db)
    jobs = await job_repo.list_by_shop_and_status(target_shop, status=status)
    return [JobResponse.model_validate(j) for j in jobs]


@router.post("/jobs/{job_id}/start", response_model=JobResponse)
async def start_printing(
    job_id: str,
    db: AsyncSession = Depends(get_db_session),
    principal: AuthPrincipal = Depends(require_roles([UserRole.SHOP_OPERATOR, UserRole.PRINT_DEVICE, UserRole.ADMIN]))
) -> JobResponse:
    job_repo = PrintJobRepository(db)
    job = await job_repo.get_by_id(job_id)
    if not job:
        raise PrivPrintException(status_code=404, code=ErrorCode.NOT_FOUND, message="Job not found")

    if principal.role != UserRole.ADMIN:
        if not principal.shop_id or job.shop_id != principal.shop_id:
            raise PrivPrintException(status_code=403, code=ErrorCode.FORBIDDEN, message="Wrong shop: device unauthorized")
        if principal.role == UserRole.SHOP_OPERATOR:
            from app.repositories.shop_repo import ShopRepository
            shop = await ShopRepository(db).get_by_id(job.shop_id)
            if not shop or shop.owner_id != principal.user_id:
                raise PrivPrintException(status_code=403, code=ErrorCode.FORBIDDEN, message="Access denied: operator does not own this shop")

    job_repo.validate_transition(job.status, PrintJobStatus.PRINTING.value)
    job.status = PrintJobStatus.PRINTING.value
    await db.commit()

    redis_service = get_redis_service()
    event_payload = {
        "job_id": job.id,
        "batch_id": job.batch_id,
        "file_index": job.file_index,
        "total_files": job.total_files,
        "page_count": job.page_count,
        "pages_printed": job.pages_printed,
        "status": job.status
    }
    await redis_service.publish_job_event(job.id, "PRINT_STARTED", event_payload)
    await redis_service.publish_user_event(job.user_id, "PRINT_STARTED", event_payload)
    await redis_service.publish_shop_event(job.shop_id, "PRINT_STARTED", event_payload)

    return JobResponse.model_validate(job)


@router.post("/jobs/{job_id}/progress", response_model=JobResponse)
async def update_job_progress(
    job_id: str,
    payload: JobProgressUpdateRequest,
    db: AsyncSession = Depends(get_db_session),
    principal: AuthPrincipal = Depends(require_roles([UserRole.SHOP_OPERATOR, UserRole.PRINT_DEVICE, UserRole.ADMIN]))
) -> JobResponse:
    job_repo = PrintJobRepository(db)
    job = await job_repo.get_by_id(job_id)
    if not job:
        raise PrivPrintException(status_code=404, code=ErrorCode.NOT_FOUND, message="Job not found")

    if principal.role != UserRole.ADMIN:
        if not principal.shop_id or job.shop_id != principal.shop_id:
            raise PrivPrintException(status_code=403, code=ErrorCode.FORBIDDEN, message="Wrong shop: device unauthorized")
        if principal.role == UserRole.SHOP_OPERATOR:
            from app.repositories.shop_repo import ShopRepository
            shop = await ShopRepository(db).get_by_id(job.shop_id)
            if not shop or shop.owner_id != principal.user_id:
                raise PrivPrintException(status_code=403, code=ErrorCode.FORBIDDEN, message="Access denied: operator does not own this shop")

    updated_job = await job_repo.update_page_progress_atomic(job_id, payload.pages_printed)
    if payload.completed_copies is not None and payload.completed_copies > updated_job.completed_copies:
        delta = payload.completed_copies - updated_job.completed_copies
        updated_job = await job_repo.increment_copies_atomic(job_id, delta=delta)

    await db.commit()

    redis_service = get_redis_service()
    progress_payload = {
        "job_id": updated_job.id,
        "batch_id": updated_job.batch_id,
        "file_index": updated_job.file_index,
        "total_files": updated_job.total_files,
        "page_count": updated_job.page_count,
        "pages_printed": updated_job.pages_printed,
        "requested_copies": updated_job.requested_copies,
        "completed_copies": updated_job.completed_copies,
        "status": updated_job.status
    }
    await redis_service.publish_job_event(updated_job.id, "JOB_PROGRESS", progress_payload)
    await redis_service.publish_user_event(updated_job.user_id, "JOB_PROGRESS", progress_payload)
    await redis_service.publish_shop_event(updated_job.shop_id, "JOB_PROGRESS", progress_payload)

    return JobResponse.model_validate(updated_job)


@router.post("/jobs/{job_id}/increment-copy", response_model=JobResponse)
async def increment_copy(
    job_id: str,
    delta: int = Query(1, ge=1),
    db: AsyncSession = Depends(get_db_session),
    principal: AuthPrincipal = Depends(require_roles([UserRole.SHOP_OPERATOR, UserRole.PRINT_DEVICE, UserRole.ADMIN]))
) -> JobResponse:
    job_repo = PrintJobRepository(db)
    job = await job_repo.get_by_id(job_id)
    if not job:
        raise PrivPrintException(status_code=404, code=ErrorCode.NOT_FOUND, message="Job not found")

    if principal.role != UserRole.ADMIN:
        if not principal.shop_id or job.shop_id != principal.shop_id:
            raise PrivPrintException(status_code=403, code=ErrorCode.FORBIDDEN, message="Wrong shop: device unauthorized")
        if principal.role == UserRole.SHOP_OPERATOR:
            from app.repositories.shop_repo import ShopRepository
            shop = await ShopRepository(db).get_by_id(job.shop_id)
            if not shop or shop.owner_id != principal.user_id:
                raise PrivPrintException(status_code=403, code=ErrorCode.FORBIDDEN, message="Access denied: operator does not own this shop")

    updated_job = await job_repo.increment_copies_atomic(job_id, delta=delta)
    await db.commit()
    return JobResponse.model_validate(updated_job)


@router.post("/jobs/{job_id}/fail", response_model=JobResponse)
async def fail_job(
    job_id: str,
    reason: str = Query("Hardware printer error"),
    db: AsyncSession = Depends(get_db_session),
    principal: AuthPrincipal = Depends(require_roles([UserRole.SHOP_OPERATOR, UserRole.PRINT_DEVICE, UserRole.ADMIN]))
) -> JobResponse:
    job_repo = PrintJobRepository(db)
    job = await job_repo.get_by_id(job_id)
    if not job:
        raise PrivPrintException(status_code=404, code=ErrorCode.NOT_FOUND, message="Job not found")

    if principal.role != UserRole.ADMIN:
        if not principal.shop_id or job.shop_id != principal.shop_id:
            raise PrivPrintException(status_code=403, code=ErrorCode.FORBIDDEN, message="Wrong shop: device unauthorized")
        if principal.role == UserRole.SHOP_OPERATOR:
            from app.repositories.shop_repo import ShopRepository
            shop = await ShopRepository(db).get_by_id(job.shop_id)
            if not shop or shop.owner_id != principal.user_id:
                raise PrivPrintException(status_code=403, code=ErrorCode.FORBIDDEN, message="Access denied: operator does not own this shop")

    job_repo.validate_transition(job.status, PrintJobStatus.FAILED.value)
    job.status = PrintJobStatus.FAILED.value
    job.failure_reason = reason
    await db.commit()
    return JobResponse.model_validate(job)

