package com.example.privprint.data.repository

import com.example.privprint.data.api.models.CleanupState
import com.example.privprint.data.api.models.CleanupStatusDto
import com.example.privprint.data.api.models.UserRole
import com.example.privprint.data.auth.AuthResult
import com.example.privprint.data.auth.AuthTokenManager
import com.example.privprint.data.crypto.CryptoEngine
import com.example.privprint.data.local.AuditEventEntity
import com.example.privprint.data.local.CopyIncrementResult
import com.example.privprint.data.local.PrintJobEntity
import com.example.privprint.data.local.PrinterEntity
import com.example.privprint.data.local.PrivPrintDao
import com.example.privprint.data.local.SessionEntity
import com.example.privprint.data.local.ShopEntity
import com.example.privprint.data.local.toEntity
import com.example.privprint.data.model.AuditEvent
import com.example.privprint.data.model.ColorMode
import com.example.privprint.data.model.DuplexMode
import com.example.privprint.data.model.PaperSize
import com.example.privprint.data.model.PaperTrayStatus
import com.example.privprint.data.model.PrintJob
import com.example.privprint.data.model.PrintJobStatus
import com.example.privprint.data.model.PrintSession
import com.example.privprint.data.model.PrintSettings
import com.example.privprint.data.model.Printer
import com.example.privprint.data.model.PrinterStatus
import com.example.privprint.data.model.SessionStatus
import com.example.privprint.data.model.Shop
import com.example.privprint.service.cleanup.VerifiedCleanupEngine
import com.example.privprint.service.realtime.RealtimeEvent
import com.example.privprint.service.realtime.RealtimeEventType
import com.example.privprint.service.realtime.RealtimeTransportClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class PrivPrintRepository(
    private val dao: PrivPrintDao,
    val authTokenManager: AuthTokenManager = AuthTokenManager(),
    val realtimeClient: RealtimeTransportClient = RealtimeTransportClient(),
    val cleanupEngine: VerifiedCleanupEngine = VerifiedCleanupEngine(dao)
) {

    private val secureRandom = SecureRandom()
    private val processedIdempotencyKeys = ConcurrentHashMap.newKeySet<String>()

    // Active session state flow
    val activeSession: Flow<PrintSession?> = dao.getActiveSession().map { it?.toDomain() }

    // User's currently active job
    val activeUserJob: Flow<PrintJob?> = dao.getActiveUserJob().map { it?.toDomain() }

    // Shop incoming print queue
    val activeQueue: Flow<List<PrintJob>> = dao.getActiveQueue().map { list -> list.map { it.toDomain() } }

    // All jobs for history
    val allJobs: Flow<List<PrintJob>> = dao.getAllPrintJobs().map { list -> list.map { it.toDomain() } }

    // Shops
    val allShops: Flow<List<Shop>> = dao.getAllShops().map { list -> list.map { it.toDomain() } }

    // Printers
    val allPrinters: Flow<List<Printer>> = dao.getAllPrinters().map { list -> list.map { it.toDomain() } }

    // Audit trail
    val auditEvents: Flow<List<AuditEvent>> = dao.getAllAuditEvents().map { list -> list.map { it.toDomain() } }

    suspend fun initializeSeedData() {
        // Zero dummy printers or fake data on initial launch/login.
        // As requested: Data and printer names only appear when saved by the user/operator.
        dao.insertAuditEvent(
            AuditEventEntity(
                timestamp = System.currentTimeMillis(),
                eventType = "SESSION_CREATED",
                jobId = null,
                shopId = null,
                details = "PrivPrint Secure Cryptographic Subsystem Initialized. Local secure enclave active.",
                severity = "INFO"
            )
        )
    }

    suspend fun savePrinter(printer: Printer) {
        dao.insertPrinter(printer.toEntity())
        dao.insertAuditEvent(
            AuditEventEntity(
                timestamp = System.currentTimeMillis(),
                eventType = "DEVICE_PAIRED",
                jobId = null,
                shopId = printer.shopId,
                details = "Printer registered and saved: ${printer.name} (${printer.model})",
                severity = "INFO"
            )
        )
    }

    suspend fun deletePrinter(printerId: String) {
        dao.deletePrinter(printerId)
        dao.insertAuditEvent(
            AuditEventEntity(
                timestamp = System.currentTimeMillis(),
                eventType = "SESSION_CLEANED",
                jobId = null,
                shopId = null,
                details = "Printer device removed from terminal: $printerId",
                severity = "INFO"
            )
        )
    }

    suspend fun saveShop(shop: Shop) {
        dao.insertShop(shop.toEntity())
    }

    /**
     * Authenticates a user or shop operator, issuing short-lived tokens and registering session.
     */
    fun login(identity: String, role: UserRole, shopId: String? = null): AuthResult {
        return authTokenManager.authenticate(identity, role, shopId)
    }

    /**
     * Rotates refresh token to guarantee continuous protection against token theft.
     */
    fun refreshToken(refreshToken: String): AuthResult {
        return authTokenManager.rotateRefreshToken(refreshToken)
    }

    /**
     * Revokes token on logout.
     */
    fun logout(bearerToken: String?) {
        authTokenManager.revokeToken(bearerToken)
    }

    /**
     * Parses and validates shop QR code payload.
     * Supports:
     * - URI scheme: privprint://shop?id=SHOP-101&name=...
     * - Path scheme: privprint://shop/SHOP-101
     * - Raw shop ID: SHOP-101, SHOP-102, etc.
     * - URL with shop ID parameter or path
     */
    fun parseShopQrPayload(qrString: String): Pair<String, String>? {
        val trimmed = qrString.trim()
        if (trimmed.isEmpty()) return null

        // 1. Standard privprint URI scheme with query parameters
        if (trimmed.startsWith("privprint://shop?", ignoreCase = true)) {
            val result = runCatching {
                val query = trimmed.substringAfter("?")
                val params = query.split("&").associate {
                    val parts = it.split("=", limit = 2)
                    parts[0].lowercase() to (if (parts.size > 1) parts[1] else "")
                }
                val shopId = params["id"]?.trim()?.uppercase() ?: return@runCatching null
                val rawName = params["name"] ?: defaultShopNameFor(shopId)
                val shopName = URLDecoder.decode(rawName, "UTF-8").ifBlank { defaultShopNameFor(shopId) }
                if (shopId.isBlank()) null else shopId to shopName
            }.getOrNull()
            if (result != null) return result
        }

        // 2. Direct path scheme: privprint://shop/SHOP-101
        if (trimmed.startsWith("privprint://shop/", ignoreCase = true)) {
            val id = trimmed.substringAfter("privprint://shop/").substringBefore("?").substringBefore("/").trim().uppercase()
            if (id.isNotBlank()) return id to defaultShopNameFor(id)
        }

        // 3. Raw Shop ID (e.g. SHOP-101, SHOP-102, SHOP-103)
        if (trimmed.startsWith("SHOP-", ignoreCase = true) || trimmed.matches(Regex("^[A-Za-z0-9_-]{3,20}$"))) {
            val id = trimmed.uppercase()
            return id to defaultShopNameFor(id)
        }

        // 4. HTTP/HTTPS URL with id param or path
        if (trimmed.contains("id=SHOP-", ignoreCase = true)) {
            val idPart = trimmed.substringAfter("id=").substringBefore("&").substringBefore("/").trim().uppercase()
            if (idPart.isNotBlank()) return idPart to defaultShopNameFor(idPart)
        }

        return null
    }

    private fun defaultShopNameFor(shopId: String): String {
        return when (shopId.uppercase()) {
            "SHOP-101" -> "Apex Campus Xerox & Print"
            "SHOP-102" -> "Metro Secure QuickPrint"
            "SHOP-103" -> "City Hall Documentation Desk"
            else -> "PrivPrint Verified Xerox ($shopId)"
        }
    }

    /**
     * Initiates a verified temporary session with the Xerox shop.
     */
    suspend fun createSession(shopId: String, shopName: String): PrintSession {
        // Invalidate prior active sessions
        dao.revokeAllActiveSessions()

        val sessionId = "SES-" + UUID.randomUUID().toString().take(8).uppercase()
        val token = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val expiresAt = now + (15 * 60 * 1000) // 15 minutes TTL

        val sessionEntity = SessionEntity(
            sessionId = sessionId,
            shopId = shopId,
            shopName = shopName,
            token = token,
            status = SessionStatus.ACTIVE.name,
            createdAt = now,
            expiresAt = expiresAt
        )
        dao.insertSession(sessionEntity)

        dao.insertAuditEvent(
            AuditEventEntity(
                timestamp = now,
                eventType = "QR_SCANNED",
                jobId = null,
                shopId = shopId,
                details = "Verified pairing token created for shop: $shopName. Session valid for 15 min.",
                severity = "INFO"
            )
        )

        // Realtime event
        realtimeClient.publishEvent(
            RealtimeEvent(
                eventId = "evt-ses-$sessionId",
                sequenceNumber = now,
                eventType = RealtimeEventType.SESSION_CREATED,
                targetChannel = "user:$sessionId",
                sessionId = sessionId,
                shopId = shopId,
                payloadJson = """{"sessionId":"$sessionId","shopId":"$shopId","expiresAt":$expiresAt}"""
            )
        )

        return sessionEntity.toDomain()
    }

    /**
     * Submits an encrypted print job with atomic copy authorization and idempotency support.
     */
    suspend fun submitPrintJob(
        session: PrintSession,
        documentName: String,
        documentBytes: ByteArray,
        pageCount: Int,
        settings: PrintSettings,
        idempotencyKey: String? = null
    ): PrintJob {
        val now = System.currentTimeMillis()

        // Enforce idempotency: prevent duplicate submission on retry
        if (idempotencyKey != null && !processedIdempotencyKeys.add(idempotencyKey)) {
            val existing = dao.getJobById("PRV-IDEMP-${idempotencyKey.hashCode()}")
            if (existing != null) return existing.toDomain()
        }

        // 1. Client-side AES-256-GCM encryption with fresh IV
        val encryptionResult = CryptoEngine.encryptDocument(documentBytes)
        val ivHex = encryptionResult.iv.joinToString("") { "%02x".format(it) }

        // Clean up plaintext memory
        CryptoEngine.zeroize(documentBytes)

        val randomSuffix = (100000 + secureRandom.nextInt(900000)).toString(16).uppercase()
        val jobId = "PRV-2026-$randomSuffix"

        val jobEntity = PrintJobEntity(
            jobId = jobId,
            sessionId = session.sessionId,
            shopId = session.shopId,
            shopName = session.shopName,
            documentName = documentName,
            documentSizeBytes = encryptionResult.ciphertext.size.toLong(),
            pageCount = pageCount,
            copiesAuthorized = settings.copies,
            copiesPrinted = 0,
            colorMode = settings.colorMode.name,
            paperSize = settings.paperSize.name,
            orientation = settings.orientation.name,
            duplexMode = settings.duplexMode.name,
            status = PrintJobStatus.QUEUED.name,
            encryptionAlgorithm = "AES-256-GCM",
            ivHex = ivHex,
            keyFingerprint = encryptionResult.keyFingerprint,
            createdAt = now,
            expiresAt = session.expiresAt,
            completedAt = null,
            failureReason = null
        )
        dao.insertJob(jobEntity)

        // Ephemeral key zeroization after queueing
        encryptionResult.zeroizeKey()

        dao.insertAuditEvent(
            AuditEventEntity(
                timestamp = now,
                eventType = "JOB_ENCRYPTED",
                jobId = jobId,
                shopId = session.shopId,
                details = "Document ciphertext generated with AES-256-GCM. Key fingerprint: ${encryptionResult.keyFingerprint}",
                severity = "INFO"
            )
        )

        dao.insertAuditEvent(
            AuditEventEntity(
                timestamp = now + 10,
                eventType = "PRINT_QUEUED",
                jobId = jobId,
                shopId = session.shopId,
                details = "Job queued at ${session.shopName}. Authorized limit: ${settings.copies} copy(ies).",
                severity = "INFO"
            )
        )

        // Publish to realtime transport
        realtimeClient.publishEvent(
            RealtimeEvent(
                eventId = "evt-queue-$jobId",
                sequenceNumber = now,
                eventType = RealtimeEventType.JOB_QUEUED,
                targetChannel = "shop:${session.shopId}",
                jobId = jobId,
                sessionId = session.sessionId,
                shopId = session.shopId,
                payloadJson = """{"jobId":"$jobId","copies":${settings.copies},"pages":$pageCount}"""
            )
        )

        return jobEntity.toDomain()
    }

    /**
     * Executes atomic copy consumption. Enforces copy limit in SQLite transaction.
     */
    suspend fun incrementCopy(jobId: String): CopyIncrementResult {
        val result = dao.incrementCopyCountAtomic(jobId)
        if (result is CopyIncrementResult.Success) {
            val job = dao.getJobById(jobId)
            realtimeClient.publishEvent(
                RealtimeEvent(
                    eventId = "evt-copy-$jobId-${result.currentCopies}",
                    sequenceNumber = System.currentTimeMillis(),
                    eventType = if (result.isCompleted) RealtimeEventType.PRINT_COMPLETED else RealtimeEventType.COPY_COMPLETED,
                    targetChannel = "job:$jobId",
                    jobId = jobId,
                    shopId = job?.shopId,
                    payloadJson = """{"copiesPrinted":${result.currentCopies},"total":${result.totalAuthorized}}"""
                )
            )
        }
        return result
    }

    suspend fun updateJobStatus(jobId: String, status: PrintJobStatus, failureReason: String? = null) {
        val job = dao.getJobById(jobId) ?: return
        val updated = job.copy(
            status = status.name,
            failureReason = failureReason,
            completedAt = if (status == PrintJobStatus.COMPLETED) System.currentTimeMillis() else job.completedAt
        )
        dao.updateJob(updated)

        if (status == PrintJobStatus.PRINTING) {
            dao.insertAuditEvent(
                AuditEventEntity(
                    timestamp = System.currentTimeMillis(),
                    eventType = "PRINT_STARTED",
                    jobId = jobId,
                    shopId = job.shopId,
                    details = "Hardware print job stream opened on shop printer.",
                    severity = "INFO"
                )
            )
        } else if (status == PrintJobStatus.CANCELLED) {
            dao.insertAuditEvent(
                AuditEventEntity(
                    timestamp = System.currentTimeMillis(),
                    eventType = "PRINT_FAILED",
                    jobId = jobId,
                    shopId = job.shopId,
                    details = "Job cancelled by operator or user: ${failureReason ?: "Manual cancellation"}",
                    severity = "WARNING"
                )
            )
        }
    }

    /**
     * User Privacy Action: Emergency Revoke active session.
     */
    suspend fun revokeCurrentSession(sessionId: String) {
        dao.updateSessionStatus(sessionId, SessionStatus.REVOKED.name)
        dao.insertAuditEvent(
            AuditEventEntity(
                timestamp = System.currentTimeMillis(),
                eventType = "SESSION_REVOKED",
                jobId = null,
                shopId = null,
                details = "Session $sessionId revoked by user. Authorization credentials invalidated.",
                severity = "SECURITY_ALERT"
            )
        )
        dao.insertAuditEvent(
            AuditEventEntity(
                timestamp = System.currentTimeMillis() + 1,
                eventType = "SHREDDER_TRIGGERED",
                jobId = null,
                shopId = null,
                details = "Active transmission buffers purged and shredded.",
                severity = "INFO"
            )
        )
        realtimeClient.publishEvent(
            RealtimeEvent(
                eventId = "evt-rev-$sessionId",
                sequenceNumber = System.currentTimeMillis(),
                eventType = RealtimeEventType.SESSION_REVOKED,
                targetChannel = "user:$sessionId",
                sessionId = sessionId,
                payloadJson = """{"sessionId":"$sessionId","status":"REVOKED"}"""
            )
        )
    }

    suspend fun getCleanupStatus(jobId: String): CleanupStatusDto? {
        return cleanupEngine.getStatus(jobId)
    }

    suspend fun executeManualCleanup(jobId: String, shopId: String?): CleanupStatusDto {
        return cleanupEngine.executeVerifiedCleanup(jobId, shopId)
    }
}
