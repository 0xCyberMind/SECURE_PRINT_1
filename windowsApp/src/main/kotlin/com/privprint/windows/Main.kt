package com.privprint.windows

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
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
import java.time.LocalTime

// ─── Design Tokens & Refined Color System ─────────────────────────────────────
private val Slate950         = Color(0xFF090D16) // Deepest navy
private val Slate900         = Color(0xFF0F172A) // Sidebar dark
private val Slate850         = Color(0xFF162032) // Sidebar card / elevated
private val Slate800         = Color(0xFF1E293B) // Dark item border / hover
private val Slate700         = Color(0xFF334155) // Dark muted text
private val Slate600         = Color(0xFF475569) // Secondary dark
private val Slate500         = Color(0xFF64748B) // Neutral muted
private val Slate400         = Color(0xFF94A3B8) // Light muted
private val Slate300         = Color(0xFFCBD5E1) // Strong border
private val Slate200         = Color(0xFFE2E8F0) // Subtle border
private val Slate100         = Color(0xFFF1F5F9) // Surface variant
private val Slate50          = Color(0xFFF8FAFC) // App canvas background
private val PureWhite        = Color(0xFFFFFFFF) // Card surface

// Brand Accent Colors
private val BrandBlue        = Color(0xFF2563EB) // Electric blue
private val BrandBlueHover   = Color(0xFF1D4ED8) // Deep blue
private val BrandBlueLight   = Color(0xFFEFF6FF) // Blue tint surface
private val BrandBlueBorder  = Color(0xFFBFDBFE) // Blue border
private val BrandBlueNavy    = Color(0xFF1E3A8A) // Active nav accent

// Status Colors
private val Emerald          = Color(0xFF059669) // Success green
private val EmeraldHover     = Color(0xFF047857)
private val EmeraldLight     = Color(0xFFECFDF5)
private val EmeraldBorder    = Color(0xFFA7F3D0)

private val Amber            = Color(0xFFD97706) // Warning amber
private val AmberLight       = Color(0xFFFFFBEB)
private val AmberBorder      = Color(0xFFFDE68A)

private val Rose             = Color(0xFFDC2626) // Danger red
private val RoseLight        = Color(0xFFFEF2F2)
private val RoseBorder       = Color(0xFFFECACA)

private val Violet           = Color(0xFF7C3AED) // Security audit purple
private val VioletLight      = Color(0xFFF5F3FF)
private val VioletBorder     = Color(0xFFDDD6FE)

// ─── Navigation Categorization ────────────────────────────────────────────────
enum class NavCategory(val label: String) {
    WORKSPACE("WORKSPACE"),
    OPERATIONS("OPERATIONS"),
    SYSTEM("SYSTEM")
}

enum class StationPage(val title: String, val subtitle: String, val category: NavCategory) {
    OVERVIEW ("Dashboard",       "Real-time shop station telemetry & queue overview", NavCategory.WORKSPACE),
    QUEUE    ("Print Queue",     "Customer-authorized encrypted document jobs",       NavCategory.WORKSPACE),
    PRINTERS ("Printers",        "Local hardware drivers & spooler telemetry",       NavCategory.WORKSPACE),
    SHOP_QR  ("Shop QR",         "Customer pairing & counter signage",                NavCategory.OPERATIONS),
    AUDIT    ("Security Audit",  "Immutable station events & cryptographic log",     NavCategory.OPERATIONS),
    SECURITY ("Windows Station", "Hardware daemon & DPAPI key status",               NavCategory.SYSTEM),
    SETTINGS ("Settings",        "Station configuration & print policies",           NavCategory.SYSTEM),
}

// ─── Application Entry Point ──────────────────────────────────────────────────
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
        title          = "PrivPrint | Enterprise Xerox Shop Station",
        state          = rememberWindowState(width = 1380.dp, height = 880.dp),
    ) {
        MaterialTheme(
            colorScheme = lightColorScheme(
                primary             = BrandBlue,
                onPrimary           = PureWhite,
                primaryContainer    = BrandBlueLight,
                onPrimaryContainer  = BrandBlueHover,
                secondary           = Slate900,
                background          = Slate50,
                onBackground        = Slate900,
                surface             = PureWhite,
                onSurface           = Slate900,
                surfaceVariant      = Slate100,
                onSurfaceVariant    = Slate600,
                outline             = Slate200,
                error               = Rose,
            ),
        ) {
            Surface(Modifier.fillMaxSize(), color = Slate50) {
                when {
                    startupError != null -> StartupFailureScreen(startupError!!)
                    !isReady             -> SplashScreen()
                    else                 -> AppRoot(bridge)
                }
            }
        }
    }
}

// ─── Splash Screen ────────────────────────────────────────────────────────────
@Composable
private fun SplashScreen() {
    Box(Modifier.fillMaxSize().background(Slate950), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(BrandBlue),
                contentAlignment = Alignment.Center
            ) {
                StationIcons.Shield(modifier = Modifier.size(32.dp), color = PureWhite)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("PRIVPRINT", color = PureWhite, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
                Text("SECURE PRINT STATION", color = Slate400, fontSize = 10.sp, letterSpacing = 3.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))
            CircularProgressIndicator(color = BrandBlue, modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
            Text("Initializing secure Windows hardware spooler…", color = Slate400, fontSize = 12.sp)
        }
    }
}

@Composable
private fun StartupFailureScreen(message: String) {
    Box(Modifier.fillMaxSize().background(Slate50), contentAlignment = Alignment.Center) {
        Card(
            Modifier.widthIn(max = 520.dp),
            colors = CardDefaults.cardColors(PureWhite),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Slate200),
            elevation = CardDefaults.cardElevation(2.dp),
        ) {
            Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(RoseLight), contentAlignment = Alignment.Center) {
                        StationIcons.Alert(modifier = Modifier.size(18.dp), color = Rose)
                    }
                    Text("Could not start PrivPrint Station", color = Slate900, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                Text(message, color = Rose, fontSize = 13.sp)
                Text("Please ensure the background print daemon is not blocked by Windows Security and reopen the app.", color = Slate500, fontSize = 12.sp)
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
            runCatching { status = bridge.status() }
        }
    }
}

// ─── Login Root ───────────────────────────────────────────────────────────────
@Composable
private fun LoginRoot(bridge: StationBridge, onAuthenticated: suspend () -> Unit) {
    var status by remember { mutableStateOf<StationStatus?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1_500)
            runCatching { status = withContext(Dispatchers.IO) { bridge.status() } }
            if (status?.authenticated == true) {
                onAuthenticated()
                break
            }
        }
    }

    if (status?.authenticated == true) {
        StationApplication(bridge, initialStatus = status)
        return
    }

    LoginScreen(bridge)
}

// ─── Redesigned Enterprise Login Screen ───────────────────────────────────────
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
        // Left Brand Showcase Panel (MNC Enterprise Feel)
        Box(
            Modifier.width(440.dp).fillMaxHeight().background(Slate950).padding(44.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                // Top Brand Mark
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(
                        Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(BrandBlue),
                        contentAlignment = Alignment.Center
                    ) {
                        StationIcons.Shield(modifier = Modifier.size(26.dp), color = PureWhite)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("PRIVPRINT", color = PureWhite, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
                        Text("SECURE PRINT STATION", color = Slate400, fontSize = 10.sp, letterSpacing = 3.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                // Middle Value Propositions
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text("Enterprise-grade zero-knowledge printing for Xerox & copy centers.", color = Slate200, fontSize = 15.sp, fontWeight = FontWeight.Medium, lineHeight = 22.sp)

                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        EnterpriseFeatureItem(
                            title = "Zero-Knowledge Encryption",
                            desc = "RSA-3072 + AES-256-GCM envelope decrypted strictly in station RAM."
                        )
                        EnterpriseFeatureItem(
                            title = "Strict Hardware Copy Caps",
                            desc = "Atomic copy limits enforced directly at the Windows spooler."
                        )
                        EnterpriseFeatureItem(
                            title = "Zero Disk Footprint",
                            desc = "Documents are never written to disk and memory is wiped post-print."
                        )
                        EnterpriseFeatureItem(
                            title = "DPAPI Key Protection",
                            desc = "Station private keys sealed using Windows DPAPI user cryptography."
                        )
                    }
                }

                // Bottom Version & Trust Badges
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(Emerald))
                    Text("PrivPrint Enterprise v1.0.5  ·  SOC-2 Compliant Architecture", color = Slate500, fontSize = 11.sp)
                }
            }
        }

        // Right Authentication Form Area
        Box(
            Modifier.fillMaxSize().background(Slate50).padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Card(
                Modifier.widthIn(min = 400.dp, max = 480.dp),
                colors = CardDefaults.cardColors(PureWhite),
                shape  = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Slate200),
                elevation = CardDefaults.cardElevation(2.dp),
            ) {
                Column(Modifier.padding(36.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            if (isRegistering) "Register Xerox Shop" else "Station Operator Sign In",
                            color = Slate900, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        )
                        Text(
                            if (isRegistering) "Create a verified shop operator profile to link this station."
                            else "Sign in with your operator credentials to connect this Windows workstation.",
                            color = Slate500, fontSize = 13.sp, lineHeight = 18.sp,
                        )
                    }

                    HorizontalDivider(color = Slate100, modifier = Modifier.padding(vertical = 4.dp))

                    if (shops.isEmpty()) {
                        if (isRegistering) {
                            ModernInputField("Operator Full Name", fullName, { fullName = it }, placeholder = "e.g. Rahul Sharma")
                        }
                        ModernInputField("Operator Email Address", email, { email = it }, placeholder = "operator@xeroxcenter.com")
                        ModernInputField(
                            "Account Password",
                            password,
                            { password = it },
                            secret = true,
                            placeholder = "••••••••••••",
                            onEnter = {
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
                            }
                        )
                        if (isRegistering) {
                            ModernInputField("Shop Name", shopName, { shopName = it }, placeholder = "e.g. Apex Xerox & Print Hub")
                            ModernInputField("Shop Physical Address", shopAddress, { shopAddress = it }, placeholder = "e.g. 102 MG Road, Paldi, Ahmedabad")
                        }

                        error?.let {
                            ModernNotice(it, isError = true)
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
                            modifier = Modifier.fillMaxWidth().height(46.dp),
                            colors   = ButtonDefaults.buttonColors(BrandBlue, PureWhite),
                            shape    = RoundedCornerShape(10.dp),
                        ) {
                            if (busy) {
                                CircularProgressIndicator(Modifier.size(18.dp), color = PureWhite, strokeWidth = 2.dp)
                            } else {
                                Text(if (isRegistering) "Create Shop & Continue" else "Sign In to Workstation", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            }
                        }

                        TextButton(
                            onClick  = { isRegistering = !isRegistering; error = null; shops = emptyList(); selectedShop = null },
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        ) {
                            Text(
                                if (isRegistering) "Already registered? Sign in" else "Need to register a new shop? Create an account",
                                color = BrandBlue, fontSize = 12.sp, fontWeight = FontWeight.Medium
                            )
                        }
                    } else {
                        // Shop Picker Step
                        Text("Select Target Shop", color = Slate900, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text("Choose which shop counter this physical workstation will spool jobs for:", color = Slate500, fontSize = 12.sp)

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            shops.forEach { shop ->
                                val isSelected = selectedShop?.id == shop.id
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                        .background(if (isSelected) BrandBlueLight else Slate100)
                                        .border(1.dp, if (isSelected) BrandBlue else Slate200, RoundedCornerShape(10.dp))
                                        .clickable { selectedShop = shop }
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (isSelected) BrandBlue else Slate300))
                                    Column(Modifier.weight(1f)) {
                                        Text(shop.name, color = Slate900, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                        Text("Shop ID: ${shop.id}", color = Slate500, fontSize = 11.sp)
                                    }
                                    if (isSelected) {
                                        Text("Selected", color = BrandBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }

                        error?.let {
                            ModernNotice(it, isError = true)
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
                            modifier = Modifier.fillMaxWidth().height(46.dp),
                            colors   = ButtonDefaults.buttonColors(BrandBlue, PureWhite),
                            shape    = RoundedCornerShape(10.dp),
                        ) {
                            if (busy) {
                                CircularProgressIndicator(Modifier.size(18.dp), color = PureWhite, strokeWidth = 2.dp)
                            } else {
                                Text("Connect Workstation & Register DPAPI Key", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            }
                        }

                        TextButton(
                            onClick = { shops = emptyList(); selectedShop = null; error = null },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            Text("← Back to login", color = BrandBlue, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EnterpriseFeatureItem(title: String, desc: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(20.dp).clip(CircleShape).background(Color(0xFF1E293B)),
            contentAlignment = Alignment.Center
        ) {
            StationIcons.Check(modifier = Modifier.size(10.dp), color = BrandBlue)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = PureWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(desc, color = Slate400, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}

// ─── Main Station Application (Post-Authentication) ───────────────────────────
@Composable
private fun StationApplication(bridge: StationBridge, initialStatus: StationStatus?) {
    var status         by remember { mutableStateOf(initialStatus) }
    var shop           by remember { mutableStateOf<ShopDetails?>(null) }
    var selectedPage   by remember { mutableStateOf(StationPage.OVERVIEW) }
    var queue          by remember { mutableStateOf<List<PrintJobStatus>>(emptyList()) }
    var queueBusy      by remember { mutableStateOf(false) }
    var queueError     by remember { mutableStateOf<String?>(null) }
    var actionMessage  by remember { mutableStateOf<String?>(null) }
    var autoPrintBusy  by remember { mutableStateOf(false) }
    var loggedOut      by remember { mutableStateOf(false) }
    var statusError    by remember { mutableStateOf<String?>(null) }

    // Dialog & Inspection State
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
                previewError = ex.message ?: "Could not decrypt document in RAM."
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
            try {
                viewingJob = withContext(Dispatchers.IO) { bridge.jobDetails(job.id) }
            } catch (ex: Exception) {
                jobDetailError = ex.message ?: "Could not load job details."
            } finally {
                jobDetailBusy = false
            }
        }
    }

    // Initial data load and background polling
    LaunchedEffect(Unit) {
        runCatching { refreshStatus() }
            .onSuccess {
                if (status?.authenticated == true) {
                    runCatching { refreshShop() }.onFailure { statusError = it.message }
                    refreshQueue(showLoading = false)
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

    // Faster polling on QUEUE page
    LaunchedEffect(selectedPage, status?.authenticated) {
        if (selectedPage == StationPage.QUEUE && status?.authenticated == true) {
            refreshQueue(showLoading = false)
            while (true) {
                delay(3_000)
                refreshQueue(showLoading = false)
            }
        }
    }

    if (loggedOut) {
        LoginRoot(bridge) {
            status = bridge.status()
            loggedOut = false
        }
        return
    }

    // ── Dialog Overlays ──
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
                        actionMessage = "Job #${job.id.takeLast(8).uppercase()} rejected and cancelled."
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
                        actionMessage = "Job #${job.id.takeLast(8).uppercase()} sent to ${printerName.ifBlank { "default printer" }}."
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

    // ── Master Desktop Application Shell ──
    Row(Modifier.fillMaxSize()) {
        // ── 1. Sophisticated Enterprise Sidebar (250dp) ──
        Column(
            Modifier.width(250.dp).fillMaxHeight().background(Slate900).padding(0.dp),
        ) {
            // Brand Area
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(BrandBlue),
                        contentAlignment = Alignment.Center
                    ) {
                        StationIcons.Shield(modifier = Modifier.size(18.dp), color = PureWhite)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text("PRIVPRINT", color = PureWhite, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                        Text("SECURE PRINT STATION", color = Slate400, fontSize = 9.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // Compact Professional Shop Identity Card
            if (shop != null) {
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Slate850)
                        .border(1.dp, Slate800, RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("SHOP PROFILE", color = Slate400, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(Modifier.size(6.dp).clip(CircleShape).background(Emerald))
                                Text("Verified", color = Emerald, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(shop!!.name, color = PureWhite, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(shop!!.address.ifBlank { "Registered Xerox Center" }, color = Slate400, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }

                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("ID: ${shop!!.id.takeLast(10)}", color = Slate500, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                            Text(
                                "Display QR →",
                                color = BrandBlue,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable { selectedPage = StationPage.SHOP_QR }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            // Categorized Navigation List
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                val activeJobsCount = queue.count { it.status in ACTIVE_STATUSES }
                val readyPrintersCount = status?.printers?.count { it.status == "READY" } ?: 0
                val auditEventsCount = status?.auditLog?.size ?: 0

                NavCategory.entries.forEach { category ->
                    val pages = StationPage.entries.filter { it.category == category }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            category.label,
                            color = Slate500,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                        )

                        pages.forEach { page ->
                            val badge = when (page) {
                                StationPage.QUEUE -> if (activeJobsCount > 0) activeJobsCount.toString() else null
                                StationPage.PRINTERS -> if (readyPrintersCount > 0) readyPrintersCount.toString() else null
                                StationPage.AUDIT -> if (auditEventsCount > 0) auditEventsCount.toString() else null
                                else -> null
                            }
                            ModernSidebarNavItem(
                                page = page,
                                selected = selectedPage == page,
                                badge = badge,
                                onClick = {
                                    selectedPage = page
                                    actionMessage = null
                                }
                            )
                        }
                    }
                }
            }

            // Sidebar Footer: Status & Account
            HorizontalDivider(color = Slate800)

            Column(
                Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Live Station Status Indicator
                val isOnline = status?.realtimeConnected == true
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isOnline) Color(0xFF0D251C) else Color(0xFF2E2010))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(if (isOnline) Emerald else Amber))
                    Text(
                        if (isOnline) "Station Online · DPAPI Active" else "Reconnecting Cloud…",
                        color = if (isOnline) Emerald else Amber,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // Operator Row + Logout Action
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).background(Slate800),
                            contentAlignment = Alignment.Center
                        ) {
                            StationIcons.User(modifier = Modifier.size(14.dp), color = Slate400)
                        }
                        Column {
                            Text("Operator", color = PureWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(status?.shopId?.takeLast(10) ?: "Station Account", color = Slate500, fontSize = 10.sp)
                        }
                    }

                    TextButton(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { bridge.logout() }
                                status = null
                                shop = null
                                queue = emptyList()
                                loggedOut = true
                            }
                        },
                        modifier = Modifier.height(30.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            StationIcons.Logout(modifier = Modifier.size(12.dp), color = Rose)
                            Text("Exit", color = Rose, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        // ── 2. Master Content Canvas ──
        Column(Modifier.fillMaxSize()) {
            // Enterprise Top Header Bar
            Row(
                Modifier.fillMaxWidth().height(64.dp).background(PureWhite).padding(horizontal = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Breadcrumbs & Page Description
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("PrivPrint", color = Slate500, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        StationIcons.ChevronRight(modifier = Modifier.size(10.dp), color = Slate400)
                        Text(selectedPage.title, color = Slate900, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(selectedPage.subtitle, color = Slate500, fontSize = 11.sp)
                }

                // Header Controls
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Auto-Print Indicator Pill
                    val isAutoPrint = status?.autoPrintEnabled == true
                    Box(
                        Modifier.clip(RoundedCornerShape(20.dp))
                            .background(if (isAutoPrint) BrandBlueLight else Slate100)
                            .border(1.dp, if (isAutoPrint) BrandBlueBorder else Slate200, RoundedCornerShape(20.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            if (isAutoPrint) "Auto-Print: ACTIVE" else "Auto-Print: MANUAL",
                            color = if (isAutoPrint) BrandBlueHover else Slate600,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    // Connection Badge
                    HeaderConnectionBadge(status)

                    // Quick Refresh Button
                    OutlinedButton(
                        enabled = !queueBusy && !autoPrintBusy,
                        onClick = {
                            scope.launch {
                                actionMessage = null
                                when (selectedPage) {
                                    StationPage.QUEUE    -> refreshQueue()
                                    StationPage.PRINTERS -> runCatching {
                                        val n = withContext(Dispatchers.IO) { bridge.refreshPrinters() }
                                        refreshStatus()
                                        actionMessage = "$n Windows printer(s) synchronized."
                                    }.onFailure { actionMessage = it.message ?: "Printer sync failed." }
                                    StationPage.SHOP_QR  -> runCatching {
                                        refreshShop(); actionMessage = "Shop QR updated."
                                    }.onFailure { actionMessage = it.message }
                                    else -> runCatching { refreshStatus() }.onFailure { statusError = it.message }
                                }
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(34.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Slate700),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Slate200),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            StationIcons.Refresh(modifier = Modifier.size(12.dp), color = Slate600)
                            Text(
                                when (selectedPage) {
                                    StationPage.QUEUE    -> "Sync Queue"
                                    StationPage.PRINTERS -> "Sync Hardware"
                                    StationPage.SHOP_QR  -> "Refresh QR"
                                    else                 -> "Refresh"
                                },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
            HorizontalDivider(color = Slate200)

            // Scrollable Content Viewport
            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    statusError?.let { ModernNotice(it, isError = true) }
                    actionMessage?.let { ModernNotice(it, isError = it.contains("fail", true) || it.contains("could not", true)) }

                    when (selectedPage) {
                        StationPage.OVERVIEW  -> OverviewPage(
                            status, shop, queue, autoPrintBusy,
                            onOpenQr        = { selectedPage = StationPage.SHOP_QR },
                            onOpenQueue     = { selectedPage = StationPage.QUEUE },
                            onOpenPrinters  = { selectedPage = StationPage.PRINTERS },
                            onToggleAutoPrint = { enabled ->
                                autoPrintBusy = true; actionMessage = null
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) { bridge.setAutoPrintEnabled(enabled) }
                                        refreshStatus()
                                        actionMessage = if (enabled) "Direct auto-print enabled." else "Direct auto-print paused (Manual verification mode)."
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
                            queue, queueBusy, queueError,
                            onVerify        = onVerifyJob,
                            onAccept        = onAcceptJob,
                            onCancel        = onCancelJob,
                            onDetails       = onDetailsJob,
                            jobDetailBusy   = jobDetailBusy,
                            jobDetailError  = jobDetailError,
                            onRefresh       = { scope.launch { refreshQueue() } }
                        )
                        StationPage.PRINTERS  -> PrinterPage(status, onSync = {
                            scope.launch {
                                runCatching {
                                    val n = withContext(Dispatchers.IO) { bridge.refreshPrinters() }
                                    refreshStatus()
                                    actionMessage = "$n Windows printer(s) synchronized."
                                }.onFailure { actionMessage = it.message ?: "Printer sync failed." }
                            }
                        })
                        StationPage.SHOP_QR   -> ShopQrPage(shop, onRefresh = {
                            scope.launch {
                                runCatching { refreshShop(); actionMessage = "Shop QR reloaded." }
                                    .onFailure { actionMessage = it.message }
                            }
                        })
                        StationPage.AUDIT     -> AuditPage(status?.auditLog.orEmpty())
                        StationPage.SECURITY  -> StationSecurityPage(
                            status,
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
                        StationPage.SETTINGS  -> SettingsPage(
                            status,
                            shop,
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
                            onUpdateHistoryRetention = { hours ->
                                try {
                                    withContext(Dispatchers.IO) { bridge.setHistoryRetentionHours(hours) }
                                    refreshStatus()
                                    refreshQueue(showLoading = false)
                                    true
                                } catch (ex: Exception) {
                                    actionMessage = ex.message ?: "Failed to update history retention."
                                    false
                                }
                            },
                            onUpdateLocation = { lat, lng, addr ->
                                try {
                                    val updated = withContext(Dispatchers.IO) { bridge.updateShopLocation(lat, lng, addr) }
                                    shop = updated
                                    refreshStatus()
                                    actionMessage = "Shop location saved. Shop is now discoverable in nearby searches."
                                    true
                                } catch (ex: Exception) {
                                    actionMessage = ex.message ?: "Failed to save location."
                                    false
                                }
                            },
                            onDetectLocation = {
                                withContext(Dispatchers.IO) { bridge.detectCurrentLocation() }
                            },
                            onDisableLocation = {
                                try {
                                    val updated = withContext(Dispatchers.IO) { bridge.disableShopLocation() }
                                    shop = updated
                                    refreshStatus()
                                    actionMessage = "Shop location disabled."
                                    true
                                } catch (ex: Exception) {
                                    actionMessage = ex.message ?: "Failed to disable location."
                                    false
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

// ─── Modern Sidebar Navigation Item ───────────────────────────────────────────
@Composable
private fun ModernSidebarNavItem(
    page: StationPage,
    selected: Boolean,
    badge: String? = null,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 1.5.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) BrandBlueNavy else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val iconColor = if (selected) PureWhite else Slate400
            when (page) {
                StationPage.OVERVIEW -> StationIcons.Dashboard(modifier = Modifier.size(15.dp), color = iconColor)
                StationPage.QUEUE    -> StationIcons.PrintQueue(modifier = Modifier.size(15.dp), color = iconColor)
                StationPage.PRINTERS -> StationIcons.Printer(modifier = Modifier.size(15.dp), color = iconColor)
                StationPage.SHOP_QR  -> StationIcons.QrCode(modifier = Modifier.size(15.dp), color = iconColor)
                StationPage.AUDIT    -> StationIcons.Audit(modifier = Modifier.size(15.dp), color = iconColor)
                StationPage.SECURITY -> StationIcons.Station(modifier = Modifier.size(15.dp), color = iconColor)
                StationPage.SETTINGS -> StationIcons.Settings(modifier = Modifier.size(15.dp), color = iconColor)
            }
            Text(
                page.title,
                color = if (selected) PureWhite else Slate300,
                fontSize = 12.5.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
        }

        if (badge != null) {
            Box(
                Modifier.clip(RoundedCornerShape(10.dp))
                    .background(if (selected) BrandBlue else Slate800)
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text(badge, color = PureWhite, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ─── Header Connection Badge ──────────────────────────────────────────────────
@Composable
private fun HeaderConnectionBadge(status: StationStatus?) {
    val connected = status?.realtimeConnected == true
    val bg = if (connected) EmeraldLight else AmberLight
    val border = if (connected) EmeraldBorder else AmberBorder
    val fg = if (connected) Emerald else Amber

    Row(
        Modifier.clip(RoundedCornerShape(20.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(fg))
        Text(
            if (connected) "Cloud Connected" else "Reconnecting…",
            color = fg,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// ─── Dashboard Hero & Overview Page ───────────────────────────────────────────
@Composable
private fun OverviewPage(
    status: StationStatus?,
    shop: ShopDetails?,
    queue: List<PrintJobStatus>,
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
    // 1. Dashboard Hero Banner
    val currentHour = remember { LocalTime.now().hour }
    val greeting = when {
        currentHour < 12 -> "Good morning"
        currentHour < 17 -> "Good afternoon"
        else -> "Good evening"
    }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(PureWhite),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Slate200),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("$greeting, ${shop?.name ?: "Operator"}", color = Slate900, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    if (shop?.verified == true) {
                        Box(Modifier.clip(RoundedCornerShape(4.dp)).background(EmeraldLight).padding(horizontal = 6.dp, vertical = 2.dp)) {
                            Text("Verified Counter", color = Emerald, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Text("Here is your station's current print queue, hardware availability, and security telemetry.", color = Slate500, fontSize = 13.sp)
            }

            OutlinedButton(
                onClick = onOpenQr,
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Slate300),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StationIcons.QrCode(modifier = Modifier.size(14.dp), color = Slate700)
                    Text("Display Shop QR", color = Slate800, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }

    // 2. Metric Cards Grid (Stripe / Linear Inspired Clean Metrics)
    val activeJobs = queue.count { it.status in ACTIVE_STATUSES }
    val readyPrinters = status?.printers?.count { it.status == "READY" } ?: 0
    val completedCount = status?.completedJobCount ?: 0

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        EnterpriseMetricCard(
            title = "PENDING JOBS",
            value = activeJobs.toString().padStart(2, '0'),
            subtitle = if (activeJobs > 0) "$activeJobs jobs awaiting review" else "Queue is clear",
            badgeText = if (activeJobs > 0) "Active" else "Idle",
            badgeColor = if (activeJobs > 0) BrandBlue else Slate400,
            onClick = onOpenQueue,
            modifier = Modifier.weight(1f)
        )
        EnterpriseMetricCard(
            title = "ACTIVE PRINTERS",
            value = readyPrinters.toString().padStart(2, '0'),
            subtitle = "${status?.printers?.size ?: 0} spooler devices detected",
            badgeText = if (readyPrinters > 0) "Ready" else "No Printer",
            badgeColor = if (readyPrinters > 0) Emerald else Amber,
            onClick = onOpenPrinters,
            modifier = Modifier.weight(1f)
        )
        EnterpriseMetricCard(
            title = "COMPLETED TODAY",
            value = completedCount.toString().padStart(2, '0'),
            subtitle = "Zero-disk RAM payloads wiped",
            badgeText = "Session",
            badgeColor = Slate500,
            onClick = onOpenQueue,
            modifier = Modifier.weight(1f)
        )
        EnterpriseMetricCard(
            title = "STATION SECURITY",
            value = if (status?.realtimeConnected == true) "Online" else "Syncing",
            subtitle = "RSA-3072 + DPAPI Protected",
            badgeText = if (status?.encryptionKeyRegistered == true) "Key Sealed" else "Key Pending",
            badgeColor = if (status?.encryptionKeyRegistered == true) Emerald else Amber,
            onClick = {},
            modifier = Modifier.weight(1f)
        )
    }

    // 3. High-Priority Action Banner (Incoming Authorized Customer Jobs)
    val authorizedJobs = queue.filter { it.status == "AUTHORIZED" }
    if (authorizedJobs.isNotEmpty()) {
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(PureWhite),
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, BrandBlue),
        ) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(BrandBlue))
                        Text("Action Required — Incoming Customer Jobs (${authorizedJobs.size})", color = Slate900, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    Box(Modifier.clip(RoundedCornerShape(20.dp)).background(BrandBlueLight).padding(horizontal = 10.dp, vertical = 4.dp)) {
                        Text("READY FOR OPERATOR REVIEW", color = BrandBlueHover, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Text(
                    "Customer documents encrypted on mobile devices are awaiting your verification in RAM before sending to the Windows printer spooler.",
                    color = Slate600, fontSize = 12.5.sp
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    authorizedJobs.take(3).forEach { job ->
                        JobRowItem(
                            job = job,
                            onVerify = onVerifyJob,
                            onAccept = onAcceptJob,
                            onCancel = onCancelJob,
                            onDetails = onDetailsJob,
                            compact = true
                        )
                    }
                }

                if (authorizedJobs.size > 3) {
                    TextButton(onClick = onOpenQueue, modifier = Modifier.align(Alignment.End)) {
                        Text("View all ${authorizedJobs.size} incoming jobs in Print Queue →", color = BrandBlue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    // 4. Direct Auto-Print Control Card
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(PureWhite),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Slate200),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.weight(1f)) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(if (status?.autoPrintEnabled == true) BrandBlueLight else Slate100),
                    contentAlignment = Alignment.Center
                ) {
                    StationIcons.Printer(modifier = Modifier.size(20.dp), color = if (status?.autoPrintEnabled == true) BrandBlue else Slate500)
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Direct Auto-Print Mode", color = Slate900, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Box(
                            Modifier.clip(RoundedCornerShape(4.dp))
                                .background(if (status?.autoPrintEnabled == true) EmeraldLight else AmberLight)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                if (status?.autoPrintEnabled == true) "ENABLED" else "MANUAL VERIFY MODE",
                                color = if (status?.autoPrintEnabled == true) Emerald else Amber,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Text(
                        if (status?.autoPrintEnabled == true)
                            "Customer authorized print jobs are automatically rasterized in volatile RAM and sent directly to the default printer."
                        else
                            "Operator verification is required before printing. Recommended for privacy-sensitive documents to review pages in RAM.",
                        color = Slate500, fontSize = 12.sp
                    )
                }
            }

            Spacer(Modifier.width(16.dp))

            Switch(
                checked = status?.autoPrintEnabled == true,
                onCheckedChange = onToggleAutoPrint,
                enabled = !autoPrintBusy,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = PureWhite,
                    checkedTrackColor = BrandBlue,
                    uncheckedThumbColor = Slate400,
                    uncheckedTrackColor = Slate200,
                )
            )
        }
    }

    // 5. Station Hardware & Cryptographic Health Summary
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(PureWhite),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Slate200),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Station Cryptographic & Spooler Health", color = Slate900, fontWeight = FontWeight.Bold, fontSize = 14.sp)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                HealthIndicatorItem("DPAPI Sealed Key", if (status?.encryptionKeyRegistered == true) "Active" else "Pending", status?.encryptionKeyRegistered == true, Modifier.weight(1f))
                HealthIndicatorItem("Cloud WebSocket", if (status?.realtimeConnected == true) "Online" else "Reconnecting", status?.realtimeConnected == true, Modifier.weight(1f))
                HealthIndicatorItem("In-RAM Decryption", "Zero-Disk", true, Modifier.weight(1f))
                HealthIndicatorItem("Windows Spooler", "${status?.printers?.size ?: 0} Devices", (status?.printers?.size ?: 0) > 0, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun HealthIndicatorItem(title: String, statusText: String, ok: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(10.dp)).background(Slate50).border(1.dp, Slate200, RoundedCornerShape(10.dp)).padding(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = Slate500, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(if (ok) Emerald else Amber))
                Text(statusText, color = Slate900, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ─── Enterprise Metric Card ───────────────────────────────────────────────────
@Composable
private fun EnterpriseMetricCard(
    title: String,
    value: String,
    subtitle: String,
    badgeText: String,
    badgeColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        colors = CardDefaults.cardColors(PureWhite),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Slate200),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(title, color = Slate500, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                Box(
                    Modifier.clip(RoundedCornerShape(4.dp))
                        .background(badgeColor.copy(alpha = 0.12f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(badgeText, color = badgeColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            Text(value, color = Slate900, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Slate500, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ─── Print Queue Screen (Enterprise Table Experience) ─────────────────────────
private val ACTIVE_STATUSES = setOf("AUTHORIZED", "PENDING", "PRINTING", "ACCEPTED")
private val DONE_STATUSES   = setOf("COMPLETED", "FAILED", "CANCELLED", "EXPIRED")

@Composable
private fun PrintQueuePage(
    queue: List<PrintJobStatus>,
    busy: Boolean,
    error: String?,
    onVerify: (PrintJobStatus) -> Unit,
    onAccept: (PrintJobStatus) -> Unit,
    onCancel: (PrintJobStatus) -> Unit,
    onDetails: (PrintJobStatus) -> Unit,
    jobDetailBusy: Boolean,
    jobDetailError: String?,
    onRefresh: () -> Unit
) {
    var selectedFilter by remember { mutableStateOf("ALL") }
    var searchQuery by remember { mutableStateOf("") }

    val activeCount    = queue.count { it.status in ACTIVE_STATUSES }
    val completedCount = queue.count { it.status == "COMPLETED" }
    val failedCount    = queue.count { it.status in setOf("FAILED", "CANCELLED", "EXPIRED") }

    val filteredQueue = remember(queue, selectedFilter, searchQuery) {
        queue.filter { job ->
            val matchesFilter = when (selectedFilter) {
                "ACTIVE"    -> job.status in ACTIVE_STATUSES
                "COMPLETED" -> job.status == "COMPLETED"
                "FAILED"    -> job.status in setOf("FAILED", "CANCELLED", "EXPIRED")
                else        -> true
            }
            val matchesSearch = searchQuery.isBlank() ||
                job.documentName.contains(searchQuery, ignoreCase = true) ||
                job.id.contains(searchQuery, ignoreCase = true)
            matchesFilter && matchesSearch
        }
    }

    // Filter Tabs & Search Header Row
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QueueFilterTab("All Jobs", queue.size, selectedFilter == "ALL") { selectedFilter = "ALL" }
            QueueFilterTab("Active & Pending", activeCount, selectedFilter == "ACTIVE", BrandBlue) { selectedFilter = "ACTIVE" }
            QueueFilterTab("Completed", completedCount, selectedFilter == "COMPLETED", Emerald) { selectedFilter = "COMPLETED" }
            QueueFilterTab("Failed / Cancelled", failedCount, selectedFilter == "FAILED", Rose) { selectedFilter = "FAILED" }
        }

        // Search Input
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search by document or Job ID…", fontSize = 12.sp, color = Slate400) },
            singleLine = true,
            modifier = Modifier.width(280.dp).height(40.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = PureWhite,
                unfocusedContainerColor = PureWhite,
                focusedIndicatorColor = BrandBlue,
                unfocusedIndicatorColor = Slate200,
            ),
            shape = RoundedCornerShape(8.dp),
        )
    }

    jobDetailError?.let { ModernNotice(it, isError = true) }

    when {
        busy && queue.isEmpty() -> {
            // Skeleton Loading State
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(4) {
                    Box(
                        Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(12.dp))
                            .background(Slate100)
                            .border(1.dp, Slate200, RoundedCornerShape(12.dp))
                    )
                }
            }
        }
        error != null -> {
            ModernNotice(error, isError = true)
        }
        filteredQueue.isEmpty() -> {
            // Empty State
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(PureWhite),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Slate200),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(Modifier.size(48.dp).clip(CircleShape).background(Slate100), contentAlignment = Alignment.Center) {
                        StationIcons.PrintQueue(modifier = Modifier.size(24.dp), color = Slate400)
                    }
                    Text("No print jobs found", color = Slate900, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        if (searchQuery.isNotBlank()) "No jobs match your search query '$searchQuery'."
                        else "There are currently no print jobs matching the selected filter.",
                        color = Slate500, fontSize = 13.sp, textAlign = TextAlign.Center
                    )
                    OutlinedButton(onClick = onRefresh, shape = RoundedCornerShape(8.dp)) {
                        Text("Refresh Queue", fontSize = 12.sp)
                    }
                }
            }
        }
        else -> {
            // Table-like Job Cards List
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                filteredQueue.forEach { job ->
                    JobRowItem(
                        job = job,
                        onVerify = onVerify,
                        onAccept = onAccept,
                        onCancel = onCancel,
                        onDetails = onDetails,
                        compact = false
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueFilterTab(label: String, count: Int, selected: Boolean, activeColor: Color = BrandBlue, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp))
            .background(if (selected) PureWhite else Slate100)
            .border(1.dp, if (selected) activeColor else Slate200, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, color = if (selected) Slate900 else Slate600, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            Box(
                Modifier.clip(RoundedCornerShape(10.dp))
                    .background(if (selected) activeColor.copy(alpha = 0.12f) else Slate200)
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            ) {
                Text(count.toString(), color = if (selected) activeColor else Slate600, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ─── Professional Job Row Item ────────────────────────────────────────────────
@Composable
private fun JobRowItem(
    job: PrintJobStatus,
    onVerify: (PrintJobStatus) -> Unit,
    onAccept: (PrintJobStatus) -> Unit,
    onCancel: (PrintJobStatus) -> Unit,
    onDetails: (PrintJobStatus) -> Unit,
    compact: Boolean = false
) {
    val isAuthorized = job.status == "AUTHORIZED"
    val isCompleted  = job.status == "COMPLETED"
    val isFailed     = job.status in setOf("FAILED", "CANCELLED", "EXPIRED")

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(PureWhite),
        shape  = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(
            if (isAuthorized) 1.5.dp else 1.dp,
            if (isAuthorized) BrandBlue else Slate200
        ),
        elevation = CardDefaults.cardElevation(if (isAuthorized) 2.dp else 0.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(if (compact) 14.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Main Top Row
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left: Icon + Doc Info
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(if (isAuthorized) BrandBlueLight else Slate100),
                        contentAlignment = Alignment.Center
                    ) {
                        StationIcons.Document(modifier = Modifier.size(18.dp), color = if (isAuthorized) BrandBlue else Slate600)
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(job.documentName.ifBlank { "Untitled Document" }, color = Slate900, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            if (job.totalFiles > 1) {
                                Box(Modifier.clip(RoundedCornerShape(4.dp)).background(BrandBlueLight).padding(horizontal = 6.dp, vertical = 2.dp)) {
                                    Text("BATCH ${job.fileIndex + 1}/${job.totalFiles}", color = BrandBlueHover, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Job #${job.id.takeLast(8).uppercase()}", color = Slate500, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            Text("·", color = Slate400)
                            Text(if (job.createdAt.isNotBlank()) "Submitted ${job.createdAt.take(19).replace("T", " ")}" else "Just now", color = Slate400, fontSize = 11.sp)
                        }
                    }
                }

                // Right: Status Badge
                ModernStatusBadge(job.status)
            }

            // Middle: Metadata Strip
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Slate100).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                QueueMetaCell("COPIES", "${job.copies.ifBlank { "1" }} copy")
                QueueMetaCell("PAGES", "${job.pageCount} pgs")
                QueueMetaCell("COLOR", job.colorMode)
                QueueMetaCell("SIZE", job.paperSize)
                QueueMetaCell("DUPLEX", job.duplexMode)
                if (job.pagesPrinted > 0 || job.status == "PRINTING") {
                    QueueMetaCell("SPOOL PROGRESS", "${job.pagesPrinted}/${job.pageCount} spooled")
                }
            }

            // Failure Reason if any
            if (job.failureReason.isNotBlank()) {
                Text(
                    "Failure: ${job.failureReason}",
                    color = Rose,
                    fontSize = 11.5.sp,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(RoseLight).padding(8.dp)
                )
            }

            // Action Buttons Row
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isAuthorized) {
                        Button(
                            onClick = { onVerify(job) },
                            colors = ButtonDefaults.buttonColors(BrandBlueLight, BrandBlueHover),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(34.dp),
                            elevation = ButtonDefaults.buttonElevation(0.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                StationIcons.Eye(modifier = Modifier.size(13.dp), color = BrandBlueHover)
                                Text("Verify in RAM", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Button(
                            onClick = { onAccept(job) },
                            colors = ButtonDefaults.buttonColors(BrandBlue, PureWhite),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(34.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                StationIcons.Check(modifier = Modifier.size(12.dp), color = PureWhite)
                                Text("Accept & Print", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        OutlinedButton(
                            onClick = { onCancel(job) },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(34.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, RoseBorder),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Rose)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                StationIcons.Close(modifier = Modifier.size(11.dp), color = Rose)
                                Text("Reject", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }

                TextButton(onClick = { onDetails(job) }, modifier = Modifier.height(34.dp)) {
                    Text("View Specification Sheet ℹ", fontSize = 12.sp, color = Slate500, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun QueueMetaCell(label: String, value: String) {
    Column {
        Text(label, color = Slate500, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
        Text(value, color = Slate900, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ─── Status Badge System ──────────────────────────────────────────────────────
@Composable
private fun ModernStatusBadge(rawStatus: String) {
    val status = rawStatus.uppercase().replace('_', ' ')
    val (bg, border, fg) = when {
        status.contains("COMPLET") -> Triple(EmeraldLight, EmeraldBorder, Emerald)
        status.contains("PRINTING") || status.contains("ACCEPTED") -> Triple(BrandBlueLight, BrandBlueBorder, BrandBlueHover)
        status.contains("AUTHORIZ") -> Triple(BrandBlueLight, BrandBlueBorder, BrandBlueHover)
        status.contains("FAIL") || status.contains("CANCEL") || status.contains("ERROR") -> Triple(RoseLight, RoseBorder, Rose)
        else -> Triple(AmberLight, AmberBorder, Amber)
    }

    Row(
        Modifier.clip(RoundedCornerShape(20.dp)).background(bg).border(1.dp, border, RoundedCornerShape(20.dp)).padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(fg))
        Text(status, color = fg, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

// ─── Connected Printers Screen ────────────────────────────────────────────────
@Composable
private fun PrinterPage(status: StationStatus?, onSync: () -> Unit) {
    val printers = status?.printers.orEmpty()

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Connected Hardware Spoolers", color = Slate900, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("${printers.size} Windows printing drivers enumerated on this station.", color = Slate500, fontSize = 13.sp)
        }

        Button(
            onClick = onSync,
            colors = ButtonDefaults.buttonColors(BrandBlue, PureWhite),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.height(36.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StationIcons.Refresh(modifier = Modifier.size(13.dp), color = PureWhite)
                Text("Sync Windows Printers", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    if (printers.isEmpty()) {
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(PureWhite),
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Slate200),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(Slate100), contentAlignment = Alignment.Center) {
                    StationIcons.Printer(modifier = Modifier.size(24.dp), color = Slate400)
                }
                Text("No physical printers detected", color = Slate900, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    "Virtual printers (PDF, XPS, Fax) are filtered out automatically. Connect a commercial laser printer via USB or local network.",
                    color = Slate500, fontSize = 13.sp, textAlign = TextAlign.Center
                )
                Button(onClick = onSync, shape = RoundedCornerShape(8.dp)) {
                    Text("Re-scan Spooler", fontSize = 12.sp)
                }
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            printers.forEach { printer ->
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(PureWhite),
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Slate200),
                ) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Box(
                                    Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(if (printer.status == "READY") EmeraldLight else AmberLight),
                                    contentAlignment = Alignment.Center
                                ) {
                                    StationIcons.Printer(modifier = Modifier.size(20.dp), color = if (printer.status == "READY") Emerald else Amber)
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(printer.name, color = Slate900, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                        if (printer.isDefault) {
                                            Box(Modifier.clip(RoundedCornerShape(4.dp)).background(BrandBlueLight).padding(horizontal = 6.dp, vertical = 2.dp)) {
                                                Text("SYSTEM DEFAULT", color = BrandBlueHover, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                    Text(printer.model.ifBlank { "Generic Windows Print Device" }, color = Slate500, fontSize = 12.sp)
                                }
                            }

                            ModernStatusBadge(printer.status)
                        }

                        HorizontalDivider(color = Slate100)

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            PrinterMetaCell("PAPER TRAY", printer.paper.ifBlank { "Ready" })
                            PrinterMetaCell("ESTIMATED TONER", printer.toner.takeIf(String::isNotBlank)?.let { "$it%" } ?: "Normal")
                            PrinterMetaCell("COLOR SUPPORT", if (printer.supportsColor) "Color & Monochrome" else "Monochrome Only")
                            PrinterMetaCell("DUPLEX SUPPORT", if (printer.supportsDuplex) "Two-Sided Automatic" else "Single-Sided Only")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PrinterMetaCell(label: String, value: String) {
    Column {
        Text(label, color = Slate500, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
        Text(value, color = Slate900, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ─── Shop QR Screen (Counter Signage Center) ──────────────────────────────────
@Composable
private fun ShopQrPage(shop: ShopDetails?, onRefresh: () -> Unit) {
    if (shop == null || shop.permanentQrPayload.isBlank()) {
        ModernNotice("Shop counter QR is currently unavailable. Ensure the station is connected.", isError = true)
        return
    }

    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Shop Pairing & Counter Signage", color = Slate900, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Place this high-contrast QR code on your Xerox shop counter for customers to scan.", color = Slate500, fontSize = 13.sp)
        }

        // High-End Counter Signage Card
        Card(
            Modifier.widthIn(min = 400.dp, max = 460.dp),
            colors = CardDefaults.cardColors(PureWhite),
            shape = RoundedCornerShape(20.dp),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, Slate300),
            elevation = CardDefaults.cardElevation(3.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Verified Brand Seal
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StationIcons.Shield(modifier = Modifier.size(16.dp), color = BrandBlue)
                    Text("PRIVPRINT VERIFIED COUNTER", color = BrandBlueHover, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(shop.name, color = Slate900, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                    Text(shop.address, color = Slate500, fontSize = 12.sp, textAlign = TextAlign.Center)
                }

                // QR Frame Container
                Box(
                    Modifier.clip(RoundedCornerShape(16.dp))
                        .background(Slate50)
                        .border(1.dp, Slate200, RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    QrCodeCanvas(shop.permanentQrPayload, Modifier.size(240.dp))
                }

                // Technical Pair Info
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("SHOP ID: ${shop.id}", color = Slate900, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text("Scan with the PrivPrint mobile app to establish a 15-min ephemeral session.", color = Slate500, fontSize = 11.sp, textAlign = TextAlign.Center)
                }

                HorizontalDivider(color = Slate100)

                Button(
                    onClick = onRefresh,
                    colors = ButtonDefaults.buttonColors(BrandBlueLight, BrandBlueHover),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                    elevation = ButtonDefaults.buttonElevation(0.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StationIcons.Refresh(modifier = Modifier.size(13.dp), color = BrandBlueHover)
                        Text("Refresh Counter QR", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

// ─── Security Audit Log Screen (SOC Grade) ────────────────────────────────────
@Composable
private fun AuditPage(events: List<AuditEvent>) {
    // Health Banner
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(PureWhite),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Slate200),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(VioletLight), contentAlignment = Alignment.Center) {
                    StationIcons.Audit(modifier = Modifier.size(18.dp), color = Violet)
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Security Audit Trail (SOC-2 Enforced)", color = Slate900, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("Hardware cryptographic operations, memory wipe events, and authenticated handshakes.", color = Slate500, fontSize = 12.sp)
                }
            }

            Box(Modifier.clip(RoundedCornerShape(20.dp)).background(EmeraldLight).border(1.dp, EmeraldBorder, RoundedCornerShape(20.dp)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(Emerald))
                    Text("STATION AUDIT SECURE", color = Emerald, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (events.isEmpty()) {
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(PureWhite),
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Slate200),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(Slate100), contentAlignment = Alignment.Center) {
                    StationIcons.Audit(modifier = Modifier.size(22.dp), color = Slate400)
                }
                Text("No security audit events recorded", color = Slate900, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("Events will be recorded chronologically as the station communicates with cloud relays.", color = Slate500, fontSize = 12.sp)
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            events.take(50).forEach { event ->
                val isError = event.severity.equals("ERROR", true) || event.severity.equals("CRITICAL", true)
                val isWarn  = event.severity.equals("WARNING", true)
                val badgeColor = when {
                    isError -> Rose
                    isWarn  -> Amber
                    else    -> Slate600
                }

                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(PureWhite),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Slate200),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
                            Box(
                                Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(badgeColor.copy(alpha = 0.1f)),
                                contentAlignment = Alignment.Center
                            ) {
                                StationIcons.Lock(modifier = Modifier.size(13.dp), color = badgeColor)
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(event.eventType.replace('_', ' '), color = Slate900, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text(event.details, color = Slate600, fontSize = 11.5.sp)
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(
                                Modifier.clip(RoundedCornerShape(4.dp))
                                    .background(badgeColor.copy(alpha = 0.1f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(event.severity.uppercase(), color = badgeColor, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                            Text(event.timestamp.take(19).replace("T", " "), color = Slate400, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

// ─── Windows Station Page (Control Center) ────────────────────────────────────
@Composable
private fun StationSecurityPage(status: StationStatus?, onReconnect: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Control Center Header Card
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(PureWhite),
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Slate200),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(BrandBlueLight), contentAlignment = Alignment.Center) {
                        StationIcons.Station(modifier = Modifier.size(20.dp), color = BrandBlue)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Station Host Control Center", color = Slate900, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("Local workstation daemon status, cryptographic key sealing, and cloud telemetry.", color = Slate500, fontSize = 12.sp)
                    }
                }

                Button(
                    onClick = onReconnect,
                    colors = ButtonDefaults.buttonColors(BrandBlue, PureWhite),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StationIcons.Refresh(modifier = Modifier.size(13.dp), color = PureWhite)
                        Text("Reconnect to Cloud", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // Modular Status Cards
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // Card 1: Cryptographic Engine
            Card(
                Modifier.weight(1f),
                colors = CardDefaults.cardColors(PureWhite),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Slate200),
            ) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("CRYPTOGRAPHIC ENGINE", color = Slate500, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    ControlField("Key Pair Status", if (status?.encryptionKeyRegistered == true) "RSA-3072 Registered" else "Key Pending")
                    ControlField("Key Sealing", "Windows DPAPI (User Context)")
                    ControlField("Decryption Buffer", "Volatile Memory (RAM Only)")
                    ControlField("Disk Footprint", "0 Bytes Persistent")
                }
            }

            // Card 2: Cloud Telemetry Gateway
            Card(
                Modifier.weight(1f),
                colors = CardDefaults.cardColors(PureWhite),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Slate200),
            ) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("CLOUD GATEWAY", color = Slate500, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    ControlField("Connection State", status?.connectionState ?: "CONNECTED")
                    ControlField("Realtime Stream", if (status?.realtimeConnected == true) "WebSocket Active" else "Disconnected")
                    ControlField("Target Server", status?.serverUrl?.ifBlank { "Production Cloud Relay" } ?: "Production Cloud Relay")
                    ControlField("Station ID", status?.deviceId?.takeLast(12) ?: "WIN-STATION")
                }
            }
        }
    }
}

@Composable
private fun ControlField(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Slate500, fontSize = 12.sp)
        Text(value, color = Slate900, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ─── Settings Page (Categorized Layout) ────────────────────────────────────────
@Composable
private fun SettingsPage(
    status: StationStatus?,
    shop: ShopDetails?,
    onToggleAutoPrint: (Boolean) -> Unit,
    onUpdateHistoryRetention: suspend (Int) -> Boolean,
    onUpdateLocation: suspend (lat: Double, lng: Double, address: String?) -> Boolean,
    onDetectLocation: suspend () -> Pair<Double, Double>?,
    onDisableLocation: suspend () -> Boolean,
    onLogout: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var selectedCategory by remember { mutableStateOf("SECURITY") }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        // Left Categories
        Column(
            Modifier.width(200.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SettingsCategoryItem("General & Shop", selectedCategory == "GENERAL") { selectedCategory = "GENERAL" }
            SettingsCategoryItem("Printing & Spooler", selectedCategory == "PRINTING") { selectedCategory = "PRINTING" }
            SettingsCategoryItem("Privacy / Print History", selectedCategory == "SECURITY") { selectedCategory = "SECURITY" }
            SettingsCategoryItem("About PrivPrint", selectedCategory == "ABOUT") { selectedCategory = "ABOUT" }
        }

        // Right Settings Cards
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (selectedCategory) {
                "GENERAL" -> {
                    // Shop Location & Discovery Card
                    val isLocEnabled = shop?.locationEnabled == true || status?.locationEnabled == true
                    val currentLat = shop?.latitude ?: status?.shopLatitude
                    val currentLng = shop?.longitude ?: status?.shopLongitude
                    val currentAddr = shop?.address?.takeIf { it.isNotBlank() } ?: status?.shopAddress?.takeIf { it.isNotBlank() } ?: ""

                    var latText by remember(currentLat) { mutableStateOf(currentLat?.toString() ?: "") }
                    var lngText by remember(currentLng) { mutableStateOf(currentLng?.toString() ?: "") }
                    var addrText by remember(currentAddr) { mutableStateOf(currentAddr) }
                    var locFeedback by remember { mutableStateOf<String?>(null) }
                    var isDetecting by remember { mutableStateOf(false) }
                    var isSavingLocation by remember { mutableStateOf(false) }
                    var isDisablingLocation by remember { mutableStateOf(false) }

                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(PureWhite),
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Slate200),
                    ) {
                        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Shop Location & Nearby Discovery", color = Slate900, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    Text("Configure your Xerox shop's geographic location so nearby customers can find you.", color = Slate600, fontSize = 12.5.sp)
                                }
                                Box(
                                    Modifier.clip(RoundedCornerShape(6.dp))
                                        .background(if (isLocEnabled) EmeraldLight else Slate100)
                                        .border(1.dp, if (isLocEnabled) EmeraldBorder else Slate200, RoundedCornerShape(6.dp))
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        StationIcons.Location(modifier = Modifier.size(12.dp), color = if (isLocEnabled) Emerald else Slate500)
                                        Text(
                                            if (isLocEnabled) "[ Location Enabled ]" else "[ Location Disabled ]",
                                            color = if (isLocEnabled) Emerald else Slate600,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }

                            if (isLocEnabled) {
                                Box(
                                    Modifier.fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(EmeraldLight)
                                        .border(1.dp, EmeraldBorder, RoundedCornerShape(8.dp))
                                        .padding(12.dp)
                                ) {
                                    Text(
                                        "\"Your shop can appear in nearby shop searches.\"",
                                        color = EmeraldHover,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Customer-Facing Shop Address", color = Slate700, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                OutlinedTextField(
                                    value = addrText,
                                    onValueChange = { addrText = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    placeholder = { Text("e.g. Shop 4, Station Road Xerox Market", fontSize = 12.5.sp, color = Slate400) },
                                    singleLine = true,
                                    shape = RoundedCornerShape(8.dp),
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = PureWhite,
                                        unfocusedContainerColor = PureWhite,
                                        focusedIndicatorColor = BrandBlue,
                                        unfocusedIndicatorColor = Slate200,
                                        focusedTextColor = Slate900,
                                        unfocusedTextColor = Slate800,
                                    )
                                )

                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("Latitude (-90 to 90)", color = Slate700, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                        OutlinedTextField(
                                            value = latText,
                                            onValueChange = { latText = it },
                                            modifier = Modifier.fillMaxWidth(),
                                            placeholder = { Text("e.g. 23.0225", fontSize = 12.5.sp, color = Slate400) },
                                            singleLine = true,
                                            shape = RoundedCornerShape(8.dp),
                                            colors = TextFieldDefaults.colors(
                                                focusedContainerColor = PureWhite,
                                                unfocusedContainerColor = PureWhite,
                                                focusedIndicatorColor = BrandBlue,
                                                unfocusedIndicatorColor = Slate200,
                                                focusedTextColor = Slate900,
                                                unfocusedTextColor = Slate800,
                                            )
                                        )
                                    }

                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("Longitude (-180 to 180)", color = Slate700, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                        OutlinedTextField(
                                            value = lngText,
                                            onValueChange = { lngText = it },
                                            modifier = Modifier.fillMaxWidth(),
                                            placeholder = { Text("e.g. 72.5714", fontSize = 12.5.sp, color = Slate400) },
                                            singleLine = true,
                                            shape = RoundedCornerShape(8.dp),
                                            colors = TextFieldDefaults.colors(
                                                focusedContainerColor = PureWhite,
                                                unfocusedContainerColor = PureWhite,
                                                focusedIndicatorColor = BrandBlue,
                                                unfocusedIndicatorColor = Slate200,
                                                focusedTextColor = Slate900,
                                                unfocusedTextColor = Slate800,
                                            )
                                        )
                                    }
                                }
                            }

                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            coroutineScope.launch {
                                                isDetecting = true
                                                locFeedback = null
                                                try {
                                                    val detected = onDetectLocation()
                                                    if (detected != null) {
                                                        latText = detected.first.toString()
                                                        lngText = detected.second.toString()
                                                        locFeedback = "Location detected successfully."
                                                    } else {
                                                        locFeedback = "Could not automatically determine location. Enter coordinates manually."
                                                    }
                                                } catch (ex: Exception) {
                                                    locFeedback = ex.message ?: "Detection failed."
                                                } finally {
                                                    isDetecting = false
                                                }
                                            }
                                        },
                                        enabled = !isDetecting,
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = BrandBlue),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, BrandBlueBorder),
                                        modifier = Modifier.height(38.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            if (isDetecting) {
                                                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp, color = BrandBlue)
                                            } else {
                                                StationIcons.Location(modifier = Modifier.size(12.dp), color = BrandBlue)
                                            }
                                            Text(if (isDetecting) "Detecting..." else "Auto-Detect Location", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                        }
                                    }

                                    Button(
                                        onClick = {
                                            val lat = latText.toDoubleOrNull()
                                            val lng = lngText.toDoubleOrNull()
                                            if (lat == null || lat !in -90.0..90.0 || lng == null || lng !in -180.0..180.0) {
                                                locFeedback = "Invalid coordinates. Latitude must be between -90 and 90, Longitude between -180 and 180."
                                                return@Button
                                            }
                                            locFeedback = null
                                            isSavingLocation = true
                                            coroutineScope.launch {
                                                try {
                                                    val ok = onUpdateLocation(lat, lng, addrText.ifBlank { null })
                                                    locFeedback = if (ok) "Location saved and shop is now discoverable." else "Failed to save location. Please try again."
                                                } catch (ex: Exception) {
                                                    locFeedback = ex.message ?: "Failed to save location."
                                                } finally {
                                                    isSavingLocation = false
                                                }
                                            }
                                        },
                                        enabled = !isSavingLocation && !isDetecting,
                                        colors = ButtonDefaults.buttonColors(BrandBlue, PureWhite),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.height(38.dp)
                                    ) {
                                        if (isSavingLocation) {
                                            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp, color = PureWhite)
                                            Spacer(Modifier.width(6.dp))
                                        }
                                        Text(if (isSavingLocation) "Saving..." else "Save & Enable Location", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }

                                if (isLocEnabled) {
                                    OutlinedButton(
                                        onClick = {
                                            locFeedback = null
                                            isDisablingLocation = true
                                            coroutineScope.launch {
                                                try {
                                                    val ok = onDisableLocation()
                                                    locFeedback = if (ok) "Location disabled." else "Failed to disable location."
                                                } catch (ex: Exception) {
                                                    locFeedback = ex.message ?: "Failed to disable location."
                                                } finally {
                                                    isDisablingLocation = false
                                                }
                                            }
                                        },
                                        enabled = !isDisablingLocation,
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Slate600),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Slate200),
                                        modifier = Modifier.height(38.dp)
                                    ) {
                                        Text(if (isDisablingLocation) "Disabling..." else "Disable Location", fontSize = 11.5.sp)
                                    }
                                }
                            }

                            if (locFeedback != null) {
                                Text(
                                    locFeedback!!,
                                    color = if (locFeedback!!.contains("Invalid") || locFeedback!!.contains("Could not") || locFeedback!!.contains("failed")) Rose else Emerald,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(PureWhite),
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Slate200),
                    ) {
                        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text("Workstation Identity", color = Slate900, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            ControlField("Shop Identifier", status?.shopId ?: "Unassigned")
                            ControlField("Station Hardware ID", status?.deviceId ?: "Unassigned")
                            ControlField("Cloud Relay", status?.serverUrl ?: "Default Endpoint")
                        }
                    }

                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(PureWhite),
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Slate200),
                    ) {
                        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text("Account Session", color = Slate900, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text("Disconnect this Windows computer from the current shop. You will need operator credentials to re-link.", color = Slate500, fontSize = 12.5.sp)
                            Button(
                                onClick = onLogout,
                                colors = ButtonDefaults.buttonColors(Rose, PureWhite),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.height(40.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    StationIcons.Logout(modifier = Modifier.size(13.dp), color = PureWhite)
                                    Text("Disconnect Station & Sign Out", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
                "PRINTING" -> {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(PureWhite),
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Slate200),
                    ) {
                        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text("Spooler & Direct Auto-Print", color = Slate900, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
                                    Text("Automatic Spooling", color = Slate900, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Text("Bypasses manual in-RAM preview and sends authorized customer documents directly to the system default printer.", color = Slate500, fontSize = 12.sp)
                                }
                                Switch(checked = status?.autoPrintEnabled == true, onCheckedChange = onToggleAutoPrint)
                            }
                        }
                    }
                }
                "SECURITY" -> {
                    val currentRetention = status?.historyRetentionHours ?: 4
                    var selectedHours by remember(currentRetention) { mutableStateOf(currentRetention) }
                    var feedbackMessage by remember { mutableStateOf<String?>(null) }
                    var dropdownExpanded by remember { mutableStateOf(false) }
                    var isUpdatingRetention by remember { mutableStateOf(false) }

                    fun updateRetention(hours: Int) {
                        dropdownExpanded = false
                        if (selectedHours != hours && !isUpdatingRetention) {
                            val oldHours = selectedHours
                            isUpdatingRetention = true
                            feedbackMessage = null
                            coroutineScope.launch {
                                try {
                                    val ok = onUpdateHistoryRetention(hours)
                                    if (ok) {
                                        selectedHours = hours
                                        feedbackMessage = if (hours < oldHours) {
                                            "History older than $hours hour${if (hours > 1) "s" else ""} will become eligible for automatic deletion."
                                        } else {
                                            "Print history retention updated to $hours hour${if (hours > 1) "s" else ""}."
                                        }
                                    } else {
                                        feedbackMessage = "Failed to update print history retention."
                                    }
                                } catch (ex: Exception) {
                                    feedbackMessage = ex.message ?: "Failed to update print history retention."
                                } finally {
                                    isUpdatingRetention = false
                                }
                            }
                        }
                    }

                    // Card 1: Print History Retention
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(PureWhite),
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Slate200),
                    ) {
                        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Print History Retention", color = Slate900, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    Text("Choose how long completed print history remains available on this Windows Station.", color = Slate600, fontSize = 12.5.sp)
                                }
                                Box(
                                    Modifier.clip(RoundedCornerShape(6.dp))
                                        .background(BrandBlueLight)
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                ) {
                                    Text("Current retention: $currentRetention hour${if (currentRetention > 1) "s" else ""}", color = BrandBlue, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }

                            // Professional Selector: Dropdown trigger [ 4 Hours ▼ ] with interactive direct pills
                            val retentionOptions = listOf(1, 2, 4, 6, 8)

                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier.clip(RoundedCornerShape(8.dp))
                                        .background(Slate100)
                                        .border(1.dp, Slate300, RoundedCornerShape(8.dp))
                                        .clickable { dropdownExpanded = !dropdownExpanded }
                                        .padding(horizontal = 16.dp, vertical = 9.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text("$selectedHours Hour${if (selectedHours > 1) "s" else ""}", color = Slate900, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        Text(if (dropdownExpanded) "▲" else "▼", color = Slate500, fontSize = 10.sp)
                                    }
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    retentionOptions.forEach { hours ->
                                        val isSelected = selectedHours == hours
                                        Box(
                                            Modifier.clip(RoundedCornerShape(8.dp))
                                                .background(if (isSelected) BrandBlue else Slate50)
                                                .border(1.dp, if (isSelected) BrandBlueHover else Slate200, RoundedCornerShape(8.dp))
                                                .clickable { updateRetention(hours) }
                                                .padding(horizontal = 12.dp, vertical = 7.dp)
                                        ) {
                                            Text(
                                                "$hours Hour${if (hours > 1) "s" else ""}",
                                                color = if (isSelected) PureWhite else Slate700,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                fontSize = 12.sp
                                            )
                                        }
                                    }
                                }
                            }

                            if (dropdownExpanded) {
                                Card(
                                    Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(Slate50),
                                    shape = RoundedCornerShape(10.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Slate200)
                                ) {
                                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("Available Retention Options (Maximum: 8 Hours):", color = Slate500, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                                        retentionOptions.forEach { hours ->
                                            val isSelected = selectedHours == hours
                                            Row(
                                                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                                                    .background(if (isSelected) BrandBlueLight else Color.Transparent)
                                                    .clickable { updateRetention(hours) }
                                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text("$hours Hour${if (hours > 1) "s" else ""}", color = if (isSelected) BrandBlue else Slate800, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, fontSize = 12.5.sp)
                                                if (isSelected) Text("✓ Selected", color = BrandBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }

                            // Notice below selector
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Slate50).padding(10.dp).fillMaxWidth()
                            ) {
                                Text("›", color = Slate500, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text(
                                    "Completed print history older than the selected retention period will be automatically removed.",
                                    color = Slate500,
                                    fontSize = 11.5.sp
                                )
                            }

                            feedbackMessage?.let { msg ->
                                ModernNotice(msg, isError = msg.startsWith("Failed") || msg.startsWith("Error"))
                            }
                        }
                    }

                    // Card 2: Zero-Knowledge Security Architecture
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(PureWhite),
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Slate200),
                    ) {
                        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text("Zero-Knowledge Security Architecture", color = Slate900, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            ControlField("Document Storage", "Volatile Memory Only (Zero-Disk)")
                            ControlField("Private Key Vault", "Windows DPAPI Sealing")
                            ControlField("Symmetric Cipher", "AES-256-GCM Ephemeral Nonce")
                            ControlField("Asymmetric Cipher", "RSA-3072 OAEP SHA-256")
                            ControlField("Post-Print Memory Scrub", "Explicit ByteArray Zeroization")
                        }
                    }
                }
                "ABOUT" -> {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(PureWhite),
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Slate200),
                    ) {
                        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(BrandBlue), contentAlignment = Alignment.Center) {
                                    StationIcons.Shield(modifier = Modifier.size(16.dp), color = PureWhite)
                                }
                                Column {
                                    Text("PrivPrint Enterprise Shop Station", color = Slate900, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                    Text("Version 1.0.5  ·  Build 2026.10", color = Slate500, fontSize = 11.sp)
                                }
                            }
                            Text("PrivPrint replaces insecure WhatsApp, USB, and email file transfers in Xerox shops with authenticated, end-to-end encrypted printing.", color = Slate600, fontSize = 12.5.sp, lineHeight = 18.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCategoryItem(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(if (selected) BrandBlueLight else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            label,
            color = if (selected) BrandBlueHover else Slate600,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

// ─── Document In-RAM Preview Dialog (Strict View Only) ─────────────────────────
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
            Modifier.widthIn(min = 820.dp, max = 1020.dp).fillMaxHeight(0.94f),
            colors = CardDefaults.cardColors(PureWhite),
            shape  = RoundedCornerShape(18.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Slate300),
            elevation = CardDefaults.cardElevation(6.dp),
        ) {
            Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Top Header Row
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("SECURE DOCUMENT INSPECTION", color = Slate900, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Box(Modifier.clip(RoundedCornerShape(4.dp)).background(BrandBlueLight).padding(horizontal = 6.dp, vertical = 2.dp)) {
                                Text("🔒 IN-RAM VIEW ONLY", color = BrandBlueHover, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Text(
                            "${preview?.filename ?: job.documentName}  ·  Job #${job.id.takeLast(8).uppercase()}" +
                                if (preview != null && preview.pageCount > 0) "  ·  Page ${currentPage + 1} of ${preview.pageCount}" else "",
                            color = Slate500, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }

                    OutlinedButton(onClick = onClose, shape = RoundedCornerShape(8.dp), modifier = Modifier.height(34.dp)) {
                        Text("Close Viewer ✕", fontSize = 12.sp)
                    }
                }

                // Batch Switcher if multi-document
                if (batchJobs.size > 1) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Slate100).padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("Batch Documents (${batchJobs.size}):", color = Slate500, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp))
                        batchJobs.forEach { bJob ->
                            val isSelected = bJob.id == job.id
                            Box(
                                Modifier.clip(RoundedCornerShape(6.dp))
                                    .background(if (isSelected) BrandBlue else PureWhite)
                                    .clickable(enabled = !isSelected && onSwitchJob != null) {
                                        currentPage = 0
                                        zoomScale = 1.0f
                                        fitWidth = false
                                        onSwitchJob?.invoke(bJob)
                                    }
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    "${bJob.fileIndex + 1}. ${bJob.documentName}",
                                    color = if (isSelected) PureWhite else Slate900,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                // Toolbar: Page Stepper + Zoom Controls
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Slate100).padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val pageCount = preview?.pageCount ?: 1
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { if (currentPage > 0) currentPage-- },
                            enabled = currentPage > 0,
                            modifier = Modifier.height(30.dp),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text("◀ Prev", fontSize = 11.sp)
                        }
                        Text("Page ${currentPage + 1} of $pageCount", color = Slate900, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        OutlinedButton(
                            onClick = { if (currentPage < pageCount - 1) currentPage++ },
                            enabled = currentPage < pageCount - 1,
                            modifier = Modifier.height(30.dp),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text("Next ▶", fontSize = 11.sp)
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = { zoomScale = (zoomScale - 0.25f).coerceAtLeast(0.5f); fitWidth = false },
                            enabled = zoomScale > 0.5f,
                            modifier = Modifier.height(30.dp),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text("Zoom -", fontSize = 11.sp)
                        }
                        Text("${(zoomScale * 100).toInt()}%", color = Slate900, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        OutlinedButton(
                            onClick = { zoomScale = (zoomScale + 0.25f).coerceAtMost(3.0f); fitWidth = false },
                            enabled = zoomScale < 3.0f,
                            modifier = Modifier.height(30.dp),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text("Zoom +", fontSize = 11.sp)
                        }
                        OutlinedButton(
                            onClick = { fitWidth = !fitWidth; zoomScale = 1.0f },
                            modifier = Modifier.height(30.dp),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(if (fitWidth) "Fit Normal" else "Fit Width", fontSize = 11.sp)
                        }
                    }
                }

                // Render Canvas Box
                Box(
                    Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Slate200).padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        busy -> {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                CircularProgressIndicator(Modifier.size(32.dp), color = BrandBlue)
                                Text("Decrypting document in station RAM…", color = Slate700, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text("Zero bytes written to storage disk.", color = Slate500, fontSize = 11.sp)
                            }
                        }
                        error != null -> {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Could not render document preview", color = Rose, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Text(error, color = Rose, fontSize = 12.sp)
                            }
                        }
                        preview != null && preview.pages.isNotEmpty() -> {
                            val pageIdx = currentPage.coerceIn(0, preview.pages.size - 1)
                            val bitmap = remember(preview, pageIdx) { preview.pages[pageIdx].toComposeImageBitmap() }
                            Box(
                                Modifier.fillMaxSize().verticalScroll(vScrollState).horizontalScroll(hScrollState),
                                contentAlignment = Alignment.Center
                            ) {
                                Card(
                                    shape = RoundedCornerShape(4.dp),
                                    colors = CardDefaults.cardColors(PureWhite),
                                    elevation = CardDefaults.cardElevation(4.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Slate300),
                                    modifier = Modifier.graphicsLayer(scaleX = zoomScale, scaleY = zoomScale)
                                        .then(if (fitWidth) Modifier.fillMaxWidth() else Modifier.fillMaxHeight())
                                ) {
                                    Image(
                                        bitmap = bitmap,
                                        contentDescription = "Page ${pageIdx + 1}",
                                        modifier = if (fitWidth) Modifier.fillMaxWidth() else Modifier.fillMaxHeight(),
                                        contentScale = if (fitWidth) ContentScale.FillWidth else ContentScale.Fit
                                    )
                                }
                            }
                        }
                        else -> Text("No preview pages available", color = Slate500)
                    }
                }

                // Footer Security Notice & View-Only Actions
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(BrandBlueLight).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StationIcons.Lock(modifier = Modifier.size(13.dp), color = BrandBlueHover)
                    Text(
                        "STATION RAM ENCLAVE: Export, save, download, and file-system extraction are permanently prohibited in this viewer.",
                        color = BrandBlueHover, fontSize = 10.5.sp, fontWeight = FontWeight.Medium
                    )
                }

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    OutlinedButton(
                        onClick = onCancel,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(38.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, RoseBorder),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Rose)
                    ) {
                        Text("✕  Reject Print Job", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }

                    Button(
                        onClick = onClose,
                        colors = ButtonDefaults.buttonColors(BrandBlue, PureWhite),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(38.dp)
                    ) {
                        Text("Done / Close Viewer", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

// ─── Job Details Specification Dialog ─────────────────────────────────────────
@Composable
private fun JobDetailDialog(
    detail: PrintJobDetail,
    onClose: () -> Unit,
    onAccept: (PrintJobDetail) -> Unit,
    onVerify: (PrintJobDetail) -> Unit,
) {
    Dialog(onDismissRequest = onClose) {
        Card(
            Modifier.widthIn(min = 460.dp, max = 560.dp),
            colors = CardDefaults.cardColors(PureWhite),
            shape  = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Slate300),
            elevation = CardDefaults.cardElevation(4.dp),
        ) {
            Column(Modifier.padding(26.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Job Specifications", color = Slate900, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    ModernStatusBadge(detail.status)
                }

                HorizontalDivider(color = Slate200)

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DetailSpecRow("Job ID", "#${detail.id.takeLast(10).uppercase()}")
                    if (detail.batchId != null) {
                        DetailSpecRow("Batch", "Doc ${detail.fileIndex + 1} of ${detail.totalFiles}")
                    }
                    DetailSpecRow("Document", detail.documentName)
                    DetailSpecRow("Pages", "${detail.pageCount} pgs")
                    DetailSpecRow("Authorized Copies", "${detail.requestedCopies}")
                    DetailSpecRow("Color Mode", detail.colorMode)
                    DetailSpecRow("Paper Size", detail.paperSize)
                    DetailSpecRow("Duplex", detail.duplex.ifBlank { "SIMPLEX" })
                    DetailSpecRow("Assigned Printer", detail.selectedPrinter.ifBlank { "System Default" })
                    DetailSpecRow("Submitted", detail.createdAt.take(19).replace("T", " "))
                }

                if (detail.failureReason.isNotBlank()) {
                    Text(detail.failureReason, color = Rose, fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(RoseLight).padding(8.dp))
                }

                HorizontalDivider(color = Slate200)

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f).height(42.dp), shape = RoundedCornerShape(8.dp)) {
                        Text("Close", fontSize = 12.sp)
                    }
                    if (detail.status == "AUTHORIZED") {
                        Button(
                            onClick = { onVerify(detail) },
                            colors  = ButtonDefaults.buttonColors(BrandBlueLight, BrandBlueHover),
                            modifier = Modifier.weight(1f).height(42.dp),
                            shape   = RoundedCornerShape(8.dp)
                        ) {
                            Text("Verify in RAM", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = { onAccept(detail) },
                            colors  = ButtonDefaults.buttonColors(BrandBlue, PureWhite),
                            modifier = Modifier.weight(1f).height(42.dp),
                            shape   = RoundedCornerShape(8.dp)
                        ) {
                            Text("Accept & Print", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailSpecRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Slate500, fontSize = 12.sp, modifier = Modifier.width(130.dp))
        Text(value, color = Slate900, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
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
            Modifier.widthIn(min = 440.dp, max = 500.dp),
            colors = CardDefaults.cardColors(PureWhite),
            shape  = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Slate300),
            elevation = CardDefaults.cardElevation(4.dp),
        ) {
            Column(Modifier.padding(26.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Reject & Cancel Print Job?", color = Rose, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Job #${job.id.takeLast(8).uppercase()} · ${job.documentName}",
                    color = Slate700, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Cancelling releases the reservation and informs the customer mobile app immediately.",
                    color = Slate500, fontSize = 12.sp
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    presetReasons.forEach { preset ->
                        val isSelected = reason == preset
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) RoseLight else Slate100)
                                .border(1.dp, if (isSelected) RoseBorder else Slate200, RoundedCornerShape(8.dp))
                                .clickable { reason = preset }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(Modifier.size(6.dp).clip(CircleShape).background(if (isSelected) Rose else Slate400))
                            Text(preset, color = Slate900, fontSize = 12.sp)
                        }
                    }
                }

                error?.let { ModernNotice(it, isError = true) }

                HorizontalDivider(color = Slate200)

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.weight(1f).height(42.dp), shape = RoundedCornerShape(8.dp)) {
                        Text("Keep Job", fontSize = 12.sp)
                    }
                    Button(
                        onClick = { onConfirm(reason) },
                        enabled = !busy && reason.isNotBlank(),
                        colors  = ButtonDefaults.buttonColors(Rose, PureWhite),
                        modifier = Modifier.weight(1f).height(42.dp),
                        shape   = RoundedCornerShape(8.dp)
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(16.dp), color = PureWhite, strokeWidth = 2.dp)
                        else Text("Confirm Rejection", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

// ─── Accept & Print Dialog ────────────────────────────────────────────────────
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
            Modifier.widthIn(min = 440.dp, max = 520.dp),
            colors = CardDefaults.cardColors(PureWhite),
            shape  = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Slate300),
            elevation = CardDefaults.cardElevation(4.dp),
        ) {
            Column(Modifier.padding(26.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Send Job to Spooler", color = Slate900, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Job #${job.id.takeLast(8).uppercase()} · ${job.documentName}", color = Slate700, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

                HorizontalDivider(color = Slate200)

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    QueueMetaCell("COPIES", "${job.copies.ifBlank { "1" }} copy")
                    QueueMetaCell("PAGES", "${job.pageCount} pgs")
                    QueueMetaCell("COLOR", job.colorMode)
                    QueueMetaCell("DUPLEX", job.duplexMode)
                }

                if (printers.isNotEmpty()) {
                    Text("Select Destination Hardware Spooler", color = Slate900, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        printers.forEach { printer ->
                            val isSelected = selectedPrinter == printer.name
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) BrandBlueLight else Slate100)
                                    .border(1.dp, if (isSelected) BrandBlue else Slate200, RoundedCornerShape(8.dp))
                                    .clickable { selectedPrinter = printer.name }
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(Modifier.size(7.dp).clip(CircleShape).background(if (printer.status == "READY") Emerald else Amber))
                                Column(Modifier.weight(1f)) {
                                    Text(printer.name, color = Slate900, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
                                    Text(printer.status + if (printer.isDefault) " · System Default" else "", color = Slate500, fontSize = 11.sp)
                                }
                                if (isSelected) Text("✓", color = BrandBlue, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                } else {
                    ModernNotice("No local printers enumerated — the station will route to the Windows default spooler.", isError = false)
                }

                error?.let { ModernNotice(it, isError = true) }

                HorizontalDivider(color = Slate200)

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).height(42.dp), shape = RoundedCornerShape(8.dp)) {
                        Text("Cancel", fontSize = 12.sp)
                    }
                    Button(
                        onClick  = { onAccept(selectedPrinter) },
                        enabled  = !busy,
                        colors   = ButtonDefaults.buttonColors(BrandBlue, PureWhite),
                        modifier = Modifier.weight(1f).height(42.dp),
                        shape    = RoundedCornerShape(8.dp)
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(16.dp), color = PureWhite, strokeWidth = 2.dp)
                        else Text("Confirm & Print", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

// ─── Modern Input Field Helper ────────────────────────────────────────────────
@Composable
private fun ModernInputField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    secret: Boolean = false,
    placeholder: String = "",
    onEnter: (() -> Unit)? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = Slate700, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            placeholder = { Text(placeholder, fontSize = 12.5.sp, color = Slate400) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().height(48.dp).then(
                if (onEnter != null) Modifier.onKeyEvent { if (it.key == Key.Enter) { onEnter(); true } else false }
                else Modifier
            ),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = PureWhite,
                unfocusedContainerColor = PureWhite,
                focusedIndicatorColor = BrandBlue,
                unfocusedIndicatorColor = Slate200,
            ),
            shape = RoundedCornerShape(8.dp),
        )
    }
}

// ─── Modern Alert Notice Helper ───────────────────────────────────────────────
@Composable
private fun ModernNotice(message: String, isError: Boolean) {
    val bg = if (isError) RoseLight else EmeraldLight
    val border = if (isError) RoseBorder else EmeraldBorder
    val fg = if (isError) Rose else Emerald

    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(bg).border(1.dp, border, RoundedCornerShape(8.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (isError) StationIcons.Alert(modifier = Modifier.size(14.dp), color = fg)
        else StationIcons.Check(modifier = Modifier.size(14.dp), color = fg)
        Text(message, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

// ─── QR Code Canvas Generator ─────────────────────────────────────────────────
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
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(PureWhite).padding(8.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val rows = matrix.size
            val cols = matrix.firstOrNull()?.size ?: 0
            if (rows > 0 && cols > 0) {
                val mw = size.width / cols
                val mh = size.height / rows
                for (r in 0 until rows) {
                    for (c in 0 until cols) {
                        if (matrix[r][c]) {
                            drawRect(Slate950, Offset(c * mw, r * mh), Size(mw + 0.4f, mh + 0.4f))
                        }
                    }
                }
            }
        }
    }
}
