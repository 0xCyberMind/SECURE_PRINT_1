package com.privprint.windows

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.jna.platform.win32.Crypt32Util
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.apache.pdfbox.Loader
import org.apache.pdfbox.printing.PDFPageable
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.rendering.ImageType
import java.io.IOException
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec
import javax.print.PrintService
import javax.print.PrintServiceLookup
import javax.print.attribute.HashPrintRequestAttributeSet
import javax.print.attribute.standard.Copies
import javax.print.attribute.standard.MediaSizeName
import javax.imageio.ImageIO
import java.awt.print.PrinterJob
import java.awt.RenderingHints

data class ShopOption(val id: String, val name: String)

data class ShopDetails(
    val id: String,
    val name: String,
    val address: String,
    val permanentQrPayload: String,
    val verified: Boolean,
)

data class PrinterStatus(
    val name: String,
    val model: String,
    val status: String,
    val paper: String,
    val toner: String,
    val isDefault: Boolean = false,
    val supportsColor: Boolean = true,
    val supportsDuplex: Boolean = true,
)

data class PrintJobStatus(
    val id: String,
    val status: String,
    val createdAt: String,
    val copies: String,
    val failureReason: String = "",
    val pageCount: Int = 1,
    val colorMode: String = "MONOCHROME",
    val paperSize: String = "A4",
    val duplexMode: String = "SIMPLEX",
    val documentId: String = "",
    val documentName: String = "Document",
    val batchId: String? = null,
    val fileIndex: Int = 0,
    val totalFiles: Int = 1,
    val pagesPrinted: Int = 0,
)

data class DocumentPreview(
    val jobId: String,
    val documentId: String,
    val filename: String,
    val pageCount: Int,
    val pages: List<java.awt.image.BufferedImage>,
)

data class PrintJobDetail(
    val id: String,
    val status: String,
    val createdAt: String,
    val requestedCopies: Int,
    val documentName: String,
    val pageCount: Int,
    val colorMode: String,
    val paperSize: String,
    val duplex: String,
    val selectedPrinter: String,
    val failureReason: String,
    val batchId: String? = null,
    val fileIndex: Int = 0,
    val totalFiles: Int = 1,
    val pagesPrinted: Int = 0,
)

data class AuditEvent(
    val timestamp: String,
    val eventType: String,
    val details: String,
    val severity: String,
)

data class StationStatus(
    val authenticated: Boolean = false,
    val shopId: String = "",
    val deviceId: String = "",
    val serverUrl: String = "",
    val encryptionKeyRegistered: Boolean = false,
    val realtimeConnected: Boolean = false,
    val connectionState: String = "DISCONNECTED",
    val connectionError: String = "",
    val workerExecutable: String = "",
    val autoPrintEnabled: Boolean = false,
    val printers: List<PrinterStatus> = emptyList(),
    val auditLog: List<AuditEvent> = emptyList(),
    val activeJobCount: Int = 0,
    val completedJobCount: Int = 0,
)

private data class StoredStationCredentials(
    val deviceId: String = "",
    val deviceApiKey: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val shopId: String = "",
    val privateKey: String = "",
    val publicKey: String = "",
    val autoPrintEnabled: Boolean = false,
)

private data class LocalPrinter(
    val id: String,
    val name: String,
    val model: String,
    val service: PrintService,
    val isDefault: Boolean,
    val isOnline: Boolean = true,
    val status: String = "READY",
    val supportsColor: Boolean = true,
    val supportsDuplex: Boolean = true,
    val supportedPaperSizes: String = "A4, Letter, Legal",
    val queuedJobCount: Int = 0,
)

class StationBridge {
    private val gson = Gson()
    private val baseUrl = "https://secure-print-1.onrender.com"
    private val client = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(10))
        .readTimeout(Duration.ofSeconds(30))
        .callTimeout(Duration.ofSeconds(45))
        .build()
    private val stateFile: Path = Path.of(
        System.getenv("LOCALAPPDATA") ?: throw IOException("Windows user profile storage is unavailable."),
        "PrivPrintStation",
        "station-credentials.bin",
    )
    private val legacyCredentialsFile = stateFile.resolveSibling(".secure_credentials.dat")
    private val legacyConfigFile = stateFile.resolveSibling("shop_station_config.json")
    private val background: ScheduledExecutorService = Executors.newScheduledThreadPool(2)
    private val printExecutor = Executors.newCachedThreadPool()
    private val processingJobs = ConcurrentHashMap.newKeySet<String>()
    private val cachedDocumentNames = ConcurrentHashMap<String, String>()
    private val backgroundLoopsStarted = java.util.concurrent.atomic.AtomicBoolean(false)
    private val auditEvents = mutableListOf<AuditEvent>()
    private val operatorLock = Any()
    private var operatorToken: String? = null
    private var credentials: StoredStationCredentials? = null
    private var privateKey: java.security.PrivateKey? = null
    private var realtime: WebSocket? = null
    @Volatile private var realtimeDesired = false
    @Volatile private var connected = false
    @Volatile private var connectionState = "DISCONNECTED"
    @Volatile private var connectionError = ""
    @Volatile private var keyRegistered = false
    @Volatile private var lastPrinters = emptyList<PrinterStatus>()
    @Volatile private var lastJobCount = 0

    fun start() {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            throw IOException("PrivPrint Shop Station requires Windows.")
        }
        Files.createDirectories(stateFile.parent)
        credentials = loadCredentials()
        credentials?.takeIf { it.deviceId.isNotBlank() && it.accessToken.isNotBlank() }?.let { saved ->
            privateKey = decodePrivateKey(saved.privateKey)
            refreshStationTokenIfNeeded()
            registerPrintKey()
            openRealtime()
            startBackgroundLoops()
            runCatching { refreshPrinters() }.onFailure {
                recordAudit("PRINTER_SYNC_FAILED", it.message ?: "Could not sync Windows printers.", "WARNING")
            }
        }
    }

    fun login(email: String, password: String): List<ShopOption> {
        val response = post("/api/v1/auth/login", jsonObject(
            "email" to email.trim(),
            "password" to password,
        ), authenticated = false)
        return selectOperatorShops(response)
    }

    fun register(
        name: String,
        email: String,
        password: String,
        shopName: String,
        shopAddress: String,
    ): List<ShopOption> {
        val registration = post(
            "/api/v1/auth/register",
            jsonObject(
                "full_name" to name.trim(),
                "email" to email.trim(),
                "password" to password,
                "role" to "SHOP_OPERATOR",
            ),
            authenticated = false,
        )
        val token = registration.stringOrEmpty("access_token")
        requireOperator(registration)
        if (token.isBlank()) throw IOException("Cloud login response did not include an access token.")
        operatorToken = token
        val shop = post(
            "/api/v1/shops",
            jsonObject("name" to shopName.trim(), "address" to shopAddress.trim()),
            bearerToken = token,
        )
        val shopId = shop.stringOrEmpty("id")
        if (shopId.isBlank()) throw IOException("The cloud did not return the new shop ID.")
        return listOf(ShopOption(shopId, shop.stringOrEmpty("name").ifBlank { "Xerox shop" }))
    }

    fun connect(shopId: String) {
        val operator = operatorToken ?: throw IOException("Sign in to your shop operator account before connecting this station.")
        val previous = credentials
        val deviceId: String
        val apiKey: String
        if (previous?.deviceId?.isNotBlank() == true) {
            if (previous.shopId != shopId) {
                throw IOException(
                    "This Windows station is already registered to shop ${previous.shopId}. " +
                        "Remove its old station record before connecting it to a different shop."
                )
            }
            deviceId = previous.deviceId
            apiKey = previous.deviceApiKey
            privateKey = decodePrivateKey(previous.privateKey)
        } else {
            val registered = post(
                "/api/v1/devices/register",
                jsonObject(
                    "shop_id" to shopId,
                    "name" to "PrivPrint Windows Station",
                    "os_info" to "${System.getProperty("os.name")} ${System.getProperty("os.version")}",
                    "app_version" to "1.0.4",
                ),
                bearerToken = operator,
            )
            deviceId = registered.stringOrEmpty("device_id")
            apiKey = registered.stringOrEmpty("api_key")
            if (deviceId.isBlank() || apiKey.isBlank()) {
                throw IOException("The cloud did not return complete station credentials.")
            }
        }
        val auth = post(
            "/api/v1/devices/authenticate",
            jsonObject("device_id" to deviceId, "api_key" to apiKey),
            authenticated = false,
        )
        val accessToken = auth.stringOrEmpty("access_token").ifBlank { auth.stringOrEmpty("accessToken") }
        val refreshToken = auth.stringOrEmpty("refresh_token").ifBlank { auth.stringOrEmpty("refreshToken") }
        if (accessToken.isBlank() || refreshToken.isBlank()) {
            throw IOException("The cloud did not return a complete station token pair.")
        }
        val keyPair = if (previous == null) {
            KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
        } else {
            null
        }
        keyPair?.let { privateKey = it.private }
        credentials = StoredStationCredentials(
            deviceId = deviceId,
            deviceApiKey = apiKey,
            accessToken = accessToken,
            refreshToken = refreshToken,
            shopId = shopId,
            privateKey = previous?.privateKey
                ?: Base64.getEncoder().encodeToString(keyPair!!.private.encoded),
            publicKey = previous?.publicKey
                ?: Base64.getEncoder().encodeToString(keyPair!!.public.encoded),
            autoPrintEnabled = previous?.autoPrintEnabled ?: true,
        )
        saveCredentials()
        registerPrintKey()
        refreshPrinters()
        operatorToken = null
        openRealtime()
        startBackgroundLoops()
        recordAudit("STATION_CONNECTED", "Connected directly to the PrivPrint cloud.")
    }

    fun shopDetails(): ShopDetails {
        val saved = requireCredentials()
        val shop = get("/api/v1/shops/${saved.shopId}")
        val qr = if (shop.stringOrEmpty("permanent_qr_payload").isBlank()) {
            get("/api/v1/shops/${saved.shopId}/permanent-qr").stringOrEmpty("qr_payload")
        } else {
            shop.stringOrEmpty("permanent_qr_payload")
        }
        return ShopDetails(
            id = shop.stringOrEmpty("id"),
            name = shop.stringOrEmpty("name"),
            address = shop.stringOrEmpty("address"),
            permanentQrPayload = qr,
            verified = shop.get("is_verified")?.asBoolean ?: false,
        )
    }

    fun setAutoPrintEnabled(enabled: Boolean) {
        val saved = requireCredentials().copy(autoPrintEnabled = enabled)
        credentials = saved
        saveCredentials()
        if (enabled) pollAndProcessAuthorizedJobs()
    }

    fun status(): StationStatus {
        val saved = credentials
        return StationStatus(
            authenticated = saved != null && saved.deviceId.isNotBlank() && saved.accessToken.isNotBlank(),
            shopId = saved?.shopId.orEmpty(),
            deviceId = saved?.deviceId.orEmpty(),
            serverUrl = baseUrl,
            encryptionKeyRegistered = keyRegistered,
            realtimeConnected = connected,
            connectionState = connectionState,
            connectionError = connectionError,
            workerExecutable = "",
            autoPrintEnabled = saved?.autoPrintEnabled ?: true,
            printers = lastPrinters,
            auditLog = synchronized(auditEvents) { auditEvents.reversed().toList() },
            activeJobCount = processingJobs.size,
            completedJobCount = lastJobCount,
        )
    }

    fun queue(): List<PrintJobStatus> {
        val saved = requireCredentials()
        val jobs = getArray("/api/v1/print/jobs?shopId=${encodeQuery(saved.shopId)}")
        maybeProcessAuthorizedJobs(jobs)
        return jobs.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val job = element.asJsonObject
            val id = job.stringOrEmpty("id")
            if (id.isBlank()) return@mapNotNull null
            val docId = job.stringOrEmpty("document_id")
            val docName = cachedDocumentNames.computeIfAbsent(docId) { dId ->
                if (dId.isBlank()) "Document"
                else runCatching {
                    get("/api/v1/documents/${encodePath(dId)}").stringOrEmpty("filename").ifBlank { "Document" }
                }.getOrDefault("Document")
            }
            PrintJobStatus(
                id = id,
                status = job.stringOrEmpty("status"),
                createdAt = job.stringOrEmpty("created_at"),
                copies = job.get("requested_copies")?.asString.orEmpty().ifBlank { "1" },
                failureReason = job.stringOrEmpty("failure_reason"),
                pageCount = job.get("page_count")?.asInt ?: 1,
                colorMode = job.stringOrEmpty("color_mode").ifBlank { "MONOCHROME" },
                paperSize = job.stringOrEmpty("paper_size").ifBlank { "A4" },
                duplexMode = job.stringOrEmpty("duplex_mode").ifBlank { "SIMPLEX" },
                documentId = docId,
                documentName = docName,
                batchId = job.stringOrEmpty("batch_id").ifBlank { null },
                fileIndex = job.get("file_index")?.asInt ?: 0,
                totalFiles = job.get("total_files")?.asInt ?: 1,
                pagesPrinted = job.get("pages_printed")?.asInt ?: 0,
            )
        }.also { rows ->
            lastJobCount = rows.count { it.status == "COMPLETED" }
        }
    }

    fun batchJobs(batchId: String): List<PrintJobStatus> {
        val jobs = getArray("/api/v1/jobs/batch/${encodePath(batchId)}")
        return jobs.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val job = element.asJsonObject
            val id = job.stringOrEmpty("id")
            if (id.isBlank()) return@mapNotNull null
            val docId = job.stringOrEmpty("document_id")
            val docName = cachedDocumentNames.computeIfAbsent(docId) { dId ->
                if (dId.isBlank()) "Document"
                else runCatching {
                    get("/api/v1/documents/${encodePath(dId)}").stringOrEmpty("filename").ifBlank { "Document" }
                }.getOrDefault("Document")
            }
            PrintJobStatus(
                id = id,
                status = job.stringOrEmpty("status"),
                createdAt = job.stringOrEmpty("created_at"),
                copies = job.get("requested_copies")?.asString.orEmpty().ifBlank { "1" },
                failureReason = job.stringOrEmpty("failure_reason"),
                pageCount = job.get("page_count")?.asInt ?: 1,
                colorMode = job.stringOrEmpty("color_mode").ifBlank { "MONOCHROME" },
                paperSize = job.stringOrEmpty("paper_size").ifBlank { "A4" },
                duplexMode = job.stringOrEmpty("duplex_mode").ifBlank { "SIMPLEX" },
                documentId = docId,
                documentName = docName,
                batchId = job.stringOrEmpty("batch_id").ifBlank { null },
                fileIndex = job.get("file_index")?.asInt ?: 0,
                totalFiles = job.get("total_files")?.asInt ?: 1,
                pagesPrinted = job.get("pages_printed")?.asInt ?: 0,
            )
        }
    }

    fun refreshPrinters(): Int {
        val saved = requireCredentials()
        val local = discoverPrinters()
        val existing = getArray("/api/v1/shops/${saved.shopId}/printers")
            .mapNotNull { it.takeIf { element -> element.isJsonObject }?.asJsonObject }
            .associateBy { it.stringOrEmpty("name").lowercase() }
        val payload = JsonObject().apply {
            addProperty("shop_id", saved.shopId)
            add("printers", JsonArray().apply {
                local.forEach { printer ->
                    val previous = existing[printer.name.lowercase()]
                    add(jsonObject(
                        "id" to (previous?.stringOrEmpty("id") ?: printer.id),
                        "shop_id" to saved.shopId,
                        "name" to printer.name,
                        "model" to printer.model,
                        "driver_name" to printer.model,
                        "connection_info" to (previous?.stringOrEmpty("connection_info") ?: "WINDOWS_SPOOLER"),
                        "status" to printer.status,
                        "is_default" to printer.isDefault,
                        "is_online" to printer.isOnline,
                        "supports_color" to printer.supportsColor,
                        "supports_duplex" to printer.supportsDuplex,
                        "supported_paper_sizes" to printer.supportedPaperSizes,
                        "paper_tray_status" to (previous?.stringOrEmpty("paper_tray_status") ?: "READY"),
                        "toner_level_percent" to (previous?.get("toner_level_percent")?.asInt ?: 90),
                    ))
                }
            })
        }
        val body = gson.toJson(payload).toRequestBody("application/json".toMediaType())
        executeText(Request.Builder().url(baseUrl + "/api/v1/printers/sync").post(body))
        lastPrinters = local.map {
            PrinterStatus(
                name = it.name,
                model = it.model,
                status = it.status,
                paper = "Ready (${it.supportedPaperSizes.take(24)})",
                toner = if (it.isOnline) "Online" else "Offline",
                isDefault = it.isDefault,
                supportsColor = it.supportsColor,
                supportsDuplex = it.supportsDuplex,
            )
        }
        recordAudit("PRINTERS_SYNCED", "Synced ${local.size} Windows printer(s) to the cloud.")
        return local.size
    }

    fun reconnectRealtime() {
        openRealtime()
    }

    fun printJob(jobId: String, preferredPrinterName: String? = null) {
        printExecutor.execute { processJob(jobId, preferredPrinterName) }
    }

    fun cancelJob(jobId: String, reason: String = "Cancelled by shop operator") {
        requireCredentials()
        post(
            "/api/v1/jobs/${encodePath(jobId)}/cancel",
            jsonObject("reason" to reason),
        )
        runCatching {
            post("/api/v1/cleanup/execute/${encodePath(jobId)}", JsonObject())
        }
        recordAudit("JOB_CANCELLED", "Print job $jobId was cancelled by operator ($reason).")
    }

    fun previewJob(jobId: String): DocumentPreview {
        val saved = requireCredentials()
        val job = get("/api/v1/jobs/${encodePath(jobId)}")
        val status = job.stringOrEmpty("status")
        if (status != "AUTHORIZED" && status != "PRINTING" && status != "COMPLETED") {
            throw IOException("Preview is only available for active or authorized jobs (current status: $status).")
        }
        val documentId = job.stringOrEmpty("document_id")
        if (documentId.isBlank()) throw IOException("Job has no document ID.")

        val download = getBytes("/api/v1/documents/${encodePath(documentId)}/print-content?job_id=${encodeQuery(jobId)}")
        val expectedHash = download.headers.getHeader("X-PrivPrint-SHA256")
            ?: throw IOException("Encrypted document checksum is missing.")
        val actualHash = MessageDigest.getInstance("SHA-256").digest(download.body)
            .joinToString("") { "%02x".format(it) }
        if (!actualHash.equals(expectedHash, ignoreCase = true)) {
            throw IOException("The encrypted document failed its SHA-256 integrity check.")
        }

        var clearDocument: ByteArray? = null
        try {
            clearDocument = decryptDocument(download.body, download.headers)
            val filename = URLDecoder.decode(
                download.headers.getHeader("X-PrivPrint-Filename") ?: "Document.pdf",
                StandardCharsets.UTF_8,
            )
            val ext = filename.substringAfterLast('.', "").lowercase()
            val renderedPages = mutableListOf<java.awt.image.BufferedImage>()

            when (ext) {
                "pdf" -> {
                    Loader.loadPDF(clearDocument).use { document ->
                        val renderer = PDFRenderer(document)
                        val totalPages = document.numberOfPages
                        val limit = minOf(totalPages, 100)
                        for (i in 0 until limit) {
                            val bim = renderer.renderImageWithDPI(i, 130f, ImageType.RGB)
                            renderedPages.add(bim)
                        }
                    }
                }
                "png", "jpg", "jpeg" -> {
                    val bim = ImageIO.read(ByteArrayInputStream(clearDocument))
                        ?: throw IOException("Image document could not be decoded.")
                    renderedPages.add(bim)
                }
                else -> {
                    runCatching {
                        Loader.loadPDF(clearDocument).use { document ->
                            val renderer = PDFRenderer(document)
                            for (i in 0 until minOf(document.numberOfPages, 100)) {
                                renderedPages.add(renderer.renderImageWithDPI(i, 130f, ImageType.RGB))
                            }
                        }
                    }.recoverCatching {
                        val bim = ImageIO.read(ByteArrayInputStream(clearDocument))
                            ?: throw IOException("Document format '.$ext' is not previewable.")
                        renderedPages.add(bim)
                    }.getOrThrow()
                }
            }

            recordAudit("DOCUMENT_PREVIEWED", "Job $jobId ($filename, ${renderedPages.size} pages) previewed in RAM.")
            return DocumentPreview(
                jobId = jobId,
                documentId = documentId,
                filename = filename,
                pageCount = renderedPages.size,
                pages = renderedPages,
            )
        } finally {
            clearDocument?.fill(0)
        }
    }

    fun logout() {
        val saved = credentials
        if (saved != null) {
            runCatching {
                post(
                    "/api/v1/auth/logout",
                    jsonObject("refresh_token" to saved.refreshToken),
                    bearerToken = saved.accessToken,
                )
            }.onFailure {
                recordAudit("LOGOUT_REVOKE_FAILED", it.message ?: "Could not revoke cloud session.", "WARNING")
            }
        }
        realtimeDesired = false
        realtime?.close(1000, "Operator logged out")
        realtime = null
        connected = false
        connectionState = "DISCONNECTED"
        credentials = null
        privateKey = null
        operatorToken = null
        keyRegistered = false
        processingJobs.clear()
        runCatching { Files.deleteIfExists(stateFile) }
        runCatching { Files.deleteIfExists(legacyCredentialsFile) }
        runCatching { Files.deleteIfExists(legacyConfigFile) }
        recordAudit("OPERATOR_LOGOUT", "Station credentials removed from this PC; operator logged out.")
    }

    fun jobDetails(jobId: String): PrintJobDetail {
        val job = get("/api/v1/jobs/${encodePath(jobId)}")
        val documentId = job.stringOrEmpty("document_id")
        val document = get("/api/v1/documents/${encodePath(documentId)}")
        return PrintJobDetail(
            id = job.stringOrEmpty("id"),
            status = job.stringOrEmpty("status"),
            createdAt = job.stringOrEmpty("created_at"),
            requestedCopies = job.get("requested_copies")?.asInt ?: 1,
            documentName = document.stringOrEmpty("filename").ifBlank { "Document" },
            pageCount = job.get("page_count")?.asInt ?: 0,
            colorMode = job.stringOrEmpty("color_mode").ifBlank { "Unknown" },
            paperSize = job.stringOrEmpty("paper_size").ifBlank { "A4" },
            duplex = job.stringOrEmpty("duplex_mode").ifBlank { "SIMPLEX" },
            selectedPrinter = job.stringOrEmpty("printer_id"),
            failureReason = job.stringOrEmpty("failure_reason"),
            batchId = job.stringOrEmpty("batch_id").ifBlank { null },
            fileIndex = job.get("file_index")?.asInt ?: 0,
            totalFiles = job.get("total_files")?.asInt ?: 1,
            pagesPrinted = job.get("pages_printed")?.asInt ?: 0,
        )
    }

    fun close() {
        realtimeDesired = false
        realtime?.close(1000, "Application closed")
        realtime = null
        connected = false
        background.shutdownNow()
        printExecutor.shutdownNow()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun selectOperatorShops(response: JsonObject): List<ShopOption> {
        requireOperator(response)
        val token = response.stringOrEmpty("access_token")
        if (token.isBlank()) throw IOException("Cloud login response did not include an access token.")
        operatorToken = token
        val shops = getArray("/api/v1/shops", bearerToken = token)
            .mapNotNull { element ->
                element.takeIf { it.isJsonObject }?.asJsonObject?.let { shop ->
                    val id = shop.stringOrEmpty("id")
                    if (id.isBlank()) null else ShopOption(id, shop.stringOrEmpty("name"))
                }
            }
        if (shops.isEmpty()) throw IOException("No shop is linked to this operator account.")
        return shops
    }

    private fun requireOperator(response: JsonObject) {
        if (response.getAsJsonObject("user")?.stringOrEmpty("role") != "SHOP_OPERATOR") {
            throw IOException("This account is not registered as a Xerox shop operator.")
        }
    }

    private fun registerPrintKey() {
        val saved = requireCredentials()
        if (privateKey == null) throw IOException("The station's protected encryption key is unavailable.")
        if (saved.publicKey.isBlank()) throw IOException("The station's public encryption key is missing.")
        put(
            "/api/v1/devices/${encodePath(saved.deviceId)}/print-key",
            jsonObject("public_key" to saved.publicKey),
        )
        keyRegistered = true
        recordAudit("PRINT_KEY_REGISTERED", "Station encryption key is registered with the cloud.")
    }

    private fun discoverPrinters(): List<LocalPrinter> {
        val defaultService = PrintServiceLookup.lookupDefaultPrintService()
        val services = PrintServiceLookup.lookupPrintServices(null, null)
        val allPrinters = services.mapNotNull { service ->
            val name = service.name ?: return@mapNotNull null
            val makeAndModel = service.getAttribute(javax.print.attribute.standard.PrinterMakeAndModel::class.java)
                ?.toString()?.takeIf(String::isNotBlank) ?: name
            val isDefault = service == defaultService
            val accepting = service.getAttribute(javax.print.attribute.standard.PrinterIsAcceptingJobs::class.java)
            val isOnline = accepting != javax.print.attribute.standard.PrinterIsAcceptingJobs.NOT_ACCEPTING_JOBS
            val status = if (isOnline) "READY" else "PAUSED"
            val colorAttr = service.getAttribute(javax.print.attribute.standard.ColorSupported::class.java)
            val supportsColor = colorAttr == javax.print.attribute.standard.ColorSupported.SUPPORTED
            val supportsDuplex = service.isAttributeCategorySupported(javax.print.attribute.standard.Sides::class.java)
            val queuedCount = service.getAttribute(javax.print.attribute.standard.QueuedJobCount::class.java)?.value ?: 0
            val id = stablePrinterId(requireCredentials().shopId, name)
            LocalPrinter(
                id = id,
                name = name,
                model = makeAndModel,
                service = service,
                isDefault = isDefault,
                isOnline = isOnline,
                status = status,
                supportsColor = supportsColor,
                supportsDuplex = supportsDuplex,
                supportedPaperSizes = "A4, Letter, Legal",
                queuedJobCount = queuedCount,
            )
        }
        val physicalPrinters = allPrinters.filterNot { isVirtualPrinter(it.name) }
        return if (physicalPrinters.isNotEmpty()) physicalPrinters else allPrinters
    }

    private fun isVirtualPrinter(name: String): Boolean {
        val normalized = name.lowercase()
        return listOf("print to pdf", "pdf writer", "xps document", "onenote", "fax")
            .any(normalized::contains)
    }

    private fun stablePrinterId(shopId: String, name: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("$shopId:$name:LOCAL".toByteArray(StandardCharsets.UTF_8))
        val shortHash = bytes.take(4).joinToString("") { "%02X".format(it) }
        return "PRN-WIN-$shortHash"
    }

    private fun reportProgress(jobId: String, pagesPrinted: Int, copiesPrinted: Int = 0) {
        runCatching {
            post(
                "/api/v1/print/jobs/${encodePath(jobId)}/progress",
                jsonObject(
                    "pages_printed" to pagesPrinted,
                    "copies_printed" to copiesPrinted,
                ),
            )
        }
    }

    private fun processJob(jobId: String, preferredPrinterName: String? = null) {
        if (!processingJobs.add(jobId)) return
        var claimed = false
        var clearDocument: ByteArray? = null
        try {
            val saved = requireCredentials()
            val job = get("/api/v1/jobs/${encodePath(jobId)}")
            if (job.stringOrEmpty("status") != "AUTHORIZED") return
            if (job.stringOrEmpty("shop_id") != saved.shopId) {
                throw IOException("This print job belongs to a different shop.")
            }
            val copies = job.get("requested_copies")?.asInt ?: 0
            if (copies !in 1..100) throw IOException("The requested copy count is invalid.")
            post("/api/v1/print/jobs/${encodePath(jobId)}/start", JsonObject())
            claimed = true

            val localPrinters = discoverPrinters()
            val requestedPrinter = job.stringOrEmpty("printer_id")
            val cloudPrinters = getArray("/api/v1/shops/${saved.shopId}/printers")
                .mapNotNull { it.takeIf { element -> element.isJsonObject }?.asJsonObject }
            val matchedCloudPrinter = cloudPrinters.firstOrNull {
                it.stringOrEmpty("id") == requestedPrinter
            }
            val targetName = matchedCloudPrinter?.stringOrEmpty("name").orEmpty()
            val targetPrinter = when {
                !preferredPrinterName.isNullOrBlank() -> {
                    localPrinters.firstOrNull { it.name.equals(preferredPrinterName, ignoreCase = true) }
                        ?: localPrinters.firstOrNull { it.isDefault }
                        ?: localPrinters.firstOrNull()
                        ?: throw IOException("The selected printer '$preferredPrinterName' is not available.")
                }
                requestedPrinter.isNotBlank() -> {
                    localPrinters.firstOrNull { it.name.equals(targetName, ignoreCase = true) }
                        ?: localPrinters.firstOrNull { it.isDefault }
                        ?: localPrinters.firstOrNull()
                        ?: throw IOException("The selected printer '$targetName' is not installed on this Windows station.")
                }
                else -> {
                    localPrinters.firstOrNull { it.isDefault }
                        ?: localPrinters.firstOrNull()
                        ?: throw IOException("No usable Windows printer is installed. Connect a printer or enable Microsoft Print to PDF.")
                }
            }

            val documentId = job.stringOrEmpty("document_id")
            if (documentId.isBlank()) throw IOException("The authorized print job has no document ID.")
            val download = getBytes(
                "/api/v1/documents/${encodePath(documentId)}/print-content?job_id=${encodeQuery(jobId)}",
            )
            val expectedHash = download.headers.getHeader("X-PrivPrint-SHA256")
                ?: throw IOException("Encrypted document checksum is missing.")
            val actualHash = MessageDigest.getInstance("SHA-256").digest(download.body)
                .joinToString("") { "%02x".format(it) }
            if (!actualHash.equals(expectedHash, ignoreCase = true)) {
                throw IOException("The encrypted document failed its SHA-256 integrity check.")
            }
            clearDocument = decryptDocument(download.body, download.headers)
            val filename = URLDecoder.decode(
                download.headers.getHeader("X-PrivPrint-Filename") ?: "Document.pdf",
                StandardCharsets.UTF_8,
            )
            printDocument(jobId, clearDocument, filename, targetPrinter.service, copies, job.stringOrEmpty("paper_size"))
            post(
                "/api/v1/print/jobs/${encodePath(jobId)}/increment-copy?delta=$copies",
                JsonObject(),
            )
            runCatching {
                post("/api/v1/cleanup/execute/${encodePath(jobId)}", JsonObject())
            }.onFailure {
                recordAudit("CLOUD_CLEANUP_FAILED", it.message ?: "Cloud document cleanup is pending.", "WARNING")
            }
            recordAudit("PRINT_COMPLETED", "$jobId submitted to ${targetPrinter.name}.")
        } catch (failure: Exception) {
            val reason = failure.message ?: "Windows station could not print this job."
            recordAudit("PRINT_FAILED", "$jobId: $reason", "ERROR")
            if (claimed) {
                runCatching {
                    post(
                        "/api/v1/print/jobs/${encodePath(jobId)}/fail?reason=${encodeQuery(reason.take(500))}",
                        JsonObject(),
                    )
                }.onFailure {
                    recordAudit("PRINT_FAILURE_SYNC_FAILED", it.message ?: "Could not report the print failure.", "ERROR")
                }
            }
        } finally {
            clearDocument?.fill(0)
            processingJobs.remove(jobId)
        }
    }

    private data class DownloadedDocument(val body: ByteArray, val headers: Map<String, String>)

    private fun decryptDocument(ciphertext: ByteArray, headers: Map<String, String>): ByteArray {
        val key = privateKey ?: throw IOException("The station's protected encryption key is unavailable.")
        val wrappedKey = headers.getHeader("X-PrivPrint-Wrapped-Key")
            ?: throw IOException("Encrypted document key is missing.")
        val ivHex = headers.getHeader("X-PrivPrint-IV") ?: throw IOException("Encrypted document IV is missing.")
        val privateCipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
        privateCipher.init(
            Cipher.DECRYPT_MODE,
            key,
            OAEPParameterSpec(
                "SHA-256",
                "MGF1",
                MGF1ParameterSpec.SHA256,
                PSource.PSpecified.DEFAULT,
            ),
        )
        val aesKey = privateCipher.doFinal(Base64.getDecoder().decode(wrappedKey))
        if (aesKey.size != 32) {
            aesKey.fill(0)
            throw IOException("The unwrapped document key has an invalid length.")
        }
        val iv = runCatching {
            require(ivHex.length == 24) { "Invalid document nonce length." }
            ivHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }.getOrElse {
            aesKey.fill(0)
            throw IOException("Encrypted document IV is invalid.")
        }
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, iv))
                doFinal(ciphertext)
            }
        } catch (failure: Exception) {
            throw IOException("Encrypted document authentication failed.", failure)
        } finally {
            aesKey.fill(0)
            iv.fill(0)
        }
    }

    private fun printDocument(
        jobId: String,
        bytes: ByteArray,
        filename: String,
        service: PrintService,
        copies: Int,
        paperSize: String,
    ) {
        val printerJob = PrinterJob.getPrinterJob()
        printerJob.printService = service
        printerJob.jobName = filename
        val attributes = HashPrintRequestAttributeSet()
        attributes.add(Copies(copies))
        when (paperSize.uppercase()) {
            "LETTER" -> attributes.add(MediaSizeName.NA_LETTER)
            "LEGAL" -> attributes.add(MediaSizeName.NA_LEGAL)
            "A3" -> attributes.add(MediaSizeName.ISO_A3)
            else -> attributes.add(MediaSizeName.ISO_A4)
        }
        when (filename.substringAfterLast('.', "").lowercase()) {
            "pdf" -> Loader.loadPDF(bytes).use { document ->
                val basePageable = PDFPageable(document)
                val trackingPageable = object : java.awt.print.Pageable {
                    override fun getNumberOfPages(): Int = basePageable.numberOfPages
                    override fun getPageFormat(pageIndex: Int): java.awt.print.PageFormat = basePageable.getPageFormat(pageIndex)
                    override fun getPrintable(pageIndex: Int): java.awt.print.Printable {
                        val basePrintable = basePageable.getPrintable(pageIndex)
                        return java.awt.print.Printable { graphics, pageFormat, pIdx ->
                            val result = basePrintable.print(graphics, pageFormat, pIdx)
                            if (result == java.awt.print.Printable.PAGE_EXISTS) {
                                reportProgress(jobId, pIdx + 1)
                            }
                            result
                        }
                    }
                }
                printerJob.setPageable(trackingPageable)
                printerJob.print(attributes)
            }
            "png", "jpg", "jpeg" -> {
                reportProgress(jobId, 1)
                val image = ImageIO.read(ByteArrayInputStream(bytes))
                    ?: throw IOException("The image document could not be decoded.")
                printerJob.setPrintable({ graphics, pageFormat, pageIndex ->
                    if (pageIndex > 0) {
                        java.awt.print.Printable.NO_SUCH_PAGE
                    } else {
                        val g = graphics.create() as java.awt.Graphics2D
                        try {
                            g.setRenderingHint(
                                RenderingHints.KEY_INTERPOLATION,
                                RenderingHints.VALUE_INTERPOLATION_BICUBIC,
                            )
                            val scale = minOf(
                                pageFormat.imageableWidth / image.width,
                                pageFormat.imageableHeight / image.height,
                            )
                            val width = (image.width * scale).toInt()
                            val height = (image.height * scale).toInt()
                            val x = (pageFormat.imageableX + (pageFormat.imageableWidth - width) / 2).toInt()
                            val y = (pageFormat.imageableY + (pageFormat.imageableHeight - height) / 2).toInt()
                            g.drawImage(image, x, y, width, height, null)
                        } finally {
                            g.dispose()
                        }
                        java.awt.print.Printable.PAGE_EXISTS
                    }
                })
                try {
                    printerJob.print(attributes)
                } finally {
                    image.flush()
                }
            }
            else -> throw IOException("Unsupported print format. Windows station accepts PDF, PNG, and JPEG files.")
        }
    }

    private fun pollAndProcessAuthorizedJobs() {
        val saved = credentials ?: return
        if (saved.accessToken.isBlank()) return
        runCatching { queue() }.onFailure {
            connectionError = it.message ?: "Could not refresh the cloud print queue."
        }
    }

    private fun maybeProcessAuthorizedJobs(jobs: JsonArray) {
        val saved = credentials ?: return
        if (!saved.autoPrintEnabled) return
        jobs.forEach { element ->
            if (!element.isJsonObject) return@forEach
            val job = element.asJsonObject
            if (job.stringOrEmpty("status") == "AUTHORIZED") {
                val id = job.stringOrEmpty("id")
                if (id.isNotBlank() && processingJobs.add(id)) {
                    processingJobs.remove(id)
                    printExecutor.execute { processJob(id) }
                }
            }
        }
    }

    private fun startBackgroundLoops() {
        if (!backgroundLoopsStarted.compareAndSet(false, true)) return
        background.scheduleWithFixedDelay(
            {
                if (!credentials?.accessToken.isNullOrBlank()) {
                    runCatching { refreshStationTokenIfNeeded(); sendHeartbeat() }.onFailure {
                        connectionError = it.message ?: "Cloud heartbeat failed."
                        recordAudit("CLOUD_HEARTBEAT_FAILED", connectionError, "WARNING")
                    }
                }
            },
            15,
            15,
            TimeUnit.SECONDS,
        )
        background.scheduleWithFixedDelay(
            { pollAndProcessAuthorizedJobs() },
            2,
            4,
            TimeUnit.SECONDS,
        )
    }

    private fun sendHeartbeat() {
        val saved = requireCredentials()
        post("/api/v1/devices/${encodePath(saved.deviceId)}/heartbeat", JsonObject())
    }

    private fun openRealtime() {
        val saved = credentials ?: return
        realtimeDesired = true
        connected = false
        connectionState = "CONNECTING"
        realtime?.cancel()
        val socketUrl = baseUrl.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") +
            "/api/v1/realtime/ws"
        val request = Request.Builder()
            .url(socketUrl)
            .header("Authorization", "Bearer ${saved.accessToken}")
            .build()
        realtime = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                realtime = webSocket
                connected = true
                connectionState = "CONNECTED"
                connectionError = ""
                webSocket.send(gson.toJson(jsonObject("type" to "subscribe", "channel" to "shop:${saved.shopId}")))
                recordAudit("CLOUD_REALTIME_CONNECTED", "Connected to the cloud print queue.")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val message = JsonParser.parseString(text).asJsonObject
                    if (message.stringOrEmpty("type") == "event" &&
                        message.stringOrEmpty("event") == "JOB_AUTHORIZED"
                    ) {
                        val jobId = message.getAsJsonObject("data")?.stringOrEmpty("job_id").orEmpty()
                        if (jobId.isNotBlank() && credentials?.autoPrintEnabled == true) {
                            printExecutor.execute { processJob(jobId) }
                        }
                    }
                }.onFailure {
                    recordAudit("CLOUD_EVENT_INVALID", it.message ?: "Could not parse a cloud event.", "WARNING")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                connectionState = "DISCONNECTED"
                scheduleRealtimeReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                connectionState = "ERROR"
                connectionError = t.message ?: "Cloud realtime connection failed."
                scheduleRealtimeReconnect()
            }
        })
    }

    private fun scheduleRealtimeReconnect() {
        if (!realtimeDesired || background.isShutdown) return
        connectionState = "RECONNECTING"
        background.schedule({ if (realtimeDesired) openRealtime() }, 5, TimeUnit.SECONDS)
    }

    private fun refreshStationTokenIfNeeded(force: Boolean = false) {
        val saved = credentials ?: return
        if (!force && !tokenExpiresSoon(saved.accessToken)) return
        synchronized(operatorLock) {
            val current = credentials ?: return
            if (!force && !tokenExpiresSoon(current.accessToken)) return
            var access = ""
            var refresh = ""
            if (current.refreshToken.isNotBlank()) {
                val refreshed = runCatching {
                    post(
                        "/api/v1/auth/refresh",
                        jsonObject("refresh_token" to current.refreshToken),
                        authenticated = false,
                    )
                }.getOrNull()
                if (refreshed != null) {
                    access = refreshed.stringOrEmpty("access_token").ifBlank { refreshed.stringOrEmpty("accessToken") }
                    refresh = refreshed.stringOrEmpty("refresh_token").ifBlank { refreshed.stringOrEmpty("refreshToken") }
                }
            }
            if (access.isBlank()) {
                if (current.deviceId.isNotBlank() && current.deviceApiKey.isNotBlank()) {
                    val auth = post(
                        "/api/v1/devices/authenticate",
                        jsonObject("device_id" to current.deviceId, "api_key" to current.deviceApiKey),
                        authenticated = false,
                    )
                    access = auth.stringOrEmpty("access_token").ifBlank { auth.stringOrEmpty("accessToken") }
                    refresh = auth.stringOrEmpty("refresh_token").ifBlank { auth.stringOrEmpty("refreshToken") }
                } else {
                    throw IOException("Station authentication failed; please reconnect this station.")
                }
            }
            if (access.isBlank()) throw IOException("Cloud token refresh returned an incomplete token pair.")
            credentials = current.copy(
                accessToken = access,
                refreshToken = refresh.ifBlank { current.refreshToken },
            )
            saveCredentials()
            openRealtime()
        }
    }

    private fun tokenExpiresSoon(token: String): Boolean = runCatching {
        val payload = token.split(".")[1]
        val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
        val json = String(Base64.getUrlDecoder().decode(padded), StandardCharsets.UTF_8)
        val expiry = JsonParser.parseString(json).asJsonObject.get("exp").asLong
        expiry <= System.currentTimeMillis() / 1000 + 180
    }.getOrDefault(true)

    private fun requireCredentials(): StoredStationCredentials =
        credentials?.takeIf { it.deviceId.isNotBlank() && it.shopId.isNotBlank() }
            ?: throw IOException("Connect this Windows station to a shop first.")

    private fun loadCredentials(): StoredStationCredentials? {
        if (!Files.exists(stateFile)) return null
        return try {
            val plaintext = Crypt32Util.cryptUnprotectData(Files.readAllBytes(stateFile))
            gson.fromJson(String(plaintext, StandardCharsets.UTF_8), StoredStationCredentials::class.java)
                .also { plaintext.fill(0) }
        } catch (_: Exception) {
            null
        }
    }

    private fun saveCredentials() {
        val saved = credentials ?: return
        val plain = gson.toJson(saved).toByteArray(StandardCharsets.UTF_8)
        val protected = Crypt32Util.cryptProtectData(plain)
        plain.fill(0)
        val temporary = stateFile.resolveSibling("${stateFile.fileName}.new")
        Files.write(
            temporary,
            protected,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        protected.fill(0)
        try {
            Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun decodePrivateKey(encoded: String): java.security.PrivateKey {
        if (encoded.isBlank()) throw IOException("The saved station encryption key is missing; reconnect this station.")
        val bytes = Base64.getDecoder().decode(encoded)
        return try {
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bytes))
        } finally {
            bytes.fill(0)
        }
    }

    private fun get(path: String, bearerToken: String? = null): JsonObject =
        execute(Request.Builder().url(baseUrl + path).get(), bearerToken)

    private fun getArray(path: String, bearerToken: String? = null): JsonArray {
        val request = Request.Builder().url(baseUrl + path).get()
        return JsonParser.parseString(executeText(request, bearerToken)).asJsonArray
    }

    private fun post(
        path: String,
        payload: JsonObject,
        authenticated: Boolean = true,
        bearerToken: String? = null,
    ): JsonObject {
        val body = gson.toJson(payload).toRequestBody("application/json".toMediaType())
        return execute(Request.Builder().url(baseUrl + path).post(body), bearerToken, authenticated)
    }

    private fun put(path: String, payload: JsonObject) {
        val body = gson.toJson(payload).toRequestBody("application/json".toMediaType())
        executeText(Request.Builder().url(baseUrl + path).put(body), null)
    }

    private fun getBytes(path: String): DownloadedDocument {
        val request = Request.Builder().url(baseUrl + path).get()
        val response = executeResponse(request)
        response.use {
            if (!it.isSuccessful) {
                val text = it.body?.string().orEmpty()
                throw IOException(responseDetail(text, it.code))
            }
            val bytes = it.body?.bytes() ?: throw IOException("Cloud response did not contain document data.")
            return DownloadedDocument(bytes, it.headers.toMap())
        }
    }

    private fun execute(
        builder: Request.Builder,
        bearerToken: String? = null,
        authenticated: Boolean = true,
    ): JsonObject {
        val text = executeText(builder, bearerToken, authenticated)
        return if (text.isBlank()) JsonObject() else JsonParser.parseString(text).asJsonObject
    }

    private fun executeText(
        builder: Request.Builder,
        bearerToken: String? = null,
        authenticated: Boolean = true,
    ): String {
        val response = executeResponse(builder, bearerToken, authenticated)
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw IOException(responseDetail(text, it.code))
            return text
        }
    }

    private fun executeResponse(
        builder: Request.Builder,
        bearerToken: String? = null,
        authenticated: Boolean = true,
    ): Response {
        if (authenticated && bearerToken == null) refreshStationTokenIfNeeded()
        val token = bearerToken ?: if (authenticated) credentials?.accessToken else null
        if (token != null) builder.header("Authorization", "Bearer $token")
        val response = client.newCall(builder.build()).execute()
        if (response.code == 401 && authenticated && bearerToken == null) {
            response.close()
            refreshStationTokenIfNeeded(force = true)
            val retryToken = credentials?.accessToken
            if (retryToken != null) builder.header("Authorization", "Bearer $retryToken")
            return client.newCall(builder.build()).execute()
        }
        return response
    }

    private fun responseDetail(text: String, statusCode: Int): String = runCatching {
        val body = JsonParser.parseString(text).asJsonObject
        val error = body.getAsJsonObject("error")
        error?.stringOrEmpty("message")
            ?.takeIf(String::isNotBlank)
            ?: body.stringOrEmpty("detail").ifBlank { body.stringOrEmpty("message") }
    }.getOrNull()?.takeIf(String::isNotBlank) ?: "Cloud request failed (HTTP $statusCode)."

    private fun jsonObject(vararg values: Pair<String, Any?>): JsonObject =
        JsonObject().apply {
            values.forEach { (key, value) ->
                when (value) {
                    null -> add(key, null)
                    is Boolean -> addProperty(key, value)
                    is Number -> addProperty(key, value)
                    else -> addProperty(key, value.toString())
                }
            }
        }

    private fun encodeQuery(value: String): String =
        java.net.URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun encodePath(value: String): String =
        java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    private fun recordAudit(type: String, details: String, severity: String = "INFO") {
        synchronized(auditEvents) {
            auditEvents.add(AuditEvent(
                timestamp = java.time.Instant.now().toString(),
                eventType = type,
                details = details,
                severity = severity,
            ))
            while (auditEvents.size > 200) auditEvents.removeAt(0)
        }
    }

    private fun JsonObject.stringOrEmpty(name: String): String =
        get(name)?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asString.orEmpty()

    private fun JsonObject.toMap(): Map<String, String> =
        entrySet().associate { it.key to it.value.asString }

    private fun Map<String, String>.getHeader(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

