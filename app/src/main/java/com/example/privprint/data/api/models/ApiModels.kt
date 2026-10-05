package com.example.privprint.data.api.models

import com.example.privprint.data.model.ColorMode
import com.example.privprint.data.model.DuplexMode
import com.example.privprint.data.model.Orientation
import com.example.privprint.data.model.PaperSize
import com.example.privprint.data.model.PrintJobStatus
import com.squareup.moshi.Json

/**
 * Canonical client DTOs for the PrivPrint `/api/v1` contract.
 *
 * The server serializes snake_case JSON; every field that deviates from the
 * server key is annotated with @Json so Moshi maps it exactly. Nullable
 * fields with defaults keep parsing tolerant across minor server versions.
 */
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

// Auth DTOs (server: UserLoginRequest {email, password})
data class LoginRequest(
    val email: String,
    val password: String
)

data class RegisterRequest(
    val email: String,
    val password: String,
    @Json(name = "full_name") val fullName: String,
    @Json(name = "phone_number") val phoneNumber: String? = null,
    val role: UserRole = UserRole.USER
)

data class ShopCreateRequest(
    val name: String,
    val address: String
)

data class LoginResponse(
    @Json(name = "access_token") val accessToken: String,
    @Json(name = "refresh_token") val refreshToken: String,
    @Json(name = "token_type") val tokenType: String = "Bearer",
    @Json(name = "expires_in") val expiresInSeconds: Long = 900,
    val user: ApiUser
)

data class ApiUser(
    val id: String,
    val email: String,
    @Json(name = "full_name") val fullName: String?,
    @Json(name = "phone_number") val phoneNumber: String?,
    val role: UserRole
)

data class PhoneOtpRequest(
    @Json(name = "phone_number") val phoneNumber: String,
    val role: UserRole = UserRole.USER,
    @Json(name = "shop_id") val shopId: String? = null
)

data class PhoneOtpVerifyRequest(
    @Json(name = "phone_number") val phoneNumber: String,
    val otp: String,
    val role: UserRole = UserRole.USER,
    @Json(name = "shop_id") val shopId: String? = null
)

data class OtpRequestResponse(
    val message: String,
    @Json(name = "expires_in") val expiresIn: Int,
    @Json(name = "development_otp") val developmentOtp: String?
)

data class TokenResponse(
    @Json(name = "access_token") val accessToken: String,
    @Json(name = "refresh_token") val refreshToken: String,
    val user: ApiUser
)

data class RefreshTokenRequest(
    @Json(name = "refresh_token") val refreshToken: String
)

data class TokenRevocationRequest(
    val token: String? = null,
    @Json(name = "all_sessions") val allSessions: Boolean = false
)

enum class EnvironmentMode(val label: String, val baseUrl: String) {
    DEVELOPMENT("Development (Local Docker)", "http://10.0.2.2:8080/"),
    STAGING("Staging Cloud", "https://staging-api.privprint.com/"),
    PRODUCTION("Production Cloud (HTTPS)", "https://ais-dev-6u62dc37mqabbjyehi6umo-408539472511.asia-southeast1.run.app/")
}

// Shop & QR DTOs (server: ShopResponse)
data class ShopDto(
    val id: String,
    val name: String,
    val address: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val status: String = "ACTIVE",
    @Json(name = "is_verified") val isVerified: Boolean = true,
    @Json(name = "is_online") val isOnline: Boolean = true,
    @Json(name = "supports_color") val supportsColor: Boolean = true,
    @Json(name = "supports_duplex") val supportsDuplex: Boolean = true,
    @Json(name = "permanent_qr_payload") val permanentQrPayload: String = ""
)

data class NearbyShopDto(
    @com.squareup.moshi.Json(name = "shop_id") val shopId: String? = null,
    @com.squareup.moshi.Json(name = "id") val rawId: String? = null,
    val name: String = "",
    val address: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    @com.squareup.moshi.Json(name = "distance_km") val distanceKm: Double = 0.0,
    val status: String = "ACTIVE",
    val isOnline: Boolean = true,
    val isVerified: Boolean = true,
    val supportedColor: Boolean = true,
    val supportedDuplex: Boolean = true,
    val activeQueueCount: Int = 0,
    val permanentQrPayload: String = ""
) {
    val id: String get() = shopId ?: rawId ?: ""
}

// Server: PermanentQrResponse {shop_id, qr_payload, format}
data class PermanentQrResponse(
    @Json(name = "shop_id") val shopId: String,
    @Json(name = "qr_payload") val qrPayload: String,
    val format: String = "privprint://shop?id={shop_id}"
)

// Session DTOs (server: SessionCreateRequest {shop_id} / SessionResponse)
data class CreateSessionRequest(
    @Json(name = "shop_id") val shopId: String,
    @Json(name = "pairing_nonce") val pairingNonce: String? = null,
    @Json(name = "client_fingerprint") val clientFingerprint: String? = null
)

data class SessionResponse(
    @Json(name = "id") val id: String,
    @Json(name = "shop_id") val shopId: String,
    val token: String? = null,
    val nonce: String? = null,
    val status: String = "ACTIVE",
    @Json(name = "created_at") val createdAt: String? = null,
    @Json(name = "expires_at") val expiresAt: String? = null
)

// Server: SessionRevokeRequest {reason}
data class RevokeSessionRequest(
    val reason: String? = null
)

// Document Upload DTOs (server: InitUploadRequest)
data class InitUploadRequest(
    @Json(name = "session_id") val sessionId: String,
    val filename: String,
    @Json(name = "file_size_bytes") val fileSizeBytes: Long,
    @Json(name = "mime_type") val mimeType: String = "application/pdf",
    @Json(name = "sha256_hash") val sha256Hash: String,
    @Json(name = "iv_hex") val ivHex: String,
    @Json(name = "key_fingerprint") val keyFingerprint: String,
    @Json(name = "wrapped_keys") val wrappedKeys: Map<String, String> = emptyMap(),
    @Json(name = "copies_authorized") val copiesAuthorized: Int = 1
)

data class StationPrintKeyDto(
    @Json(name = "device_id") val deviceId: String,
    @Json(name = "public_key") val publicKey: String
)

// Server: InitUploadResponse
data class InitUploadResponse(
    @Json(name = "upload_id") val uploadId: String,
    @Json(name = "document_id") val documentId: String,
    @Json(name = "storage_path") val storagePath: String? = null,
    @Json(name = "presigned_upload_url") val presignedUploadUrl: String? = null,
    @Json(name = "expires_in_seconds") val expiresInSeconds: Int = 300
)

// Server: CompleteUploadRequest
data class CompleteUploadRequest(
    @Json(name = "document_id") val documentId: String,
    @Json(name = "session_id") val sessionId: String,
    @Json(name = "sha256_hash") val sha256Hash: String? = null,
    @Json(name = "file_size_bytes") val fileSizeBytes: Long? = null
)

// Server responds with a full DocumentResponse; these are the fields the client consumes.
data class CompleteUploadResponse(
    val id: String,
    val filename: String? = null,
    @Json(name = "file_size_bytes") val fileSizeBytes: Long? = null,
    @Json(name = "cleanup_state") val cleanupState: String = "PENDING",
    @Json(name = "expires_at") val expiresAt: String? = null,
    @Json(name = "created_at") val createdAt: String? = null
)

// Print Job DTOs (server: JobCreateRequest)
data class CreateJobRequest(
    @Json(name = "shop_id") val shopId: String,
    @Json(name = "session_id") val sessionId: String,
    @Json(name = "document_id") val documentId: String,
    @Json(name = "printer_id") val printerId: String? = null,
    @Json(name = "requested_copies") val requestedCopies: Int = 1,
    @Json(name = "page_count") val pageCount: Int = 1,
    @Json(name = "color_mode") val colorMode: String = "MONOCHROME",
    @Json(name = "paper_size") val paperSize: String = "A4",
    val orientation: String = "PORTRAIT",
    @Json(name = "duplex_mode") val duplexMode: String = "SIMPLEX",
    @Json(name = "idempotency_key") val idempotencyKey: String? = null
)

// Server: JobResponse (snake_case). Compatibility aliases preserve the
// historical client-facing property names (jobId, copiesAuthorized, ...).
data class JobResponse(
    @Json(name = "id") val id: String,
    @Json(name = "user_id") val userId: String? = null,
    @Json(name = "shop_id") val shopId: String,
    @Json(name = "session_id") val sessionId: String,
    @Json(name = "document_id") val documentId: String? = null,
    @Json(name = "printer_id") val printerId: String? = null,
    @Json(name = "page_count") val pageCount: Int = 1,
    @Json(name = "requested_copies") val requestedCopies: Int = 1,
    @Json(name = "completed_copies") val completedCopies: Int = 0,
    val status: String = "CREATED",
    @Json(name = "color_mode") val colorMode: String? = null,
    @Json(name = "paper_size") val paperSize: String? = null,
    val orientation: String? = null,
    @Json(name = "duplex_mode") val duplexMode: String? = null,
    @Json(name = "failure_reason") val failureReason: String? = null,
    @Json(name = "idempotency_key") val idempotencyKey: String? = null,
    @Json(name = "created_at") val createdAt: String? = null,
    @Json(name = "updated_at") val updatedAt: String? = null,
    @Json(name = "completed_at") val completedAt: String? = null,
    @Json(name = "expires_at") val expiresAt: String? = null
) {
    val jobId: String get() = id
    val copiesAuthorized: Int get() = requestedCopies
    val copiesPrinted: Int get() = completedCopies
}

// Server replies to increment-copy with the full job payload.
data class IncrementCopyResponse(
    @Json(name = "id") val id: String,
    @Json(name = "requested_copies") val requestedCopies: Int = 1,
    @Json(name = "completed_copies") val completedCopies: Int = 0,
    val status: String = "PRINTING"
) {
    val jobId: String get() = id
    val copiesPrinted: Int get() = completedCopies
    val copiesAuthorized: Int get() = requestedCopies
    val isCompleted: Boolean get() = completedCopies >= requestedCopies
}

data class CleanupStatusDto(
    val jobId: String,
    val state: CleanupState,
    val storageDeleted: Boolean,
    val memoryPurged: Boolean,
    val attempts: Int,
    val timestamp: Long
)

// Server: PrinterResponse (returned by /shops/{id}/printers and /printers/sync)
data class PrinterDto(
    val id: String,
    @Json(name = "shop_id") val shopId: String,
    val name: String,
    val model: String = "Generic Printer",
    @Json(name = "driver_name") val driverName: String? = null,
    @Json(name = "connection_info") val connectionInfo: String? = null,
    val status: String = "READY",
    @Json(name = "is_default") val isDefault: Boolean = false,
    @Json(name = "is_online") val isOnline: Boolean = true,
    @Json(name = "supports_color") val supportsColor: Boolean = true,
    @Json(name = "supports_duplex") val supportsDuplex: Boolean = true,
    @Json(name = "supported_paper_sizes") val supportedPaperSizes: String? = null,
    @Json(name = "paper_tray_status") val paperTrayStatus: String = "READY",
    @Json(name = "toner_level_percent") val tonerLevelPercent: Int = 100
)

data class HealthResponse(
    val status: String = "healthy",
    val apiVersion: String = "v1",
    val timestamp: Long = System.currentTimeMillis()
)
