"""add station document encryption keys

Revision ID: a31f0de29c7b
Revises: 80420dd65062
Create Date: 2026-10-05
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = "a31f0de29c7b"
down_revision: Union[str, Sequence[str], None] = "80420dd65062"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column("devices", sa.Column("encryption_public_key", sa.Text(), nullable=True))
    op.add_column("pending_uploads", sa.Column("wrapped_keys", sa.JSON(), nullable=True))
    op.add_column("documents", sa.Column("wrapped_keys", sa.JSON(), nullable=True))


def downgrade() -> None:
    op.drop_column("documents", "wrapped_keys")
    op.drop_column("pending_uploads", "wrapped_keys")
    op.drop_column("devices", "encryption_public_key")
