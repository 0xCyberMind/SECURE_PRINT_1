from fastapi import APIRouter, Depends, Request, status
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select

from app.core.exceptions import PrivPrintException, ErrorCode
from app.models.base import get_db_session
from app.models.enums import UserRole
from app.models.entities import PrintJob, Document
from app.api.deps import get_current_user, require_roles, AuthPrincipal
from app.services.cleanup_service import DocumentCleanupService
from app.schemas.cleanup import (
    CleanupResponse,
    CleanupStatusResponse,
    CleanupSweepResponse,
    HistoryCleanupResponse,
    HistoryCleanupSweepResponse,
)

router = APIRouter()


@router.post("/execute/{job_id}", response_model=CleanupResponse)
async def execute_job_cleanup(
    job_id: str,
    request: Request,
    db: AsyncSession = Depends(get_db_session),
    principal: AuthPrincipal = Depends(get_current_user)
) -> CleanupResponse:
    """
    Executes document lifecycle cleanup for a completed/terminal print job.
    Evaluates retention requirements, securely deletes the encrypted storage object when allowed,
    verifies storage deletion, records an audit trail, and broadcasts state update.
    """
    job_query = select(PrintJob).where(PrintJob.id == job_id)
    job_res = await db.execute(job_query)
    job = job_res.scalar_one_or_none()

    if not job:
        raise PrivPrintException(
            status_code=status.HTTP_404_NOT_FOUND,
            code=ErrorCode.NOT_FOUND,
            message=f"Print job {job_id} not found"
        )

    # Authorization verification
    if principal.role != UserRole.ADMIN:
        if principal.role == UserRole.USER:
            if job.user_id != principal.user_id:
                raise PrivPrintException(
                    status_code=status.HTTP_403_FORBIDDEN,
                    code=ErrorCode.FORBIDDEN,
                    message="Cannot execute cleanup on another user's job"
                )
        elif principal.role in [UserRole.SHOP_OPERATOR, UserRole.PRINT_DEVICE]:
            if not principal.shop_id or job.shop_id != principal.shop_id:
                raise PrivPrintException(
                    status_code=status.HTTP_403_FORBIDDEN,
                    code=ErrorCode.FORBIDDEN,
                    message="Cannot execute cleanup for another shop's job"
                )
            if principal.role == UserRole.SHOP_OPERATOR:
                from app.repositories.shop_repo import ShopRepository
                shop = await ShopRepository(db).get_by_id(job.shop_id)
                if not shop or shop.owner_id != principal.user_id:
                    raise PrivPrintException(
                        status_code=status.HTTP_403_FORBIDDEN,
                        code=ErrorCode.FORBIDDEN,
                        message="Access denied: operator does not own this shop"
                    )

    client_ip = request.client.host if request.client else None
    cleanup_service = DocumentCleanupService(db)
    return await cleanup_service.cleanup_job_document(job_id=job_id, ip_address=client_ip)


@router.post("/document/{document_id}", response_model=CleanupResponse)
async def execute_document_cleanup(
    document_id: str,
    request: Request,
    db: AsyncSession = Depends(get_db_session),
    principal: AuthPrincipal = Depends(get_current_user)
) -> CleanupResponse:
    """
    Directly triggers cleanup evaluation and storage object deletion for a document.
    """
    doc_query = select(Document).where(Document.id == document_id)
    doc_res = await db.execute(doc_query)
    doc = doc_res.scalar_one_or_none()

    if not doc:
        raise PrivPrintException(
            status_code=status.HTTP_404_NOT_FOUND,
            code=ErrorCode.NOT_FOUND,
            message=f"Document {document_id} not found"
        )

    if principal.role != UserRole.ADMIN:
        if principal.role == UserRole.USER:
            if doc.user_id != principal.user_id:
                raise PrivPrintException(
                    status_code=status.HTTP_403_FORBIDDEN,
                    code=ErrorCode.FORBIDDEN,
                    message="Cannot delete another user's document"
                )
        else:
            raise PrivPrintException(
                status_code=status.HTTP_403_FORBIDDEN,
                code=ErrorCode.FORBIDDEN,
                message="Only document owner or admin can directly shred documents"
            )

    client_ip = request.client.host if request.client else None
    cleanup_service = DocumentCleanupService(db)
    return await cleanup_service.cleanup_document(
        document_id=document_id,
        force_shred=False,
        ip_address=client_ip
    )


@router.get("/status/{document_id}", response_model=CleanupStatusResponse)
async def get_document_cleanup_status(
    document_id: str,
    db: AsyncSession = Depends(get_db_session),
    principal: AuthPrincipal = Depends(get_current_user)
) -> CleanupStatusResponse:
    """
    Retrieves the current retention, storage verification, and shredding status of a document.
    """
    doc_query = select(Document).where(Document.id == document_id)
    doc_res = await db.execute(doc_query)
    doc = doc_res.scalar_one_or_none()

    if not doc:
        raise PrivPrintException(
            status_code=status.HTTP_404_NOT_FOUND,
            code=ErrorCode.NOT_FOUND,
            message=f"Document {document_id} not found"
        )

    if principal.role != UserRole.ADMIN:
        if principal.role == UserRole.USER:
            if doc.user_id != principal.user_id:
                raise PrivPrintException(
                    status_code=status.HTTP_403_FORBIDDEN,
                    code=ErrorCode.FORBIDDEN,
                    message="Access denied to document status"
                )
        elif principal.role in [UserRole.SHOP_OPERATOR, UserRole.PRINT_DEVICE]:
            if not principal.shop_id:
                raise PrivPrintException(
                    status_code=status.HTTP_403_FORBIDDEN,
                    code=ErrorCode.FORBIDDEN,
                    message="Access denied to document status"
                )
            job_stmt = select(PrintJob).where(PrintJob.document_id == document_id, PrintJob.shop_id == principal.shop_id)
            res = await db.execute(job_stmt)
            if not res.scalars().first():
                raise PrivPrintException(
                    status_code=status.HTTP_403_FORBIDDEN,
                    code=ErrorCode.FORBIDDEN,
                    message="Access denied to document status"
                )
            if principal.role == UserRole.SHOP_OPERATOR:
                from app.repositories.shop_repo import ShopRepository
                shop = await ShopRepository(db).get_by_id(principal.shop_id)
                if not shop or shop.owner_id != principal.user_id:
                    raise PrivPrintException(
                        status_code=status.HTTP_403_FORBIDDEN,
                        code=ErrorCode.FORBIDDEN,
                        message="Access denied to document status"
                    )

    cleanup_service = DocumentCleanupService(db)
    return await cleanup_service.get_document_status(document_id=document_id)


@router.post("/sweep", response_model=CleanupSweepResponse)
async def trigger_cleanup_sweep(
    db: AsyncSession = Depends(get_db_session),
    principal: AuthPrincipal = Depends(require_roles([UserRole.ADMIN, UserRole.SHOP_OPERATOR]))
) -> CleanupSweepResponse:
    """
    Triggers batch cleanup sweep of all pending and expired documents across the system.
    """
    cleanup_service = DocumentCleanupService(db)
    return await cleanup_service.run_cleanup_sweep()


@router.post("/history/sweep", response_model=HistoryCleanupSweepResponse)
async def trigger_history_cleanup_sweep(
    db: AsyncSession = Depends(get_db_session),
    principal: AuthPrincipal = Depends(require_roles([UserRole.ADMIN, UserRole.SHOP_OPERATOR]))
) -> HistoryCleanupSweepResponse:
    """
    Triggers batch cleanup sweep of expired completed print history records.
    Admin sweeps all shops; operator sweeps only their own shop.
    """
    cleanup_service = DocumentCleanupService(db)
    if principal.role == UserRole.SHOP_OPERATOR:
        if not principal.shop_id:
            raise PrivPrintException(status_code=400, code=ErrorCode.VALIDATION_ERROR, message="Operator shop required")
        from app.repositories.shop_repo import ShopRepository
        shop = await ShopRepository(db).get_by_id(principal.shop_id)
        if not shop or shop.owner_id != principal.user_id:
            raise PrivPrintException(status_code=403, code=ErrorCode.FORBIDDEN, message="Operator does not own shop")
        shop_res = await cleanup_service.cleanup_history_for_shop(principal.shop_id)
        return HistoryCleanupSweepResponse(
            total_scanned_jobs=shop_res.deleted_count + shop_res.retained_count,
            total_deleted_jobs=shop_res.deleted_count,
            total_retained_jobs=shop_res.retained_count,
            details_by_shop={principal.shop_id: shop_res}
        )
    return await cleanup_service.run_history_cleanup_sweep()


@router.post("/history/shop/{shop_id}", response_model=HistoryCleanupResponse)
async def trigger_shop_history_cleanup(
    shop_id: str,
    db: AsyncSession = Depends(get_db_session),
    principal: AuthPrincipal = Depends(require_roles([UserRole.ADMIN, UserRole.SHOP_OPERATOR, UserRole.PRINT_DEVICE]))
) -> HistoryCleanupResponse:
    """
    Triggers history cleanup for a specific shop enforcing strict tenant authorization.
    """
    if principal.role != UserRole.ADMIN:
        if principal.role == UserRole.SHOP_OPERATOR:
            from app.repositories.shop_repo import ShopRepository
            shop = await ShopRepository(db).get_by_id(shop_id)
            if not shop or shop.owner_id != principal.user_id:
                raise PrivPrintException(status_code=403, code=ErrorCode.FORBIDDEN, message="Operator does not own shop")
        elif principal.role == UserRole.PRINT_DEVICE:
            if principal.shop_id != shop_id:
                raise PrivPrintException(status_code=403, code=ErrorCode.FORBIDDEN, message="Device does not belong to shop")

    cleanup_service = DocumentCleanupService(db)
    return await cleanup_service.cleanup_history_for_shop(shop_id)
