package com.privprint.windows

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration

data class ShopOption(val id: String, val name: String)

data class PrinterStatus(
    val name: String,
    val model: String,
    val status: String,
    val paper: String,
    val toner: String,
)

data class PrintJobStatus(
    val id: String,
    val status: String,
    val createdAt: String,
    val copies: String,
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
    val printers: List<PrinterStatus> = emptyList(),
    val auditLog: List<AuditEvent> = emptyList(),
    val activeJobCount: Int = 0,
    val completedJobCount: Int = 0,
)

class StationBridge {
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(2))
        .readTimeout(Duration.ofSeconds(5))
        .callTimeout(Duration.ofSeconds(8))
        .build()
    private var baseUrl = "http://127.0.0.1:8888"
    private var agentProcess: Process? = null

    fun start() {
        val executable = extractAgent()
        val runningStatus = getStatusOrNull()
        if (runningStatus != null) {
            if (!samePath(runningStatus.workerExecutable, executable.toString())) {
                throw IOException(
                    "Another PrivPrint station worker is using port 8888. Close the older station app, " +
                        "then reopen PrivPrint Shop Station."
                )
            }
            return
        }

        agentProcess = ProcessBuilder(executable.toString())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .apply { environment()["PRIVPRINT_OPEN_DASHBOARD"] = "0" }
            .start()

        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        while (System.nanoTime() < deadline) {
            if (agentProcess?.isAlive == false) {
                throw IOException("The Windows station service stopped during startup.")
            }
            val status = getStatusOrNull()
            if (status != null) {
                if (!samePath(status.workerExecutable, executable.toString())) {
                    agentProcess?.destroy()
                    throw IOException(
                        "Another PrivPrint station worker is using port 8888. Close the older station app, " +
                            "then reopen PrivPrint Shop Station."
                    )
                }
                return
            }
            Thread.sleep(300)
        }
        agentProcess?.destroy()
        throw IOException("The Windows station service did not start. Restart PrivPrint and try again.")
    }

    fun login(email: String, password: String): List<ShopOption> {
        val json = post(
            "/api/auth/login",
            mapOf("email" to email.trim(), "password" to password),
        )
        return parseShops(json)
    }

    fun register(
        name: String,
        email: String,
        password: String,
        shopName: String,
        shopAddress: String,
    ): List<ShopOption> {
        val json = post(
            "/api/auth/register",
            mapOf(
                "full_name" to name.trim(),
                "email" to email.trim(),
                "password" to password,
                "shop_name" to shopName.trim(),
                "shop_address" to shopAddress.trim(),
            ),
        )
        return parseShops(json)
    }

    fun connect(shopId: String) {
        post("/api/auth/connect", mapOf("shop_id" to shopId))
    }

    fun status(): StationStatus =
        getStatusOrNull() ?: throw IOException("The Windows station service is not responding.")

    fun queue(): List<PrintJobStatus> {
        val request = Request.Builder().url("$baseUrl/api/queue").get().build()
        return client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException(responseDetail(text, response.code))
            JsonParser.parseString(text).asJsonArray.mapNotNull { element ->
                element.takeIf { it.isJsonObject }?.asJsonObject?.let { job ->
                    val id = job.stringOrEmpty("id").ifBlank { job.stringOrEmpty("jobId") }
                    if (id.isBlank()) null else PrintJobStatus(
                        id = id,
                        status = job.stringOrEmpty("status"),
                        createdAt = job.stringOrEmpty("created_at").ifBlank { job.stringOrEmpty("createdAt") },
                        copies = job.get("requested_copies")?.takeIf { it.isJsonPrimitive }?.asString
                            ?: job.get("requestedCopies")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                    )
                }
            }
        }
    }

    fun refreshPrinters(): Int {
        val request = Request.Builder().url("$baseUrl/api/printers/refresh")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        return client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException(responseDetail(text, response.code))
            JsonParser.parseString(text).asJsonObject.get("synced_count")?.asInt ?: 0
        }
    }

    fun reconnectRealtime() {
        val request = Request.Builder().url("$baseUrl/api/realtime/reconnect")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException(responseDetail(text, response.code))
        }
    }

    fun close() {
        agentProcess?.takeIf { it.isAlive }?.destroy()
        agentProcess = null
    }

    private fun parseShops(json: JsonObject): List<ShopOption> {
        val shops = json.getAsJsonArray("shops") ?: JsonArray()
        return shops.mapNotNull { element ->
            element.takeIf { it.isJsonObject }?.asJsonObject?.let { shop ->
                val id = shop.stringOrEmpty("id")
                if (id.isBlank()) null else ShopOption(id, shop.stringOrEmpty("name"))
            }
        }.also {
            if (it.isEmpty()) throw IOException("No shop is linked to this operator account.")
        }
    }

    private fun getStatusOrNull(): StationStatus? = try {
        val request = Request.Builder().url("$baseUrl/api/status").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Local station returned HTTP ${response.code}.")
            parseStatus(JsonParser.parseString(response.body?.string().orEmpty()).asJsonObject)
        }
    } catch (_: java.net.ConnectException) {
        null
    } catch (_: java.net.SocketTimeoutException) {
        null
    }

    private fun parseStatus(json: JsonObject): StationStatus {
        val printers = json.getAsJsonArray("printers") ?: JsonArray()
        val auditLog = json.getAsJsonArray("audit_log") ?: JsonArray()
        val activeJobs = json.get("active_jobs")?.takeIf { it.isJsonObject }?.asJsonObject?.size() ?: 0
        val completedJobs = json.getAsJsonArray("job_history")?.size() ?: 0
        return StationStatus(
            authenticated = json.booleanOrFalse("authenticated"),
            shopId = json.stringOrEmpty("shop_id"),
            deviceId = json.stringOrEmpty("device_id"),
            serverUrl = json.stringOrEmpty("server_base_url"),
            encryptionKeyRegistered = json.booleanOrFalse("encryption_key_registered"),
            realtimeConnected = json.booleanOrFalse("is_wss_connected"),
            connectionState = json.stringOrEmpty("connection_state").ifBlank { "DISCONNECTED" },
            connectionError = json.stringOrEmpty("connection_error"),
            workerExecutable = json.stringOrEmpty("worker_executable"),
            printers = printers.mapNotNull { element ->
                element.takeIf { it.isJsonObject }?.asJsonObject?.let { printer ->
                    PrinterStatus(
                        name = printer.stringOrEmpty("name"),
                        model = printer.stringOrEmpty("model"),
                        status = printer.stringOrEmpty("status"),
                        paper = printer.stringOrEmpty("paper_tray_status"),
                        toner = printer.get("toner_level_percent")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                    )
                }
            },
            auditLog = auditLog.mapNotNull { element ->
                element.takeIf { it.isJsonObject }?.asJsonObject?.let { event ->
                    AuditEvent(
                        timestamp = event.stringOrEmpty("timestamp"),
                        eventType = event.stringOrEmpty("eventType"),
                        details = event.stringOrEmpty("details"),
                        severity = event.stringOrEmpty("severity"),
                    )
                }
            }.reversed(),
            activeJobCount = activeJobs,
            completedJobCount = completedJobs,
        )
    }

    private fun post(path: String, payload: Map<String, String>): JsonObject {
        val body = gson.toJson(payload).toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("$baseUrl$path").post(body).build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException(responseDetail(text, response.code))
            }
            return JsonParser.parseString(text).asJsonObject
        }
    }

    private fun responseDetail(text: String, statusCode: Int): String =
        runCatching { JsonParser.parseString(text).asJsonObject.get("detail")?.asString }
            .getOrNull() ?: "Station request failed (HTTP $statusCode)."

    private fun samePath(first: String, second: String): Boolean =
        first.isNotBlank() && runCatching {
            java.nio.file.Path.of(first).toAbsolutePath().normalize().toString()
                .equals(java.nio.file.Path.of(second).toAbsolutePath().normalize().toString(), ignoreCase = true)
        }.getOrDefault(false)

    private fun extractAgent(): java.nio.file.Path {
        val appData = System.getenv("LOCALAPPDATA")
            ?: throw IOException("Windows user profile storage is unavailable.")
        val directory = java.nio.file.Path.of(appData, "PrivPrintStation", "runtime")
        Files.createDirectories(directory)
        val destination = directory.resolve("PrivPrintStationWorker.exe")
        val input = StationBridge::class.java.getResourceAsStream("/PrivPrintStationWorker.exe")
            ?: throw IOException("PrivPrint station service is missing from this installation.")
        val temporary = directory.resolve("PrivPrintStationWorker.exe.new")
        input.use { source ->
            Files.newOutputStream(
                temporary,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ).use(source::copyTo)
        }
        val isCurrent = Files.exists(destination) &&
            MessageDigest.isEqual(sha256(destination), sha256(temporary))
        if (isCurrent) {
            Files.delete(temporary)
        } else {
            try {
                Files.move(
                    temporary,
                    destination,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        return destination
    }

    private fun sha256(path: java.nio.file.Path): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }

    private fun JsonObject.stringOrEmpty(name: String): String =
        get(name)?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asString.orEmpty()

    private fun JsonObject.booleanOrFalse(name: String): Boolean =
        get(name)?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asBoolean ?: false
}
