package com.example.privprint.data.repository

import com.example.privprint.data.api.ApiClient
import com.example.privprint.data.api.models.CleanupStatusDto
import com.example.privprint.data.api.models.CompleteUploadRequest
import com.example.privprint.data.api.models.CreateJobRequest
import com.example.privprint.data.api.models.CreateSessionRequest
import com.example.privprint.data.api.models.InitUploadRequest
import com.example.privprint.data.api.models.LoginRequest
import com.example.privprint.data.api.models.RegisterRequest
import com.example.privprint.data.api.models.ShopCreateRequest
import com.example.privprint.data.api.models.PhoneOtpRequest
import com.example.privprint.data.api.models.PhoneOtpVerifyRequest
import com.example.privprint.data.api.models.OtpRequestResponse
import com.example.privprint.data.api.models.NearbyShopDto
import com.example.privprint.data.api.models.StationPrintKeyDto
import com.example.privprint.data.api.models.UserRole
import com.example.privprint.data.auth.AuthResult
import com.example.privprint.data.auth.AuthTokenManager
import com.example.privprint.data.auth.AuthenticatedUser
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLDecoder
import java.io.IOException
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

    private fun <T : Any> requireApiBody(
        response: retrofit2.Response<T>,
        operation: String
    ): T {
        if (!response.isSuccessful) {
            val serverMessage = response.errorBody()?.string()?.take(400)
            throw IOException(
                if (serverMessage.isNullOrBlank()) "$operation failed (HTTP ${response.code()})."
                else "$operation failed (HTTP ${response.code()}): $serverMessage"
            )
        }
        return response.body() ?: throw IOException("$operation returned an empty response.")
    }

    private fun requireApiSuccess(response: retrofit2.Response<*>, operation: String) {
        if (!response.isSuccessful) {
            val serverMessage = response.errorBody()?.string()?.take(400)
            throw IOException(
                if (serverMessage.isNullOrBlank()) "$operation failed (HTTP ${response.code()})."
                else "$operation failed (HTTP ${response.code()}): $serverMessage"
            )
        }
    }

    init {
        ApiClient.init(authTokenManager)
    }

    suspend fun requestPhoneOtp(
        phoneNumber: String,
        role: UserRole = UserRole.USER,
        shopId: String? = null
    ): OtpRequestResult {
        return try {
            val response = ApiClient.apiService.requestPhoneOtp(
                PhoneOtpRequest(phoneNumber, role, shopId)
            )
            if (!response.isSuccessful || response.body() == null) {
                val errorBody = response.errorBody()?.string()
                OtpRequestResult.Failure(
                    apiErrorMessage(errorBody, "OTP request failed")
                )
            } else {
                OtpRequestResult.Sent(response.body()!!)
            }
        } catch (e: Exception) {
            OtpRequestResult.Failure(e.message ?: "Network error")
        }
    }

    suspend fun verifyPhoneOtp(
        phoneNumber: String,
        otp: String,
        role: UserRole = UserRole.USER,
        shopId: String? = null
    ): AuthResult {
        return try {
            val response = ApiClient.apiService.verifyPhoneOtp(
                PhoneOtpVerifyRequest(phoneNumber, otp, role, shopId)
            )
            if (!response.isSuccessful || response.body() == null) {
                val errorBody = response.errorBody()?.string()
                return AuthResult.Failure(
                    apiErrorMessage(errorBody, "OTP verification failed"),
                    if (response.code() == 409) {
                        "CONFLICT"
                    } else {
                        apiErrorCode(errorBody) ?: "OTP_VERIFY_FAILED"
                    }
                )
            }

            val body = response.body()!!
            val user = AuthenticatedUser(
                body.user.id,
                body.user.role,
                shopId,
                fullName = body.user.fullName,
                email = body.user.email,
                phoneNumber = body.user.phoneNumber
            )
            authTokenManager.saveAuth(user, body.accessToken, body.refreshToken)
            realtimeClient.connect("user:${body.user.id}", body.accessToken)
            AuthResult.Success(user, body.accessToken, body.refreshToken)
        } catch (e: Exception) {
            AuthResult.Failure(e.message ?: "Network error", "NETWORK_ERROR")
        }
    }

    private fun apiErrorMessage(body: String?, fallback: String): String {
        if (body.isNullOrBlank()) return fallback
        val message = runCatching {
            org.json.JSONObject(body)
                .optJSONObject("error")
                ?.optString("message")
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
        return message ?: body.take(300)
    }

    private fun apiErrorCode(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return runCatching {
            org.json.JSONObject(body)
                .optJSONObject("error")
                ?.optString("code")
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    suspend fun loginWithPhoneOtp(
        phoneNumber: String,
        role: UserRole = UserRole.USER,
        shopId: String? = null
    ): AuthResult {
        return when (val request = requestPhoneOtp(phoneNumber, role, shopId)) {
            is OtpRequestResult.Failure ->
                AuthResult.Failure(request.error, "OTP_REQUEST_FAILED")
            is OtpRequestResult.Sent -> {
                val code = request.response.developmentOtp
                    ?: return AuthResult.Failure(
                        "Enter the verification code sent to your phone",
                        "OTP_REQUIRED"
                    )
                verifyPhoneOtp(phoneNumber, code, role, shopId)
            }
        }
    }

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

    sealed class OtpRequestResult {
        data class Sent(val response: OtpRequestResponse) : OtpRequestResult()
        data class Failure(val error: String) : OtpRequestResult()
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
     * Fetches nearby registered Xerox shops from real cloud backend given lat, lng, and radius.
     */
    suspend fun getNearbyShops(
        lat: Double,
        lng: Double,
        radiusKm: Double = 25.0
    ): List<NearbyShopDto> {
        return try {
            val response = ApiClient.apiService.getNearbyShops(lat = lat, lng = lng, radiusKm = radiusKm)
            if (response.isSuccessful && response.body() != null) {
                val shops = response.body()!!
                shops.forEach { s ->
                    dao.insertShop(
                        ShopEntity(
                            id = s.id,
                            name = s.name,
                            address = s.address,
                            permanentQrPayload = if (s.permanentQrPayload.isNotEmpty()) s.permanentQrPayload else "privprint://shop?id=${s.id}",
                            isVerified = s.isVerified,
                            isOnline = s.isOnline,
                            supportedColor = s.supportedColor,
                            supportedDuplex = s.supportedDuplex
                        )
                    )
                }
                shops
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Authenticates a user or shop operator via real API endpoint `/api/v1/auth/login`.
     */
    suspend fun login(
        identity: String,
        secret: String,
        role: UserRole,
        shopName: String? = null
    ): AuthResult {
        return try {
            val response = ApiClient.apiService.login(LoginRequest(email = identity, password = secret))
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                if (body.user.role != role) {
                    return AuthResult.Failure(
                        if (role == UserRole.SHOP_OPERATOR) {
                            "This account is not registered as a Xerox shop operator."
                        } else {
                            "This account is not registered as a customer."
                        },
                        "ROLE_MISMATCH"
                    )
                }

                val authenticatedUser = AuthenticatedUser(
                    userId = body.user.id,
                    role = body.user.role,
                    fullName = body.user.fullName,
                    email = body.user.email,
                    phoneNumber = body.user.phoneNumber
                )
                authTokenManager.saveAuth(authenticatedUser, body.accessToken, body.refreshToken)
                var authenticatedShopName: String? = null
                var shopPendingApproval = false
                val shopId = if (role == UserRole.SHOP_OPERATOR) {
                    val shopsResponse = ApiClient.apiService.getShops()
                    if (!shopsResponse.isSuccessful || shopsResponse.body() == null) {
                        authTokenManager.clearSession()
                        return AuthResult.Failure(
                            "Could not load shops for this operator account.",
                            "SHOP_LOAD_FAILED"
                        )
                    }
                    val shop = shopsResponse.body()!!.firstOrNull()
                        ?: if (!shopName.isNullOrBlank()) {
                            val createResponse = ApiClient.apiService.createShop(
                                bearerToken = "Bearer ${body.accessToken}",
                                request = ShopCreateRequest(
                                    name = shopName.trim(),
                                    address = "Address pending registration"
                                )
                            )
                            if (!createResponse.isSuccessful || createResponse.body() == null) {
                                authTokenManager.clearSession()
                                return AuthResult.Failure(
                                    apiErrorMessage(
                                        createResponse.errorBody()?.string(),
                                        "Could not create the shop for this operator account."
                                    ),
                                    "SHOP_CREATE_FAILED"
                                )
                            }
                            createResponse.body()!!
                        } else {
                            authTokenManager.clearSession()
                            return AuthResult.Failure(
                                "No shop is linked to this operator account. Enter the shop name to set it up.",
                                "SHOP_NOT_LINKED"
                            )
                        }
                    saveShop(
                        Shop(
                            id = shop.id,
                            name = shop.name,
                            address = shop.address,
                            permanentQrPayload = shop.permanentQrPayload,
                            isVerified = shop.isVerified,
                            isOnline = shop.isOnline,
                            supportedColor = shop.supportsColor,
                            supportedDuplex = shop.supportsDuplex
                        )
                    )
                    authenticatedShopName = shop.name
                    shopPendingApproval = shop.status != "ACTIVE" || !shop.isVerified
                    shop.id
                } else {
                    null
                }
                val user = AuthenticatedUser(
                    userId = body.user.id,
                    role = body.user.role,
                    shopId = shopId,
                    fullName = body.user.fullName,
                    email = body.user.email,
                    phoneNumber = body.user.phoneNumber,
                    shopName = authenticatedShopName,
                    shopPendingApproval = shopPendingApproval
                )
                authTokenManager.saveAuth(user, body.accessToken, body.refreshToken)
                realtimeClient.connect("user:${body.user.id}", body.accessToken)
                AuthResult.Success(user, body.accessToken, body.refreshToken)
            } else {
                val errBody = response.errorBody()?.string()
                AuthResult.Failure(apiErrorMessage(errBody, "Invalid email or password."), "AUTH_FAILED")
            }
        } catch (e: Exception) {
            AuthResult.Failure(e.message ?: "Network error", "NETWORK_ERROR")
        }
    }

    suspend fun register(
        email: String,
        password: String,
        fullName: String,
        phoneNumber: String?,
        role: UserRole,
        shopName: String? = null
    ): AuthResult {
        return try {
            val response = ApiClient.apiService.register(
                RegisterRequest(
                    email = email.trim(),
                    password = password,
                    fullName = fullName.trim(),
                    phoneNumber = phoneNumber?.trim()?.takeIf { it.isNotBlank() },
                    role = role
                )
            )
            if (!response.isSuccessful || response.body() == null) {
                return AuthResult.Failure(
                    apiErrorMessage(response.errorBody()?.string(), "Account registration failed."),
                    if (response.code() == 409) "CONFLICT" else "REGISTER_FAILED"
                )
            }

            val body = response.body()!!
            if (body.user.role != role) {
                authTokenManager.clearSession()
                return AuthResult.Failure("The server returned an unexpected account role.", "ROLE_MISMATCH")
            }

            val initialUser = AuthenticatedUser(
                body.user.id,
                body.user.role,
                fullName = body.user.fullName,
                email = body.user.email,
                phoneNumber = body.user.phoneNumber
            )
            authTokenManager.saveAuth(initialUser, body.accessToken, body.refreshToken)
            val shopId = if (role == UserRole.SHOP_OPERATOR) {
                if (shopName.isNullOrBlank()) {
                    authTokenManager.clearSession()
                    return AuthResult.Failure("Enter the shop name.", "SHOP_NAME_REQUIRED")
                }
                val createResponse = ApiClient.apiService.createShop(
                    bearerToken = "Bearer ${body.accessToken}",
                    request = ShopCreateRequest(
                        name = shopName.trim(),
                        address = "Address pending registration"
                    )
                )
                if (!createResponse.isSuccessful || createResponse.body() == null) {
                    authTokenManager.clearSession()
                    return AuthResult.Failure(
                        apiErrorMessage(
                            createResponse.errorBody()?.string(),
                            "Account created, but shop setup failed. Sign in and retry shop setup."
                        ),
                        "SHOP_CREATE_FAILED"
                    )
                }
                val shop = createResponse.body()!!
                saveShop(
                    Shop(
                        id = shop.id,
                        name = shop.name,
                        address = shop.address,
                        permanentQrPayload = shop.permanentQrPayload,
                        isVerified = shop.isVerified,
                        isOnline = shop.isOnline,
                        supportedColor = shop.supportsColor,
                        supportedDuplex = shop.supportsDuplex
                    )
                )
                shop.id
            } else {
                null
            }

            val user = AuthenticatedUser(
                body.user.id,
                body.user.role,
                shopId,
                fullName = body.user.fullName,
                email = body.user.email,
                phoneNumber = body.user.phoneNumber,
                shopName = shopName?.trim(),
                shopPendingApproval = false
            )
            authTokenManager.saveAuth(user, body.accessToken, body.refreshToken)
            realtimeClient.connect("user:${body.user.id}", body.accessToken)
            AuthResult.Success(user, body.accessToken, body.refreshToken)
        } catch (e: Exception) {
            AuthResult.Failure(e.message ?: "Network error", "NETWORK_ERROR")
        }
    }

    /**
     * Logout user and invalidate token.
     */
    suspend fun logout(bearerToken: String?) {
        if (!bearerToken.isNullOrEmpty()) {
            try {
                ApiClient.apiService.logout("Bearer $bearerToken")
            } catch (e: Exception) {
                // Ignore network errors on logout
            }
        }
        authTokenManager.clearSession()
        realtimeClient.disconnect()
    }

    fun parseShopQrPayload(qrString: String): Pair<String, String>? {
        val trimmed = qrString.trim()
        if (trimmed.isEmpty()) return null

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

        if (trimmed.startsWith("privprint://shop/", ignoreCase = true)) {
            val id = trimmed.substringAfter("privprint://shop/").substringBefore("?").substringBefore("/").trim().uppercase()
            if (id.isNotBlank()) return id to defaultShopNameFor(id)
        }

        if (trimmed.startsWith("SHOP-", ignoreCase = true) || trimmed.matches(Regex("^[A-Za-z0-9_-]{3,20}$"))) {
            val id = trimmed.uppercase()
            return id to defaultShopNameFor(id)
        }

        if (trimmed.contains("id=SHOP-", ignoreCase = true)) {
            val idPart = trimmed.substringAfter("id=").substringBefore("&").substringBefore("/").trim().uppercase()
            if (idPart.isNotBlank()) return idPart to defaultShopNameFor(idPart)
        }

        return null
    }

    private fun defaultShopNameFor(shopId: String): String {
        return "PrivPrint Shop ($shopId)"
    }

    /**
     * Parses an ISO-8601 timestamp (server datetime JSON) into epoch millis.
     * Uses java.time on API 26+, with a SimpleDateFormat fallback for older devices.
     */
    private fun parseIsoToMillis(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return runCatching {
            java.time.Instant.parse(iso).toEpochMilli()
        }.getOrElse {
            runCatching {
                val normalized = iso.replace(Regex("\\.\\d+"), "")
                val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US)
                fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
                fmt.parse(normalized)?.time
            }.getOrNull()
        }
    }

    suspend fun getShopPrintKeys(shopId: String): List<StationPrintKeyDto> {
        val token = authTokenManager.getAccessToken()
            ?: throw IOException("Sign in before connecting to a print shop.")
        return requireApiBody(
            ApiClient.apiService.getShopPrintKeys(
                bearerToken = "Bearer $token",
                shopId = shopId
            ),
            "Checking Windows station setup for shop $shopId"
        )
    }

    /**
     * Initiates a verified temporary session with the Xerox shop via `/api/v1/sessions`.
     */
    suspend fun createSession(shopId: String, shopName: String): PrintSession {
        val token = authTokenManager.getAccessToken()
            ?: throw IOException("Sign in before connecting to a print shop.")
        val nonce = UUID.randomUUID().toString()

        val sessionResponse = requireApiBody(
            ApiClient.apiService.createSession(
                bearerToken = "Bearer $token",
                request = CreateSessionRequest(
                    shopId = shopId,
                    pairingNonce = nonce,
                    clientFingerprint = "android_secure_enclave"
                )
            ),
            "Connecting to print shop $shopId"
        )
        val sessionId = sessionResponse.id
        val expiresAt = parseIsoToMillis(sessionResponse.expiresAt)
            ?: System.currentTimeMillis() + (15 * 60 * 1000)
        dao.revokeAllActiveSessions()
        val now = System.currentTimeMillis()

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
     * Uploads an encrypted document and creates an authorized remote print job.
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
        val token = authTokenManager.getAccessToken()
            ?: throw IOException("Sign in before uploading a document to a print station.")
        val stationKeys = getShopPrintKeys(session.shopId)
        if (stationKeys.isEmpty()) {
            throw IOException(
                "Shop ${session.shopId} has no Windows station encryption key registered. " +
                    "Ask the shop to connect its Windows station before sending a file."
            )
        }

        val encryptionResult = try {
            CryptoEngine.encryptDocument(documentBytes)
        } finally {
            CryptoEngine.zeroize(documentBytes)
        }
        try {
            val wrappedKeys = stationKeys.associate { station ->
                station.deviceId to CryptoEngine.wrapDocumentKeyForStation(
                    encryptionResult.ephemeralKey,
                    station.publicKey
                )
            }
            val ivHex = encryptionResult.iv.joinToString("") { "%02x".format(it) }
            val ciphertextSha256 = CryptoEngine.computeSha256Hex(encryptionResult.ciphertext)
            val uploadInfo = requireApiBody(
                ApiClient.apiService.initUpload(
                    bearerToken = "Bearer $token",
                    request = InitUploadRequest(
                        sessionId = session.sessionId,
                        filename = documentName,
                        fileSizeBytes = encryptionResult.ciphertext.size.toLong(),
                        mimeType = "application/pdf",
                        sha256Hash = ciphertextSha256,
                        ivHex = ivHex,
                        keyFingerprint = encryptionResult.keyFingerprint,
                        wrappedKeys = wrappedKeys,
                        copiesAuthorized = settings.copies
                    )
                ),
                "Starting encrypted document upload"
            )

            val requestBody = encryptionResult.ciphertext.toRequestBody("application/octet-stream".toMediaType())
            requireApiSuccess(
                ApiClient.apiService.uploadCiphertextChunk("Bearer $token", uploadInfo.uploadId, requestBody),
                "Uploading encrypted document"
            )
            requireApiBody(
                ApiClient.apiService.completeUpload(
                    bearerToken = "Bearer $token",
                    uploadId = uploadInfo.uploadId,
                    request = CompleteUploadRequest(
                        documentId = uploadInfo.documentId,
                        sessionId = session.sessionId,
                        sha256Hash = ciphertextSha256,
                        fileSizeBytes = encryptionResult.ciphertext.size.toLong()
                    )
                ),
                "Finalizing encrypted document upload"
            )

            val jobKey = idempotencyKey ?: UUID.randomUUID().toString()
            val createdJob = requireApiBody(
                ApiClient.apiService.createJob(
                    bearerToken = "Bearer $token",
                    idempotencyKey = jobKey,
                    request = CreateJobRequest(
                        shopId = session.shopId,
                        sessionId = session.sessionId,
                        documentId = uploadInfo.documentId,
                        requestedCopies = settings.copies,
                        pageCount = pageCount,
                        colorMode = if (settings.colorMode == ColorMode.BLACK_AND_WHITE) "MONOCHROME" else "COLOR",
                        paperSize = settings.paperSize.name,
                        orientation = settings.orientation.name,
                        duplexMode = if (settings.duplexMode == DuplexMode.SINGLE_SIDED) "SIMPLEX" else "DUPLEX",
                        idempotencyKey = jobKey
                    )
                ),
                "Creating print job"
            )
            val authorizedJob = requireApiBody(
                ApiClient.apiService.authorizeJob(
                    bearerToken = "Bearer $token",
                    idempotencyKey = jobKey,
                    jobId = createdJob.jobId
                ),
                "Authorizing print job"
            )
            val jobId = authorizedJob.jobId

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
        } finally {
            encryptionResult.zeroizeKey()
            CryptoEngine.zeroize(encryptionResult.ciphertext)
        }
    }

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
    }

    suspend fun revokeCurrentSession(sessionId: String) {
        val token = authTokenManager.getAccessToken() ?: ""
        if (token.isNotEmpty()) {
            try {
                ApiClient.apiService.revokeSession(
                    bearerToken = "Bearer $token",
                    sessionId = sessionId,
                    request = com.example.privprint.data.api.models.RevokeSessionRequest(reason = "User emergency revocation")
                )
            } catch (e: Exception) {
                // Local fallback
            }
        }
        dao.updateSessionStatus(sessionId, SessionStatus.REVOKED.name)
    }

    suspend fun getCleanupStatus(jobId: String): CleanupStatusDto? {
        return cleanupEngine.getStatus(jobId)
    }

    /**
     * Reconciliation mechanism: Fetches authoritative job state from backend
     * and reconciles local Room database to prevent stale state after network reconnects.
     */
    suspend fun reconcileJobState(jobId: String): PrintJob? {
        val token = authTokenManager.getAccessToken() ?: return dao.getJobById(jobId)?.toDomain()
        return try {
            val response = ApiClient.apiService.getJob("Bearer $token", jobId)
            if (response.isSuccessful && response.body() != null) {
                val remoteJob = response.body()!!
                val localJob = dao.getJobById(jobId)
                if (localJob != null) {
                    val updated = localJob.copy(
                        status = remoteJob.status,
                        copiesPrinted = remoteJob.copiesPrinted,
                        completedAt = if (remoteJob.status == PrintJobStatus.COMPLETED.name)
                            parseIsoToMillis(remoteJob.completedAt) ?: localJob.completedAt
                        else localJob.completedAt
                    )
                    dao.updateJob(updated)
                    updated.toDomain()
                } else null
            } else {
                dao.getJobById(jobId)?.toDomain()
            }
        } catch (e: Exception) {
            dao.getJobById(jobId)?.toDomain()
        }
    }

    suspend fun executeManualCleanup(jobId: String, shopId: String?): CleanupStatusDto {
        return cleanupEngine.executeVerifiedCleanup(jobId, shopId)
    }
}
