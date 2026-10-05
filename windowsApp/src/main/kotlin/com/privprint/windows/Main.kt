package com.privprint.windows

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Navy = Color(0xFF0B1220)
private val Panel = Color(0xFF111C2E)
private val RaisedPanel = Color(0xFF18263A)
private val Cyan = Color(0xFF38BDF8)
private val Muted = Color(0xFF9AAAC0)
private val Green = Color(0xFF34D399)

private enum class StationPage(val title: String) {
    OVERVIEW("Overview"),
    QUEUE("Print queue"),
    PRINTERS("Printers"),
    SECURITY("Security & station"),
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
        title = "PrivPrint | Shop Station",
        state = rememberWindowState(width = 1180.dp, height = 800.dp),
    ) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = Cyan,
                onPrimary = Navy,
                background = Navy,
                surface = Panel,
                onSurface = Color(0xFFF4F8FF),
                secondary = Green,
            ),
        ) {
            Surface(Modifier.fillMaxSize(), color = Navy) {
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
        CircularProgressIndicator(color = Cyan)
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
        Text("PrivPrint could not start", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(message, color = Color(0xFFFCA5A5))
        Spacer(Modifier.height(10.dp))
        Text("Close and reopen the application. If it continues, send this error to support.", color = Muted)
    }
}

@Composable
private fun StationApplication(bridge: StationBridge) {
    var status by remember { mutableStateOf<StationStatus?>(null) }
    var shops by remember { mutableStateOf<List<ShopOption>>(emptyList()) }
    var selectedShop by remember { mutableStateOf<ShopOption?>(null) }
    var isRegistering by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedPage by remember { mutableStateOf(StationPage.OVERVIEW) }
    var queue by remember { mutableStateOf<List<PrintJobStatus>>(emptyList()) }
    var queueBusy by remember { mutableStateOf(false) }
    var queueError by remember { mutableStateOf<String?>(null) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun refreshStatus() {
        status = withContext(Dispatchers.IO) { bridge.status() }
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
            .onFailure { error = it.message }
        while (true) {
            delay(4_000)
            runCatching { refreshStatus() }
                .onFailure { error = it.message }
        }
    }

    Row(Modifier.fillMaxSize().background(Navy)) {
        Column(
            Modifier.width(252.dp).fillMaxHeight().background(Color(0xFF080E18)).padding(22.dp),
        ) {
            Text("PRIVPRINT", color = Cyan, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
            Text("SECURE SHOP STATION", color = Muted, fontSize = 10.sp, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(38.dp))
            Text("WORKSPACE", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            NavLabel("Overview", selectedPage == StationPage.OVERVIEW) {
                selectedPage = StationPage.OVERVIEW
                actionMessage = null
            }
            NavLabel("Print queue", selectedPage == StationPage.QUEUE) {
                selectedPage = StationPage.QUEUE
                if (status?.authenticated == true) scope.launch { refreshQueue() }
            }
            NavLabel("Printers", selectedPage == StationPage.PRINTERS) {
                selectedPage = StationPage.PRINTERS
                actionMessage = null
            }
            NavLabel("Security & station", selectedPage == StationPage.SECURITY) {
                selectedPage = StationPage.SECURITY
                actionMessage = null
            }
            Spacer(Modifier.weight(1f))
            HorizontalDivider(color = RaisedPanel)
            Spacer(Modifier.height(16.dp))
            Text("Encrypted delivery", color = Color.White, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text("Files remain encrypted until authorized by this station.", color = Muted, fontSize = 12.sp)
        }

        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(68.dp).background(Panel).padding(horizontal = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(selectedPage.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(status?.shopId?.takeIf(String::isNotBlank)?.let { "Shop ID  $it" } ?: "Set up your shop account", color = Muted, fontSize = 12.sp)
                }
                TextButton(
                    onClick = {
                        scope.launch {
                            when (selectedPage) {
                                StationPage.QUEUE -> refreshQueue()
                                StationPage.PRINTERS -> {
                                    actionMessage = null
                                    runCatching {
                                        val count = withContext(Dispatchers.IO) { bridge.refreshPrinters() }
                                        refreshStatus()
                                        actionMessage = "$count printer(s) synced with the shop backend."
                                    }.onFailure {
                                        actionMessage = it.message ?: "Printer sync failed."
                                    }
                                }
                                else -> runCatching { refreshStatus() }
                                    .onFailure { error = it.message }
                            }
                        }
                    },
                    enabled = !queueBusy,
                ) {
                    Text(if (selectedPage == StationPage.PRINTERS) "Sync printers" else "Refresh", color = Cyan)
                }
                StatusPill(
                    if (status?.realtimeConnected == true) "CLOUD CONNECTED" else "CLOUD RECONNECTING",
                    status?.realtimeConnected == true,
                )
            }

            Row(
                Modifier.fillMaxSize().padding(28.dp),
                horizontalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    if (status?.authenticated != true) {
                        if (selectedPage == StationPage.OVERVIEW) {
                            Text("Welcome to PrivPrint", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                            Text("Sign in with your Xerox shop operator account, or register a shop. This station registers its encryption key when connected.", color = Muted)
                            AuthCard(
                                isRegistering = isRegistering,
                                busy = busy,
                                error = error,
                                onModeChange = {
                                    isRegistering = it
                                    error = null
                                    shops = emptyList()
                                },
                                onSubmit = { fullName, email, password, shopName, address ->
                                    busy = true
                                    error = null
                                    scope.launch {
                                        runCatching {
                                            shops = withContext(Dispatchers.IO) {
                                                if (isRegistering) {
                                                    bridge.register(fullName, email, password, shopName, address)
                                                } else {
                                                    bridge.login(email, password)
                                                }
                                            }
                                            selectedShop = shops.firstOrNull()
                                        }.onFailure { error = it.message ?: "Account request failed." }
                                        busy = false
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
                                        val shop = selectedShop ?: return@ShopPicker
                                        busy = true
                                        error = null
                                        scope.launch {
                                            runCatching {
                                                withContext(Dispatchers.IO) { bridge.connect(shop.id) }
                                                refreshStatus()
                                            }.onFailure { error = it.message ?: "Station connection failed." }
                                            busy = false
                                        }
                                    },
                                )
                            }
                        } else {
                            Text("Connect a Xerox shop account to view ${selectedPage.title.lowercase()}.", color = Muted)
                        }
                    } else {
                        when (selectedPage) {
                            StationPage.OVERVIEW -> {
                                status?.let { ShopConnectedCard(it, error) }
                                Text("Station overview", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                                Text("Jobs are received from the PrivPrint cloud and printed by this Windows station.", color = Muted)
                            }
                            StationPage.QUEUE -> PrintQueue(queue, queueBusy, queueError)
                            StationPage.PRINTERS -> {
                                actionMessage?.let { Text(it, color = if (it.contains("failed", true) || it.contains("could not", true)) Color(0xFFFCA5A5) else Green, fontSize = 12.sp) }
                                status?.let { PrinterList(it.printers) }
                            }
                            StationPage.SECURITY -> {
                                status?.let {
                                    SecurityDetails(it, actionMessage) {
                                        scope.launch {
                                            actionMessage = null
                                            runCatching {
                                                withContext(Dispatchers.IO) { bridge.reconnectRealtime() }
                                                actionMessage = "Cloud reconnect requested. The station will update its connection status automatically."
                                            }.onFailure {
                                                actionMessage = it.message ?: "Could not reconnect to the cloud."
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (selectedPage == StationPage.OVERVIEW) {
                    Column(Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        MetricCard("Shop queue", queue.size.toString(), "Cloud jobs loaded when you open Print queue")
                        MetricCard("Available printers", status?.printers?.size?.toString() ?: "—", "Detected from Windows")
                        MetricCard("Completed this session", status?.completedJobCount?.toString() ?: "—", "Recent station activity")
                        StationSecurityCard(status)
                    }
                }
            }
        }
    }
}

@Composable
private fun NavLabel(label: String, selected: Boolean = false, onClick: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) RaisedPanel else Color.Transparent, RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(if (selected) Cyan else Muted, RoundedCornerShape(50)))
        Spacer(Modifier.width(11.dp))
        Text(label, color = if (selected) Color.White else Muted, fontSize = 13.sp)
    }
}

@Composable
private fun PrintQueue(jobs: List<PrintJobStatus>, loading: Boolean, error: String?) {
        Text("Print queue", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Live jobs for this shop, loaded from the shared PrivPrint cloud backend.", color = Muted)
        if (loading) CircularProgressIndicator(color = Cyan, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        if (!error.isNullOrBlank()) Text(error, color = Color(0xFFFCA5A5), fontSize = 13.sp)
        if (jobs.isEmpty() && !loading && error.isNullOrBlank()) Text("The cloud queue is empty.", color = Muted)
        jobs.forEach { job ->
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(12.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(job.id, color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text(
                            listOfNotNull(
                                job.createdAt.takeIf(String::isNotBlank),
                                job.copies.takeIf(String::isNotBlank)?.let { "$it copies" },
                            ).joinToString(" · ").ifBlank { "Shop print job" },
                            color = Muted,
                            fontSize = 11.sp,
                        )
                    }
                    StatusPill(job.status.ifBlank { "UNKNOWN" }, job.status == "COMPLETED")
                }
            }
        }
    }

@Composable
private fun SecurityDetails(status: StationStatus, message: String?, onReconnect: () -> Unit) {
        Text("Security & station", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Shop: ${status.shopId}", color = Color.White)
                Text("Station device: ${status.deviceId.ifBlank { "Not registered" }}", color = Muted)
                Text("Backend: ${status.serverUrl}", color = Muted, fontSize = 11.sp)
                StatusPill(
                    if (status.encryptionKeyRegistered) "ENCRYPTION KEY REGISTERED" else "ENCRYPTION KEY NOT REGISTERED",
                    status.encryptionKeyRegistered,
                )
                StatusPill("${status.connectionState}: ${if (status.realtimeConnected) "Connected" else "Not connected"}", status.realtimeConnected)
                if (!status.connectionError.isNullOrBlank()) {
                    Text("Realtime issue: ${status.connectionError}", color = Color(0xFFFFC66D), fontSize = 12.sp)
                }
                if (!message.isNullOrBlank()) Text(message, color = Muted, fontSize = 12.sp)
                Button(
                    onClick = onReconnect,
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Navy),
                ) {
                    Text("Reconnect cloud", fontWeight = FontWeight.Bold)
                }
            }
        }
        Text("Recent station audit", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        if (status.auditLog.isEmpty()) Text("No station events recorded in this session.", color = Muted)
        status.auditLog.take(30).forEach { event ->
            Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(9.dp)).padding(12.dp)) {
                Text("${event.severity} · ${event.eventType}", color = if (event.severity == "ERROR") Color(0xFFFCA5A5) else Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(event.details, color = Color.White, fontSize = 12.sp)
            }
        }
    }

@Composable
private fun StatusPill(label: String, ready: Boolean) {
    Text(
        label,
        Modifier.background(
            if (ready) Color(0xFF073B32) else Color(0xFF3D2B17),
            RoundedCornerShape(30.dp),
        ).padding(horizontal = 13.dp, vertical = 8.dp),
        color = if (ready) Green else Color(0xFFFFC66D),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun MetricCard(title: String, value: String, detail: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text(title, color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Text(value, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(3.dp))
            Text(detail, color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ShopConnectedCard(status: StationStatus, error: String?) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Shop connected", color = Green, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("Shop ID: ${status.shopId}", color = Color.White)
            Text("Station ID: ${status.deviceId.ifBlank { "Registering…" }}", color = Muted, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusPill(
                    if (status.encryptionKeyRegistered) "KEY REGISTERED" else "KEY PENDING",
                    status.encryptionKeyRegistered,
                )
                StatusPill(if (status.realtimeConnected) "ONLINE" else "RECONNECTING", status.realtimeConnected)
            }
            if (!status.encryptionKeyRegistered) {
                Text("Keep this app open while the station finishes secure registration.", color = Color(0xFFFFC66D), fontSize = 12.sp)
            }
            if (!error.isNullOrBlank()) Text(error, color = Color(0xFFFCA5A5), fontSize = 12.sp)
        }
    }
}

@Composable
private fun StationSecurityCard(status: StationStatus?) {
    Card(colors = CardDefaults.cardColors(containerColor = RaisedPanel), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Station security", color = Color.White, fontWeight = FontWeight.Bold)
            Text(
                if (status?.encryptionKeyRegistered == true) "This station has a registered encryption key. Customer uploads can be securely addressed to this station."
                else "Connect this Windows station to your shop to create and register its encryption key.",
                color = Muted,
                fontSize = 12.sp,
            )
            Text("Private key storage: Windows protected profile", color = Cyan, fontSize = 11.sp)
        }
    }
}

@Composable
private fun PrinterList(printers: List<PrinterStatus>) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Windows printers", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            if (printers.isEmpty()) {
                Text("No printers detected. Connect a printer and check that it appears in Windows Settings.", color = Muted, fontSize = 12.sp)
            } else {
                printers.forEach { printer ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(printer.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text(printer.model, color = Muted, fontSize = 11.sp)
                        }
                        Text(printer.status, color = Green, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    HorizontalDivider(color = RaisedPanel)
                }
            }
        }
    }
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

    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (isRegistering) "Create your shop account" else "Sign in to your shop", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (isRegistering) {
                FormField("Your name", fullName, { fullName = it })
            }
            FormField("Email", email, { email = it })
            FormField("Password", password, { password = it }, secret = true)
            if (isRegistering) {
                FormField("Shop name", shopName, { shopName = it })
                FormField("Shop address", address, { address = it })
            }
            if (!error.isNullOrBlank()) Text(error, color = Color(0xFFFCA5A5), fontSize = 12.sp)
            Button(
                enabled = !busy,
                onClick = { onSubmit(fullName, email, password, shopName, address) },
                modifier = Modifier.fillMaxWidth().height(46.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Navy),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(if (isRegistering) "Create account and shop" else "Sign in", fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = { onModeChange(!isRegistering) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(if (isRegistering) "Already have an account? Sign in" else "New shop? Create an account", color = Cyan)
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
    var expanded by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Choose your shop", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Box {
                OutlinedTextField(
                    value = selected?.name.orEmpty(),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Shop") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Box(Modifier.matchParentSize().background(Color.Transparent).padding(1.dp))
                TextButton(onClick = { expanded = true }, modifier = Modifier.align(Alignment.CenterEnd)) {
                    Text("Select", color = Cyan)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    shops.forEach { shop ->
                        DropdownMenuItem(
                            text = { Text("${shop.name}  ·  ${shop.id}") },
                            onClick = {
                                onSelect(shop)
                                expanded = false
                            },
                        )
                    }
                }
            }
            if (!error.isNullOrBlank()) Text(error, color = Color(0xFFFCA5A5), fontSize = 12.sp)
            Button(
                enabled = !busy && selected != null,
                onClick = onConnect,
                modifier = Modifier.fillMaxWidth().height(46.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Navy),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Connect this Windows station", fontWeight = FontWeight.Bold)
            }
            Text("Connecting registers this device and its encryption key with your shop.", color = Muted, fontSize = 11.sp)
        }
    }
}
