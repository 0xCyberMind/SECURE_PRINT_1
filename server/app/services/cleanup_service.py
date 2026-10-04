import json
import logging
from datetime import datetime, timezone
from typing import Optional, Tuple, Dict, Any, List
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select, and_, or_

from app.models.entities import Document, PrintJob, Session, AuditLog
from app.models.enums import PrintJobStatus, CleanupState, AuditSeverity, SessionStatus
from app.services.storage import get_storage_service, StorageService
from app.services.redis_service import get_redis_service
from app.schemas.cleanup import CleanupResponse, CleanupStatusResponse, CleanupSweepResponse
from app.core.exceptions import PrivPrintException, ErrorCode

logger = logging.getLogger("privprint.cleanup")


class DocumentCleanupService:
    """
    Production Document Lifecycle & Cryptographic Storage Cleanup Service.
    Enforces zero-trust document retention policies, verified private storage object deletion,
    audit trail recording, and background sweep execution.
    """

    def __init__(self, db: AsyncSession, storage: Optional[StorageService] = None):
        self.db = db
        self.storage = storage or get_storage_service()
        self.redis = get_redis_service()

    async def evaluate_retention(self, doc: Document) -> Tuple[bool, str, int]:
        """
        Determines whether document retention is still required.
        Returns (retention_required, reason, active_job_count).
        """
        now_utc = datetime.now(timezone.utc)

        # 1. Check if document is already shredded / marked deleted
        if doc.is_deleted or doc.cleanup_state == CleanupState.SHREDDED.value:
            return False, "Document is already shredded", 0

        # 2. Check for active/non-terminal print jobs referencing this document
        non_terminal_statuses = [
            PrintJobStatus.CREATED.value,
            PrintJobStatus.QUEUED.value,
            PrintJobStatus.AUTHORIZED.value,
            PrintJobStatus.PRINTING.value
        ]
        job_query = select(PrintJob).where(
            and_(
                PrintJob.document_id == doc.id,
                PrintJob.status.in_(non_terminal_statuses)
            )
        )
        result = await self.db.execute(job_query)
        active_jobs = result.scalars().all()
        active_job_count = len(active_jobs)

        if active_job_count > 0:
            return True, f"Document has {active_job_count} active/in-progress print job(s)", active_job_count

        # 3. Check Document Expiration
        doc_exp = doc.expires_at if doc.expires_at.tzinfo else doc.expires_at.replace(tzinfo=timezone.utc)
        if doc_exp < now_utc:
            return False, "Document expiration time has passed", 0

        # 4. Check Session Expiration & Status
        session_query = select(Session).where(Session.id == doc.session_id)
        sess_res = await self.db.execute(session_query)
        session = sess_res.scalar_one_or_none()

        if session:
            sess_exp = session.expires_at if session.expires_at.tzinfo else session.expires_at.replace(tzinfo=timezone.utc)
            if session.status != SessionStatus.ACTIVE.value:
                return False, f"Parent session status is {session.status}", 0
            if sess_exp < now_utc:
                return False, "Parent session has expired", 0

        # 5. Check Authorized Copies Limit
        if doc.copies_consumed >= doc.copies_authorized:
            return False, f"All authorized copies ({doc.copies_authorized}) consumed", 0

        # Document is still within valid active session and has remaining copies quota
        return True, "Document has valid session and remaining copies quota", 0

    async def cleanup_document(
        self,
        document_id: str,
        job_id: Optional[str] = None,
        force_shred: bool = False,
        ip_address: Optional[str] = None
    ) -> CleanupResponse:
        """
        Executes verified storage deletion for an eligible document.
        Never marks cleanup successful before storage deletion has been verified.
        """
        now_utc = datetime.now(timezone.utc)

        # 1. Fetch document
        doc_query = select(Document).where(Document.id == document_id)
        res = await self.db.execute(doc_query)
        doc = res.scalar_one_or_none()

        if not doc:
            raise PrivPrintException(
                status_code=404,
                code=ErrorCode.NOT_FOUND,
                message=f"Document {document_id} not found"
            )

        # 2. If already shredded and object does not exist, return verified
        if doc.is_deleted and doc.cleanup_state == CleanupState.SHREDDED.value:
            return CleanupResponse(
                job_id=job_id,
                document_id=doc.id,
                cleanup_state=CleanupState.SHREDDED.value,
                storage_verified_deleted=True,
                retention_required=False,
                reason="Document was previously verified and shredded",
                storage_path=doc.storage_path,
                storage_backend="S3_COMPATIBLE_OBJECT_STORE",
                guarantee_level="OBJECT_DELETION_VERIFIED",
                cleaned_at=now_utc
            )

        # 3. Evaluate retention requirement
        retention_required, reason, _ = await self.evaluate_retention(doc)

        if retention_required and not force_shred:
            # Audit retention preservation
            audit = AuditLog(
                event_type="RETENTION_PRESERVED",
                severity=AuditSeverity.INFO.value,
                user_id=doc.user_id,
                job_id=job_id,
                ip_address=ip_address,
                details=json.dumps({
                    "document_id": doc.id,
                    "reason": reason,
                    "copies_authorized": doc.copies_authorized,
                    "copies_consumed": doc.copies_consumed,
                    "cleanup_state": doc.cleanup_state
                })
            )
            self.db.add(audit)
            await self.db.commit()

            return CleanupResponse(
                job_id=job_id,
                document_id=doc.id,
                cleanup_state=doc.cleanup_state,
                storage_verified_deleted=False,
                retention_required=True,
                reason=reason,
                storage_path=doc.storage_path,
                storage_backend="S3_COMPATIBLE_OBJECT_STORE",
                guarantee_level="OBJECT_DELETION_VERIFIED",
                cleaned_at=now_utc
            )

        # 4. Perform Storage Object Deletion
        storage_deleted = False
        try:
            storage_deleted = self.storage.delete_object(doc.storage_path)
        except Exception as e:
            logger.error(f"Storage deletion exception for {doc.storage_path}: {e}")
            storage_deleted = False

        # 5. Verify Storage Deletion
        object_still_exists = True
        try:
            object_still_exists = self.storage.object_exists(doc.storage_path)
        except Exception as e:
            logger.error(f"Storage existence check exception for {doc.storage_path}: {e}")
            object_still_exists = True

        storage_verified = (storage_deleted and not object_still_exists) or (not object_still_exists)

        if not storage_verified:
            # Storage deletion failed or could not be verified
            doc.cleanup_state = CleanupState.PENDING.value
            await self.db.commit()

            audit = AuditLog(
                event_type="STORAGE_DELETION_FAILED",
                severity=AuditSeverity.WARNING.value,
                user_id=doc.user_id,
                job_id=job_id,
                ip_address=ip_address,
                details=json.dumps({
                    "document_id": doc.id,
                    "storage_path": doc.storage_path,
                    "error": "Storage provider failed to delete object or object still exists after deletion"
                })
            )
            self.db.add(audit)
            await self.db.commit()

            return CleanupResponse(
                job_id=job_id,
                document_id=doc.id,
                cleanup_state=CleanupState.PENDING.value,
                storage_verified_deleted=False,
                retention_required=False,
                reason="Storage object deletion could not be verified",
                storage_path=doc.storage_path,
                storage_backend="S3_COMPATIBLE_OBJECT_STORE",
                guarantee_level="OBJECT_DELETION_VERIFIED",
                cleaned_at=now_utc
            )

        # 6. Update Document State
        doc.is_deleted = True
        doc.cleanup_state = CleanupState.SHREDDED.value
        await self.db.commit()

        # 7. Record Audit Trail
        audit = AuditLog(
            event_type="DOCUMENT_SHREDDED",
            severity=AuditSeverity.INFO.value,
            user_id=doc.user_id,
            job_id=job_id,
            ip_address=ip_address,
            details=json.dumps({
                "document_id": doc.id,
                "storage_path": doc.storage_path,
                "sha256_hash": doc.sha256_hash,
                "file_size_bytes": doc.file_size_bytes,
                "verification_status": "STORAGE_VERIFIED_DELETED",
                "storage_backend": "S3_COMPATIBLE_OBJECT_STORE",
                "guarantee_note": "Object unlinked and verified deleted from object storage",
                "reason": reason
            })
        )
        self.db.add(audit)
        await self.db.commit()

        # 8. Publish Redis Event
        event_payload = {
            "document_id": doc.id,
            "job_id": job_id,
            "user_id": doc.user_id,
            "cleanup_state": CleanupState.SHREDDED.value,
            "storage_verified": True
        }
        await self.redis.publish_user_event(doc.user_id, "DOCUMENT_SHREDDED", event_payload)
        if job_id:
            await self.redis.publish_job_event(job_id, "DOCUMENT_SHREDDED", event_payload)

        return CleanupResponse(
            job_id=job_id,
            document_id=doc.id,
            cleanup_state=CleanupState.SHREDDED.value,
            storage_verified_deleted=True,
            retention_required=False,
            reason=f"Storage object deleted and verified ({reason})",
            storage_path=doc.storage_path,
            storage_backend="S3_COMPATIBLE_OBJECT_STORE",
            guarantee_level="OBJECT_DELETION_VERIFIED",
            cleaned_at=now_utc
        )

    async def cleanup_job_document(
        self,
        job_id: str,
        ip_address: Optional[str] = None
    ) -> CleanupResponse:
        """
        Cleans up the document associated with a specific print job after it reaches a terminal state.
        """
        job_query = select(PrintJob).where(PrintJob.id == job_id)
        job_res = await self.db.execute(job_query)
        job = job_res.scalar_one_or_none()

        if not job:
            raise PrivPrintException(
                status_code=404,
                code=ErrorCode.NOT_FOUND,
                message=f"Print job {job_id} not found"
            )

        terminal_statuses = [
            PrintJobStatus.COMPLETED.value,
            PrintJobStatus.FAILED.value,
            PrintJobStatus.CANCELLED.value,
            PrintJobStatus.EXPIRED.value
        ]
        if job.status not in terminal_statuses:
            return CleanupResponse(
                job_id=job.id,
                document_id=job.document_id,
                cleanup_state=CleanupState.PENDING.value,
                storage_verified_deleted=False,
                retention_required=True,
                reason=f"Print job {job.id} is in non-terminal state ({job.status})",
                storage_path=None,
                storage_backend="S3_COMPATIBLE_OBJECT_STORE",
                guarantee_level="OBJECT_DELETION_VERIFIED",
                cleaned_at=datetime.now(timezone.utc)
            )

        return await self.cleanup_document(
            document_id=job.document_id,
            job_id=job.id,
            force_shred=False,
            ip_address=ip_address
        )

    async def get_document_status(self, document_id: str) -> CleanupStatusResponse:
        """
        Queries detailed cleanup & retention status of a document.
        """
        doc_query = select(Document).where(Document.id == document_id)
        res = await self.db.execute(doc_query)
        doc = res.scalar_one_or_none()

        if not doc:
            raise PrivPrintException(
                status_code=404,
                code=ErrorCode.NOT_FOUND,
                message=f"Document {document_id} not found"
            )

        retention_req, _, active_count = await self.evaluate_retention(doc)
        storage_exists = self.storage.object_exists(doc.storage_path)

        return CleanupStatusResponse(
            document_id=doc.id,
            cleanup_state=doc.cleanup_state,
            is_deleted=doc.is_deleted,
            retention_required=retention_req,
            active_job_count=active_count,
            copies_authorized=doc.copies_authorized,
            copies_consumed=doc.copies_consumed,
            storage_exists=storage_exists,
            guarantee_level="OBJECT_DELETION_VERIFIED",
            expires_at=doc.expires_at
        )

    async def run_cleanup_sweep(self) -> CleanupSweepResponse:
        """
        Batch background sweep worker.
        Scans all documents that are pending cleanup or expired, evaluates retention,
        and deletes eligible storage objects.
        """
        now_utc = datetime.now(timezone.utc)
        query = select(Document).where(
            or_(
                Document.cleanup_state == CleanupState.PENDING.value,
                and_(
                    Document.is_deleted == False,
                    Document.expires_at < now_utc
                )
            )
        )
        res = await self.db.execute(query)
        docs = res.scalars().all()

        scanned = len(docs)
        shredded = 0
        retained = 0
        failed = 0
        details: List[CleanupResponse] = []

        for doc in docs:
            try:
                result = await self.cleanup_document(doc.id, job_id=None)
                details.append(result)
                if result.storage_verified_deleted:
                    shredded += 1
                elif result.retention_required:
                    retained += 1
                else:
                    failed += 1
            except Exception as e:
                logger.error(f"Error during cleanup sweep for doc {doc.id}: {e}")
                failed += 1

        return CleanupSweepResponse(
            scanned_documents=scanned,
            shredded_documents=shredded,
            retained_documents=retained,
            failed_documents=failed,
            details=details
        )
