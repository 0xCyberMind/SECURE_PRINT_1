"""activate shops owned by shop operators

Revision ID: c6b8e914af20
Revises: a31f0de29c7b
Create Date: 2026-10-05
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = "c6b8e914af20"
down_revision: Union[str, Sequence[str], None] = "a31f0de29c7b"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.execute(
        sa.text(
            """
            UPDATE shops
            SET status = 'ACTIVE', is_verified = TRUE
            WHERE owner_id IN (
                SELECT id FROM users WHERE role = 'SHOP_OPERATOR'
            )
            """
        )
    )


def downgrade() -> None:
    pass
