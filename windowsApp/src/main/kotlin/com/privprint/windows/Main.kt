package com.privprint.windows

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Ink = Color(0xFF0F172A)
private val InkMuted = Color(0xFF475569)
private val Muted = Color(0xFF64748B)
private val CanvasWhite = Color(0xFFFFFFFF)
private val Background = Color(0xFFF8FAFC)
private val SurfaceVariant = Color(0xFFF1F5F9)
private val Border = Color(0xFFE2E8F0)
private val Blue = Color(0xFF2563EB)
private val BlueTint = Color(0xFFEFF6FF)
private val Green = Color(0xFF059669)
private val GreenTint = Color(0xFFECFDF5)
private val Amber = Color(0xFFD97706)
private val AmberTint = Color(0xFFFFFBEB)
private val Red = Color(0xFFDC2626)
private val RedTint = Color(0xFFFEF2F2)

private enum class StationPage(val title: String, val symbol: String) {
    OVERVIEW("Dashboard", "⌂"),
    SHOP_QR("Shop QR", "▦"),
    QUEUE("Print queue", "▤"),
    PRINTERS("Printers", "▣"),
    AUDIT("Security audit", "◈"),
    SECURITY("Windows station", "⊞"),
}

fun main() = application {
    val bridge = remember { StationBridge() }
    var startupError by remember { mutableStateOf<String?>(null) }
    var isReady by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching { withContext(Dispatchers.IO) { bridge.start() } }
            .onSuccess { isReady = true }
            .onFailure { startupError = it.message ?: "Could not start the Windows station." }
    }

    Window(
        onCloseRequest = {
            bridge.close()
            exitApplication()
        },
        title = "PrivPrint | Xerox Shop",
        state = rememberWindowState(width = 1280.dp, height = 820.dp),
    ) {
        MaterialTheme(
            colorScheme = lightColorScheme(
                primary = Blue,
                onPrimary = CanvasWhite,
                primaryContainer = BlueTint,
                onPrimaryContainer = Color(0xFF1E40AF),
                secondary = Ink,
                background = Background,
                onBackground = Ink,
                surface = CanvasWhite,
                onSurface = Ink,
                surfaceVariant = SurfaceVariant,
                onSurfaceVariant = InkMuted,
                outline = Border,
                error = Red,
            ),
        ) {
            Surface(Modifier.fillMaxSize(), color = Background) {
                when {
                    startupError != null -> StartupFailure(startupError!!)
                    !isReady -> LoadingScreen()
                    else -> StationApplication(bridge)
                }
            }
        }
    }
}

@Composable
private fun LoadingScreen() {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = Blue)
        Spacer(Modifier.height(18.dp))
        Text("Starting secure Windows station…", color = Muted)
    }
}

@Composable
private fun StartupFailure(message: String) {
    Column(
        Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("PrivPrint could not start", color = Ink, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(message, color = Red)
        Spacer(Modifier.height(10.dp))
        Text("Close and reopen the application. If it continues, send this error to support.", color = Muted)
    }
}

@Composable
private fun StationApplication(bridge: StationBridge) {
    var status by remember { mutableStateOf<StationStatus?>(null) }
    var shop by remember { mutableStateOf<ShopDetails?>(null) }
    var shops by remember { mutableStateOf<List<ShopOption>>(emptyList()) }
    var selectedShop by remember { mutableStateOf<ShopOption?>(null) }
    var isRegistering by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var statusError by remember { mutableStateOf<String?>(null) }
    var selectedPage by remember { mutableStateOf(StationPage.OVERVIEW) }
    var queue by remember { mutableStateOf<List<PrintJobStatus>>(emptyList()) }
    var queueBusy by remember { mutableStateOf(false) }
    var queueError by remember { mutableStateOf<String?>(null) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var autoPrintBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun refreshStatus() {
        status = withContext(Dispatchers.IO) { bridge.status() }
        statusError = null
    }

    suspend fun refreshShop() {
        shop = withContext(Dispatchers.IO) { bridge.shopDetails() }
    }

    suspend fun refreshQueue() {
        queueBusy = true
        try {
            queue = withContext(Dispatchers.IO) { bridge.queue() }
            queueError = null
        } catch (exception: Exception) {
            queueError = exception.message ?: "Could not load the shop print queue."
        } finally {
            queueBusy = false
        }
    }

    LaunchedEffect(Unit) {
        runCatching { refreshStatus() }
            .onSuccess {
                if (status?.authenticated == true) {
                    runCatching { refreshShop() }
                        .onFailure { statusError = it.message ?: "Could not load Xerox shop details." }
                    refreshQueue()
                }
            }
            .onFailure { statusError = it.message ?: "Could not refresh station status." }
        while (true) {
            delay(4_000)
            runCatching { refreshStatus() }
                .onFailure { statusError = it.message ?: "Could not refresh station status." }
        }
    }

    LaunchedEffect(selectedPage, status?.authenticated) {
        if (selectedPage == StationPage.QUEUE && status?.authenticated == true) {
            refreshQueue()
        }
    }

    Row(Modifier.fillMaxSize().background(Background)) {
        Column(
            Modifier.width(240.dp).fillMaxHeight().background(CanvasWhite).padding(20.dp),
        ) {
            Text("PRIVPRINT", color = Blue, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
            Text("XEROX SHOP", color = Muted, fontSize = 10.sp, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(30.dp))
            Text("SHOP WORKSPACE", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            StationPage.entries.forEach { page ->
                NavLabel(page, selectedPage == page) {
                    selectedPage = page
                    actionMessage = null
                }
            }
            Spacer(Modifier.weight(1f))
            HorizontalDivider(color = Border)
            Spacer(Modifier.height(15.dp))
            Text("Secure printing", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "Customer documents stay encrypted until an authorized shop station prints them.",
                color = Muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }

        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(72.dp).background(CanvasWhite).padding(horizontal = 26.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(selectedPage.title, color = Ink, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text(
                        shop?.name ?: status?.shopId?.takeIf(String::isNotBlank)?.let { "Shop ID  $it" }
                            ?: "Sign in to connect your Xerox shop",
                        color = Muted,
                        fontSize = 12.sp,
                    )
                }
                TextButton(
                    enabled = !queueBusy && !autoPrintBusy,
                    onClick = {
                        scope.launch {
                            actionMessage = null
                            when (selectedPage) {
                                StationPage.QUEUE -> refreshQueue()
                                StationPage.PRINTERS -> runCatching {
                                    val count = withContext(Dispatchers.IO) { bridge.refreshPrinters() }
                                    refreshStatus()
                                    actionMessage = "$count Windows printer(s) synced with the shop."
                                }.onFailure {
                                    actionMessage = it.message ?: "Printer sync failed."
                                }
                                StationPage.SHOP_QR -> runCatching {
                                    refreshShop()
                                    actionMessage = "Permanent shop QR refreshed."
                                }.onFailure {
                                    actionMessage = it.message ?: "Could not refresh shop QR."
                                }
                                else -> runCatching { refreshStatus() }
                                    .onFailure { statusError = it.message ?: "Could not refresh station status." }
                            }
                        }
                    },
                ) {
                    Text(
                        when (selectedPage) {
                            StationPage.QUEUE -> "Refresh queue"
                            StationPage.PRINTERS -> "Sync printers"
                            StationPage.SHOP_QR -> "Refresh QR"
                            else -> "Refresh"
                        },
                        color = Blue,
                    )
                }
                Spacer(Modifier.width(8.dp))
                StatusPill(
                    if (status?.realtimeConnected == true) "CLOUD CONNECTED" else "CLOUD RECONNECTING",
                    status?.realtimeConnected == true,
                )
            }

            Row(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (status?.authenticated != true) {
                        Text("Welcome to PrivPrint", color = Ink, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "Sign in with your Xerox shop operator account or create a shop. Connecting this Windows station registers its encryption key.",
                            color = InkMuted,
                            fontSize = 14.sp,
                        )
                        AuthCard(
                            isRegistering = isRegistering,
                            busy = busy,
                            error = error,
                            onModeChange = {
                                isRegistering = it
                                error = null
                                shops = emptyList()
                                selectedShop = null
                            },
                            onSubmit = { fullName, email, password, shopName, address ->
                                busy = true
                                error = null
                                scope.launch {
                                    try {
                                        shops = withContext(Dispatchers.IO) {
                                            if (isRegistering) {
                                                bridge.register(fullName, email, password, shopName, address)
                                            } else {
                                                bridge.login(email, password)
                                            }
                                        }
                                        selectedShop = shops.firstOrNull()
                                    } catch (exception: Exception) {
                                        error = exception.message ?: "Account request failed."
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                        )
                        if (shops.isNotEmpty()) {
                            ShopPicker(
                                shops = shops,
                                selected = selectedShop,
                                busy = busy,
                                error = error,
                                onSelect = { selectedShop = it },
                                onConnect = {
                                    val selected = selectedShop ?: return@ShopPicker
                                    busy = true
                                    error = null
                                    scope.launch {
                                        try {
                                            withContext(Dispatchers.IO) { bridge.connect(selected.id) }
                                            refreshStatus()
                                            refreshShop()
                                            refreshQueue()
                                            selectedPage = StationPage.OVERVIEW
                                            actionMessage = "Shop connected and the Windows station key registered."
                                        } catch (exception: Exception) {
                                            if (status?.authenticated == true) {
                                                statusError = exception.message ?: "Station connection failed."
                                            } else {
                                                error = exception.message ?: "Station connection failed."
                                            }
                                        } finally {
                                            busy = false
                                        }
                                    }
                                },
                            )
                        }
                    } else {
                        statusError?.let { NoticeCard(it, isError = true) }
                        when (selectedPage) {
                            StationPage.OVERVIEW -> OverviewPage(
                                status = status,
                                shop = shop,
                                queue = queue,
                                actionMessage = actionMessage,
                                autoPrintBusy = autoPrintBusy,
                                onOpenQr = { selectedPage = StationPage.SHOP_QR },
                                onOpenQueue = { selectedPage = StationPage.QUEUE },
                                onOpenPrinters = { selectedPage = StationPage.PRINTERS },
                                onToggleAutoPrint = { enabled ->
                                    autoPrintBusy = true
                                    actionMessage = null
                                    scope.launch {
                                        try {
                                            withContext(Dispatchers.IO) {
                                                bridge.setAutoPrintEnabled(enabled)
                                            }
                                            refreshStatus()
                                            actionMessage = if (enabled) {
                                                "Automatic printing enabled."
                                            } else {
                                                "Automatic printing paused. Authorized jobs remain queued."
                                            }
                                        } catch (exception: Exception) {
                                            actionMessage = exception.message ?: "Could not update auto-print setting."
                                        } finally {
                                            autoPrintBusy = false
                                        }
                                    }
                                },
                            )
                            StationPage.SHOP_QR -> ShopQrPage(shop, actionMessage)
                            StationPage.QUEUE -> PrintQueuePage(queue, queueBusy, queueError, actionMessage)
                            StationPage.PRINTERS -> PrinterPage(status, actionMessage)
                            StationPage.AUDIT -> AuditPage(status?.auditLog.orEmpty())
                            StationPage.SECURITY -> StationSecurityPage(
                                status = status,
                                actionMessage = actionMessage,
                                onReconnect = {
                                    scope.launch {
                                        actionMessage = null
                                        runCatching {
                                            withContext(Dispatchers.IO) { bridge.reconnectRealtime() }
                                            actionMessage = "Cloud reconnect requested. Connection status will update automatically."
                                        }.onFailure {
                                            actionMessage = it.message ?: "Could not reconnect to the cloud."
                                        }
                                    }
                                },
                            )
                        }
                    }
                }

                if (status?.authenticated == true && selectedPage == StationPage.OVERVIEW) {
                    Column(Modifier.width(280.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        MetricCard(
                            "Shop queue",
                            queue.size.toString(),
                            "Cloud jobs currently in the queue",
                            onClick = { selectedPage = StationPage.QUEUE },
                        )
                        MetricCard(
                            "Windows printers",
                            status?.printers?.size?.toString() ?: "0",
                            "Detected on this station",
                            onClick = { selectedPage = StationPage.PRINTERS },
                        )
                        MetricCard(
                            "Completed this session",
                            status?.completedJobCount?.toString() ?: "0",
                            "Recent station print activity",
                            onClick = { selectedPage = StationPage.AUDIT },
                        )
                        StationSecurityCard(status)
                    }
                }
            }
        }
    }
}

@Composable
private fun NavLabel(page: StationPage, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) BlueTint else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(page.symbol, color = if (selected) Blue else Muted, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(
            page.title,
            color = if (selected) Color(0xFF1E40AF) else InkMuted,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

@Composable
private fun StatusPill(label: String, ready: Boolean) {
    val failed = label.contains("FAILED", ignoreCase = true) ||
        label.contains("ERROR", ignoreCase = true) ||
        label.contains("OFFLINE", ignoreCase = true) ||
        label.contains("SECURITY_ALERT", ignoreCase = true)
    val background = when {
        failed -> RedTint
        ready -> GreenTint
        else -> AmberTint
    }
    val foreground = when {
        failed -> Red
        ready -> Color(0xFF047857)
        else -> Color(0xFFB45309)
    }
    Text(
        label,
        Modifier.background(background, RoundedCornerShape(30.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        color = foreground,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun MetricCard(title: String, value: String, detail: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = CanvasWhite),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
    ) {
        Column(Modifier.fillMaxWidth().padding(17.dp)) {
            Text(title, color = InkMuted, fontSize = 12.sp)
            Spacer(Modifier.height(7.dp))
            Text(value, color = Ink, fontSize = 27.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(3.dp))
            Text(detail, color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun OverviewPage(
    status: StationStatus?,
    shop: ShopDetails?,
    queue: List<PrintJobStatus>,
    actionMessage: String?,
    autoPrintBusy: Boolean,
    onOpenQr: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenPrinters: () -> Unit,
    onToggleAutoPrint: (Boolean) -> Unit,
) {
    Text("Shop dashboard", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    Text("Your counter, print queue, printers and secure Windows station.", color = InkMuted, fontSize = 13.sp)
    shop?.let { ShopIdentityCard(it, onOpenQr) }
    actionMessage?.let { NoticeCard(it, isError = it.contains("could not", true) || it.contains("failed", true)) }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SummaryCard(
            "Print queue",
            queue.count { it.status !in setOf("COMPLETED", "FAILED", "CANCELLED", "EXPIRED") }.toString(),
            "Open queue",
            onOpenQueue,
            Modifier.weight(1f),
        )
        SummaryCard(
            "Printer",
            status?.printers?.firstOrNull()?.status ?: "Not configured",
            "Manage printers",
            onOpenPrinters,
            Modifier.weight(1f),
        )
    }
    CardBlock {
        Text("Direct auto-print", color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(
            "Automatically print jobs after the customer authorizes them. Turn this off to hold authorized jobs at the shop.",
            color = InkMuted,
            fontSize = 12.sp,
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                if (status?.autoPrintEnabled == true) "Automatic printing is on" else "Automatic printing is paused",
                color = if (status?.autoPrintEnabled == true) Green else Amber,
                fontWeight = FontWeight.SemiBold,
            )
            Switch(
                checked = status?.autoPrintEnabled == true,
                onCheckedChange = onToggleAutoPrint,
                enabled = !autoPrintBusy,
            )
        }
    }
    status?.let { ShopConnectedCard(it) }
}

@Composable
private fun ShopIdentityCard(shop: ShopDetails, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = CanvasWhite),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (shop.permanentQrPayload.isNotBlank()) {
                QrCodeCanvas(shop.permanentQrPayload, Modifier.size(72.dp))
            } else {
                Box(
                    Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)).background(SurfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("QR", color = Muted, fontWeight = FontWeight.Bold)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(shop.name, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(if (shop.verified) "✓" else "•", color = if (shop.verified) Green else Muted)
                }
                Text(shop.address.ifBlank { "Xerox shop" }, color = InkMuted, fontSize = 12.sp)
                Text("Permanent counter QR · ${shop.id}", color = Muted, fontSize = 11.sp)
                Text("Tap to display shop QR", color = Blue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SummaryCard(
    title: String,
    value: String,
    action: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = CanvasWhite),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = InkMuted, fontSize = 12.sp)
            Text(value, color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(action, color = Blue, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ShopQrPage(shop: ShopDetails?, actionMessage: String?) {
    Text("Permanent counter QR", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    Text("Customers scan this code in the PrivPrint Android app to choose your shop.", color = InkMuted, fontSize = 13.sp)
    actionMessage?.let { NoticeCard(it, isError = false) }
    if (shop == null || shop.permanentQrPayload.isBlank()) {
        NoticeCard("Shop QR is unavailable. Refresh the shop details and try again.", isError = true)
        return
    }
    CardBlock {
        Text(shop.name, color = Ink, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Text(shop.address, color = InkMuted, fontSize = 13.sp)
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            QrCodeCanvas(shop.permanentQrPayload, Modifier.size(280.dp))
        }
        Text("Shop ID  ${shop.id}", color = Muted, fontSize = 12.sp)
        Text("This permanent QR contains the shop ID only; it does not contain credentials.", color = InkMuted, fontSize = 12.sp)
    }
}

@Composable
private fun PrintQueuePage(
    queue: List<PrintJobStatus>,
    busy: Boolean,
    error: String?,
    actionMessage: String?,
) {
    Text("Print queue", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    Text("Customer-authorized jobs are processed by this encrypted Windows station.", color = InkMuted, fontSize = 13.sp)
    actionMessage?.let { NoticeCard(it, isError = false) }
    when {
        busy -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(Modifier.size(20.dp), color = Blue, strokeWidth = 2.dp)
            Text("Loading shop queue…", color = InkMuted, fontSize = 13.sp)
        }
        error != null -> NoticeCard(error, isError = true)
        queue.isEmpty() -> CardBlock {
            Text("No print jobs", color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("New customer jobs will appear here after they are submitted to this shop.", color = InkMuted, fontSize = 12.sp)
        }
        else -> queue.forEach { job ->
            CardBlock {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Job #${job.id.takeLast(8)}", color = Ink, fontWeight = FontWeight.Bold)
                    StatusPill(job.status.replace('_', ' '), job.status in setOf("COMPLETED", "PRINTING"))
                }
                Text("${job.copies.ifBlank { "1" }} authorized copy/copies", color = InkMuted, fontSize = 12.sp)
                if (job.createdAt.isNotBlank()) Text("Submitted ${job.createdAt}", color = Muted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun PrinterPage(status: StationStatus?, actionMessage: String?) {
    Text("Connected printers", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    Text("Windows printers detected by the station and synchronized with your shop.", color = InkMuted, fontSize = 13.sp)
    actionMessage?.let {
        NoticeCard(it, isError = it.contains("failed", true) || it.contains("could not", true))
    }
    val printers = status?.printers.orEmpty()
    if (printers.isEmpty()) {
        CardBlock {
            Text("No Windows printers detected", color = Ink, fontWeight = FontWeight.Bold)
            Text("Connect and install a printer in Windows, then choose Sync printers.", color = InkMuted, fontSize = 12.sp)
        }
    } else {
        printers.forEach { printer ->
            CardBlock {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(printer.name, color = Ink, fontWeight = FontWeight.SemiBold)
                        Text(printer.model.ifBlank { "Windows printer" }, color = InkMuted, fontSize = 12.sp)
                    }
                    StatusPill(printer.status, printer.status == "READY")
                }
                HorizontalDivider(color = Border)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Paper", color = InkMuted, fontSize = 12.sp)
                    Text(printer.paper.ifBlank { "Unknown" }, color = Ink, fontSize = 12.sp)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Toner", color = InkMuted, fontSize = 12.sp)
                    Text(printer.toner.takeIf(String::isNotBlank)?.let { "$it%" } ?: "Unknown", color = Ink, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun AuditPage(events: List<AuditEvent>) {
    Text("Shop security audit", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    Text("Recent Windows station events for printer, connection and key-registration activity.", color = InkMuted, fontSize = 13.sp)
    if (events.isEmpty()) {
        CardBlock {
            Text("No station events recorded", color = Ink, fontWeight = FontWeight.Bold)
            Text("Events will appear as this station connects and processes print jobs.", color = InkMuted, fontSize = 12.sp)
        }
    } else {
        events.take(50).forEach { event ->
            CardBlock {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(event.eventType.replace('_', ' '), color = Ink, fontWeight = FontWeight.SemiBold)
                    StatusPill(event.severity, event.severity.equals("INFO", true))
                }
                Text(event.details, color = InkMuted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun StationSecurityPage(
    status: StationStatus?,
    actionMessage: String?,
    onReconnect: () -> Unit,
) {
    Text("Windows PC station", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    Text("Station registration and encrypted printing health.", color = InkMuted, fontSize = 13.sp)
    status?.let { ShopConnectedCard(it) }
    CardBlock {
        Text("Station security", color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(
            if (status?.encryptionKeyRegistered == true) {
                "The station's encryption key is registered. This device can receive encrypted customer documents for this shop."
            } else {
                "The station key is not registered yet. Keep the app open and reconnect the shop station."
            },
            color = InkMuted,
            fontSize = 12.sp,
        )
        Text("Private key storage: Windows protected user profile", color = Blue, fontSize = 12.sp)
        Text("Connection: ${status?.connectionState ?: "UNKNOWN"}", color = InkMuted, fontSize = 12.sp)
        if (status?.connectionError?.isNotBlank() == true) {
            Text(status.connectionError, color = Red, fontSize = 12.sp)
        }
        actionMessage?.let { NoticeCard(it, isError = it.contains("could not", true)) }
        OutlinedButton(onClick = onReconnect) {
            Text("Reconnect to cloud")
        }
    }
}

@Composable
private fun ShopConnectedCard(status: StationStatus) {
    CardBlock {
        Text("Shop connected", color = Green, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Text("Shop ID  ${status.shopId}", color = Ink)
        Text("Station ID  ${status.deviceId.ifBlank { "Registering…" }}", color = InkMuted, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(
                if (status.encryptionKeyRegistered) "KEY REGISTERED" else "KEY PENDING",
                status.encryptionKeyRegistered,
            )
            StatusPill(if (status.realtimeConnected) "ONLINE" else "RECONNECTING", status.realtimeConnected)
        }
        if (!status.encryptionKeyRegistered) {
            Text("Keep this app open while the station finishes secure registration.", color = Amber, fontSize = 12.sp)
        }
    }
}

@Composable
private fun StationSecurityCard(status: StationStatus?) {
    Card(
        colors = CardDefaults.cardColors(containerColor = BlueTint),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Station security", color = Ink, fontWeight = FontWeight.Bold)
            Text(
                if (status?.encryptionKeyRegistered == true) "Encryption key registered"
                else "Connect to register this station key",
                color = InkMuted,
                fontSize = 12.sp,
            )
            Text("Windows protected storage", color = Blue, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun CardBlock(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CanvasWhite),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(17.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
private fun NoticeCard(message: String, isError: Boolean) {
    val foreground = if (isError) Red else Color(0xFF047857)
    val background = if (isError) RedTint else GreenTint
    Text(
        message,
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(background).padding(12.dp),
        color = foreground,
        fontSize = 12.sp,
    )
}

@Composable
private fun AuthCard(
    isRegistering: Boolean,
    busy: Boolean,
    error: String?,
    onModeChange: (Boolean) -> Unit,
    onSubmit: (String, String, String, String, String) -> Unit,
) {
    var fullName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var shopName by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CanvasWhite),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
    ) {
        Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (isRegistering) "Create your shop account" else "Sign in to your shop",
                color = Ink,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            if (isRegistering) FormField("Your name", fullName, { fullName = it })
            FormField("Email", email, { email = it })
            FormField("Password", password, { password = it }, secret = true)
            if (isRegistering) {
                FormField("Shop name", shopName, { shopName = it })
                FormField("Shop address", address, { address = it })
            }
            if (!error.isNullOrBlank()) Text(error, color = Red, fontSize = 12.sp)
            Button(
                enabled = !busy,
                onClick = { onSubmit(fullName, email, password, shopName, address) },
                modifier = Modifier.fillMaxWidth().height(46.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = CanvasWhite),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(if (isRegistering) "Create account and shop" else "Sign in", fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = { onModeChange(!isRegistering) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(if (isRegistering) "Already have an account? Sign in" else "New shop? Create an account", color = Blue)
            }
        }
    }
}

@Composable
private fun FormField(label: String, value: String, onValueChange: (String) -> Unit, secret: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
    )
}

@Composable
private fun ShopPicker(
    shops: List<ShopOption>,
    selected: ShopOption?,
    busy: Boolean,
    error: String?,
    onSelect: (ShopOption) -> Unit,
    onConnect: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CanvasWhite),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
    ) {
        Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Choose your shop", color = Ink, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            shops.forEach { shop ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(if (selected?.id == shop.id) BlueTint else Background)
                        .clickable { onSelect(shop) }
                        .padding(13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(shop.name, color = Ink, fontWeight = FontWeight.SemiBold)
                        Text(shop.id, color = Muted, fontSize = 11.sp)
                    }
                    Text(if (selected?.id == shop.id) "Selected" else "Select", color = Blue, fontSize = 12.sp)
                }
            }
            if (!error.isNullOrBlank()) Text(error, color = Red, fontSize = 12.sp)
            Button(
                enabled = selected != null && !busy,
                onClick = onConnect,
                modifier = Modifier.fillMaxWidth().height(46.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = CanvasWhite),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Connect shop and register station", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun QrCodeCanvas(payload: String, modifier: Modifier = Modifier) {
    val matrix = remember(payload) {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val encoded = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0, hints)
        Array(encoded.height) { row ->
            BooleanArray(encoded.width) { column -> encoded.get(column, row) }
        }
    }

    Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(CanvasWhite).padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val rows = matrix.size
            val columns = matrix.firstOrNull()?.size ?: 0
            if (rows > 0 && columns > 0) {
                val moduleWidth = size.width / columns
                val moduleHeight = size.height / rows
                for (row in 0 until rows) {
                    for (column in 0 until columns) {
                        if (matrix[row][column]) {
                            drawRect(
                                color = Ink,
                                topLeft = Offset(column * moduleWidth, row * moduleHeight),
                                size = Size(moduleWidth + 0.4f, moduleHeight + 0.4f),
                            )
                        }
                    }
                }
            }
        }
    }
}
