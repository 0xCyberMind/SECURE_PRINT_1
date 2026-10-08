"""add shop history retention hours

Revision ID: f23a5b6c7d8e
Revises: e18f4a7c29d1
Create Date: 2026-10-07
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = "f23a5b6c7d8e"
down_revision: Union[str, Sequence[str], None] = "e18f4a7c29d1"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    bind = op.get_bind()
    dialect = bind.dialect.name

    if dialect == "postgresql":
        op.execute(sa.text("ALTER TABLE shops ADD COLUMN IF NOT EXISTS history_retention_hours INTEGER NOT NULL DEFAULT 4"))
        op.execute(sa.text("ALTER TABLE shops DROP CONSTRAINT IF EXISTS chk_shop_history_retention_range"))
        op.execute(sa.text("ALTER TABLE shops ADD CONSTRAINT chk_shop_history_retention_range CHECK (history_retention_hours IN (1, 2, 4, 6, 8))"))
    elif dialect == "sqlite":
        with op.batch_alter_table("shops") as batch_op:
            batch_op.add_column(sa.Column("history_retention_hours", sa.Integer(), nullable=False, server_default="4"))


def downgrade() -> None:
    bind = op.get_bind()
    dialect = bind.dialect.name

    if dialect == "postgresql":
        op.execute(sa.text("ALTER TABLE shops DROP CONSTRAINT IF EXISTS chk_shop_history_retention_range"))
        op.execute(sa.text("ALTER TABLE shops DROP COLUMN IF EXISTS history_retention_hours"))
    elif dialect == "sqlite":
        with op.batch_alter_table("shops") as batch_op:
            batch_op.drop_column("history_retention_hours")
