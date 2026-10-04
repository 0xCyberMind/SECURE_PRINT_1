from enum import Enum


class UserRole(str, Enum):
    USER = "USER"
    SHOP_OPERATOR = "SHOP_OPERATOR"
    PRINT_DEVICE = "PRINT_DEVICE"
    ADMIN = "ADMIN"


class SessionStatus(str, Enum):
    ACTIVE = "ACTIVE"
    REVOKED = "REVOKED"
    EXPIRED = "EXPIRED"


class PrintJobStatus(str, Enum):
    CREATED = "CREATED"
    QUEUED = "QUEUED"
    AUTHORIZED = "AUTHORIZED"
    PRINTING = "PRINTING"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"
    CANCELLED = "CANCELLED"
    EXPIRED = "EXPIRED"


class CleanupState(str, Enum):
    PENDING = "PENDING"
    VERIFIED = "VERIFIED"
    SHREDDED = "SHREDDED"


class AuditSeverity(str, Enum):
    INFO = "INFO"
    WARNING = "WARNING"
    SECURITY_ALERT = "SECURITY_ALERT"
