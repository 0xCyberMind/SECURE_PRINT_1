package com.privprint.windows

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
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

// ─── Colour tokens ────────────────────────────────────────────────────────────
private val Ink           = Color(0xFF0F172A)
private val InkMuted      = Color(0xFF475569)
private val Muted         = Color(0xFF64748B)
private val CanvasWhite   = Color(0xFFFFFFFF)
private val Background    = Color(0xFFF8FAFC)
private val SurfaceVariant= Color(0xFFF1F5F9)
private val Border        = Color(0xFFE2E8F0)
private val Blue          = Color(0xFF2563EB)
private val BlueDark      = Color(0xFF1E40AF)
private val BlueTint      = Color(0xFFEFF6FF)
private val Green         = Color(0xFF059669)
private val GreenDark     = Color(0xFF047857)
private val GreenTint     = Color(0xFFECFDF5)
private val Amber         = Color(0xFFD97706)
private val AmberTint     = Color(0xFFFFFBEB)
private val Red           = Color(0xFFDC2626)
private val RedTint       = Color(0xFFFEF2F2)
private val Sidebar       = Color(0xFF0F172A)
private val SidebarItem   = Color(0xFF1E293B)

// ─── Navigation pages ─────────────────────────────────────────────────────────
private enum class StationPage(val title: String, val symbol: String) {
    OVERVIEW  ("Dashboard",        "⌂"),
    QUEUE     ("Print Jobs",       "▤"),
    PRINTERS  ("Printers",         "▣"),
    SHOP_QR   ("Shop QR",          "▦"),
    AUDIT     ("Security Audit",   "◈"),
    SECURITY  ("Windows Station",  "⊞"),
    SETTINGS  ("Settings",         "⚙"),
}

// ─── App entry point ──────────────────────────────────────────────────────────
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
        onCloseRequest = { bridge.close(); exitApplication() },
        title          = "PrivPrint | Xerox Shop Station",
        state          = rememberWindowState(width = 1340.dp, height = 860.dp),
    ) {
        MaterialTheme(
            colorScheme = lightColorScheme(
                primary             = Blue,
                onPrimary           = CanvasWhite,
                primaryContainer    = BlueTint,
                onPrimaryContainer  = BlueDark,
                secondary           = Ink,
                background          = Background,
                onBackground        = Ink,
                surface             = CanvasWhite,
                onSurface           = Ink,
                surfaceVariant      = SurfaceVariant,
                onSurfaceVariant    = InkMuted,
                outline             = Border,
                error               = Red,
            ),
        ) {
            Surface(Modifier.fillMaxSize(), color = Background) {
                when {
                    startupError != null -> StartupFailure(startupError!!)
                    !isReady             -> SplashScreen()
                    else                 -> AppRoot(bridge)
                }
            }
        }
    }
}

// ─── Splash / error ───────────────────────────────────────────────────────────
@Composable
private fun SplashScreen() {
    Box(Modifier.fillMaxSize().background(Sidebar), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("PRIVPRINT", color = Blue, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
            Text("XEROX SHOP STATION", color = Muted, fontSize = 11.sp, letterSpacing = 3.sp)
            Spacer(Modifier.height(8.dp))
            CircularProgressIndicator(color = Blue, modifier = Modifier.size(28.dp))
            Text("Starting secure Windows station…", color = Muted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun StartupFailure(message: String) {
    Box(Modifier.fillMaxSize().background(Background), contentAlignment = Alignment.Center) {
        Card(Modifier.widthIn(max = 520.dp), colors = CardDefaults.cardColors(CanvasWhite), shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Border)) {
            Column(Modifier.padding(36.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Could not start PrivPrint Station", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(message, color = Red, fontSize = 13.sp)
                Text("Close and reopen the application. If this continues, contact support.", color = Muted, fontSize = 12.sp)
            }
        }
    }
}

// ─── App Root — decides login vs dashboard ────────────────────────────────────
@Composable
private fun AppRoot(bridge: StationBridge) {
    var status by remember { mutableStateOf<StationStatus?>(null) }
    var sessionChecked by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching { status = withContext(Dispatchers.IO) { bridge.status() } }
        sessionChecked = true
    }

    if (!sessionChecked) {
        SplashScreen()
        return
    }

    if (status?.authenticated == true) {
        StationApplication(bridge, initialStatus = status)
    } else {
        LoginRoot(bridge) {
            // On successful login+connect, refresh status and enter dashboard
            runCatching { status = bridge.status() }
        }
    }
}

// ─── LOGIN ROOT ───────────────────────────────────────────────────────────────
@Composable
private fun LoginRoot(bridge: StationBridge, onAuthenticated: suspend () -> Unit) {
    var status    by remember { mutableStateOf<StationStatus?>(null) }
    val scope      = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        // after a successful connect the bridge will report authenticated
        while (true) {
            delay(1_500)
            runCatching { status = withContext(Dispatchers.IO) { bridge.status() } }
            if (status?.authenticated == true) { onAuthenticated(); break }
        }
    }

    if (status?.authenticated == true) {
        StationApplication(bridge, initialStatus = status)
        return
    }

    LoginScreen(bridge)
}

// ─── LOGIN SCREEN ─────────────────────────────────────────────────────────────
@Composable
private fun LoginScreen(bridge: StationBridge) {
    var isRegistering by remember { mutableStateOf(false) }
    var email         by remember { mutableStateOf("") }
    var password      by remember { mutableStateOf("") }
    var fullName      by remember { mutableStateOf("") }
    var shopName      by remember { mutableStateOf("") }
    var shopAddress   by remember { mutableStateOf("") }
    var busy          by remember { mutableStateOf(false) }
    var error         by remember { mutableStateOf<String?>(null) }
    var shops         by remember { mutableStateOf<List<ShopOption>>(emptyList()) }
    var selectedShop  by remember { mutableStateOf<ShopOption?>(null) }
    val scope         = rememberCoroutineScope()

    Row(Modifier.fillMaxSize()) {
        // Left brand panel
        Box(
            Modifier.width(420.dp).fillMaxHeight().background(Sidebar),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Logo mark
                Box(Modifier.size(72.dp).clip(RoundedCornerShape(20.dp)).background(Blue), contentAlignment = Alignment.Center) {
                    Text("🖨", fontSize = 36.sp)
                }
                Spacer(Modifier.height(4.dp))
                Text("PRIVPRINT", color = CanvasWhite, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                Text("XEROX SHOP STATION", color = Color(0xFF64748B), fontSize = 10.sp, letterSpacing = 3.sp)
                Spacer(Modifier.height(32.dp))
                BrandPoint("End-to-end encrypted printing")
                BrandPoint("Operator-controlled job queue")
                BrandPoint("Direct secure cloud connection")
                BrandPoint("Privacy-first document handling")
                Spacer(Modifier.height(32.dp))
                Text("v1.0.4  ·  Secure Windows Station", color = Color(0xFF334155), fontSize = 11.sp)
            }
        }

        // Right login form
        Box(
            Modifier.fillMaxSize().background(Background),
            contentAlignment = Alignment.Center,
        ) {
            Card(
                Modifier.widthIn(min = 380.dp, max = 460.dp),
                colors = CardDefaults.cardColors(CanvasWhite),
                shape  = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                elevation = CardDefaults.cardElevation(2.dp),
            ) {
                Column(Modifier.padding(36.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    Text(
                        if (isRegistering) "Create Shop Account" else "Shop Operator Login",
                        color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (isRegistering) "Register a new shop operator account." else "Sign in to manage your Xerox shop station.",
                        color = InkMuted, fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(24.dp))

                    if (shops.isEmpty()) {
                        // ── Auth form ──
                        if (isRegistering) {
                            LoginField("Full Name", fullName, { fullName = it })
                            Spacer(Modifier.height(12.dp))
                        }
                        LoginField("Email Address", email, { email = it })
                        Spacer(Modifier.height(12.dp))
                        LoginField("Password", password, { password = it }, secret = true, onEnter = {
                            if (!busy && email.isNotBlank() && password.isNotBlank()) {
                                busy = true; error = null
                                scope.launch {
                                    try {
                                        shops = withContext(Dispatchers.IO) {
                                            if (isRegistering) bridge.register(fullName, email, password, shopName, shopAddress)
                                            else bridge.login(email, password)
                                        }
                                        selectedShop = shops.firstOrNull()
                                    } catch (e: Exception) { error = e.message ?: "Authentication failed." }
                                    finally { busy = false }
                                }
                            }
                        })
                        if (isRegistering) {
                            Spacer(Modifier.height(12.dp))
                            LoginField("Shop Name", shopName, { shopName = it })
                            Spacer(Modifier.height(12.dp))
                            LoginField("Shop Address", shopAddress, { shopAddress = it })
                        }
                        Spacer(Modifier.height(8.dp))
                        error?.let {
                            Text(it, color = Red, fontSize = 12.sp,
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(RedTint).padding(10.dp))
                            Spacer(Modifier.height(6.dp))
                        }
                        Button(
                            enabled  = !busy && email.isNotBlank() && password.isNotBlank(),
                            onClick  = {
                                busy = true; error = null
                                scope.launch {
                                    try {
                                        shops = withContext(Dispatchers.IO) {
                                            if (isRegistering) bridge.register(fullName, email, password, shopName, shopAddress)
                                            else bridge.login(email, password)
                                        }
                                        selectedShop = shops.firstOrNull()
                                    } catch (e: Exception) { error = e.message ?: "Authentication failed." }
                                    finally { busy = false }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            colors   = ButtonDefaults.buttonColors(Blue, CanvasWhite),
                            shape    = RoundedCornerShape(12.dp),
                        ) {
                            if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = CanvasWhite, strokeWidth = 2.dp)
                            else Text(if (isRegistering) "Create Account & Shop" else "Sign In", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                        Spacer(Modifier.height(14.dp))
                        TextButton(
                            onClick  = { isRegistering = !isRegistering; error = null; shops = emptyList(); selectedShop = null },
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        ) {
                            Text(if (isRegistering) "Already have an account? Sign in" else "New shop? Create an account", color = Blue, fontSize = 13.sp)
                        }
                    } else {
                        // ── Shop picker ──
                        Text("Select Your Shop", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Spacer(Modifier.height(10.dp))
                        shops.forEach { shop ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                    .background(if (selectedShop?.id == shop.id) BlueTint else SurfaceVariant)
                                    .border(1.dp, if (selectedShop?.id == shop.id) Blue else Border, RoundedCornerShape(10.dp))
                                    .clickable { selectedShop = shop }
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(if (selectedShop?.id == shop.id) Blue else Border))
                                Column(Modifier.weight(1f)) {
                                    Text(shop.name, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    Text(shop.id.takeLast(12), color = Muted, fontSize = 11.sp)
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                        error?.let {
                            Text(it, color = Red, fontSize = 12.sp,
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(RedTint).padding(10.dp))
                            Spacer(Modifier.height(6.dp))
                        }
                        Button(
                            enabled  = selectedShop != null && !busy,
                            onClick  = {
                                val picked = selectedShop ?: return@Button
                                busy = true; error = null
                                scope.launch {
                                    try { withContext(Dispatchers.IO) { bridge.connect(picked.id) } }
                                    catch (e: Exception) { error = e.message ?: "Station connection failed." }
                                    finally { busy = false }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            colors   = ButtonDefaults.buttonColors(Blue, CanvasWhite),
                            shape    = RoundedCornerShape(12.dp),
                        ) {
                            if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = CanvasWhite, strokeWidth = 2.dp)
                            else Text("Connect Station & Register Key", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { shops = emptyList(); selectedShop = null; error = null }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                            Text("← Back to sign in", color = Blue, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrandPoint(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(Blue))
        Text(text, color = Color(0xFF94A3B8), fontSize = 13.sp)
    }
}

@Composable
private fun LoginField(label: String, value: String, onChange: (String) -> Unit, secret: Boolean = false, onEnter: (() -> Unit)? = null) {
    OutlinedTextField(
        value   = value,
        onValueChange = onChange,
        label   = { Text(label, fontSize = 13.sp) },
        singleLine = true,
        modifier   = Modifier.fillMaxWidth().then(
            if (onEnter != null) Modifier.onKeyEvent { if (it.key == Key.Enter) { onEnter(); true } else false }
            else Modifier
        ),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        colors = TextFieldDefaults.colors(
            focusedContainerColor   = CanvasWhite,
            unfocusedContainerColor = CanvasWhite,
            focusedIndicatorColor   = Blue,
            unfocusedIndicatorColor = Border,
        ),
        shape = RoundedCornerShape(10.dp),
    )
}

// ─── MAIN STATION APPLICATION (post-login) ────────────────────────────────────
@Composable
private fun StationApplication(bridge: StationBridge, initialStatus: StationStatus?) {
    var status         by remember { mutableStateOf(initialStatus) }
    var shop           by remember { mutableStateOf<ShopDetails?>(null) }
    var shops          by remember { mutableStateOf<List<ShopOption>>(emptyList()) }
    var selectedShop   by remember { mutableStateOf<ShopOption?>(null) }
    var isRegistering  by remember { mutableStateOf(false) }
    var busy           by remember { mutableStateOf(false) }
    var error          by remember { mutableStateOf<String?>(null) }
    var statusError    by remember { mutableStateOf<String?>(null) }
    var selectedPage   by remember { mutableStateOf(StationPage.OVERVIEW) }
    var queue          by remember { mutableStateOf<List<PrintJobStatus>>(emptyList()) }
    var queueBusy      by remember { mutableStateOf(false) }
    var queueError     by remember { mutableStateOf<String?>(null) }
    var actionMessage  by remember { mutableStateOf<String?>(null) }
    var autoPrintBusy  by remember { mutableStateOf(false) }
    var loggedOut      by remember { mutableStateOf(false) }

    // Dialog/overlay state
    var viewingJob      by remember { mutableStateOf<PrintJobDetail?>(null) }
    var acceptingJob    by remember { mutableStateOf<PrintJobStatus?>(null) }
    var jobDetailBusy   by remember { mutableStateOf(false) }
    var jobDetailError  by remember { mutableStateOf<String?>(null) }

    var previewingJob   by remember { mutableStateOf<PrintJobStatus?>(null) }
    var previewData     by remember { mutableStateOf<DocumentPreview?>(null) }
    var previewBusy     by remember { mutableStateOf(false) }
    var previewError    by remember { mutableStateOf<String?>(null) }

    var cancellingJob   by remember { mutableStateOf<PrintJobStatus?>(null) }
    var cancelBusy      by remember { mutableStateOf(false) }
    var cancelError     by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()

    suspend fun refreshStatus() {
        status = withContext(Dispatchers.IO) { bridge.status() }
        statusError = null
    }

    suspend fun refreshShop() {
        shop = withContext(Dispatchers.IO) { bridge.shopDetails() }
    }

    suspend fun refreshQueue(showLoading: Boolean = true) {
        if (showLoading) queueBusy = true
        try {
            queue = withContext(Dispatchers.IO) { bridge.queue() }
            queueError = null
        } catch (ex: Exception) {
            queueError = ex.message ?: "Could not load the shop print queue."
        } finally {
            if (showLoading) queueBusy = false
        }
    }

    val onVerifyJob: (PrintJobStatus) -> Unit = { job ->
        previewingJob = job
        previewBusy = true
        previewError = null
        previewData = null
        scope.launch {
            try {
                val preview = withContext(Dispatchers.IO) { bridge.previewJob(job.id) }
                previewData = preview
            } catch (ex: Exception) {
                previewError = ex.message ?: "Could not decrypt document for preview."
            } finally {
                previewBusy = false
            }
        }
    }

    val onAcceptJob: (PrintJobStatus) -> Unit = { job ->
        acceptingJob = job
        jobDetailError = null
    }

    val onCancelJob: (PrintJobStatus) -> Unit = { job ->
        cancellingJob = job
        cancelError = null
    }

    val onDetailsJob: (PrintJobStatus) -> Unit = { job ->
        jobDetailError = null
        scope.launch {
            jobDetailBusy = true
            try { viewingJob = withContext(Dispatchers.IO) { bridge.jobDetails(job.id) } }
            catch (ex: Exception) { jobDetailError = ex.message ?: "Could not load job details." }
            finally { jobDetailBusy = false }
        }
    }

    // Initial data load and continuous background refresh
    LaunchedEffect(Unit) {
        runCatching { refreshStatus() }
            .onSuccess {
                if (status?.authenticated == true) {
                    runCatching { refreshShop() }.onFailure { statusError = it.message }
                    refreshQueue()
                }
            }.onFailure { statusError = it.message ?: "Could not refresh station status." }

        while (true) {
            delay(3_000)
            runCatching { refreshStatus() }.onFailure { statusError = it.message }
            if (status?.authenticated == true) {
                runCatching { refreshQueue(showLoading = false) }
            }
        }
    }

    // Queue auto-refresh when on QUEUE page
    LaunchedEffect(selectedPage, status?.authenticated) {
        if (selectedPage == StationPage.QUEUE && status?.authenticated == true) {
            refreshQueue()
            while (true) { delay(4_000); refreshQueue(showLoading = false) }
        }
    }

    if (loggedOut) {
        LoginRoot(bridge) {
            status = bridge.status()
            loggedOut = false
        }
        return
    }

    // ── Document Preview Dialog (In-RAM View Only) ────────────────────────────
    previewingJob?.let { job ->
        DocumentPreviewDialog(
            job         = job,
            preview     = previewData,
            busy        = previewBusy,
            error       = previewError,
            bridge      = bridge,
            onClose     = {
                previewingJob = null
                previewData = null
                previewError = null
            },
            onCancel    = {
                val j = previewingJob ?: job
                previewingJob = null
                previewData = null
                onCancelJob(j)
            },
            onSwitchJob = onVerifyJob,
        )
    }

    // ── Cancel Confirmation Dialog ────────────────────────────────────────────
    cancellingJob?.let { job ->
        CancelJobDialog(
            job       = job,
            busy      = cancelBusy,
            error     = cancelError,
            onDismiss = { cancellingJob = null; cancelError = null },
            onConfirm = { reason ->
                scope.launch {
                    cancelBusy = true
                    cancelError = null
                    try {
                        withContext(Dispatchers.IO) { bridge.cancelJob(job.id, reason) }
                        actionMessage = "Job #${job.id.takeLast(8).uppercase()} cancelled successfully."
                        cancellingJob = null
                        refreshQueue(showLoading = false)
                    } catch (ex: Exception) {
                        cancelError = ex.message ?: "Could not cancel job."
                    } finally {
                        cancelBusy = false
                    }
                }
            },
        )
    }

    // ── Job Detail Dialog ──────────────────────────────────────────────────────
    viewingJob?.let { detail ->
        JobDetailDialog(
            detail        = detail,
            onClose       = { viewingJob = null },
            onAccept      = { d ->
                viewingJob = null
                val matched = queue.firstOrNull { it.id == d.id }
                if (matched != null) onAcceptJob(matched)
            },
            onVerify      = { d ->
                viewingJob = null
                val matched = queue.firstOrNull { it.id == d.id }
                if (matched != null) onVerifyJob(matched)
            },
        )
    }

    // ── Accept Confirmation Dialog ─────────────────────────────────────────────
    acceptingJob?.let { job ->
        AcceptJobDialog(
            job       = job,
            printers  = status?.printers.orEmpty(),
            busy      = jobDetailBusy,
            error     = jobDetailError,
            onDismiss = { acceptingJob = null; jobDetailError = null },
            onAccept  = { printerName ->
                jobDetailBusy = true; jobDetailError = null
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            bridge.printJob(job.id, printerName.takeIf(String::isNotBlank))
                        }
                        actionMessage = "Job #${job.id.takeLast(8).uppercase()} accepted and sent to ${printerName.ifBlank { "default printer" }}."
                        refreshQueue(showLoading = false)
                        selectedPage = StationPage.QUEUE
                    } catch (ex: Exception) {
                        jobDetailError = ex.message ?: "Could not accept job."
                    } finally {
                        jobDetailBusy = false
                        if (jobDetailError == null) acceptingJob = null
                    }
                }
            },
        )
    }

    Row(Modifier.fillMaxSize()) {
        // ── SIDEBAR ───────────────────────────────────────────────────────────
        Column(
            Modifier.width(240.dp).fillMaxHeight().background(Sidebar).padding(0.dp),
        ) {
            // Brand
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp)) {
                Text("PRIVPRINT", color = Blue, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                Text("XEROX SHOP STATION", color = Color(0xFF334155), fontSize = 9.sp, letterSpacing = 2.sp)
            }

            if (shop != null) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(10.dp)).background(SidebarItem).padding(12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(shop!!.name, color = CanvasWhite, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(shop!!.address.ifBlank { "Xerox shop" }, color = Color(0xFF64748B), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            Text("WORKSPACE", color = Color(0xFF334155), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            Spacer(Modifier.height(4.dp))

            StationPage.entries.forEach { page ->
                SidebarNavItem(page, selectedPage == page) {
                    selectedPage = page; actionMessage = null
                }
            }

            Spacer(Modifier.weight(1f))

            HorizontalDivider(color = Color(0xFF1E293B))
            Spacer(Modifier.height(8.dp))

            // Logout
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        scope.launch {
                            withContext(Dispatchers.IO) { bridge.logout() }
                            status = null
                            shop = null
                            queue = emptyList()
                            loggedOut = true
                        }
                    }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("⏏", color = Red, fontSize = 15.sp)
                Text("Logout", color = Red, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
        }

        // ── MAIN CONTENT ─────────────────────────────────────────────────────
        Column(Modifier.fillMaxSize()) {
            // Top bar
            Row(
                Modifier.fillMaxWidth().height(68.dp).background(CanvasWhite).padding(horizontal = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(selectedPage.title, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(
                        shop?.name ?: status?.shopId?.takeIf(String::isNotBlank)?.let { "Shop ID $it" } ?: "Connecting to Xerox shop…",
                        color = Muted, fontSize = 12.sp,
                    )
                }
                // Refresh button
                TextButton(
                    enabled = !queueBusy && !autoPrintBusy,
                    onClick = {
                        scope.launch {
                            actionMessage = null
                            when (selectedPage) {
                                StationPage.QUEUE    -> refreshQueue()
                                StationPage.PRINTERS -> runCatching {
                                    val n = withContext(Dispatchers.IO) { bridge.refreshPrinters() }
                                    refreshStatus()
                                    actionMessage = "$n Windows printer(s) synced."
                                }.onFailure { actionMessage = it.message ?: "Printer sync failed." }
                                StationPage.SHOP_QR  -> runCatching {
                                    refreshShop(); actionMessage = "Shop QR refreshed."
                                }.onFailure { actionMessage = it.message }
                                else -> runCatching { refreshStatus() }.onFailure { statusError = it.message }
                            }
                        }
                    }
                ) {
                    Text(when (selectedPage) {
                        StationPage.QUEUE    -> "↺  Refresh"
                        StationPage.PRINTERS -> "↺  Sync"
                        StationPage.SHOP_QR  -> "↺  Refresh QR"
                        else                 -> "↺  Refresh"
                    }, color = Blue, fontSize = 13.sp)
                }
                Spacer(Modifier.width(8.dp))
                ConnPill(status)
            }
            HorizontalDivider(color = Border)

            // Page content
            Box(Modifier.fillMaxSize()) {
                if (status?.authenticated != true) {
                    // Not authenticated → show auth form inline
                    Column(Modifier.fillMaxSize().padding(32.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Welcome to PrivPrint", color = Ink, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Text("Sign in with your Xerox shop operator account to connect this Windows station.", color = InkMuted, fontSize = 14.sp)
                        AuthCard(
                            isRegistering = isRegistering, busy = busy, error = error,
                            onModeChange = { isRegistering = it; error = null; shops = emptyList(); selectedShop = null },
                            onSubmit = { fn, em, pw, sn, sa ->
                                busy = true; error = null
                                scope.launch {
                                    try {
                                        shops = withContext(Dispatchers.IO) {
                                            if (isRegistering) bridge.register(fn, em, pw, sn, sa) else bridge.login(em, pw)
                                        }
                                        selectedShop = shops.firstOrNull()
                                    } catch (ex: Exception) { error = ex.message ?: "Account request failed." }
                                    finally { busy = false }
                                }
                            },
                        )
                        if (shops.isNotEmpty()) {
                            ShopPicker(shops, selectedShop, busy, error,
                                onSelect = { selectedShop = it },
                                onConnect = {
                                    val picked = selectedShop ?: return@ShopPicker
                                    busy = true; error = null
                                    scope.launch {
                                        try {
                                            withContext(Dispatchers.IO) { bridge.connect(picked.id) }
                                            refreshStatus(); refreshShop(); refreshQueue()
                                            selectedPage = StationPage.OVERVIEW
                                            actionMessage = "Shop connected and station key registered."
                                        } catch (ex: Exception) {
                                            error = ex.message ?: "Station connection failed."
                                        } finally { busy = false }
                                    }
                                },
                            )
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize().padding(28.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        statusError?.let { NoticeCard(it, isError = true) }
                        when (selectedPage) {
                            StationPage.OVERVIEW  -> OverviewPage(
                                status, shop, queue, actionMessage, autoPrintBusy,
                                onOpenQr        = { selectedPage = StationPage.SHOP_QR },
                                onOpenQueue     = { selectedPage = StationPage.QUEUE },
                                onOpenPrinters  = { selectedPage = StationPage.PRINTERS },
                                onToggleAutoPrint = { enabled ->
                                    autoPrintBusy = true; actionMessage = null
                                    scope.launch {
                                        try {
                                            withContext(Dispatchers.IO) { bridge.setAutoPrintEnabled(enabled) }
                                            refreshStatus()
                                            actionMessage = if (enabled) "Auto-print enabled." else "Auto-print paused. Authorized jobs remain queued."
                                        } catch (ex: Exception) { actionMessage = ex.message }
                                        finally { autoPrintBusy = false }
                                    }
                                },
                                onVerifyJob     = onVerifyJob,
                                onAcceptJob     = onAcceptJob,
                                onCancelJob     = onCancelJob,
                                onDetailsJob    = onDetailsJob,
                            )
                            StationPage.QUEUE     -> PrintQueuePage(
                                queue, queueBusy, queueError, actionMessage,
                                onVerify        = onVerifyJob,
                                onAccept        = onAcceptJob,
                                onCancel        = onCancelJob,
                                onDetails       = onDetailsJob,
                                jobDetailBusy   = jobDetailBusy,
                                jobDetailError  = jobDetailError,
                            )
                            StationPage.PRINTERS  -> PrinterPage(status, actionMessage)
                            StationPage.SHOP_QR   -> ShopQrPage(shop, actionMessage)
                            StationPage.AUDIT     -> AuditPage(status?.auditLog.orEmpty())
                            StationPage.SECURITY  -> StationSecurityPage(
                                status, actionMessage,
                                onReconnect = {
                                    scope.launch {
                                        actionMessage = null
                                        runCatching {
                                            withContext(Dispatchers.IO) { bridge.reconnectRealtime() }
                                            actionMessage = "Cloud reconnect requested."
                                        }.onFailure { actionMessage = it.message }
                                    }
                                },
                            )
                            StationPage.SETTINGS  -> SettingsPage(status, actionMessage,
                                onToggleAutoPrint = { enabled ->
                                    autoPrintBusy = true; actionMessage = null
                                    scope.launch {
                                        try {
                                            withContext(Dispatchers.IO) { bridge.setAutoPrintEnabled(enabled) }
                                            refreshStatus()
                                            actionMessage = if (enabled) "Auto-print enabled." else "Auto-print paused."
                                        } catch (ex: Exception) { actionMessage = ex.message }
                                        finally { autoPrintBusy = false }
                                    }
                                },
                                onLogout = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) { bridge.logout() }
                                        status = null
                                        shop = null
                                        queue = emptyList()
                                        loggedOut = true
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─── Sidebar nav item ─────────────────────────────────────────────────────────
@Composable
private fun SidebarNavItem(page: StationPage, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Color(0xFF1E3A5F) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(page.symbol, color = if (selected) Blue else Color(0xFF475569), fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(page.title, color = if (selected) CanvasWhite else Color(0xFF94A3B8), fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

// ─── Connection pill ──────────────────────────────────────────────────────────
@Composable
private fun ConnPill(status: StationStatus?) {
    val connected = status?.realtimeConnected == true
    val label = if (connected) "CLOUD CONNECTED" else "RECONNECTING"
    StatusPill(label, connected)
}

@Composable
private fun StatusPill(label: String, ok: Boolean) {
    val failed = label.contains("FAIL", true) || label.contains("ERROR", true) || label.contains("OFFLINE", true)
    val bg = when { failed -> RedTint; ok -> GreenTint; else -> AmberTint }
    val fg = when { failed -> Red;     ok -> GreenDark; else -> Amber }
    Row(
        Modifier.clip(RoundedCornerShape(30.dp)).background(bg).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(fg))
        Text(label, color = fg, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

// ─── Overview / Dashboard ────────────────────────────────────────────────────
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
    onVerifyJob: (PrintJobStatus) -> Unit,
    onAcceptJob: (PrintJobStatus) -> Unit,
    onCancelJob: (PrintJobStatus) -> Unit,
    onDetailsJob: (PrintJobStatus) -> Unit,
) {
    Text("Shop Dashboard", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    Text("Station overview, print queue summary, and printer status.", color = InkMuted, fontSize = 13.sp)

    shop?.let { ShopIdentityCard(it, onOpenQr) }
    actionMessage?.let { NoticeCard(it, it.contains("fail", true) || it.contains("could not", true)) }

    // Status row
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        val activeJobs = queue.count { it.status !in setOf("COMPLETED","FAILED","CANCELLED","EXPIRED") }
        MetricCard("Pending Jobs", activeJobs.toString(), "In queue right now", onOpenQueue, Modifier.weight(1f))
        MetricCard("Printers", status?.printers?.size?.toString() ?: "0", "Windows printers found", onOpenPrinters, Modifier.weight(1f))
        MetricCard("Completed", status?.completedJobCount?.toString() ?: "0", "This session", onOpenQueue, Modifier.weight(1f))
        MetricCard("Station", if (status?.realtimeConnected == true) "Online" else "Reconnecting", "Cloud connection", {}, Modifier.weight(1f))
    }

    // Incoming customer jobs banner/cards
    val authorizedJobs = queue.filter { it.status == "AUTHORIZED" }
    if (authorizedJobs.isNotEmpty()) {
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(CanvasWhite),
            shape  = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, Blue),
        ) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(Blue))
                        Text("Incoming Customer Jobs (${authorizedJobs.size})", color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    StatusPill("ACTION REQUIRED", ok = true)
                }
                Text("Customer sent documents ready for review and printing. Verify in RAM before printing or cancelling.", color = InkMuted, fontSize = 12.sp)
                authorizedJobs.forEach { job ->
                    JobCard(
                        job = job,
                        onVerify = onVerifyJob,
                        onAccept = onAcceptJob,
                        onCancel = onCancelJob,
                        onDetails = onDetailsJob,
                        loading = false,
                    )
                }
            }
        }
    }

    // Auto-print control
    CardBlock {
        Text("Direct Auto-Print", color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text("When enabled, authorized jobs are automatically printed without manual operator verification.", color = InkMuted, fontSize = 12.sp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (status?.autoPrintEnabled == true) "Auto-print is ON" else "Auto-print is PAUSED (Manual Accept/Verify Mode)", color = if (status?.autoPrintEnabled == true) Green else Amber, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Switch(checked = status?.autoPrintEnabled == true, onCheckedChange = onToggleAutoPrint, enabled = !autoPrintBusy)
        }
    }

    status?.let { ShopConnectedCard(it) }
}

// ─── Print Queue ──────────────────────────────────────────────────────────────
private val ACTIVE_STATUSES = setOf("AUTHORIZED","PENDING","PRINTING","ACCEPTED")
private val DONE_STATUSES   = setOf("COMPLETED","FAILED","CANCELLED","EXPIRED")

@Composable
private fun PrintQueuePage(
    queue: List<PrintJobStatus>,
    busy: Boolean,
    error: String?,
    actionMessage: String?,
    onVerify: (PrintJobStatus) -> Unit,
    onAccept: (PrintJobStatus) -> Unit,
    onCancel: (PrintJobStatus) -> Unit,
    onDetails: (PrintJobStatus) -> Unit,
    jobDetailBusy: Boolean,
    jobDetailError: String?,
) {
    Text("Print Job Queue", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    Text("Customer-authorized encrypted print jobs are processed by this station.", color = InkMuted, fontSize = 13.sp)

    actionMessage?.let { NoticeCard(it, it.contains("fail", true) || it.contains("could not", true)) }
    jobDetailError?.let { NoticeCard(it, isError = true) }

    when {
        busy -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(Modifier.size(20.dp), color = Blue, strokeWidth = 2.dp)
            Text("Loading shop queue…", color = InkMuted, fontSize = 13.sp)
        }
        error != null -> NoticeCard(error, isError = true)
        queue.isEmpty() -> CardBlock {
            Text("No print jobs", color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("New customer jobs will appear here after they are submitted and authorized for this shop.", color = InkMuted, fontSize = 12.sp)
        }
        else -> {
            val active    = queue.filter { it.status in ACTIVE_STATUSES }
            val completed = queue.filter { it.status == "COMPLETED" }
            val failed    = queue.filter { it.status in setOf("FAILED","CANCELLED","EXPIRED") }

            if (active.isNotEmpty()) {
                SectionLabel("Active Jobs (${active.size})")
                active.forEach { job ->
                    JobCard(job, onVerify, onAccept, onCancel, onDetails, jobDetailBusy)
                }
            }
            if (completed.isNotEmpty()) {
                SectionLabel("Completed (${completed.size})")
                completed.forEach { job ->
                    JobCard(job, null, null, null, onDetails, false)
                }
            }
            if (failed.isNotEmpty()) {
                SectionLabel("Failed / Cancelled (${failed.size})")
                failed.forEach { job ->
                    JobCard(job, null, null, null, onDetails, false)
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
}

@Composable
private fun JobCard(
    job: PrintJobStatus,
    onVerify: ((PrintJobStatus) -> Unit)?,
    onAccept: ((PrintJobStatus) -> Unit)?,
    onCancel: ((PrintJobStatus) -> Unit)?,
    onDetails: ((PrintJobStatus) -> Unit)?,
    loading: Boolean,
) {
    val isPending = job.status in ACTIVE_STATUSES
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(CanvasWhite),
        shape  = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(
            if (job.status == "AUTHORIZED") 1.5.dp else 1.dp,
            if (job.status == "AUTHORIZED") Blue else Border,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(if (job.status == "AUTHORIZED") BlueTint else SurfaceVariant), contentAlignment = Alignment.Center) {
                        Text(if (job.documentName.endsWith(".pdf", ignoreCase = true)) "📄" else "🖼", fontSize = 18.sp)
                    }
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(job.documentName.ifBlank { "Document" }, color = Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (job.totalFiles > 1) {
                                Box(Modifier.clip(RoundedCornerShape(4.dp)).background(BlueTint).padding(horizontal = 6.dp, vertical = 2.dp)) {
                                    Text("BATCH ${job.fileIndex + 1}/${job.totalFiles}", color = BlueDark, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        Text("Job #${job.id.takeLast(8).uppercase()}" + if (job.createdAt.isNotBlank()) " · Submitted ${job.createdAt.take(19).replace("T", " ")}" else "", color = Muted, fontSize = 11.sp)
                    }
                }
                StatusPill(job.status.replace('_', ' '), job.status in setOf("COMPLETED","PRINTING","AUTHORIZED"))
            }

            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(SurfaceVariant).padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                JobMeta("Pages", "${job.pageCount} pgs")
                JobMeta("Copies", job.copies.ifBlank { "1" })
                JobMeta("Color", job.colorMode)
                JobMeta("Size", job.paperSize)
                JobMeta("Duplex", job.duplexMode)
                if (job.pagesPrinted > 0 || job.status == "PRINTING") {
                    JobMeta("Spool Progress", "${job.pagesPrinted}/${job.pageCount} pgs")
                }
            }

            if (job.failureReason.isNotBlank()) {
                Text(job.failureReason, color = Red, fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(RedTint).padding(8.dp))
            }

            if (job.status == "AUTHORIZED" || onDetails != null) {
                HorizontalDivider(color = Border)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (job.status == "AUTHORIZED" && onVerify != null) {
                            Button(
                                onClick = { onVerify(job) },
                                enabled = !loading,
                                colors = ButtonDefaults.buttonColors(BlueTint, BlueDark),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.height(36.dp),
                            ) {
                                Text("👁  Verify Document", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        if (job.status == "AUTHORIZED" && onAccept != null) {
                            Button(
                                onClick = { onAccept(job) },
                                enabled = !loading,
                                colors = ButtonDefaults.buttonColors(Blue, CanvasWhite),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.height(36.dp),
                            ) {
                                Text("✓  Accept & Print", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        if (job.status == "AUTHORIZED" && onCancel != null) {
                            OutlinedButton(
                                onClick = { onCancel(job) },
                                enabled = !loading,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.height(36.dp),
                            ) {
                                Text("✕  Cancel Job", fontSize = 12.sp, color = Red, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    if (onDetails != null) {
                        TextButton(onClick = { onDetails(job) }, enabled = !loading) {
                            Text("Details ℹ", fontSize = 12.sp, color = Muted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun JobMeta(label: String, value: String) {
    Column {
        Text(label, color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(value, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ─── Job Detail Dialog ────────────────────────────────────────────────────────
@Composable
private fun JobDetailDialog(
    detail: PrintJobDetail,
    onClose: () -> Unit,
    onAccept: (PrintJobDetail) -> Unit,
    onVerify: (PrintJobDetail) -> Unit,
) {
    Dialog(onDismissRequest = onClose) {
        Card(
            Modifier.widthIn(min = 440.dp, max = 580.dp),
            colors = CardDefaults.cardColors(CanvasWhite),
            shape  = RoundedCornerShape(18.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Border),
        ) {
            Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Job Details", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    StatusPill(detail.status.replace('_', ' '), detail.status in setOf("COMPLETED","PRINTING","AUTHORIZED"))
                }
                HorizontalDivider(color = Border)

                DetailRow("Job ID",        "#${detail.id.takeLast(12).uppercase()}")
                if (detail.batchId != null) {
                    DetailRow("Batch Group",   "#${detail.batchId.takeLast(8).uppercase()} (File ${detail.fileIndex + 1} of ${detail.totalFiles})")
                }
                DetailRow("Document",      detail.documentName)
                DetailRow("Pages",         if (detail.pageCount > 0) "${detail.pageCount} pages" else "Unknown")
                if (detail.pagesPrinted > 0 || detail.status == "PRINTING") {
                    DetailRow("Pages Printed", "${detail.pagesPrinted} / ${detail.pageCount}")
                }
                DetailRow("Copies",        "${detail.requestedCopies}")
                DetailRow("Color Mode",    detail.colorMode)
                DetailRow("Paper Size",    detail.paperSize)
                DetailRow("Duplex",        detail.duplex.ifBlank { "Not specified" })
                DetailRow("Printer",       detail.selectedPrinter.ifBlank { "Auto / default" })
                DetailRow("Submitted",     detail.createdAt.take(19).replace("T", " ").ifBlank { "Unknown" })

                if (detail.failureReason.isNotBlank()) {
                    Text(detail.failureReason, color = Red, fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(RedTint).padding(10.dp))
                }

                // Privacy notice
                Text(
                    "ℹ  Viewing job details does not decrypt the document. Use 'Verify Document' to inspect pages safely in memory.",
                    color = Blue, fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(BlueTint).padding(10.dp),
                )

                HorizontalDivider(color = Border)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(10.dp)) {
                        Text("Close")
                    }
                    if (detail.status == "AUTHORIZED") {
                        Button(
                            onClick  = { onVerify(detail) },
                            colors   = ButtonDefaults.buttonColors(BlueTint, BlueDark),
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape    = RoundedCornerShape(10.dp),
                        ) {
                            Text("👁 Verify", fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick  = { onAccept(detail) },
                            colors   = ButtonDefaults.buttonColors(Blue, CanvasWhite),
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape    = RoundedCornerShape(10.dp),
                        ) {
                            Text("✓ Accept", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Text(label, color = Muted, fontSize = 12.sp, modifier = Modifier.width(110.dp))
        Text(value, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
    }
}

// ─── Document Preview Dialog (In-RAM View Only) ───────────────────────────────
@Composable
private fun DocumentPreviewDialog(
    job: PrintJobStatus,
    preview: DocumentPreview?,
    busy: Boolean,
    error: String?,
    bridge: StationBridge,
    onClose: () -> Unit,
    onCancel: () -> Unit,
    onSwitchJob: ((PrintJobStatus) -> Unit)? = null,
) {
    var currentPage by remember { mutableStateOf(0) }
    var zoomScale by remember { mutableStateOf(1.0f) }
    var fitWidth by remember { mutableStateOf(false) }
    var batchJobs by remember(job.batchId) { mutableStateOf<List<PrintJobStatus>>(emptyList()) }
    val vScrollState = rememberScrollState()
    val hScrollState = rememberScrollState()

    LaunchedEffect(job.batchId) {
        if (!job.batchId.isNullOrBlank()) {
            runCatching {
                withContext(Dispatchers.IO) { bridge.batchJobs(job.batchId) }
            }.onSuccess { batchJobs = it }
        } else {
            batchJobs = emptyList()
        }
    }

    Dialog(onDismissRequest = onClose) {
        Card(
            Modifier.widthIn(min = 780.dp, max = 980.dp).fillMaxHeight(0.94f),
            colors = CardDefaults.cardColors(CanvasWhite),
            shape  = RoundedCornerShape(20.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Border),
        ) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Top Header Row
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("SECURE DOCUMENT VIEWER", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            StatusPill("🔒 SECURE VIEW-ONLY SESSION", ok = true)
                            if (job.totalFiles > 1) {
                                Box(Modifier.clip(RoundedCornerShape(4.dp)).background(BlueTint).padding(horizontal = 6.dp, vertical = 2.dp)) {
                                    Text("DOC ${job.fileIndex + 1} OF ${job.totalFiles}", color = BlueDark, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        Text(
                            "${preview?.filename ?: job.documentName}  ·  Job #${job.id.takeLast(8).uppercase()}" +
                                if (preview != null && preview.pageCount > 0) "  ·  Page ${currentPage + 1} of ${preview.pageCount}" else "",
                            color = InkMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    OutlinedButton(onClick = onClose, shape = RoundedCornerShape(8.dp), modifier = Modifier.height(34.dp)) {
                        Text("Close ✕", fontSize = 12.sp)
                    }
                }

                // Batch Files Selector Tab Bar & Document Navigation
                if (batchJobs.size > 1) {
                    val currentBatchIdx = batchJobs.indexOfFirst { it.id == job.id }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(SurfaceVariant).padding(6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Documents (${batchJobs.size}):", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp))
                            batchJobs.forEach { bJob ->
                                val isSelected = bJob.id == job.id
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (isSelected) Blue else CanvasWhite)
                                        .clickable(enabled = !isSelected && onSwitchJob != null) {
                                            currentPage = 0
                                            zoomScale = 1.0f
                                            fitWidth = false
                                            onSwitchJob?.invoke(bJob)
                                        }
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                ) {
                                    Text(
                                        "${bJob.fileIndex + 1}. ${bJob.documentName}",
                                        color = if (isSelected) CanvasWhite else Ink,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = {
                                    if (currentBatchIdx > 0) {
                                        currentPage = 0
                                        zoomScale = 1.0f
                                        fitWidth = false
                                        onSwitchJob?.invoke(batchJobs[currentBatchIdx - 1])
                                    }
                                },
                                enabled = currentBatchIdx > 0 && onSwitchJob != null,
                                modifier = Modifier.height(28.dp),
                                shape = RoundedCornerShape(6.dp),
                            ) {
                                Text("◀ Prev Doc", fontSize = 10.sp)
                            }
                            OutlinedButton(
                                onClick = {
                                    if (currentBatchIdx >= 0 && currentBatchIdx < batchJobs.size - 1) {
                                        currentPage = 0
                                        zoomScale = 1.0f
                                        fitWidth = false
                                        onSwitchJob?.invoke(batchJobs[currentBatchIdx + 1])
                                    }
                                },
                                enabled = currentBatchIdx >= 0 && currentBatchIdx < batchJobs.size - 1 && onSwitchJob != null,
                                modifier = Modifier.height(28.dp),
                                shape = RoundedCornerShape(6.dp),
                            ) {
                                Text("Next Doc ▶", fontSize = 10.sp)
                            }
                        }
                    }
                }

                HorizontalDivider(color = Border)

                // Navigation & Viewer Toolbar: Page controls + Zoom controls
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(SurfaceVariant).padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Page Navigation
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val pageCount = preview?.pageCount ?: 1
                        OutlinedButton(
                            onClick = { if (currentPage > 0) currentPage-- },
                            enabled = currentPage > 0,
                            modifier = Modifier.height(30.dp),
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Text("◀ Prev Page", fontSize = 11.sp)
                        }
                        Text(
                            "Page ${currentPage + 1} / $pageCount",
                            color = Ink, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                        )
                        OutlinedButton(
                            onClick = { if (currentPage < pageCount - 1) currentPage++ },
                            enabled = currentPage < pageCount - 1,
                            modifier = Modifier.height(30.dp),
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Text("Next Page ▶", fontSize = 11.sp)
                        }
                    }

                    // Zoom and Fit Controls
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = {
                                zoomScale = (zoomScale - 0.25f).coerceAtLeast(0.5f)
                                fitWidth = false
                            },
                            enabled = zoomScale > 0.5f,
                            modifier = Modifier.height(30.dp),
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Text("🔍-", fontSize = 11.sp)
                        }
                        Text(
                            "${(zoomScale * 100).toInt()}%",
                            color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                            modifier = Modifier.widthIn(min = 40.dp)
                        )
                        OutlinedButton(
                            onClick = {
                                zoomScale = (zoomScale + 0.25f).coerceAtMost(3.0f)
                                fitWidth = false
                            },
                            enabled = zoomScale < 3.0f,
                            modifier = Modifier.height(30.dp),
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Text("🔍+", fontSize = 11.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                zoomScale = 1.0f
                                fitWidth = false
                            },
                            modifier = Modifier.height(30.dp),
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Text("Fit Page", fontSize = 11.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                fitWidth = !fitWidth
                                zoomScale = 1.0f
                            },
                            modifier = Modifier.height(30.dp),
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Text(if (fitWidth) "Default" else "Fit Width", fontSize = 11.sp)
                        }
                    }
                }

                // Document Page Render Box
                Box(
                    Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFFE2E8F0)).padding(12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        busy -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(Modifier.size(36.dp), color = Blue)
                            Text("Decrypting document in secure RAM…", color = InkMuted, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text("Zero bytes are saved to disk.", color = Muted, fontSize = 12.sp)
                        }
                        error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Could not render document preview", color = Red, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text(error, color = Red, fontSize = 12.sp)
                        }
                        preview != null && preview.pages.isNotEmpty() -> {
                            val pageIdx = currentPage.coerceIn(0, preview.pages.size - 1)
                            val bitmap = remember(preview, pageIdx) { preview.pages[pageIdx].toComposeImageBitmap() }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(vScrollState)
                                    .horizontalScroll(hScrollState),
                                contentAlignment = Alignment.Center,
                            ) {
                                Card(
                                    shape = RoundedCornerShape(4.dp),
                                    colors = CardDefaults.cardColors(CanvasWhite),
                                    elevation = CardDefaults.cardElevation(4.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFCBD5E1)),
                                    modifier = Modifier
                                        .graphicsLayer(scaleX = zoomScale, scaleY = zoomScale)
                                        .then(if (fitWidth) Modifier.fillMaxWidth() else Modifier.fillMaxHeight())
                                ) {
                                    Image(
                                        bitmap = bitmap,
                                        contentDescription = "Page ${pageIdx + 1}",
                                        modifier = if (fitWidth) Modifier.fillMaxWidth() else Modifier.fillMaxHeight(),
                                        contentScale = if (fitWidth) ContentScale.FillWidth else ContentScale.Fit,
                                    )
                                }
                            }
                        }
                        else -> Text("No preview pages available", color = Muted)
                    }
                }

                // Security Note
                Text(
                    "🔒 SECURE VIEW-ONLY SESSION: Document decrypted exclusively in station RAM. Export, download, copy, save, and print are permanently prohibited in this viewer.",
                    color = BlueDark, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(BlueTint).padding(8.dp),
                )

                // Footer Action Bar — View-Only (NO Print, NO Save, NO Export)
                HorizontalDivider(color = Border)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = onCancel,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.height(40.dp),
                    ) {
                        Text("✕  Reject Job", color = Red, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }

                    Button(
                        onClick = onClose,
                        colors = ButtonDefaults.buttonColors(Blue, CanvasWhite),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.height(40.dp),
                    ) {
                        Text("Done / Close Viewer", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

// ─── Cancel Job Confirmation Dialog ───────────────────────────────────────────
@Composable
private fun CancelJobDialog(
    job: PrintJobStatus,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var reason by remember { mutableStateOf("Cancelled by shop operator") }
    val presetReasons = listOf(
        "Cancelled by shop operator",
        "Incorrect paper size or color mode",
        "Document unreadable or blank",
        "Customer requested cancellation",
        "Printer offline or paper jam",
    )

    Dialog(onDismissRequest = onDismiss) {
        Card(
            Modifier.widthIn(min = 440.dp, max = 540.dp),
            colors = CardDefaults.cardColors(CanvasWhite),
            shape  = RoundedCornerShape(18.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Border),
        ) {
            Column(Modifier.padding(26.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Reject & Cancel Print Job?", color = Red, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    StatusPill("ACTION REQUIRED", ok = false)
                }
                Text(
                    "Job #${job.id.takeLast(8).uppercase()} · ${job.documentName.ifBlank { "Document" }}",
                    color = InkMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                )

                Text(
                    "Cancelling this job will notify the customer on their mobile phone, release any reservation, and immediately erase the encrypted document from the cloud.",
                    color = Muted, fontSize = 12.sp
                )

                Text("Reason for cancellation:", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold)

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    presetReasons.forEach { preset ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .background(if (reason == preset) RedTint else SurfaceVariant)
                                .border(1.dp, if (reason == preset) Red else Border, RoundedCornerShape(8.dp))
                                .clickable { reason = preset }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(Modifier.size(6.dp).clip(CircleShape).background(if (reason == preset) Red else Muted))
                            Text(preset, color = Ink, fontSize = 12.sp)
                        }
                    }
                }

                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Custom reason (optional)", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = CanvasWhite,
                        unfocusedContainerColor = CanvasWhite,
                        focusedIndicatorColor = Red,
                        unfocusedIndicatorColor = Border,
                    ),
                    shape = RoundedCornerShape(8.dp),
                )

                error?.let { NoticeCard(it, isError = true) }

                HorizontalDivider(color = Border)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onDismiss,
                        enabled = !busy,
                        modifier = Modifier.weight(1f).height(46.dp),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text("Keep Job")
                    }
                    Button(
                        onClick = { onConfirm(reason) },
                        enabled = !busy && reason.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(Red, CanvasWhite),
                        modifier = Modifier.weight(1f).height(46.dp),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = CanvasWhite, strokeWidth = 2.dp)
                        else Text("Confirm Cancel", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

// ─── Accept Job Confirmation Dialog ──────────────────────────────────────────
@Composable
private fun AcceptJobDialog(
    job: PrintJobStatus,
    printers: List<PrinterStatus>,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onAccept: (String) -> Unit,
) {
    var selectedPrinter by remember { mutableStateOf(printers.firstOrNull()?.name ?: "") }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            Modifier.widthIn(min = 400.dp, max = 520.dp),
            colors = CardDefaults.cardColors(CanvasWhite),
            shape  = RoundedCornerShape(18.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Border),
        ) {
            Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Accept Print Job?", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Job #${job.id.takeLast(8).uppercase()} · ${job.documentName.ifBlank { "Document" }}", color = InkMuted, fontSize = 13.sp)
                HorizontalDivider(color = Border)

                DetailRow("Document", job.documentName)
                DetailRow("Pages",    "${job.pageCount} pages")
                DetailRow("Copies",   job.copies.ifBlank { "1" })
                DetailRow("Color",    job.colorMode)
                DetailRow("Paper",    job.paperSize)
                DetailRow("Duplex",   job.duplexMode)
                DetailRow("Status",   job.status)

                if (printers.isNotEmpty()) {
                    Text("Select Printer", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    printers.forEach { printer ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selectedPrinter == printer.name) BlueTint else SurfaceVariant)
                                .border(1.dp, if (selectedPrinter == printer.name) Blue else Border, RoundedCornerShape(10.dp))
                                .clickable { selectedPrinter = printer.name }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(if (printer.status == "READY") Green else Amber))
                            Column(Modifier.weight(1f)) {
                                Text(printer.name, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text(printer.status + if (printer.model.isNotBlank()) "  ·  ${printer.model}" else "", color = Muted, fontSize = 11.sp)
                            }
                            if (selectedPrinter == printer.name)
                                Text("✓", color = Blue, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                } else {
                    Text("No printers found — the station will use the Windows default printer.", color = Amber, fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AmberTint).padding(10.dp))
                }

                error?.let { NoticeCard(it, isError = true) }

                Text("The station worker will decrypt the document in RAM, submit it to the Windows print spooler, and report status to the backend.", color = Muted, fontSize = 11.sp)

                HorizontalDivider(color = Border)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).height(46.dp), shape = RoundedCornerShape(10.dp)) {
                        Text("Cancel")
                    }
                    Button(
                        onClick  = { onAccept(selectedPrinter) },
                        enabled  = !busy,
                        colors   = ButtonDefaults.buttonColors(Blue, CanvasWhite),
                        modifier = Modifier.weight(1f).height(46.dp),
                        shape    = RoundedCornerShape(10.dp),
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = CanvasWhite, strokeWidth = 2.dp)
                        else Text("Accept & Send to Printer", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

// ─── Printers page ────────────────────────────────────────────────────────────
@Composable
private fun PrinterPage(status: StationStatus?, actionMessage: String?) {
    Text("Connected Printers", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    Text("Windows printers detected and synchronized with your shop.", color = InkMuted, fontSize = 13.sp)
    actionMessage?.let { NoticeCard(it, it.contains("fail", true) || it.contains("could not", true)) }

    val printers = status?.printers.orEmpty()
    if (printers.isEmpty()) {
        CardBlock {
            Text("No Windows printers detected", color = Ink, fontWeight = FontWeight.Bold)
            Text("Connect and install a printer in Windows settings, then choose Sync.", color = InkMuted, fontSize = 12.sp)
            Text("PDF, XPS, OneNote, and Fax queues are excluded — install a physical printer driver.", color = Amber, fontSize = 11.sp)
        }
    } else {
        printers.forEach { printer ->
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(CanvasWhite),
                shape  = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Border),
            ) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(if (printer.status == "READY") Green else Amber))
                            Column {
                                Text(printer.name, color = Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text(printer.model.ifBlank { "Windows printer" }, color = InkMuted, fontSize = 12.sp)
                            }
                        }
                        StatusPill(printer.status, printer.status == "READY")
                    }
                    HorizontalDivider(color = Border)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        PrinterMeta("Paper Tray",   printer.paper.ifBlank { "Unknown" })
                        PrinterMeta("Toner",        printer.toner.takeIf(String::isNotBlank)?.let { "$it%" } ?: "Unknown")
                    }
                }
            }
        }
    }
}

@Composable
private fun PrinterMeta(label: String, value: String) {
    Column {
        Text(label, color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(value, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ─── Shop QR ──────────────────────────────────────────────────────────────────
@Composable
private fun ShopQrPage(shop: ShopDetails?, actionMessage: String?) {
    Text("Shop Permanent QR Code", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    Text("Customers scan this code in the PrivPrint Android app to select your shop.", color = InkMuted, fontSize = 13.sp)
    actionMessage?.let { NoticeCard(it, isError = false) }

    if (shop == null || shop.permanentQrPayload.isBlank()) {
        NoticeCard("Shop QR unavailable. Refresh shop details and try again.", isError = true)
        return
    }
    CardBlock {
        Text(shop.name, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(shop.address, color = InkMuted, fontSize = 13.sp)
        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
            QrCodeCanvas(shop.permanentQrPayload, Modifier.size(260.dp))
        }
        Text("Shop ID  ${shop.id}", color = Muted, fontSize = 12.sp)
        Text("This QR contains the shop ID only — no credentials or document content.", color = InkMuted, fontSize = 12.sp)
    }
}

// ─── Audit ────────────────────────────────────────────────────────────────────
@Composable
private fun AuditPage(events: List<AuditEvent>) {
    Text("Security Audit Log", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    Text("Recent station events: printer, connection, key registration, and job processing.", color = InkMuted, fontSize = 13.sp)

    if (events.isEmpty()) {
        CardBlock {
            Text("No audit events recorded", color = Ink, fontWeight = FontWeight.Bold)
            Text("Events are logged as the station connects and processes print jobs.", color = InkMuted, fontSize = 12.sp)
        }
    } else {
        events.take(60).forEach { event ->
            CardBlock {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(event.eventType.replace('_', ' '), color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    StatusPill(event.severity, event.severity.equals("INFO", true))
                }
                Text(event.details, color = InkMuted, fontSize = 12.sp)
            }
        }
    }
}

// ─── Station Security page ────────────────────────────────────────────────────
@Composable
private fun StationSecurityPage(status: StationStatus?, actionMessage: String?, onReconnect: () -> Unit) {
    Text("Windows Station Security", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    Text("Station registration status and encrypted-printing health.", color = InkMuted, fontSize = 13.sp)

    status?.let { ShopConnectedCard(it) }

    CardBlock {
        Text("Station encryption key", color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(
            if (status?.encryptionKeyRegistered == true)
                "This station's RSA-3072 encryption key is registered. Encrypted customer documents can be decrypted in RAM on this device."
            else
                "The station key is not yet registered. Keep this app open and reconnect the shop station.",
            color = InkMuted, fontSize = 12.sp,
        )
        Text("Private key storage: Windows DPAPI (protected user profile)", color = Blue, fontSize = 12.sp)
        Text("Connection state: ${status?.connectionState ?: "UNKNOWN"}", color = InkMuted, fontSize = 12.sp)
        if (status?.connectionError?.isNotBlank() == true) {
            Text(status.connectionError, color = Red, fontSize = 12.sp)
        }
        actionMessage?.let { NoticeCard(it, it.contains("could not", true)) }
        OutlinedButton(onClick = onReconnect, shape = RoundedCornerShape(10.dp)) {
            Text("Reconnect to Cloud")
        }
    }
}

// ─── Settings page ────────────────────────────────────────────────────────────
@Composable
private fun SettingsPage(
    status: StationStatus?,
    actionMessage: String?,
    onToggleAutoPrint: (Boolean) -> Unit,
    onLogout: () -> Unit,
) {
    Text("Settings", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    Text("Station configuration and preferences.", color = InkMuted, fontSize = 13.sp)
    actionMessage?.let { NoticeCard(it, it.contains("fail", true)) }

    CardBlock {
        Text("Station Information", color = Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        SettingRow("Shop ID",     status?.shopId?.ifBlank { "Not connected" } ?: "Not connected")
        SettingRow("Device ID",   status?.deviceId?.ifBlank { "Registering…" } ?: "Registering…")
        SettingRow("Server",      status?.serverUrl?.ifBlank { "Default" } ?: "Default")
        SettingRow("Key Status",  if (status?.encryptionKeyRegistered == true) "Registered" else "Pending")
    }

    CardBlock {
        Text("Account & Station Identity", color = Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text("Sign out of this shop or disconnect this Windows computer from shop ${status?.shopId}. You will be returned to the Login / Create Account screen.", color = InkMuted, fontSize = 12.sp)
        OutlinedButton(
            onClick = onLogout,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.height(42.dp),
        ) {
            Text("⏏  Sign Out & Disconnect Station", color = Red, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
    }

    CardBlock {
        Text("Auto-Print", color = Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text("When enabled, authorized jobs are printed automatically without manual confirmation.", color = InkMuted, fontSize = 12.sp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (status?.autoPrintEnabled == true) "Auto-print enabled" else "Auto-print paused",
                color = if (status?.autoPrintEnabled == true) Green else Amber, fontWeight = FontWeight.SemiBold)
            Switch(checked = status?.autoPrintEnabled == true, onCheckedChange = onToggleAutoPrint)
        }
    }

    CardBlock {
        Text("About", color = Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        SettingRow("Application",  "PrivPrint Shop Station v1.0.4")
        SettingRow("Platform",     "Kotlin Compose Desktop")
        SettingRow("Security",     "RSA-3072 + AES-256-GCM end-to-end")
        Text("Documents remain encrypted until this authenticated station decrypts them in RAM for printing. No plaintext is written to disk.", color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun SettingRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 12.sp, modifier = Modifier.width(120.dp))
        Text(value, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ─── Shared UI components ─────────────────────────────────────────────────────
@Composable
private fun MetricCard(title: String, value: String, detail: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        colors   = CardDefaults.cardColors(CanvasWhite),
        shape    = RoundedCornerShape(14.dp),
        border   = androidx.compose.foundation.BorderStroke(1.dp, Border),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = InkMuted, fontSize = 11.sp)
            Text(value, color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(detail, color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ShopIdentityCard(shop: ShopDetails, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors   = CardDefaults.cardColors(CanvasWhite),
        shape    = RoundedCornerShape(14.dp),
        border   = androidx.compose.foundation.BorderStroke(1.dp, Border),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (shop.permanentQrPayload.isNotBlank()) {
                QrCodeCanvas(shop.permanentQrPayload, Modifier.size(68.dp))
            } else {
                Box(Modifier.size(68.dp).clip(RoundedCornerShape(10.dp)).background(SurfaceVariant), contentAlignment = Alignment.Center) {
                    Text("QR", color = Muted, fontWeight = FontWeight.Bold)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(shop.name, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    if (shop.verified) Text("✓ Verified", color = Green, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(shop.address.ifBlank { "Xerox shop" }, color = InkMuted, fontSize = 12.sp)
                Text("ID: ${shop.id}", color = Muted, fontSize = 11.sp)
                Text("Tap to display counter QR", color = Blue, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ShopConnectedCard(status: StationStatus) {
    CardBlock {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Shop Connected", color = Green, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(if (status.encryptionKeyRegistered) "KEY REGISTERED" else "KEY PENDING", status.encryptionKeyRegistered)
                StatusPill(if (status.realtimeConnected) "ONLINE" else "RECONNECTING", status.realtimeConnected)
            }
        }
        Text("Shop: ${status.shopId}", color = Ink, fontSize = 13.sp)
        Text("Station: ${status.deviceId.ifBlank { "Registering…" }}", color = InkMuted, fontSize = 12.sp)
        if (!status.encryptionKeyRegistered)
            Text("Keep this app open while the station completes secure key registration.", color = Amber, fontSize = 12.sp)
    }
}

@Composable
private fun CardBlock(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(CanvasWhite),
        shape    = RoundedCornerShape(14.dp),
        border   = androidx.compose.foundation.BorderStroke(1.dp, Border),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun NoticeCard(message: String, isError: Boolean) {
    val fg = if (isError) Red else GreenDark
    val bg = if (isError) RedTint else GreenTint
    Text(message,
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(bg).padding(12.dp),
        color = fg, fontSize = 12.sp)
}

// ─── Existing AuthCard / FormField / ShopPicker (unchanged from original) ─────
@Composable
private fun AuthCard(
    isRegistering: Boolean,
    busy: Boolean,
    error: String?,
    onModeChange: (Boolean) -> Unit,
    onSubmit: (String, String, String, String, String) -> Unit,
) {
    var fullName by remember { mutableStateOf("") }
    var email    by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var shopName by remember { mutableStateOf("") }
    var address  by remember { mutableStateOf("") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(CanvasWhite),
        shape    = RoundedCornerShape(14.dp),
        border   = androidx.compose.foundation.BorderStroke(1.dp, Border),
    ) {
        Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (isRegistering) "Create your shop account" else "Sign in to your shop", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (isRegistering) FormField("Your name", fullName, { fullName = it })
            FormField("Email", email, { email = it })
            FormField("Password", password, { password = it }, secret = true)
            if (isRegistering) {
                FormField("Shop name", shopName, { shopName = it })
                FormField("Shop address", address, { address = it })
            }
            if (!error.isNullOrBlank()) Text(error, color = Red, fontSize = 12.sp)
            Button(
                enabled  = !busy,
                onClick  = { onSubmit(fullName, email, password, shopName, address) },
                modifier = Modifier.fillMaxWidth().height(46.dp),
                colors   = ButtonDefaults.buttonColors(Blue, CanvasWhite),
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
        value                = value,
        onValueChange        = onValueChange,
        label                = { Text(label) },
        modifier             = Modifier.fillMaxWidth(),
        singleLine           = true,
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
        colors   = CardDefaults.cardColors(CanvasWhite),
        shape    = RoundedCornerShape(14.dp),
        border   = androidx.compose.foundation.BorderStroke(1.dp, Border),
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
                enabled  = selected != null && !busy,
                onClick  = onConnect,
                modifier = Modifier.fillMaxWidth().height(46.dp),
                colors   = ButtonDefaults.buttonColors(Blue, CanvasWhite),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Connect shop and register station", fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ─── QR Code canvas ──────────────────────────────────────────────────────────
@Composable
private fun QrCodeCanvas(payload: String, modifier: Modifier = Modifier) {
    val matrix = remember(payload) {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val encoded = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0, hints)
        Array(encoded.height) { row -> BooleanArray(encoded.width) { col -> encoded.get(col, row) } }
    }
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(CanvasWhite).padding(10.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val rows = matrix.size
            val cols = matrix.firstOrNull()?.size ?: 0
            if (rows > 0 && cols > 0) {
                val mw = size.width / cols
                val mh = size.height / rows
                for (r in 0 until rows) {
                    for (c in 0 until cols) {
                        if (matrix[r][c]) drawRect(Ink, Offset(c * mw, r * mh), Size(mw + 0.4f, mh + 0.4f))
                    }
                }
            }
        }
    }
}
