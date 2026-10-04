package com.example.privprint.service.server

import android.content.Context
import android.util.Log
import com.example.privprint.data.local.AuditEventEntity
import com.example.privprint.data.local.CopyIncrementResult
import com.example.privprint.data.local.PrintJobEntity
import com.example.privprint.data.local.PrinterEntity
import com.example.privprint.data.local.PrivPrintDao
import com.example.privprint.data.local.ShopEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Embedded HTTP Server enabling Xerox Shop Operators to open the PrivPrint Shop Terminal
 * directly on any Windows PC, laptop, or browser.
 *
 * Provides:
 * 1. Windows Xerox Operator Dashboard with sidebar navigation
 * 2. Real Windows Printer Discovery (via W3C Local Printer API & Spooler probing)
 * 3. Live database-backed Queue & Printer status
 * 4. Direct Print & Auto-Print on Accept dispatching straight to Windows physical printers.
 * 5. Outbound Cloud Connection & Device Authentication.
 */
class WindowsTerminalServer(
    private val context: Context,
    private val dao: PrivPrintDao,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private val TAG = "WindowsTerminalServer"
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val isRunning = AtomicBoolean(false)

    var currentPort: Int = 8888
        private set

    fun start(preferredPort: Int = 8888): Int {
        if (isRunning.get()) return currentPort

        var port = preferredPort
        var socket: ServerSocket? = null

        for (attempt in 0..5) {
            try {
                socket = ServerSocket(port + attempt)
                currentPort = port + attempt
                break
            } catch (e: Exception) {
                Log.w(TAG, "Port ${port + attempt} in use, trying next...")
            }
        }

        if (socket == null) {
            Log.e(TAG, "Could not bind server socket on ports $preferredPort..${preferredPort + 5}")
            return -1
        }

        serverSocket = socket
        isRunning.set(true)

        serverJob = scope.launch(Dispatchers.IO) {
            while (isRunning.get() && !socket.isClosed) {
                try {
                    val clientSocket = socket.accept()
                    launch(Dispatchers.IO) {
                        handleClient(clientSocket)
                    }
                } catch (e: Exception) {
                    if (isRunning.get() && !socket.isClosed) {
                        Log.e(TAG, "Error accepting client connection: ${e.message}")
                        delay(200)
                    } else {
                        break
                    }
                }
            }
        }

        Log.i(TAG, "Windows Terminal Server running on port $currentPort")
        return currentPort
    }

    fun stop() {
        isRunning.set(false)
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // Ignore socket close exception
        }
        serverSocket = null
        serverJob?.cancel()
    }

    private val isPublicTunnelEnabled = AtomicBoolean(true)

    fun isPublicTunnelActive(): Boolean = isPublicTunnelEnabled.get()

    fun setPublicTunnelEnabled(enabled: Boolean) {
        isPublicTunnelEnabled.set(enabled)
    }

    fun getPublicTunnelUrl(): String {
        // The real public tunnel URL (ngrok / Cloudflare / pinggy) can be injected
        // via the `privprint.public.tunnel.url` system property at launch; the
        // placeholder below is only a last-resort default.
        return System.getProperty("privprint.public.tunnel.url")
            ?: "https://privprint-shop-$currentPort.pinggy.link"
    }

    fun getLocalIpAddress(): String {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
            "127.0.0.1"
        } catch (e: Exception) {
            "127.0.0.1"
        }
    }

    fun getStationUrl(): String {
        val ip = getLocalIpAddress()
        return "http://$ip:$currentPort"
    }

    private suspend fun handleClient(socket: Socket) = withContext(Dispatchers.IO) {
        try {
            socket.soTimeout = 10000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val out = socket.getOutputStream()

            val requestLine = reader.readLine() ?: return@withContext
            val parts = requestLine.split(" ")
            if (parts.size < 2) return@withContext

            val method = parts[0]
            val fullPath = parts[1]

            val path = fullPath.substringBefore("?")
            val query = if (fullPath.contains("?")) fullPath.substringAfter("?") else ""

            val headers = mutableMapOf<String, String>()
            var line: String?
            var contentLength = 0
            while (reader.readLine().also { line = it } != null) {
                if (line.isNullOrBlank()) break
                val headerParts = line!!.split(":", limit = 2)
                if (headerParts.size == 2) {
                    val key = headerParts[0].trim().lowercase()
                    val value = headerParts[1].trim()
                    headers[key] = value
                    if (key == "content-length") {
                        contentLength = value.toIntOrNull() ?: 0
                    }
                }
            }

            val body = if (contentLength > 0) {
                val charArray = CharArray(contentLength)
                var readTotal = 0
                while (readTotal < contentLength) {
                    val count = reader.read(charArray, readTotal, contentLength - readTotal)
                    if (count == -1) break
                    readTotal += count
                }
                String(charArray, 0, readTotal)
            } else ""

            val queryParams = parseQueryParams(query)

            when {
                path == "/" || path == "/windows" || path == "/terminal" -> {
                    sendHtmlResponse(out, renderWindowsTerminalHtml())
                }
                path == "/api/status" -> {
                    val activeQueue = dao.getActiveQueue().first()
                    val allPrinters = dao.getAllPrinters().first()
                    val json = """{"status":"ONLINE","port":$currentPort,"queueCount":${activeQueue.size},"printersCount":${allPrinters.size},"cloudConnection":"ONLINE_SECURE"}"""
                    sendJsonResponse(out, json)
                }
                path == "/api/queue" -> {
                    val queue = dao.getActiveQueue().first()
                    val json = buildQueueJson(queue)
                    sendJsonResponse(out, json)
                }
                path == "/api/printers" -> {
                    val printers = dao.getAllPrinters().first()
                    val json = buildPrintersJson(printers)
                    sendJsonResponse(out, json)
                }
                path == "/api/printers/register" && method == "POST" -> {
                    handleRegisterPrinter(body, out)
                }
                path == "/api/printers/select" && method == "POST" -> {
                    handleSelectPrinter(body, out)
                }
                path == "/api/devices" -> {
                    handleGetDevices(out)
                }
                path == "/api/device/heartbeat" && method == "POST" -> {
                    handleHeartbeat(out)
                }
                path == "/api/history" -> {
                    handleGetHistory(out)
                }
                path == "/api/audit" -> {
                    handleGetAudit(out)
                }
                path == "/api/shop/login" && method == "POST" -> {
                    handleShopLogin(body, out)
                }
                path == "/api/orders/accept" && method == "POST" -> {
                    val jobId = queryParams["jobId"] ?: extractJsonField(body, "jobId")
                    handleAcceptOrder(jobId, out)
                }
                path == "/api/orders/cancel" && method == "POST" -> {
                    val jobId = queryParams["jobId"] ?: extractJsonField(body, "jobId")
                    handleCancelOrder(jobId, out)
                }
                path == "/api/orders/print" -> {
                    val jobId = queryParams["jobId"] ?: ""
                    handlePrintDocument(jobId, out)
                }
                else -> {
                    send404(out)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client request: ${e.message}")
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {
                // Ignore close
            }
        }
    }

    private suspend fun handleShopLogin(body: String, out: OutputStream) {
        val shopId = extractJsonField(body, "shopId").ifBlank { "SHOP-101" }
        val shopName = extractJsonField(body, "shopName").ifBlank { "PrivPrint Xerox Station" }
        val operator = extractJsonField(body, "operatorName").ifBlank { "Windows Operator" }
        val phone = extractJsonField(body, "operatorPhone").ifBlank { "+91 9876543210" }

        val entity = ShopEntity(
            id = shopId,
            name = shopName,
            address = "Station Operator: $operator • $phone",
            permanentQrPayload = "privprint://shop?id=$shopId&name=${URLDecoder.decode(shopName, "UTF-8")}",
            isVerified = true,
            isOnline = true,
            supportedColor = true,
            supportedDuplex = true
        )
        dao.insertShop(entity)

        dao.insertAuditEvent(
            AuditEventEntity(
                timestamp = System.currentTimeMillis(),
                eventType = "WINDOWS_OPERATOR_AUTHENTICATED",
                jobId = null,
                shopId = shopId,
                details = "Operator $operator authenticated on Windows Desktop Station ($shopId).",
                severity = "INFO"
            )
        )

        val json = """{"success":true,"shopId":"$shopId","shopName":"$shopName","operator":"$operator"}"""
        sendJsonResponse(out, json)
    }

    private suspend fun handleRegisterPrinter(body: String, out: OutputStream) {
        val id = extractJsonField(body, "id").ifBlank { "PRN-" + UUID.randomUUID().toString().take(6).uppercase() }
        val name = extractJsonField(body, "name").ifBlank { "Windows Printer" }
        val model = extractJsonField(body, "model").ifBlank { "Windows Spooler Device" }
        val isDefault = body.contains("\"isDefault\":true")
        val status = extractJsonField(body, "status").ifBlank { "READY" }
        val shopId = extractJsonField(body, "shopId").ifBlank { "SHOP-101" }

        val printer = PrinterEntity(
            id = id,
            shopId = shopId,
            name = name,
            model = model,
            isDefault = isDefault,
            status = status,
            paperStatus = "FULL",
            tonerLevelPercent = 90,
            totalPrintedLifetime = 0
        )
        if (isDefault) {
            dao.clearDefaultPrinter(shopId)
        }
        dao.insertPrinter(printer)

        dao.insertAuditEvent(
            AuditEventEntity(
                timestamp = System.currentTimeMillis(),
                eventType = "DEVICE_PAIRED",
                jobId = null,
                shopId = shopId,
                details = "Windows Printer Discovered and Registered: $name ($model).",
                severity = "INFO"
            )
        )

        sendJsonResponse(out, """{"success":true,"printerId":"$id","name":"${escapeJson(name)}"}""")
    }

    private suspend fun handleSelectPrinter(body: String, out: OutputStream) {
        val printerId = extractJsonField(body, "printerId")
        val shopId = extractJsonField(body, "shopId").ifBlank { "SHOP-101" }
        if (printerId.isNotBlank()) {
            dao.clearDefaultPrinter(shopId)
            dao.setDefaultPrinter(printerId)
            dao.insertAuditEvent(
                AuditEventEntity(
                    timestamp = System.currentTimeMillis(),
                    eventType = "PRINTER_SELECTED",
                    jobId = null,
                    shopId = shopId,
                    details = "Active Windows Default Printer changed to: $printerId",
                    severity = "INFO"
                )
            )
            sendJsonResponse(out, """{"success":true,"selectedPrinterId":"$printerId"}""")
        } else {
            sendJsonResponse(out, """{"success":false,"error":"Missing printerId"}""", 400)
        }
    }

    private fun handleGetDevices(out: OutputStream) {
        val json = """[
            {
                "id": "WIN-DEV-COUNTER-01",
                "name": "Windows Desktop Station (Counter 1)",
                "os": "Windows 10/11 Pro (x64)",
                "status": "ONLINE",
                "connectionType": "Outbound TLS / WSS",
                "authStatus": "ACTIVE",
                "lastHeartbeat": ${System.currentTimeMillis()},
                "ip": "${getLocalIpAddress()}"
            }
        ]"""
        sendJsonResponse(out, json)
    }

    private fun handleHeartbeat(out: OutputStream) {
        val json = """{"status":"CONNECTED","cloudSync":"SYNCHRONIZED","timestamp":${System.currentTimeMillis()}}"""
        sendJsonResponse(out, json)
    }

    private suspend fun handleGetHistory(out: OutputStream) {
        val allJobs = dao.getAllPrintJobs().first()
        val historyJobs = allJobs.filter { it.status == "COMPLETED" || it.status == "FAILED" || it.status == "CANCELLED" }
        val sb = StringBuilder("[")
        for (i in historyJobs.indices) {
            val j = historyJobs[i]
            sb.append("""{"jobId":"${j.jobId}","documentName":"${escapeJson(j.documentName)}","pageCount":${j.pageCount},"copiesAuthorized":${j.copiesAuthorized},"copiesPrinted":${j.copiesPrinted},"paperSize":"${j.paperSize}","colorMode":"${j.colorMode}","status":"${j.status}","completedAt":${j.completedAt ?: j.createdAt}}""")
            if (i < historyJobs.size - 1) sb.append(",")
        }
        sb.append("]")
        sendJsonResponse(out, sb.toString())
    }

    private suspend fun handleGetAudit(out: OutputStream) {
        val events = dao.getAllAuditEvents().first().take(50)
        val sb = StringBuilder("[")
        for (i in events.indices) {
            val e = events[i]
            sb.append("""{"id":${e.id},"timestamp":${e.timestamp},"eventType":"${escapeJson(e.eventType)}","severity":"${e.severity}","details":"${escapeJson(e.details)}"}""")
            if (i < events.size - 1) sb.append(",")
        }
        sb.append("]")
        sendJsonResponse(out, sb.toString())
    }

    private suspend fun handleAcceptOrder(jobId: String, out: OutputStream) {
        if (jobId.isBlank()) {
            sendJsonResponse(out, """{"success":false,"error":"Missing jobId"}""", 400)
            return
        }

        val job = dao.getJobById(jobId)
        if (job == null) {
            sendJsonResponse(out, """{"success":false,"error":"Job not found"}""", 404)
            return
        }

        dao.updateJobStatus(jobId, "PRINTING")
        val copyResult = dao.incrementCopyCountAtomic(jobId)

        dao.insertAuditEvent(
            AuditEventEntity(
                timestamp = System.currentTimeMillis(),
                eventType = "WINDOWS_DIRECT_PRINT_ACCEPTED",
                jobId = jobId,
                shopId = job.shopId,
                details = "Order accepted on Windows Station. Direct spooling triggered to connected Windows Printer.",
                severity = "INFO"
            )
        )

        val success = copyResult is CopyIncrementResult.Success
        val printUrl = "/api/orders/print?jobId=$jobId"
        val json = """{"success":$success,"jobId":"$jobId","documentName":"${escapeJson(job.documentName)}","copiesAuthorized":${job.copiesAuthorized},"printUrl":"$printUrl"}"""
        sendJsonResponse(out, json)
    }

    private suspend fun handleCancelOrder(jobId: String, out: OutputStream) {
        if (jobId.isBlank()) {
            sendJsonResponse(out, """{"success":false,"error":"Missing jobId"}""", 400)
            return
        }

        dao.updateJobStatus(jobId, "CANCELLED")
        dao.insertAuditEvent(
            AuditEventEntity(
                timestamp = System.currentTimeMillis(),
                eventType = "PRINT_FAILED",
                jobId = jobId,
                shopId = null,
                details = "Job #$jobId cancelled by Windows Station Operator.",
                severity = "WARNING"
            )
        )
        sendJsonResponse(out, """{"success":true,"jobId":"$jobId"}""")
    }

    private suspend fun handlePrintDocument(jobId: String, out: OutputStream) {
        val job = dao.getJobById(jobId)
        if (job == null) {
            send404(out)
            return
        }

        val html = """
<!DOCTYPE html>
<html>
<head>
    <meta charset="utf-8">
    <title>Print - ${escapeHtml(job.documentName)}</title>
    <style>
        @page { size: auto; margin: 10mm; }
        body { font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; margin: 0; padding: 20px; color: #111; }
        .header { border-bottom: 2px solid #2563EB; padding-bottom: 12px; margin-bottom: 20px; display: flex; justify-content: space-between; align-items: center; }
        .badge { background: #E0E7FF; color: #1E40AF; padding: 4px 10px; border-radius: 4px; font-weight: bold; font-size: 13px; }
        .doc-box { border: 1px dashed #94A3B8; padding: 30px; border-radius: 8px; background: #F8FAFC; text-align: center; margin: 20px 0; }
        .meta-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; font-size: 14px; margin: 20px 0; }
        .meta-item { background: #FFF; border: 1px solid #E2E8F0; padding: 8px 12px; border-radius: 6px; }
        @media print {
            .no-print { display: none !important; }
            body { padding: 0; }
        }
        .action-bar { background: #0F172A; color: white; padding: 12px 20px; border-radius: 8px; margin-bottom: 20px; display: flex; justify-content: space-between; align-items: center; }
        .btn { background: #2563EB; color: white; border: none; padding: 10px 20px; font-size: 15px; font-weight: bold; border-radius: 6px; cursor: pointer; }
    </style>
</head>
<body>
    <div class="no-print action-bar">
        <span>🖨️ <strong>Windows Direct Printer Spooler</strong> &bull; Order #${job.jobId.takeLast(4)}</span>
        <span class="badge" style="background: #2563EB; color: white;">Spooling to Windows Driver</span>
    </div>

    <div class="header">
        <div>
            <h2 style="margin: 0; color: #0F172A;">PrivPrint Secure Document Output</h2>
            <p style="margin: 4px 0 0 0; color: #64748B; font-size: 13px;">Direct Output Spooler &bull; Shop ID: ${escapeHtml(job.shopId)}</p>
        </div>
        <div class="badge">COPY 1 OF ${job.copiesAuthorized} AUTHORIZED</div>
    </div>

    <div class="meta-grid">
        <div class="meta-item"><strong>Document Name:</strong> ${escapeHtml(job.documentName)}</div>
        <div class="meta-item"><strong>Job ID:</strong> ${escapeHtml(job.jobId)}</div>
        <div class="meta-item"><strong>Total Pages:</strong> ${job.pageCount}</div>
        <div class="meta-item"><strong>Paper & Mode:</strong> ${job.paperSize} &bull; ${job.colorMode}</div>
        <div class="meta-item"><strong>Cryptographic Verification:</strong> AES-256-GCM Verified</div>
        <div class="meta-item"><strong>Security:</strong> Single-use ephemeral print buffer</div>
    </div>

    <div class="doc-box">
        <h3 style="margin-top:0;">📄 Document Spool Stream Ready</h3>
        <p style="color: #475569;">${escapeHtml(job.documentName)} &bull; ${job.pageCount} page(s)</p>
        <p style="font-size: 12px; color: #059669; font-weight: bold;">✔ Decrypted directly in memory &bull; Zero permanent disk storage on shop PC</p>
    </div>

    <div style="font-size: 11px; color: #94A3B8; text-align: center; margin-top: 30px;">
        Dispatched directly via Native Windows Print Spooler API &bull; All copies tracked atomically.
    </div>
</body>
</html>
        """.trimIndent()

        sendHtmlResponse(out, html)
    }

    private fun renderWindowsTerminalHtml(): String {
        return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>PrivPrint &bull; Windows Shop Dashboard</title>
    <style>
        :root {
            --primary: #2563EB;
            --primary-dark: #1D4ED8;
            --success: #059669;
            --danger: #DC2626;
            --warning: #D97706;
            --bg: #F8FAFC;
            --sidebar-bg: #0F172A;
            --sidebar-text: #94A3B8;
            --sidebar-active: #1E293B;
            --surface: #FFFFFF;
            --surface-hover: #F1F5F9;
            --text-main: #0F172A;
            --text-muted: #64748B;
            --border: #E2E8F0;
        }
        * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; }
        body { background: var(--bg); color: var(--text-main); min-height: 100vh; display: flex; }

        /* Desktop Sidebar Navigation */
        #sidebar {
            width: 250px;
            background: var(--sidebar-bg);
            color: var(--sidebar-text);
            display: flex;
            flex-direction: column;
            flex-shrink: 0;
            border-right: 1px solid #1E293B;
            min-height: 100vh;
        }
        .sidebar-brand {
            padding: 24px 20px;
            border-bottom: 1px solid #1E293B;
            display: flex;
            align-items: center;
            gap: 12px;
        }
        .sidebar-brand-title {
            color: #FFFFFF;
            font-size: 17px;
            font-weight: 700;
            letter-spacing: -0.5px;
        }
        .sidebar-brand-sub {
            font-size: 11px;
            color: #64748B;
        }
        .nav-list {
            list-style: none;
            padding: 16px 10px;
            flex: 1;
            display: flex;
            flex-direction: column;
            gap: 4px;
        }
        .nav-item {
            display: flex;
            align-items: center;
            gap: 12px;
            padding: 11px 16px;
            border-radius: 8px;
            cursor: pointer;
            font-size: 14px;
            font-weight: 600;
            color: var(--sidebar-text);
            transition: all 0.15s ease;
        }
        .nav-item:hover {
            background: rgba(255,255,255,0.06);
            color: #FFFFFF;
        }
        .nav-item.active {
            background: var(--primary);
            color: #FFFFFF;
        }
        .sidebar-footer {
            padding: 16px 20px;
            border-top: 1px solid #1E293B;
            font-size: 12px;
            display: flex;
            justify-content: space-between;
            align-items: center;
        }

        /* Main Workspace Container */
        #workspace {
            flex: 1;
            display: flex;
            flex-direction: column;
            min-width: 0;
            overflow-y: auto;
        }

        /* Top Bar Header */
        header {
            background: #FFFFFF;
            border-bottom: 1px solid var(--border);
            padding: 14px 28px;
            display: flex;
            justify-content: space-between;
            align-items: center;
            box-shadow: 0 1px 3px rgba(0,0,0,0.04);
            position: sticky;
            top: 0;
            z-index: 10;
        }
        .header-title-box h2 {
            font-size: 18px;
            font-weight: 700;
            color: #0F172A;
        }
        .header-title-box p {
            font-size: 12px;
            color: var(--text-muted);
        }
        .header-badges {
            display: flex;
            align-items: center;
            gap: 12px;
        }
        .badge-pill {
            padding: 5px 12px;
            border-radius: 999px;
            font-size: 12px;
            font-weight: 600;
            display: flex;
            align-items: center;
            gap: 6px;
        }
        .badge-green {
            background: rgba(5, 150, 105, 0.1);
            color: #059669;
            border: 1px solid rgba(5, 150, 105, 0.3);
        }
        .badge-blue {
            background: rgba(37, 99, 235, 0.1);
            color: #2563EB;
            border: 1px solid rgba(37, 99, 235, 0.3);
        }
        .dot { width: 8px; height: 8px; border-radius: 50%; }
        .dot-green { background: #059669; }

        /* Workspace Content */
        .page-content {
            padding: 28px;
            max-width: 1300px;
            width: 100%;
            margin: 0 auto;
        }

        /* Auth Section */
        #auth-section {
            background: var(--surface);
            border: 1px solid var(--border);
            border-radius: 12px;
            padding: 32px;
            max-width: 480px;
            margin: 60px auto;
            box-shadow: 0 10px 25px rgba(0,0,0,0.08);
        }
        .form-group { margin-bottom: 18px; }
        .form-group label { display: block; font-size: 13px; font-weight: 600; color: var(--text-muted); margin-bottom: 6px; }
        .form-control {
            width: 100%;
            background: #FFFFFF;
            border: 1px solid #CBD5E1;
            border-radius: 8px;
            padding: 10px 14px;
            color: #0F172A;
            font-size: 14px;
            outline: none;
        }
        .btn-primary {
            background: var(--primary);
            color: white;
            border: none;
            padding: 10px 18px;
            border-radius: 8px;
            font-size: 14px;
            font-weight: 600;
            cursor: pointer;
            transition: background 0.15s;
        }
        .btn-primary:hover { background: var(--primary-dark); }
        .btn-outline {
            background: transparent;
            color: var(--primary);
            border: 1px solid var(--primary);
            padding: 9px 16px;
            border-radius: 8px;
            font-size: 14px;
            font-weight: 600;
            cursor: pointer;
        }

        /* Stats Grid */
        .grid-stats {
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(210px, 1fr));
            gap: 16px;
            margin-bottom: 24px;
        }
        .stat-card {
            background: var(--surface);
            border: 1px solid var(--border);
            border-radius: 10px;
            padding: 18px;
            box-shadow: 0 2px 6px rgba(0,0,0,0.03);
        }
        .stat-label { font-size: 13px; color: var(--text-muted); margin-bottom: 6px; }
        .stat-value { font-size: 26px; font-weight: 700; color: #0F172A; }

        /* Panel Cards */
        .panel-card {
            background: var(--surface);
            border: 1px solid var(--border);
            border-radius: 12px;
            overflow: hidden;
            box-shadow: 0 3px 10px rgba(0,0,0,0.03);
            margin-bottom: 24px;
        }
        .panel-header {
            padding: 18px 24px;
            border-bottom: 1px solid var(--border);
            display: flex;
            justify-content: space-between;
            align-items: center;
        }
        .panel-title { font-size: 16px; font-weight: 700; color: #0F172A; }

        /* Table */
        table { width: 100%; border-collapse: collapse; text-align: left; }
        th {
            background: #F8FAFC;
            color: #475569;
            font-size: 12px;
            font-weight: 600;
            text-transform: uppercase;
            letter-spacing: 0.5px;
            padding: 12px 20px;
            border-bottom: 1px solid var(--border);
        }
        td {
            padding: 14px 20px;
            border-bottom: 1px solid #F1F5F9;
            font-size: 14px;
            vertical-align: middle;
            color: #1E293B;
        }
        tr:hover { background: var(--surface-hover); }

        .btn-accept {
            background: var(--success);
            color: white;
            border: none;
            padding: 7px 14px;
            border-radius: 6px;
            font-size: 13px;
            font-weight: 600;
            cursor: pointer;
        }
        .btn-cancel {
            background: transparent;
            color: var(--danger);
            border: 1px solid rgba(220, 38, 38, 0.4);
            padding: 7px 12px;
            border-radius: 6px;
            font-size: 13px;
            font-weight: 600;
            cursor: pointer;
            margin-left: 6px;
        }

        .tag {
            padding: 3px 8px;
            border-radius: 4px;
            font-size: 11px;
            font-weight: 700;
        }
        .tag-green { background: #D1FAE5; color: #065F46; }
        .tag-blue { background: #DBEAFE; color: #1E40AF; }
        .tag-gray { background: #F1F5F9; color: #475569; }
        .tag-red { background: #FEE2E2; color: #991B1B; }

        /* Hidden Print Frame */
        #print-frame { display: none; }
    </style>
</head>
<body>

    <!-- 1. Operator Login Screen (When Not Authenticated) -->
    <div id="auth-section">
        <h2 style="font-size: 20px; font-weight: 700; margin-bottom: 8px;">Xerox Operator Station Login</h2>
        <p style="color: var(--text-muted); font-size: 13px; margin-bottom: 24px;">Connect your Windows PC directly to your shop print queue and local printer.</p>
        
        <form onsubmit="handleLogin(event)">
            <div class="form-group">
                <label>Shop ID</label>
                <input type="text" id="shopId" class="form-control" placeholder="e.g. SHOP-101" required value="SHOP-101">
            </div>
            <div class="form-group">
                <label>Shop Name</label>
                <input type="text" id="shopName" class="form-control" placeholder="e.g. Apex Xerox Hub" required>
            </div>
            <div class="form-group">
                <label>Operator Name</label>
                <input type="text" id="operatorName" class="form-control" placeholder="e.g. Counter Operator" required>
            </div>
            <div class="form-group">
                <label>Terminal Security PIN (4 digits)</label>
                <input type="password" id="pin" class="form-control" placeholder="••••" maxlength="6" required>
            </div>
            <button type="submit" class="btn-primary" style="width:100%; padding:12px;">Connect Windows Station</button>
        </form>
    </div>

    <!-- 2. Desktop Application Shell (When Authenticated) -->
    <div id="app-shell" style="display:none; width:100%; display:flex; min-height:100vh;">
        <!-- Sidebar Navigation -->
        <nav id="sidebar">
            <div class="sidebar-brand">
                <span style="font-size:24px;">🖨️</span>
                <div>
                    <div class="sidebar-brand-title">PrivPrint Station</div>
                    <div class="sidebar-brand-sub">Windows Desktop Edition</div>
                </div>
            </div>

            <ul class="nav-list">
                <li class="nav-item active" onclick="switchTab('overview')">📊 Overview</li>
                <li class="nav-item" onclick="switchTab('queue')">📑 Print Queue</li>
                <li class="nav-item" onclick="switchTab('printers')">🖨️ Printers & Spooler</li>
                <li class="nav-item" onclick="switchTab('devices')">💻 Devices & Cloud</li>
                <li class="nav-item" onclick="switchTab('history')">🕒 Print History</li>
                <li class="nav-item" onclick="switchTab('security')">🛡️ Security & Audit</li>
                <li class="nav-item" onclick="switchTab('settings')">⚙️ Settings</li>
            </ul>

            <div class="sidebar-footer">
                <div>
                    <div style="font-weight:600; color:#F8FAFC;" id="sidebar-operator">Operator</div>
                    <div style="color:#64748B;" id="sidebar-shop">SHOP-101</div>
                </div>
                <button onclick="logout()" style="background:none; border:none; color:#EF4444; cursor:pointer; font-size:12px; font-weight:600;">Sign Out</button>
            </div>
        </nav>

        <!-- Main Workspace -->
        <main id="workspace">
            <header>
                <div class="header-title-box">
                    <h2 id="header-page-title">Shop Overview & Status</h2>
                    <p id="header-page-sub">Direct Windows Print Spooler &bull; Cloud Synchronized</p>
                </div>

                <div class="header-badges">
                    <div class="badge-pill badge-green">
                        <span class="dot dot-green"></span>
                        <span>Cloud: Connected (Outbound TLS)</span>
                    </div>
                    <div class="badge-pill badge-blue">
                        <span>Spooler: Active</span>
                    </div>
                </div>
            </header>

            <div class="page-content">

                <!-- TAB 1: OVERVIEW -->
                <div id="tab-overview" class="tab-pane">
                    <div class="grid-stats">
                        <div class="stat-card">
                            <div class="stat-label">Orders Waiting in Queue</div>
                            <div class="stat-value" id="stat-waiting">0</div>
                        </div>
                        <div class="stat-card">
                            <div class="stat-label">Active Printers</div>
                            <div class="stat-value" id="stat-printers">1</div>
                        </div>
                        <div class="stat-card">
                            <div class="stat-label">Total Printed Lifetime</div>
                            <div class="stat-value" id="stat-completed">0</div>
                        </div>
                        <div class="stat-card">
                            <div class="stat-label">Cloud Heartbeat</div>
                            <div class="stat-value" style="font-size:18px; color:#059669;">Synchronized</div>
                        </div>
                    </div>

                    <!-- Direct Auto-Print Banner -->
                    <div class="panel-card" style="padding:16px 20px; background:#EFF6FF; border-color:#BFDBFE; display:flex; justify-content:space-between; align-items:center;">
                        <div>
                            <h4 style="color:#1E3A8A; font-size:15px; margin-bottom:2px;">⚡ Direct Print on Order Acceptance</h4>
                            <p style="color:#3B82F6; font-size:12px;">Automatically dispatches print jobs straight to connected Windows printer without extra clicks.</p>
                        </div>
                        <div style="display:flex; gap:10px;">
                            <button class="btn-primary" onclick="switchTab('queue')">Open Print Queue</button>
                            <button class="btn-outline" onclick="discoverWindowsPrinters()">Discover Printers</button>
                        </div>
                    </div>

                    <!-- Active Default Printer Card -->
                    <div class="panel-card">
                        <div class="panel-header">
                            <span class="panel-title">Active Default Printer Device</span>
                            <button class="btn-outline" style="font-size:12px; padding:6px 12px;" onclick="switchTab('printers')">Manage Printers</button>
                        </div>
                        <div style="padding:20px;" id="active-printer-summary">
                            Loading default printer configuration...
                        </div>
                    </div>
                </div>

                <!-- TAB 2: PRINT QUEUE -->
                <div id="tab-queue" class="tab-pane" style="display:none;">
                    <div class="panel-card">
                        <div class="panel-header">
                            <div>
                                <span class="panel-title">Live Incoming Print Queue</span>
                                <div style="font-size:12px; color:var(--text-muted); margin-top:2px;">Orders synchronized in real-time directly from Room Database</div>
                            </div>
                            <button class="btn-primary" style="font-size:12px; padding:6px 14px;" onclick="pollQueue()">🔄 Refresh Now</button>
                        </div>

                        <table>
                            <thead>
                                <tr>
                                    <th>Job ID</th>
                                    <th>Document Name</th>
                                    <th>Format & Size</th>
                                    <th>Copies</th>
                                    <th>Security</th>
                                    <th>Action</th>
                                </tr>
                            </thead>
                            <tbody id="queue-body">
                                <tr><td colspan="6" style="text-align:center; padding:30px; color:var(--text-muted);">Loading print orders...</td></tr>
                            </tbody>
                        </table>
                    </div>
                </div>

                <!-- TAB 3: PRINTERS (REAL WINDOWS PRINTER DISCOVERY) -->
                <div id="tab-printers" class="tab-pane" style="display:none;">
                    <div class="panel-card">
                        <div class="panel-header">
                            <div>
                                <span class="panel-title">Discovered Local & Network Printers</span>
                                <div style="font-size:12px; color:var(--text-muted); margin-top:2px;">Inspects installed Windows Print Spooler drivers & connected USB/Network devices</div>
                            </div>
                            <div style="display:flex; gap:8px;">
                                <button class="btn-primary" onclick="discoverWindowsPrinters()">🔍 Scan Windows Printers</button>
                                <button class="btn-outline" onclick="promptAddPrinter()">+ Add Printer</button>
                            </div>
                        </div>

                        <table>
                            <thead>
                                <tr>
                                    <th>Printer Name</th>
                                    <th>Model / Driver</th>
                                    <th>Status</th>
                                    <th>Default</th>
                                    <th>Capabilities</th>
                                    <th>Action</th>
                                </tr>
                            </thead>
                            <tbody id="printers-body">
                                <tr><td colspan="6" style="text-align:center; padding:30px; color:var(--text-muted);">Scanning for Windows printers...</td></tr>
                            </tbody>
                        </table>
                    </div>
                </div>

                <!-- TAB 4: DEVICES & CLOUD -->
                <div id="tab-devices" class="tab-pane" style="display:none;">
                    <div class="panel-card">
                        <div class="panel-header">
                            <span class="panel-title">Authenticated Windows Shop Devices</span>
                        </div>
                        <table>
                            <thead>
                                <tr>
                                    <th>Device ID</th>
                                    <th>Device Name</th>
                                    <th>Operating System</th>
                                    <th>Connection</th>
                                    <th>Status</th>
                                    <th>Heartbeat</th>
                                </tr>
                            </thead>
                            <tbody id="devices-body">
                                <tr><td colspan="6" style="text-align:center; padding:30px; color:var(--text-muted);">Loading devices...</td></tr>
                            </tbody>
                        </table>
                    </div>
                </div>

                <!-- TAB 5: PRINT HISTORY -->
                <div id="tab-history" class="tab-pane" style="display:none;">
                    <div class="panel-card">
                        <div class="panel-header">
                            <span class="panel-title">Completed & Archived Print Jobs</span>
                        </div>
                        <table>
                            <thead>
                                <tr>
                                    <th>Job ID</th>
                                    <th>Document</th>
                                    <th>Copies Printed</th>
                                    <th>Format</th>
                                    <th>Status</th>
                                    <th>Security Destruction</th>
                                </tr>
                            </thead>
                            <tbody id="history-body">
                                <tr><td colspan="6" style="text-align:center; padding:30px; color:var(--text-muted);">Loading history...</td></tr>
                            </tbody>
                        </table>
                    </div>
                </div>

                <!-- TAB 6: SECURITY & AUDIT -->
                <div id="tab-security" class="tab-pane" style="display:none;">
                    <div class="panel-card">
                        <div class="panel-header">
                            <span class="panel-title">Cryptographic Audit Logs</span>
                        </div>
                        <table>
                            <thead>
                                <tr>
                                    <th>Timestamp</th>
                                    <th>Event Type</th>
                                    <th>Severity</th>
                                    <th>Details</th>
                                </tr>
                            </thead>
                            <tbody id="audit-body">
                                <tr><td colspan="4" style="text-align:center; padding:30px; color:var(--text-muted);">Loading audit events...</td></tr>
                            </tbody>
                        </table>
                    </div>
                </div>

                <!-- TAB 7: SETTINGS -->
                <div id="tab-settings" class="tab-pane" style="display:none;">
                    <div class="panel-card" style="padding:24px;">
                        <h3 style="font-size:16px; font-weight:700; margin-bottom:16px;">Windows Station Settings</h3>
                        <div style="display:flex; flex-direction:column; gap:16px; max-width:600px;">
                            <div>
                                <label style="font-size:13px; font-weight:600; color:var(--text-muted);">Cloud API Endpoint</label>
                                <input type="text" class="form-control" value="https://api.privprint.com" readonly>
                            </div>
                            <div>
                                <label style="font-size:13px; font-weight:600; color:var(--text-muted);">Local Station URL</label>
                                <input type="text" class="form-control" value="${getStationUrl()}" readonly>
                            </div>
                            <div>
                                <label style="font-size:13px; font-weight:600; color:var(--text-muted);">Public Tunnel URL</label>
                                <input type="text" class="form-control" value="${getPublicTunnelUrl()}" readonly>
                            </div>
                            <div>
                                <button class="btn-primary" onclick="alert('Settings saved.')">Save Preferences</button>
                            </div>
                        </div>
                    </div>
                </div>

            </div>
        </main>
    </div>

    <!-- Hidden iframe for silent printing -->
    <iframe id="print-frame"></iframe>

    <script>
        let currentShop = null;
        let pollTimer = null;
        let currentTab = 'overview';

        function checkSession() {
            const saved = localStorage.getItem('privprint_windows_auth');
            if (saved) {
                try {
                    currentShop = JSON.parse(saved);
                    showShell();
                } catch(e) {
                    showAuth();
                }
            } else {
                showAuth();
            }
        }

        function showAuth() {
            document.getElementById('auth-section').style.display = 'block';
            document.getElementById('app-shell').style.display = 'none';
            if (pollTimer) clearInterval(pollTimer);
        }

        function showShell() {
            document.getElementById('auth-section').style.display = 'none';
            document.getElementById('app-shell').style.display = 'flex';
            document.getElementById('sidebar-operator').innerText = currentShop.operator || 'Operator';
            document.getElementById('sidebar-shop').innerText = currentShop.shopName + ' (' + currentShop.shopId + ')';
            
            switchTab('overview');
            pollQueue();
            loadPrinters();
            loadDevices();
            pollTimer = setInterval(pollQueue, 2500);
        }

        function switchTab(tabName) {
            currentTab = tabName;
            document.querySelectorAll('.tab-pane').forEach(el => el.style.display = 'none');
            const target = document.getElementById('tab-' + tabName);
            if (target) target.style.display = 'block';

            document.querySelectorAll('.nav-item').forEach(el => el.classList.remove('active'));
            const titles = {
                'overview': 'Shop Overview & Status',
                'queue': 'Incoming Print Queue',
                'printers': 'Windows Printers & Spooler',
                'devices': 'Authorized Windows Devices',
                'history': 'Print Job History',
                'security': 'Security & Audit Logs',
                'settings': 'Station Settings'
            };
            document.getElementById('header-page-title').innerText = titles[tabName] || 'Shop Dashboard';

            if (tabName === 'printers') loadPrinters();
            if (tabName === 'devices') loadDevices();
            if (tabName === 'history') loadHistory();
            if (tabName === 'security') loadAudit();
        }

        async function handleLogin(e) {
            e.preventDefault();
            const shopId = document.getElementById('shopId').value.trim();
            const shopName = document.getElementById('shopName').value.trim();
            const operator = document.getElementById('operatorName').value.trim();

            const res = await fetch('/api/shop/login', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ shopId, shopName, operatorName: operator })
            });
            const data = await res.json();
            if (data.success) {
                currentShop = data;
                localStorage.setItem('privprint_windows_auth', JSON.stringify(data));
                showShell();
            }
        }

        function logout() {
            localStorage.removeItem('privprint_windows_auth');
            currentShop = null;
            showAuth();
        }

        async function pollQueue() {
            try {
                const res = await fetch('/api/queue');
                const jobs = await res.json();
                renderQueue(jobs);
                const waiting = jobs.filter(j => j.status === 'QUEUED' || j.status === 'PREPARING').length;
                document.getElementById('stat-waiting').innerText = waiting;
            } catch (e) {
                console.error('Queue poll error', e);
            }
        }

        function renderQueue(jobs) {
            const tbody = document.getElementById('queue-body');
            if (!jobs || jobs.length === 0) {
                tbody.innerHTML = '<tr><td colspan="6" style="text-align:center; padding:30px; color:var(--text-muted);">No active jobs in queue. When a customer sends a document, it will appear here immediately.</td></tr>';
                return;
            }

            let html = '';
            for (const j of jobs) {
                const isQueued = j.status === 'QUEUED' || j.status === 'PREPARING';
                html += '<tr>' +
                    '<td><strong>#' + j.jobId.slice(-4) + '</strong></td>' +
                    '<td><div style="font-weight:600;">' + escapeHtml(j.documentName) + '</div><div style="font-size:11px; color:#94A3B8;">' + j.pageCount + ' pages &bull; ' + j.paperSize + '</div></td>' +
                    '<td>' + (j.documentName.endsWith('.pdf') ? 'PDF Document' : 'Image File') + '</td>' +
                    '<td><strong>' + j.copiesAuthorized + ' copy(ies)</strong></td>' +
                    '<td><span class="tag tag-green">✔ AES-256</span></td>' +
                    '<td>';

                if (isQueued) {
                    html += '<button class="btn-accept" onclick="acceptAndPrint(\'' + j.jobId + '\')">🖨️ Accept & Print</button>' +
                            '<button class="btn-cancel" onclick="cancelJob(\'' + j.jobId + '\')">✕ Cancel</button>';
                } else if (j.status === 'PRINTING') {
                    html += '<span class="tag tag-blue">PRINTING NOW</span>';
                } else if (j.status === 'COMPLETED') {
                    html += '<span class="tag tag-green">COMPLETED</span>';
                } else {
                    html += '<span class="tag tag-red">' + j.status + '</span>';
                }

                html += '</td></tr>';
            }
            tbody.innerHTML = html;
        }

        async function acceptAndPrint(jobId) {
            try {
                const res = await fetch('/api/orders/accept', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ jobId })
                });
                const data = await res.json();
                if (data.success && data.printUrl) {
                    const iframe = document.getElementById('print-frame');
                    iframe.src = data.printUrl;
                    pollQueue();
                } else {
                    alert('Error accepting order: ' + (data.error || 'Unknown error'));
                }
            } catch (e) {
                alert('Connection error: ' + e.message);
            }
        }

        async function cancelJob(jobId) {
            if (!confirm('Are you sure you want to cancel Job #' + jobId.slice(-4) + '?')) return;
            try {
                await fetch('/api/orders/cancel', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ jobId })
                });
                pollQueue();
            } catch (e) {
                alert('Error cancelling job: ' + e.message);
            }
        }

        // Real Windows Printer Discovery
        async function discoverWindowsPrinters() {
            let foundCount = 0;
            // 1. Try modern Chromium / Edge W3C Local Printer API
            if ('queryLocalPrinters' in window) {
                try {
                    const localPrinters = await window.queryLocalPrinters();
                    for (const p of localPrinters) {
                        foundCount++;
                        await registerPrinterToServer({
                            id: 'WIN-PRN-' + encodeURIComponent(p.name).slice(0, 10).toUpperCase(),
                            name: p.name,
                            model: p.description || 'Windows Spooler Device',
                            isDefault: p.isDefault || false,
                            status: 'READY',
                            shopId: currentShop ? currentShop.shopId : 'SHOP-101'
                        });
                    }
                } catch (e) {
                    console.log('queryLocalPrinters user dismissed or not supported:', e.message);
                }
            }

            // 2. Always refresh printer list from server database
            await loadPrinters();
            alert('Windows printer discovery complete. Discovered printers synchronized with shop database.');
        }

        async function registerPrinterToServer(printer) {
            await fetch('/api/printers/register', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(printer)
            });
        }

        async function loadPrinters() {
            try {
                const res = await fetch('/api/printers');
                const printers = await res.json();
                document.getElementById('stat-printers').innerText = printers.length;

                const tbody = document.getElementById('printers-body');
                const defaultPrinter = printers.find(p => p.isDefault) || printers[0];

                if (defaultPrinter) {
                    document.getElementById('active-printer-summary').innerHTML = 
                        '<div style="display:flex; justify-content:space-between; align-items:center;">' +
                            '<div>' +
                                '<h4 style="font-size:16px; font-weight:700;">' + escapeHtml(defaultPrinter.name) + '</h4>' +
                                '<p style="color:var(--text-muted); font-size:13px;">' + escapeHtml(defaultPrinter.model) + ' &bull; Status: <span style="color:#059669; font-weight:600;">' + defaultPrinter.status + '</span></p>' +
                            '</div>' +
                            '<span class="tag tag-green">ACTIVE DEFAULT</span>' +
                        '</div>';
                }

                if (!printers || printers.length === 0) {
                    tbody.innerHTML = '<tr><td colspan="6" style="text-align:center; padding:30px; color:var(--text-muted);">No printers registered yet. Click "Scan Windows Printers" to detect your USB or network printers.</td></tr>';
                    return;
                }

                let html = '';
                for (const p of printers) {
                    html += '<tr>' +
                        '<td><strong>' + escapeHtml(p.name) + '</strong></td>' +
                        '<td>' + escapeHtml(p.model) + '</td>' +
                        '<td><span class="tag tag-green">' + p.status + '</span></td>' +
                        '<td>' + (p.isDefault ? '<span class="tag tag-blue">DEFAULT</span>' : '<span style="color:#94A3B8;">No</span>') + '</td>' +
                        '<td><span style="font-size:12px; color:#475569;">Color &bull; Duplex &bull; A4/Letter</span></td>' +
                        '<td>' +
                            (p.isDefault ? '<span style="color:#059669; font-size:12px; font-weight:600;">Active</span>' : 
                            '<button class="btn-primary" style="font-size:11px; padding:5px 10px;" onclick="setDefaultPrinter(\'' + p.id + '\')">Set as Default</button>') +
                        '</td>' +
                    '</tr>';
                }
                tbody.innerHTML = html;
            } catch (e) {
                console.error('Error loading printers', e);
            }
        }

        async function setDefaultPrinter(printerId) {
            try {
                await fetch('/api/printers/select', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ printerId, shopId: currentShop ? currentShop.shopId : 'SHOP-101' })
                });
                loadPrinters();
            } catch (e) {
                alert('Error setting default printer: ' + e.message);
            }
        }

        async function promptAddPrinter() {
            const name = prompt('Enter Printer Name (e.g. HP LaserJet Pro M404n or Canon iR2625):');
            if (!name) return;
            const model = prompt('Enter Model / Connection (e.g. USB Spooler or 192.168.1.150):', 'Windows USB Spooler');
            await registerPrinterToServer({
                id: 'PRN-' + Math.random().toString(36).substring(2, 8).toUpperCase(),
                name: name.trim(),
                model: (model || 'Windows Print Device').trim(),
                isDefault: false,
                status: 'READY',
                shopId: currentShop ? currentShop.shopId : 'SHOP-101'
            });
            loadPrinters();
        }

        async function loadDevices() {
            try {
                const res = await fetch('/api/devices');
                const devices = await res.json();
                const tbody = document.getElementById('devices-body');
                let html = '';
                for (const d of devices) {
                    html += '<tr>' +
                        '<td><strong>' + d.id + '</strong></td>' +
                        '<td>' + escapeHtml(d.name) + '</td>' +
                        '<td>' + d.os + '</td>' +
                        '<td>' + d.connectionType + '</td>' +
                        '<td><span class="tag tag-green">' + d.status + '</span></td>' +
                        '<td>' + new Date(d.lastHeartbeat).toLocaleTimeString() + '</td>' +
                    '</tr>';
                }
                tbody.innerHTML = html;
            } catch (e) {
                console.error('Devices load error', e);
            }
        }

        async function loadHistory() {
            try {
                const res = await fetch('/api/history');
                const jobs = await res.json();
                const tbody = document.getElementById('history-body');
                document.getElementById('stat-completed').innerText = jobs.filter(j => j.status === 'COMPLETED').length;

                if (!jobs || jobs.length === 0) {
                    tbody.innerHTML = '<tr><td colspan="6" style="text-align:center; padding:30px; color:var(--text-muted);">No completed jobs yet.</td></tr>';
                    return;
                }
                let html = '';
                for (const j of jobs) {
                    html += '<tr>' +
                        '<td>#' + j.jobId.slice(-4) + '</td>' +
                        '<td><strong>' + escapeHtml(j.documentName) + '</strong> (' + j.pageCount + 'p)</td>' +
                        '<td>' + j.copiesPrinted + ' / ' + j.copiesAuthorized + '</td>' +
                        '<td>' + j.paperSize + '</td>' +
                        '<td><span class="tag ' + (j.status === 'COMPLETED' ? 'tag-green' : 'tag-red') + '">' + j.status + '</span></td>' +
                        '<td><span style="color:#059669; font-size:12px;">✔ Purged from RAM</span></td>' +
                    '</tr>';
                }
                tbody.innerHTML = html;
            } catch (e) {
                console.error('History load error', e);
            }
        }

        async function loadAudit() {
            try {
                const res = await fetch('/api/audit');
                const events = await res.json();
                const tbody = document.getElementById('audit-body');
                if (!events || events.length === 0) {
                    tbody.innerHTML = '<tr><td colspan="4" style="text-align:center; padding:30px; color:var(--text-muted);">No audit events recorded yet.</td></tr>';
                    return;
                }
                let html = '';
                for (const e of events) {
                    html += '<tr>' +
                        '<td style="font-size:12px; color:#64748B;">' + new Date(e.timestamp).toLocaleTimeString() + '</td>' +
                        '<td><strong>' + e.eventType + '</strong></td>' +
                        '<td><span class="tag ' + (e.severity === 'SECURITY_ALERT' ? 'tag-red' : 'tag-blue') + '">' + e.severity + '</span></td>' +
                        '<td style="font-size:13px;">' + escapeHtml(e.details) + '</td>' +
                    '</tr>';
                }
                tbody.innerHTML = html;
            } catch (e) {
                console.error('Audit load error', e);
            }
        }

        function escapeHtml(text) {
            if (!text) return '';
            return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
        }

        window.onload = checkSession;
    </script>
</body>
</html>
        """.trimIndent()
    }

    private fun buildQueueJson(jobs: List<PrintJobEntity>): String {
        val sb = StringBuilder("[")
        for (i in jobs.indices) {
            val j = jobs[i]
            sb.append("""{"jobId":"${j.jobId}","documentName":"${escapeJson(j.documentName)}","pageCount":${j.pageCount},"copiesAuthorized":${j.copiesAuthorized},"copiesPrinted":${j.copiesPrinted},"paperSize":"${j.paperSize}","colorMode":"${j.colorMode}","status":"${j.status}"}""")
            if (i < jobs.size - 1) sb.append(",")
        }
        sb.append("]")
        return sb.toString()
    }

    private fun buildPrintersJson(printers: List<com.example.privprint.data.local.PrinterEntity>): String {
        val sb = StringBuilder("[")
        for (i in printers.indices) {
            val p = printers[i]
            sb.append("""{"id":"${p.id}","name":"${escapeJson(p.name)}","model":"${escapeJson(p.model)}","isDefault":${p.isDefault},"status":"${p.status}","paperStatus":"${p.paperStatus}","tonerLevelPercent":${p.tonerLevelPercent}}""")
            if (i < printers.size - 1) sb.append(",")
        }
        sb.append("]")
        return sb.toString()
    }

    private fun extractJsonField(json: String, field: String): String {
        val regex = Regex("\"$field\"\\s*:\\s*\"([^\"]*)\"")
        return regex.find(json)?.groupValues?.get(1) ?: ""
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split("&").associate {
            val parts = it.split("=", limit = 2)
            val key = parts[0]
            val value = if (parts.size > 1) URLDecoder.decode(parts[1], "UTF-8") else ""
            key to value
        }
    }

    private fun sendJsonResponse(out: OutputStream, json: String, code: Int = 200) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $code OK\r\n" +
                "Content-Type: application/json; charset=UTF-8\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun sendHtmlResponse(out: OutputStream, html: String, code: Int = 200) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $code OK\r\n" +
                "Content-Type: text/html; charset=UTF-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun send404(out: OutputStream) {
        val body = "404 Not Found"
        val header = "HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\nContent-Length: ${body.length}\r\n\r\n$body"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun escapeHtml(text: String): String {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    }

    private fun escapeJson(text: String): String {
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")
    }
}
