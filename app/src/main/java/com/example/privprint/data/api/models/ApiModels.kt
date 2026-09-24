package com.example.privprint.data.api.models

import com.example.privprint.data.model.ColorMode
import com.example.privprint.data.model.DuplexMode
import com.example.privprint.data.model.Orientation
import com.example.privprint.data.model.PaperSize
import com.example.privprint.data.model.PrintJobStatus

enum class UserRole {
    USER,
    SHOP_OPERATOR,
    PRINT_DEVICE,
    ADMIN
}

enum class ErrorCode {
    AUTH_INVALID,
    AUTH_EXPIRED,
    QR_INVALID,
    QR_EXPIRED,
    SHOP_NOT_FOUND,
    SESSION_EXPIRED,
    SESSION_REVOKED,
    DOCUMENT_INVALID,
    DOCUMENT_TOO_LARGE,
    UPLOAD_FAILED,
    JOB_NOT_FOUND,
    COPY_LIMIT_REACHED,
    PRINTER_OFFLINE,
    PRINTER_ERROR,
    RATE_LIMITED,
    NETWORK_ERROR,
    FORBIDDEN_RESOURCE,
    IDEMPOTENCY_CONFLICT
}

enum class CleanupState {
    CLEANUP_PENDING,
    CLEANUP_RETRY,
    CLEANUP_COMPLETED,
    CLEANUP_FAILED
}

// Auth DTOs
data class LoginRequest(
    val identity: String,
    val role: UserRole,
    val secret: String
)

data class LoginResponse(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
    val expiresInSeconds: Long = 900, // 15 min
    val role: UserRole,
    val userId: String
)

data class RefreshTokenRequest(
    val refreshToken: String
)

data class TokenRevocationRequest(
    val token: String,
    val reason: String = "User logout"
)

// Shop & QR DTOs
data class ShopDto(
    val id: String,
    val name: String,
    val address: String,
    val permanentQrPayload: String,
    val isVerified: Boolean,
    val isOnline: Boolean,
    val supportedColor: Boolean,
    val supportedDuplex: Boolean,
    val activeQueueCount: Int = 0
)

data class PermanentQrResponse(
    val shopId: String,
    val shopName: String,
    val qrPayload: String,
    val generatedAt: Long
)

// Session DTOs
data class CreateSessionRequest(
    val shopId: String,
    val pairingNonce: String,
    val clientFingerprint: String
)

data class SessionResponse(
    val sessionId: String,
    val shopId: String,
    val shopName: String,
    val status: String,
    val createdAt: Long,
    val expiresAt: Long,
    val authorizedCopiesMax: Int = 50
)

data class RevokeSessionRequest(
    val sessionId: String,
    val reason: String = "User emergency revocation"
)

// Document Upload DTOs
data class InitUploadRequest(
    val sessionId: String,
    val filename: String,
    val mimeType: String,
    val ciphertextSizeBytes: Long,
    val sha256Checksum: String,
    val ivHex: String
)

data class InitUploadResponse(
    val uploadId: String,
    val storageObjectId: String,
    val uploadUrl: String,
    val maxSizeBytes: Long = 50 * 1024 * 1024
)

data class CompleteUploadRequest(
    val uploadId: String,
    val storageObjectId: String,
    val actualCiphertextSize: Long,
    val sha256Checksum: String
)

data class CompleteUploadResponse(
    val storageObjectId: String,
    val status: String,
    val isVerified: Boolean
)

// Print Job DTOs
data class CreateJobRequest(
    val sessionId: String,
    val shopId: String,
    val storageObjectId: String,
    val documentName: String,
    val pageCount: Int,
    val copiesAuthorized: Int,
    val colorMode: ColorMode,
    val paperSize: PaperSize,
    val orientation: Orientation,
    val duplexMode: DuplexMode,
    val encryptionAlgorithm: String = "AES-256-GCM",
    val ivHex: String,
    val keyFingerprint: String
)

data class JobResponse(
    val jobId: String,
    val sessionId: String,
    val shopId: String,
    val shopName: String,
    val documentName: String,
    val pageCount: Int,
    val copiesAuthorized: Int,
    val copiesPrinted: Int,
    val status: PrintJobStatus,
    val cleanupState: CleanupState,
    val createdAt: Long,
    val expiresAt: Long
)

data class IncrementCopyResponse(
    val jobId: String,
    val copiesPrinted: Int,
    val copiesAuthorized: Int,
    val isCompleted: Boolean,
    val status: String
)

data class CleanupStatusDto(
    val jobId: String,
    val state: CleanupState,
    val storageDeleted: Boolean,
    val memoryPurged: Boolean,
    val attempts: Int,
    val timestamp: Long
)

data class HealthResponse(
    val status: String = "healthy",
    val apiVersion: String = "v1",
    val timestamp: Long = System.currentTimeMillis()
)
