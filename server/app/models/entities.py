import uuid
from datetime import datetime, timezone
from sqlalchemy import (
    Column,
    String,
    Integer,
    BigInteger,
    Boolean,
    Float,
    JSON,
    DateTime,
    ForeignKey,
    CheckConstraint,
    Index,
    Text,
)
from sqlalchemy.orm import relationship
from app.models.base import Base, TimestampMixin
from app.models.enums import (
    UserRole,
    SessionStatus,
    PrintJobStatus,
    CleanupState,
    AuditSeverity,
)


class User(Base, TimestampMixin):
    __tablename__ = "users"

    id = Column(String(64), primary_key=True, default=lambda: f"usr_{uuid.uuid4().hex[:12]}")
    email = Column(String(255), unique=True, nullable=False, index=True)
    hashed_password = Column(String(255), nullable=False)
    full_name = Column(String(255), nullable=True)
    phone_number = Column(String(32), nullable=True, index=True)
    role = Column(String(32), default=UserRole.USER.value, nullable=False)
    is_active = Column(Boolean, default=True, nullable=False)
    is_verified = Column(Boolean, default=False, nullable=False)

    # Relationships
    sessions = relationship("Session", back_populates="user", cascade="all, delete-orphan")
    documents = relationship("Document", back_populates="user", cascade="all, delete-orphan")
    print_jobs = relationship("PrintJob", back_populates="user", cascade="all, delete-orphan")
    refresh_tokens = relationship("RefreshToken", back_populates="user", cascade="all, delete-orphan")


class Shop(Base, TimestampMixin):
    __tablename__ = "shops"

    id = Column(String(64), primary_key=True)  # e.g. SHOP-101
    name = Column(String(255), nullable=False)
    owner_id = Column(String(64), ForeignKey("users.id", ondelete="SET NULL"), nullable=True, index=True)
    address = Column(Text, nullable=False)
    latitude = Column(Float, nullable=True)
    longitude = Column(Float, nullable=True)
    status = Column(String(32), default="ACTIVE", nullable=False, index=True)
    is_verified = Column(Boolean, default=True, nullable=False)
    is_online = Column(Boolean, default=True, nullable=False)
    supports_color = Column(Boolean, default=True, nullable=False)
    supports_duplex = Column(Boolean, default=True, nullable=False)
    permanent_qr_payload = Column(Text, nullable=False)
    history_retention_hours = Column(Integer, default=4, nullable=False)

    __table_args__ = (
        CheckConstraint("history_retention_hours IN (1, 2, 4, 6, 8)", name="chk_shop_history_retention_range"),
    )

    # Relationships
    devices = relationship("Device", back_populates="shop", cascade="all, delete-orphan")
    printers = relationship("Printer", back_populates="shop", cascade="all, delete-orphan")
    sessions = relationship("Session", back_populates="shop", cascade="all, delete-orphan")
    print_jobs = relationship("PrintJob", back_populates="shop", cascade="all, delete-orphan")


class Device(Base, TimestampMixin):
    __tablename__ = "devices"

    id = Column(String(64), primary_key=True, default=lambda: f"dev_{uuid.uuid4().hex[:12]}")
    shop_id = Column(String(64), ForeignKey("shops.id", ondelete="CASCADE"), nullable=False, index=True)
    name = Column(String(255), nullable=False)
    device_type = Column(String(64), default="WINDOWS_STATION", nullable=False)
    os_info = Column(String(255), default="Windows 11 Pro", nullable=False)
    app_version = Column(String(64), default="1.0.0", nullable=False)
    auth_state = Column(String(32), default="AUTHENTICATED", nullable=False)
    api_key_hash = Column(String(255), nullable=True)
    status = Column(String(32), default="ONLINE", nullable=False)
    hardware_fingerprint = Column(String(255), nullable=True, index=True)
    ip_address = Column(String(64), nullable=True)
    is_active = Column(Boolean, default=True, nullable=False)
    encryption_public_key = Column(Text, nullable=True)
    last_seen_at = Column(DateTime(timezone=True), nullable=True)
    last_heartbeat_at = Column(DateTime(timezone=True), nullable=True)

    # Relationships
    shop = relationship("Shop", back_populates="devices")


class Printer(Base, TimestampMixin):
    __tablename__ = "printers"

    id = Column(String(64), primary_key=True)  # e.g. PRN-HP-01
    shop_id = Column(String(64), ForeignKey("shops.id", ondelete="CASCADE"), nullable=False, index=True)
    name = Column(String(255), nullable=False)
    model = Column(String(255), nullable=False, default="Generic Printer")
    driver_name = Column(String(255), nullable=True, default="NOT_REPORTED")
    uri = Column(String(512), nullable=True)
    connection_info = Column(String(255), nullable=True, default="NOT_REPORTED")
    status = Column(String(32), default="READY", nullable=False)  # READY, BUSY, OFFLINE, ERROR, UNKNOWN
    is_default = Column(Boolean, default=False, nullable=False)
    is_online = Column(Boolean, default=True, nullable=False)
    supports_color = Column(Boolean, default=True, nullable=False)
    supports_duplex = Column(Boolean, default=True, nullable=False)
    supported_paper_sizes = Column(String(255), nullable=True, default="A4, Letter")
    paper_tray_status = Column(String(32), default="READY", nullable=False)
    toner_level_percent = Column(Integer, default=100, nullable=False)

    # Relationships
    shop = relationship("Shop", back_populates="printers")
    print_jobs = relationship("PrintJob", back_populates="printer")


class Session(Base, TimestampMixin):
    __tablename__ = "sessions"

    id = Column(String(64), primary_key=True, default=lambda: f"SES-{uuid.uuid4().hex[:8].upper()}")
    user_id = Column(String(64), ForeignKey("users.id", ondelete="SET NULL"), nullable=True, index=True)
    shop_id = Column(String(64), ForeignKey("shops.id", ondelete="CASCADE"), nullable=False, index=True)
    token = Column(String(128), unique=True, nullable=False, index=True)
    nonce = Column(String(64), nullable=False, default=lambda: uuid.uuid4().hex)
    status = Column(String(32), default=SessionStatus.ACTIVE.value, nullable=False, index=True)
    retention_hours = Column(Integer, default=2, nullable=False)
    expires_at = Column(DateTime(timezone=True), nullable=False)

    # Relationships
    user = relationship("User", back_populates="sessions")
    shop = relationship("Shop", back_populates="sessions")
    documents = relationship("Document", back_populates="session", cascade="all, delete-orphan")
    print_jobs = relationship("PrintJob", back_populates="session", cascade="all, delete-orphan")


class Document(Base, TimestampMixin):
    __tablename__ = "documents"

    id = Column(String(64), primary_key=True, default=lambda: f"DOC-{uuid.uuid4().hex[:8].upper()}")
    user_id = Column(String(64), ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True)
    session_id = Column(String(64), ForeignKey("sessions.id", ondelete="CASCADE"), nullable=False, index=True)
    batch_id = Column(String(64), nullable=True, index=True)
    filename = Column(String(255), nullable=False)
    storage_path = Column(String(512), nullable=False)
    file_size_bytes = Column(BigInteger, nullable=False)
    mime_type = Column(String(128), default="application/pdf", nullable=False)
    sha256_hash = Column(String(64), nullable=False, index=True)
    encryption_algorithm = Column(String(64), default="AES-256-GCM", nullable=False)
    iv_hex = Column(String(64), nullable=False)
    key_fingerprint = Column(String(128), nullable=False)
    wrapped_keys = Column(JSON, nullable=True)
    copies_authorized = Column(Integer, default=1, nullable=False)
    copies_consumed = Column(Integer, default=0, nullable=False)
    retention_hours = Column(Integer, default=2, nullable=False)
    expires_at = Column(DateTime(timezone=True), nullable=False)
    cleanup_state = Column(String(32), default=CleanupState.PENDING.value, nullable=False, index=True)
    is_deleted = Column(Boolean, default=False, nullable=False)

    __table_args__ = (
        CheckConstraint("copies_consumed >= 0", name="chk_doc_copies_consumed_positive"),
        CheckConstraint("copies_consumed <= copies_authorized", name="chk_doc_copies_consumed_limit"),
        CheckConstraint("retention_hours >= 1 AND retention_hours <= 24", name="chk_doc_retention_range"),
        Index("idx_doc_user_cleanup", "user_id", "cleanup_state"),
        Index("idx_doc_batch", "batch_id"),
    )

    # Relationships
    user = relationship("User", back_populates="documents")
    session = relationship("Session", back_populates="documents")
    print_jobs = relationship("PrintJob", back_populates="document", cascade="all, delete-orphan")


class PendingUpload(Base, TimestampMixin):
    __tablename__ = "pending_uploads"

    id = Column(String(64), primary_key=True)  # upl_...
    document_id = Column(String(64), nullable=False)
    user_id = Column(String(64), ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True)
    session_id = Column(String(64), ForeignKey("sessions.id", ondelete="CASCADE"), nullable=False, index=True)
    batch_id = Column(String(64), nullable=True, index=True)
    filename = Column(String(255), nullable=False)
    storage_path = Column(String(512), nullable=False)
    file_size_bytes = Column(BigInteger, nullable=False)
    mime_type = Column(String(128), nullable=False)
    sha256_hash = Column(String(64), nullable=False)
    iv_hex = Column(String(64), nullable=False)
    key_fingerprint = Column(String(128), nullable=False)
    wrapped_keys = Column(JSON, nullable=True)
    copies_authorized = Column(Integer, default=1, nullable=False)
    retention_hours = Column(Integer, default=2, nullable=False)
    expires_at = Column(DateTime(timezone=True), nullable=False)
    status = Column(String(32), default="PENDING", nullable=False)


class PrintJob(Base, TimestampMixin):
    __tablename__ = "print_jobs"

    id = Column(String(64), primary_key=True, default=lambda: f"PRV-{uuid.uuid4().hex[:8].upper()}")
    user_id = Column(String(64), ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True)
    shop_id = Column(String(64), ForeignKey("shops.id", ondelete="CASCADE"), nullable=False, index=True)
    session_id = Column(String(64), ForeignKey("sessions.id", ondelete="CASCADE"), nullable=False, index=True)
    document_id = Column(String(64), ForeignKey("documents.id", ondelete="CASCADE"), nullable=False, index=True)
    printer_id = Column(String(64), ForeignKey("printers.id", ondelete="SET NULL"), nullable=True, index=True)
    batch_id = Column(String(64), nullable=True, index=True)
    file_index = Column(Integer, default=0, nullable=False)
    total_files = Column(Integer, default=1, nullable=False)

    page_count = Column(Integer, default=1, nullable=False)
    pages_printed = Column(Integer, default=0, nullable=False)
    requested_copies = Column(Integer, default=1, nullable=False)
    completed_copies = Column(Integer, default=0, nullable=False)
    retention_hours = Column(Integer, default=2, nullable=False)
    status = Column(String(32), default=PrintJobStatus.CREATED.value, nullable=False, index=True)
    idempotency_key = Column(String(128), nullable=True, index=True)
    color_mode = Column(String(32), default="MONOCHROME", nullable=False)
    paper_size = Column(String(32), default="A4", nullable=False)
    orientation = Column(String(32), default="PORTRAIT", nullable=False)
    duplex_mode = Column(String(32), default="SIMPLEX", nullable=False)
    failure_reason = Column(Text, nullable=True)
    completed_at = Column(DateTime(timezone=True), nullable=True)
    expires_at = Column(DateTime(timezone=True), nullable=False)

    __table_args__ = (
        CheckConstraint("completed_copies >= 0", name="chk_job_completed_copies_positive"),
        CheckConstraint("completed_copies <= requested_copies", name="chk_job_completed_copies_limit"),
        CheckConstraint("pages_printed >= 0", name="chk_job_pages_printed_positive"),
        CheckConstraint("retention_hours >= 1 AND retention_hours <= 24", name="chk_job_retention_range"),
        Index("idx_job_shop_status", "shop_id", "status"),
        Index("idx_job_user_status", "user_id", "status"),
        Index("idx_job_batch", "batch_id"),
    )

    # Relationships
    user = relationship("User", back_populates="print_jobs")
    shop = relationship("Shop", back_populates="print_jobs")
    session = relationship("Session", back_populates="print_jobs")
    document = relationship("Document", back_populates="print_jobs")
    printer = relationship("Printer", back_populates="print_jobs")


class RefreshToken(Base, TimestampMixin):
    __tablename__ = "refresh_tokens"

    id = Column(String(64), primary_key=True, default=lambda: f"refr_{uuid.uuid4().hex}")
    user_id = Column(String(64), ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True)
    token_hash = Column(String(255), unique=True, nullable=False, index=True)
    jti = Column(String(128), unique=True, nullable=False, index=True)
    expires_at = Column(DateTime(timezone=True), nullable=False)
    is_revoked = Column(Boolean, default=False, nullable=False)
    revoked_at = Column(DateTime(timezone=True), nullable=True)

    # Relationships
    user = relationship("User", back_populates="refresh_tokens")


class AuditLog(Base):
    __tablename__ = "audit_logs"

    id = Column(String(64), primary_key=True, default=lambda: f"AUD-{uuid.uuid4().hex[:12].upper()}")
    event_type = Column(String(64), nullable=False, index=True)
    severity = Column(String(32), default=AuditSeverity.INFO.value, nullable=False)
    user_id = Column(String(64), nullable=True, index=True)
    shop_id = Column(String(64), nullable=True, index=True)
    job_id = Column(String(64), nullable=True, index=True)
    details = Column(Text, nullable=False)
    ip_address = Column(String(64), nullable=True)
    created_at = Column(
        DateTime(timezone=True),
        default=lambda: datetime.now(timezone.utc),
        nullable=False,
        index=True
    )
