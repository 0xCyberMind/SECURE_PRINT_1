"""initial_schema

Revision ID: 9bf371e0120c
Revises: 
Create Date: 2026-10-03 08:50:45.891632

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = '9bf371e0120c'
down_revision: Union[str, Sequence[str], None] = None
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # 1. users
    op.create_table(
        'users',
        sa.Column('id', sa.String(length=64), primary_key=True),
        sa.Column('email', sa.String(length=255), nullable=False),
        sa.Column('hashed_password', sa.String(length=255), nullable=False),
        sa.Column('full_name', sa.String(length=255), nullable=True),
        sa.Column('phone_number', sa.String(length=32), nullable=True),
        sa.Column('role', sa.String(length=32), nullable=False),
        sa.Column('is_active', sa.Boolean(), nullable=False, server_default=sa.text('true')),
        sa.Column('is_verified', sa.Boolean(), nullable=False, server_default=sa.text('false')),
        sa.Column('created_at', sa.DateTime(timezone=True), nullable=False),
        sa.Column('updated_at', sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index('ix_users_email', 'users', ['email'], unique=True)
    op.create_index('ix_users_phone_number', 'users', ['phone_number'])

    # 2. shops
    op.create_table(
        'shops',
        sa.Column('id', sa.String(length=64), primary_key=True),
        sa.Column('name', sa.String(length=255), nullable=False),
        sa.Column('owner_id', sa.String(length=64), sa.ForeignKey('users.id', ondelete='SET NULL'), nullable=True),
        sa.Column('address', sa.Text(), nullable=False),
        sa.Column('latitude', sa.Float(), nullable=True),
        sa.Column('longitude', sa.Float(), nullable=True),
        sa.Column('is_verified', sa.Boolean(), nullable=False, server_default=sa.text('true')),
        sa.Column('is_online', sa.Boolean(), nullable=False, server_default=sa.text('true')),
        sa.Column('supports_color', sa.Boolean(), nullable=False, server_default=sa.text('true')),
        sa.Column('supports_duplex', sa.Boolean(), nullable=False, server_default=sa.text('true')),
        sa.Column('permanent_qr_payload', sa.Text(), nullable=False),
        sa.Column('created_at', sa.DateTime(timezone=True), nullable=False),
        sa.Column('updated_at', sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index('ix_shops_owner_id', 'shops', ['owner_id'])

    # 3. devices
    op.create_table(
        'devices',
        sa.Column('id', sa.String(length=64), primary_key=True),
        sa.Column('shop_id', sa.String(length=64), sa.ForeignKey('shops.id', ondelete='CASCADE'), nullable=False),
        sa.Column('name', sa.String(length=255), nullable=False),
        sa.Column('device_type', sa.String(length=64), nullable=False, server_default='WINDOWS_STATION'),
        sa.Column('hardware_fingerprint', sa.String(length=255), nullable=True),
        sa.Column('ip_address', sa.String(length=64), nullable=True),
        sa.Column('is_active', sa.Boolean(), nullable=False, server_default=sa.text('true')),
        sa.Column('last_heartbeat_at', sa.DateTime(timezone=True), nullable=True),
        sa.Column('created_at', sa.DateTime(timezone=True), nullable=False),
        sa.Column('updated_at', sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index('ix_devices_shop_id', 'devices', ['shop_id'])
    op.create_index('ix_devices_hardware_fingerprint', 'devices', ['hardware_fingerprint'])

    # 4. printers
    op.create_table(
        'printers',
        sa.Column('id', sa.String(length=64), primary_key=True),
        sa.Column('shop_id', sa.String(length=64), sa.ForeignKey('shops.id', ondelete='CASCADE'), nullable=False),
        sa.Column('name', sa.String(length=255), nullable=False),
        sa.Column('model', sa.String(length=255), nullable=False),
        sa.Column('uri', sa.String(length=512), nullable=True),
        sa.Column('is_default', sa.Boolean(), nullable=False, server_default=sa.text('false')),
        sa.Column('is_online', sa.Boolean(), nullable=False, server_default=sa.text('true')),
        sa.Column('supports_color', sa.Boolean(), nullable=False, server_default=sa.text('true')),
        sa.Column('supports_duplex', sa.Boolean(), nullable=False, server_default=sa.text('true')),
        sa.Column('paper_tray_status', sa.String(length=32), nullable=False, server_default='NORMAL'),
        sa.Column('toner_level_percent', sa.Integer(), nullable=False, server_default='100'),
        sa.Column('created_at', sa.DateTime(timezone=True), nullable=False),
        sa.Column('updated_at', sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index('ix_printers_shop_id', 'printers', ['shop_id'])

    # 5. sessions
    op.create_table(
        'sessions',
        sa.Column('id', sa.String(length=64), primary_key=True),
        sa.Column('user_id', sa.String(length=64), sa.ForeignKey('users.id', ondelete='SET NULL'), nullable=True),
        sa.Column('shop_id', sa.String(length=64), sa.ForeignKey('shops.id', ondelete='CASCADE'), nullable=False),
        sa.Column('token', sa.String(length=128), nullable=False),
        sa.Column('status', sa.String(length=32), nullable=False, server_default='ACTIVE'),
        sa.Column('expires_at', sa.DateTime(timezone=True), nullable=False),
        sa.Column('created_at', sa.DateTime(timezone=True), nullable=False),
        sa.Column('updated_at', sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index('ix_sessions_token', 'sessions', ['token'], unique=True)
    op.create_index('ix_sessions_shop_id', 'sessions', ['shop_id'])
    op.create_index('ix_sessions_user_id', 'sessions', ['user_id'])
    op.create_index('ix_sessions_status', 'sessions', ['status'])

    # 6. documents
    op.create_table(
        'documents',
        sa.Column('id', sa.String(length=64), primary_key=True),
        sa.Column('user_id', sa.String(length=64), sa.ForeignKey('users.id', ondelete='CASCADE'), nullable=False),
        sa.Column('session_id', sa.String(length=64), sa.ForeignKey('sessions.id', ondelete='CASCADE'), nullable=False),
        sa.Column('filename', sa.String(length=255), nullable=False),
        sa.Column('storage_path', sa.String(length=512), nullable=False),
        sa.Column('file_size_bytes', sa.BigInteger(), nullable=False),
        sa.Column('mime_type', sa.String(length=128), nullable=False, server_default='application/pdf'),
        sa.Column('sha256_hash', sa.String(length=64), nullable=False),
        sa.Column('encryption_algorithm', sa.String(length=64), nullable=False, server_default='AES-256-GCM'),
        sa.Column('iv_hex', sa.String(length=64), nullable=False),
        sa.Column('key_fingerprint', sa.String(length=128), nullable=False),
        sa.Column('copies_authorized', sa.Integer(), nullable=False, server_default='1'),
        sa.Column('copies_consumed', sa.Integer(), nullable=False, server_default='0'),
        sa.Column('expires_at', sa.DateTime(timezone=True), nullable=False),
        sa.Column('cleanup_state', sa.String(length=32), nullable=False, server_default='PENDING'),
        sa.Column('is_deleted', sa.Boolean(), nullable=False, server_default=sa.text('false')),
        sa.Column('created_at', sa.DateTime(timezone=True), nullable=False),
        sa.Column('updated_at', sa.DateTime(timezone=True), nullable=False),
        sa.CheckConstraint('copies_consumed >= 0', name='chk_doc_copies_consumed_positive'),
        sa.CheckConstraint('copies_consumed <= copies_authorized', name='chk_doc_copies_consumed_limit'),
    )
    op.create_index('ix_documents_user_id', 'documents', ['user_id'])
    op.create_index('ix_documents_session_id', 'documents', ['session_id'])
    op.create_index('ix_documents_sha256_hash', 'documents', ['sha256_hash'])
    op.create_index('ix_documents_cleanup_state', 'documents', ['cleanup_state'])
    op.create_index('idx_doc_user_cleanup', 'documents', ['user_id', 'cleanup_state'])

    # 7. print_jobs
    op.create_table(
        'print_jobs',
        sa.Column('id', sa.String(length=64), primary_key=True),
        sa.Column('user_id', sa.String(length=64), sa.ForeignKey('users.id', ondelete='CASCADE'), nullable=False),
        sa.Column('shop_id', sa.String(length=64), sa.ForeignKey('shops.id', ondelete='CASCADE'), nullable=False),
        sa.Column('session_id', sa.String(length=64), sa.ForeignKey('sessions.id', ondelete='CASCADE'), nullable=False),
        sa.Column('document_id', sa.String(length=64), sa.ForeignKey('documents.id', ondelete='CASCADE'), nullable=False),
        sa.Column('printer_id', sa.String(length=64), sa.ForeignKey('printers.id', ondelete='SET NULL'), nullable=True),
        sa.Column('page_count', sa.Integer(), nullable=False, server_default='1'),
        sa.Column('requested_copies', sa.Integer(), nullable=False, server_default='1'),
        sa.Column('completed_copies', sa.Integer(), nullable=False, server_default='0'),
        sa.Column('status', sa.String(length=32), nullable=False, server_default='QUEUED'),
        sa.Column('color_mode', sa.String(length=32), nullable=False, server_default='MONOCHROME'),
        sa.Column('paper_size', sa.String(length=32), nullable=False, server_default='A4'),
        sa.Column('orientation', sa.String(length=32), nullable=False, server_default='PORTRAIT'),
        sa.Column('duplex_mode', sa.String(length=32), nullable=False, server_default='SIMPLEX'),
        sa.Column('failure_reason', sa.Text(), nullable=True),
        sa.Column('completed_at', sa.DateTime(timezone=True), nullable=True),
        sa.Column('expires_at', sa.DateTime(timezone=True), nullable=False),
        sa.Column('created_at', sa.DateTime(timezone=True), nullable=False),
        sa.Column('updated_at', sa.DateTime(timezone=True), nullable=False),
        sa.CheckConstraint('completed_copies >= 0', name='chk_job_completed_copies_positive'),
        sa.CheckConstraint('completed_copies <= requested_copies', name='chk_job_completed_copies_limit'),
    )
    op.create_index('ix_print_jobs_user_id', 'print_jobs', ['user_id'])
    op.create_index('ix_print_jobs_shop_id', 'print_jobs', ['shop_id'])
    op.create_index('ix_print_jobs_session_id', 'print_jobs', ['session_id'])
    op.create_index('ix_print_jobs_document_id', 'print_jobs', ['document_id'])
    op.create_index('ix_print_jobs_printer_id', 'print_jobs', ['printer_id'])
    op.create_index('ix_print_jobs_status', 'print_jobs', ['status'])
    op.create_index('idx_job_shop_status', 'print_jobs', ['shop_id', 'status'])
    op.create_index('idx_job_user_status', 'print_jobs', ['user_id', 'status'])

    # 8. refresh_tokens
    op.create_table(
        'refresh_tokens',
        sa.Column('id', sa.String(length=64), primary_key=True),
        sa.Column('user_id', sa.String(length=64), sa.ForeignKey('users.id', ondelete='CASCADE'), nullable=False),
        sa.Column('token_hash', sa.String(length=255), nullable=False),
        sa.Column('jti', sa.String(length=128), nullable=False),
        sa.Column('expires_at', sa.DateTime(timezone=True), nullable=False),
        sa.Column('is_revoked', sa.Boolean(), nullable=False, server_default=sa.text('false')),
        sa.Column('revoked_at', sa.DateTime(timezone=True), nullable=True),
        sa.Column('created_at', sa.DateTime(timezone=True), nullable=False),
        sa.Column('updated_at', sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index('ix_refresh_tokens_user_id', 'refresh_tokens', ['user_id'])
    op.create_index('ix_refresh_tokens_token_hash', 'refresh_tokens', ['token_hash'], unique=True)
    op.create_index('ix_refresh_tokens_jti', 'refresh_tokens', ['jti'], unique=True)

    # 9. audit_logs
    op.create_table(
        'audit_logs',
        sa.Column('id', sa.String(length=64), primary_key=True),
        sa.Column('event_type', sa.String(length=64), nullable=False),
        sa.Column('severity', sa.String(length=32), nullable=False, server_default='INFO'),
        sa.Column('user_id', sa.String(length=64), nullable=True),
        sa.Column('shop_id', sa.String(length=64), nullable=True),
        sa.Column('job_id', sa.String(length=64), nullable=True),
        sa.Column('details', sa.Text(), nullable=False),
        sa.Column('ip_address', sa.String(length=64), nullable=True),
        sa.Column('created_at', sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index('ix_audit_logs_event_type', 'audit_logs', ['event_type'])
    op.create_index('ix_audit_logs_user_id', 'audit_logs', ['user_id'])
    op.create_index('ix_audit_logs_shop_id', 'audit_logs', ['shop_id'])
    op.create_index('ix_audit_logs_job_id', 'audit_logs', ['job_id'])
    op.create_index('ix_audit_logs_created_at', 'audit_logs', ['created_at'])


def downgrade() -> None:
    op.drop_table('audit_logs')
    op.drop_table('refresh_tokens')
    op.drop_table('print_jobs')
    op.drop_table('documents')
    op.drop_table('sessions')
    op.drop_table('printers')
    op.drop_table('devices')
    op.drop_table('shops')
    op.drop_table('users')
