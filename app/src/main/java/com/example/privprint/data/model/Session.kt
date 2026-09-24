package com.example.privprint.data.model

enum class SessionStatus {
    ACTIVE,
    REVOKED,
    EXPIRED,
    COMPLETED
}

data class PrintSession(
    val sessionId: String,
    val shopId: String,
    val shopName: String,
    val token: String,
    val status: SessionStatus,
    val createdAt: Long,
    val expiresAt: Long
) {
    val isExpired: Boolean
        get() = System.currentTimeMillis() > expiresAt || status != SessionStatus.ACTIVE

    val remainingMinutes: Int
        get() {
            val remainingMs = expiresAt - System.currentTimeMillis()
            return if (remainingMs > 0) (remainingMs / 60000).toInt() else 0
        }
}
