from typing import List, Optional
from datetime import datetime, timezone
from sqlalchemy import select, update, and_
from sqlalchemy.ext.asyncio import AsyncSession
from app.models.entities import PrintJob
from app.models.enums import PrintJobStatus
from app.repositories.base import BaseRepository
from app.core.exceptions import PrivPrintException, ErrorCode


class CopyLimitExceededException(PrivPrintException):
    def __init__(self, requested: int, current: int, attempted_increment: int):
        super().__init__(
            status_code=403,
            code=ErrorCode.COPY_LIMIT_REACHED,
            message=f"Copy limit exceeded: Authorized {requested}, completed {current}, attempted to add {attempted_increment}",
            details={"requested": requested, "current": current, "attempted": attempted_increment}
        )


VALID_TRANSITIONS = {
    PrintJobStatus.CREATED.value: [
        PrintJobStatus.QUEUED.value,
        PrintJobStatus.AUTHORIZED.value,
        PrintJobStatus.CANCELLED.value,
        PrintJobStatus.EXPIRED.value
    ],
    PrintJobStatus.QUEUED.value: [
        PrintJobStatus.AUTHORIZED.value,
        PrintJobStatus.CANCELLED.value,
        PrintJobStatus.EXPIRED.value
    ],
    PrintJobStatus.AUTHORIZED.value: [
        PrintJobStatus.PRINTING.value,
        PrintJobStatus.CANCELLED.value,
        PrintJobStatus.EXPIRED.value
    ],
    PrintJobStatus.PRINTING.value: [
        PrintJobStatus.COMPLETED.value,
        PrintJobStatus.FAILED.value
    ],
    PrintJobStatus.COMPLETED.value: [],
    PrintJobStatus.FAILED.value: [],
    PrintJobStatus.CANCELLED.value: [],
    PrintJobStatus.EXPIRED.value: [],
}


class PrintJobRepository(BaseRepository[PrintJob]):
    def __init__(self, session: AsyncSession):
        super().__init__(PrintJob, session)

    async def get_by_idempotency_key(self, user_id: str, idempotency_key: str) -> Optional[PrintJob]:
        stmt = select(PrintJob).where(
            PrintJob.user_id == user_id,
            PrintJob.idempotency_key == idempotency_key
        )
        res = await self.session.execute(stmt)
        return res.scalars().first()

    def validate_transition(self, current_status: str, target_status: str) -> None:
        allowed = VALID_TRANSITIONS.get(current_status, [])
        if target_status not in allowed:
            raise PrivPrintException(
                status_code=400,
                code=ErrorCode.INVALID_STATE_TRANSITION,
                message=f"Invalid state transition: cannot transition from {current_status} to {target_status}"
            )

    async def get_by_id_and_user(self, job_id: str, user_id: str) -> Optional[PrintJob]:
        """
        User isolation: Customer can only view their own jobs.
        """
        result = await self.session.execute(
            select(PrintJob).where(PrintJob.id == job_id, PrintJob.user_id == user_id)
        )
        return result.scalars().first()

    async def get_by_id_and_shop(self, job_id: str, shop_id: str) -> Optional[PrintJob]:
        """
        Shop isolation: Shop/operator can only view jobs queued for their shop.
        """
        result = await self.session.execute(
            select(PrintJob).where(PrintJob.id == job_id, PrintJob.shop_id == shop_id)
        )
        return result.scalars().first()

    async def list_by_shop_and_status(self, shop_id: str, status: Optional[str] = None) -> List[PrintJob]:
        query = select(PrintJob).where(PrintJob.shop_id == shop_id)
        if status:
            query = query.where(PrintJob.status == status)
        result = await self.session.execute(query)
        return list(result.scalars().all())

    async def increment_copies_atomic(self, job_id: str, delta: int = 1) -> PrintJob:
        """
        ATOMIC COPY-COUNT ENFORCEMENT:
        Executes a single conditional atomic SQL UPDATE statement:
        UPDATE print_jobs SET completed_copies = completed_copies + delta
        WHERE id = job_id AND completed_copies + delta <= requested_copies

        Guarantees that race conditions and concurrent requests (e.g. 5 + 5)
        cannot breach the authorized copy ceiling.
        """
        stmt = (
            update(PrintJob)
            .where(
                PrintJob.id == job_id,
                PrintJob.completed_copies + delta <= PrintJob.requested_copies
            )
            .values(
                completed_copies=PrintJob.completed_copies + delta
            )
        )
        res = await self.session.execute(stmt)

        if res.rowcount == 0:
            # Check if job exists to report appropriate exception
            job = await self.get_by_id(job_id)
            if not job:
                raise PrivPrintException(
                    status_code=404,
                    code=ErrorCode.NOT_FOUND,
                    message=f"Print job {job_id} not found"
                )
            raise CopyLimitExceededException(
                requested=job.requested_copies,
                current=job.completed_copies,
                attempted_increment=delta
            )

        job = await self.get_by_id(job_id)
        if job.completed_copies == job.requested_copies:
            job.status = PrintJobStatus.COMPLETED.value
            job.completed_at = datetime.now(timezone.utc)
        else:
            job.status = PrintJobStatus.PRINTING.value

        # Atomically update Document copies_consumed
        if job.document_id:
            from app.models.entities import Document
            doc_stmt = (
                update(Document)
                .where(
                    and_(
                        Document.id == job.document_id,
                        Document.copies_consumed + delta <= Document.copies_authorized
                    )
                )
                .values(copies_consumed=Document.copies_consumed + delta)
            )
            await self.session.execute(doc_stmt)

        await self.session.flush()
        return job
