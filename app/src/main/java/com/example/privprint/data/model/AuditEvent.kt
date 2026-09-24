package com.example.privprint.data.model

enum class AuditSeverity {
    INFO,
    WARNING,
    SECURITY_ALERT
}

enum class AuditEventType(val title: String) {
    SESSION_CREATED("Session Created"),
    QR_SCANNED("Shop QR Scanned"),
    DOCUMENT_SELECTED("Document Selected"),
    JOB_ENCRYPTED("Payload Encrypted (AES-256-GCM)"),
    PRINT_QUEUED("Print Job Queued"),
    PRINT_STARTED("Printing Started"),
    PAGE_PRINTED("Page Output Verified"),
    COPY_CONSUMED("Copy Allowance Incremented"),
    COPY_LIMIT_ENFORCED("Unauthorized Copy Blocked"),
    PRINT_COMPLETED("Print Job Completed"),
    PRINT_FAILED("Print Failure Logged"),
    SESSION_REVOKED("Session Revoked by User"),
    SHREDDER_TRIGGERED("Ephemeral Key Shredded");
}

data class AuditEvent(
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val eventType: AuditEventType,
    val jobId: String? = null,
    val shopId: String? = null,
    val details: String,
    val severity: AuditSeverity = AuditSeverity.INFO
)
