from typing import List, Optional
from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession
from app.models.entities import Document
from app.models.enums import CleanupState
from app.repositories.base import BaseRepository


class DocumentRepository(BaseRepository[Document]):
    def __init__(self, session: AsyncSession):
        super().__init__(Document, session)

    async def get_by_id_and_user(self, doc_id: str, user_id: str) -> Optional[Document]:
        """
        Strict user isolation: A user cannot read or delete another user's document.
        """
        result = await self.session.execute(
            select(Document).where(
                Document.id == doc_id,
                Document.user_id == user_id,
                Document.is_deleted == False
            )
        )
        return result.scalars().first()

    async def list_by_session_for_user(self, session_id: str, user_id: str) -> List[Document]:
        result = await self.session.execute(
            select(Document).where(
                Document.session_id == session_id,
                Document.user_id == user_id,
                Document.is_deleted == False
            )
        )
        return list(result.scalars().all())

    async def soft_delete(self, doc_id: str, user_id: str) -> bool:
        doc = await self.get_by_id_and_user(doc_id, user_id)
        if not doc:
            return False
        doc.is_deleted = True
        doc.cleanup_state = CleanupState.SHREDDED.value
        await self.session.flush()
        return True
