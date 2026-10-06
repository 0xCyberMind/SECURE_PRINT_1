"""add batch and telemetry fields

Revision ID: e18f4a7c29d1
Revises: c6b8e914af20
Create Date: 2026-10-06
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = "e18f4a7c29d1"
down_revision: Union[str, Sequence[str], None] = "c6b8e914af20"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    bind = op.get_bind()
    dialect = bind.dialect.name

    if dialect == "postgresql":
        # Execute each DDL statement separately to comply with asyncpg prepared statement rules
        op.execute(sa.text("ALTER TABLE print_jobs ADD COLUMN IF NOT EXISTS batch_id VARCHAR(64)"))
        op.execute(sa.text("ALTER TABLE print_jobs ADD COLUMN IF NOT EXISTS file_index INTEGER NOT NULL DEFAULT 0"))
        op.execute(sa.text("ALTER TABLE print_jobs ADD COLUMN IF NOT EXISTS total_files INTEGER NOT NULL DEFAULT 1"))
        op.execute(sa.text("ALTER TABLE print_jobs ADD COLUMN IF NOT EXISTS pages_printed INTEGER NOT NULL DEFAULT 0"))
        op.execute(sa.text("ALTER TABLE print_jobs ADD COLUMN IF NOT EXISTS retention_hours INTEGER NOT NULL DEFAULT 2"))

        op.execute(sa.text("ALTER TABLE documents ADD COLUMN IF NOT EXISTS batch_id VARCHAR(64)"))
        op.execute(sa.text("ALTER TABLE documents ADD COLUMN IF NOT EXISTS retention_hours INTEGER NOT NULL DEFAULT 2"))

        op.execute(sa.text("ALTER TABLE pending_uploads ADD COLUMN IF NOT EXISTS batch_id VARCHAR(64)"))
        op.execute(sa.text("ALTER TABLE pending_uploads ADD COLUMN IF NOT EXISTS retention_hours INTEGER NOT NULL DEFAULT 2"))

        op.execute(sa.text("ALTER TABLE sessions ADD COLUMN IF NOT EXISTS retention_hours INTEGER NOT NULL DEFAULT 2"))

        op.execute(sa.text("CREATE INDEX IF NOT EXISTS idx_job_batch ON print_jobs (batch_id)"))
        op.execute(sa.text("CREATE INDEX IF NOT EXISTS idx_doc_batch ON documents (batch_id)"))
        op.execute(sa.text("CREATE INDEX IF NOT EXISTS ix_pending_uploads_batch_id ON pending_uploads (batch_id)"))

        op.execute(sa.text("""
            DO $$
            BEGIN
                IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_job_pages_printed_positive') THEN
                    ALTER TABLE print_jobs ADD CONSTRAINT chk_job_pages_printed_positive CHECK (pages_printed >= 0);
                END IF;
                IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_job_retention_range') THEN
                    ALTER TABLE print_jobs ADD CONSTRAINT chk_job_retention_range CHECK (retention_hours >= 1 AND retention_hours <= 24);
                END IF;
                IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_doc_retention_range') THEN
                    ALTER TABLE documents ADD CONSTRAINT chk_doc_retention_range CHECK (retention_hours >= 1 AND retention_hours <= 24);
                END IF;
            END $$;
        """))
    else:
        with op.batch_alter_table("print_jobs", schema=None) as batch_op:
            batch_op.add_column(sa.Column("batch_id", sa.String(length=64), nullable=True))
            batch_op.add_column(sa.Column("file_index", sa.Integer(), nullable=False, server_default="0"))
            batch_op.add_column(sa.Column("total_files", sa.Integer(), nullable=False, server_default="1"))
            batch_op.add_column(sa.Column("pages_printed", sa.Integer(), nullable=False, server_default="0"))
            batch_op.add_column(sa.Column("retention_hours", sa.Integer(), nullable=False, server_default="2"))
            batch_op.create_index("idx_job_batch", ["batch_id"], unique=False)

        with op.batch_alter_table("documents", schema=None) as batch_op:
            batch_op.add_column(sa.Column("batch_id", sa.String(length=64), nullable=True))
            batch_op.add_column(sa.Column("retention_hours", sa.Integer(), nullable=False, server_default="2"))
            batch_op.create_index("idx_doc_batch", ["batch_id"], unique=False)

        with op.batch_alter_table("pending_uploads", schema=None) as batch_op:
            batch_op.add_column(sa.Column("batch_id", sa.String(length=64), nullable=True))
            batch_op.add_column(sa.Column("retention_hours", sa.Integer(), nullable=False, server_default="2"))
            batch_op.create_index("ix_pending_uploads_batch_id", ["batch_id"], unique=False)

        with op.batch_alter_table("sessions", schema=None) as batch_op:
            batch_op.add_column(sa.Column("retention_hours", sa.Integer(), nullable=False, server_default="2"))


def downgrade() -> None:
    bind = op.get_bind()
    dialect = bind.dialect.name

    if dialect == "postgresql":
        op.execute(sa.text("DROP INDEX IF EXISTS idx_job_batch"))
        op.execute(sa.text("DROP INDEX IF EXISTS idx_doc_batch"))
        op.execute(sa.text("DROP INDEX IF EXISTS ix_pending_uploads_batch_id"))

        op.execute(sa.text("ALTER TABLE print_jobs DROP CONSTRAINT IF EXISTS chk_job_pages_printed_positive"))
        op.execute(sa.text("ALTER TABLE print_jobs DROP CONSTRAINT IF EXISTS chk_job_retention_range"))
        op.execute(sa.text("ALTER TABLE documents DROP CONSTRAINT IF EXISTS chk_doc_retention_range"))

        op.execute(sa.text("ALTER TABLE print_jobs DROP COLUMN IF EXISTS retention_hours"))
        op.execute(sa.text("ALTER TABLE print_jobs DROP COLUMN IF EXISTS pages_printed"))
        op.execute(sa.text("ALTER TABLE print_jobs DROP COLUMN IF EXISTS total_files"))
        op.execute(sa.text("ALTER TABLE print_jobs DROP COLUMN IF EXISTS file_index"))
        op.execute(sa.text("ALTER TABLE print_jobs DROP COLUMN IF EXISTS batch_id"))

        op.execute(sa.text("ALTER TABLE documents DROP COLUMN IF EXISTS retention_hours"))
        op.execute(sa.text("ALTER TABLE documents DROP COLUMN IF EXISTS batch_id"))

        op.execute(sa.text("ALTER TABLE pending_uploads DROP COLUMN IF EXISTS retention_hours"))
        op.execute(sa.text("ALTER TABLE pending_uploads DROP COLUMN IF EXISTS batch_id"))

        op.execute(sa.text("ALTER TABLE sessions DROP COLUMN IF EXISTS retention_hours"))
    else:
        with op.batch_alter_table("sessions", schema=None) as batch_op:
            batch_op.drop_column("retention_hours")

        with op.batch_alter_table("pending_uploads", schema=None) as batch_op:
            batch_op.drop_index("ix_pending_uploads_batch_id")
            batch_op.drop_column("retention_hours")
            batch_op.drop_column("batch_id")

        with op.batch_alter_table("documents", schema=None) as batch_op:
            batch_op.drop_index("idx_doc_batch")
            batch_op.drop_column("retention_hours")
            batch_op.drop_column("batch_id")

        with op.batch_alter_table("print_jobs", schema=None) as batch_op:
            batch_op.drop_index("idx_job_batch")
            batch_op.drop_column("retention_hours")
            batch_op.drop_column("pages_printed")
            batch_op.drop_column("total_files")
            batch_op.drop_column("file_index")
            batch_op.drop_column("batch_id")
