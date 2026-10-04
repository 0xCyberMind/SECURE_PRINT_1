from fastapi import APIRouter, Depends, Request, status
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select

from app.core.exceptions import PrivPrintException, ErrorCode
from app.models.base import get_db_session
from app.models.enums import UserRole
from app.models.entities import PrintJob, Document
from app.api.deps import get_current_user, require_roles, AuthPrincipal
from app.services.cleanup_service import DocumentCleanupService
from app.schemas.cleanup import CleanupResponse, CleanupStatusResponse, CleanupSweepResponse

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
    if principal.role == UserRole.USER:
        if job.user_id != principal.user_id:
            raise PrivPrintException(
                status_code=status.HTTP_403_FORBIDDEN,
                code=ErrorCode.FORBIDDEN,
                message="Cannot execute cleanup on another user's job"
            )
    elif principal.role in [UserRole.SHOP_OPERATOR, UserRole.PRINT_DEVICE]:
        if principal.shop_id and job.shop_id != principal.shop_id:
            raise PrivPrintException(
                status_code=status.HTTP_403_FORBIDDEN,
                code=ErrorCode.FORBIDDEN,
                message="Cannot execute cleanup for another shop's job"
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

    if principal.role == UserRole.USER and doc.user_id != principal.user_id:
        raise PrivPrintException(
            status_code=status.HTTP_403_FORBIDDEN,
            code=ErrorCode.FORBIDDEN,
            message="Cannot delete another user's document"
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

    if principal.role == UserRole.USER and doc.user_id != principal.user_id:
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
