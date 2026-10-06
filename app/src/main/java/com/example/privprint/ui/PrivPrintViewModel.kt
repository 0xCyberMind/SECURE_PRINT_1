package com.example.privprint.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.privprint.data.local.PrivPrintDatabase
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
import com.example.privprint.data.model.SelectedDocument
import com.example.privprint.data.model.Shop
import com.example.privprint.data.repository.PrivPrintRepository
import com.example.privprint.data.util.DocumentMerger
import com.example.privprint.service.PrintEngine
import com.example.privprint.service.PrintingProgressState
import com.example.privprint.service.printer.AndroidPrintAdapter
import com.example.privprint.service.printer.PrinterInterface
import com.example.privprint.service.server.WindowsTerminalServer
import com.example.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import com.example.privprint.data.api.models.EnvironmentMode
import com.example.privprint.data.api.models.NearbyShopDto
import com.example.privprint.data.api.models.UserRole
import com.example.privprint.data.auth.AuthTokenManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.IOException

enum class AppMode {
    USER,
    SHOP
}

enum class AuthState {
    LOGGED_OUT,
    USER_LOGGED_IN,
    SHOP_LOGGED_IN
}

data class AuthUser(
    val name: String = "",
    val phoneNumber: String = ""
)

data class AuthShop(
    val shopId: String = "",
    val shopName: String = "",
    val operatorName: String = "",
    val operatorPhone: String = ""
)

enum class UserScreen {
    HOME,
    NEARBY_SHOPS,
    QR_SCANNER,
    SHOP_CONNECTED,
    DOCUMENT_PICKER,
    PRINT_SETTINGS,
    CONFIRMATION,
    ACTIVE_TRACKING,
    HISTORY,
    PRIVACY_CENTER,
    SETTINGS
}

enum class ShopScreen {
    DASHBOARD,
    PERMANENT_QR,
    QUEUE,
    PRINTERS,
    AUDIT,
    WINDOWS_STATION
}

data class UserUiState(
    val currentScreen: UserScreen = UserScreen.HOME,
    val selectedShop: Shop? = null,
    val selectedDocument: SelectedDocument? = null,
    val selectedDocuments: List<SelectedDocument> = emptyList(),
    val printSettings: PrintSettings = PrintSettings(),
    val scannerError: String? = null,
    val manualCodeInput: String = "",
    val toastMessage: String? = null
)

data class ShopUiState(
    val currentScreen: ShopScreen = ShopScreen.DASHBOARD,
    val selectedPrinterId: String = "PRN-HP-01",
    val statusNotice: String? = null
)

class PrivPrintViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("privprint_preferences", Context.MODE_PRIVATE)
    private var shopConnectionInProgress = false
    private var shopSessionRetryUntilMillis = 0L

    private val db = PrivPrintDatabase.getInstance(application)
    val authTokenManager = AuthTokenManager(application)
    val repository = PrivPrintRepository(db.privPrintDao(), authTokenManager)
    val printerAdapter: PrinterInterface = AndroidPrintAdapter(application)
    val printEngine = PrintEngine(
        repository = repository,
        printerAdapter = printerAdapter,
        realtimeClient = repository.realtimeClient,
        cleanupEngine = repository.cleanupEngine,
        scope = viewModelScope
    )

    private val _themeMode = MutableStateFlow(
        runCatching {
            ThemeMode.valueOf(prefs.getString("theme_mode", ThemeMode.LIGHT.name) ?: ThemeMode.LIGHT.name)
        }.getOrDefault(ThemeMode.LIGHT)
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs.edit().putString("theme_mode", mode.name).apply()
    }

    // --- Authentication State ---
    private val savedAuthState = runCatching {
        AuthState.valueOf(prefs.getString("auth_state", AuthState.LOGGED_OUT.name) ?: AuthState.LOGGED_OUT.name)
    }.getOrDefault(AuthState.LOGGED_OUT)

    private val _authState = MutableStateFlow(savedAuthState)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _currentUser = MutableStateFlow<AuthUser?>(
        if (savedAuthState == AuthState.USER_LOGGED_IN) {
            val name = prefs.getString("user_name", "") ?: ""
            val phone = prefs.getString("user_phone", "") ?: ""
            if (name.isNotBlank()) AuthUser(name = name, phoneNumber = phone) else null
        } else null
    )
    val currentUser: StateFlow<AuthUser?> = _currentUser.asStateFlow()

    private val _currentShopAuth = MutableStateFlow<AuthShop?>(
        if (savedAuthState == AuthState.SHOP_LOGGED_IN) {
            val sId = prefs.getString("shop_id", "") ?: ""
            val sName = prefs.getString("shop_name", "") ?: ""
            val opName = prefs.getString("operator_name", "") ?: ""
            val opPhone = prefs.getString("operator_phone", "") ?: ""
            if (opName.isNotBlank()) {
                AuthShop(
                    shopId = sId.ifBlank { "SHOP-101" },
                    shopName = sName.ifBlank { "Xerox Shop Terminal" },
                    operatorName = opName,
                    operatorPhone = opPhone
                )
            } else null
        } else null
    )
    val currentShopAuth: StateFlow<AuthShop?> = _currentShopAuth.asStateFlow()

    private val _pendingLoginRole = MutableStateFlow<AppMode?>(null)
    val pendingLoginRole: StateFlow<AppMode?> = _pendingLoginRole.asStateFlow()

    private val _authLoginInProgress = MutableStateFlow(false)
    val authLoginInProgress: StateFlow<Boolean> = _authLoginInProgress.asStateFlow()

    private val _authLoginError = MutableStateFlow<String?>(null)
    val authLoginError: StateFlow<String?> = _authLoginError.asStateFlow()


    private val _currentMode = MutableStateFlow(
        if (savedAuthState == AuthState.SHOP_LOGGED_IN) AppMode.SHOP else AppMode.USER
    )
    val currentMode: StateFlow<AppMode> = _currentMode.asStateFlow()

    private val _userUiState = MutableStateFlow(UserUiState())
    val userUiState: StateFlow<UserUiState> = _userUiState.asStateFlow()

    private val _shopUiState = MutableStateFlow(ShopUiState())
    val shopUiState: StateFlow<ShopUiState> = _shopUiState.asStateFlow()


    // Reactive streams from Repository
    val activeSession: StateFlow<PrintSession?> = repository.activeSession
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val activeUserJob: StateFlow<PrintJob?> = repository.activeUserJob
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val activeQueue: StateFlow<List<PrintJob>> = repository.activeQueue
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val jobHistory: StateFlow<List<PrintJob>> = repository.allJobs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allShops: StateFlow<List<Shop>> = repository.allShops
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allPrinters: StateFlow<List<Printer>> = repository.allPrinters
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val auditEvents: StateFlow<List<AuditEvent>> = repository.auditEvents
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val printProgress: StateFlow<PrintingProgressState> = printEngine.progress

    // Embedded Windows Xerox Desktop Station Server
    val windowsTerminalServer = WindowsTerminalServer(application, db.privPrintDao(), viewModelScope)
    private val _windowsStationUrl = MutableStateFlow("http://localhost:8888")
    val windowsStationUrl: StateFlow<String> = _windowsStationUrl.asStateFlow()

    private val _isPublicTunnelEnabled = MutableStateFlow(
        prefs.getBoolean("public_tunnel_enabled", true)
    )
    val isPublicTunnelEnabled: StateFlow<Boolean> = _isPublicTunnelEnabled.asStateFlow()

    private val _publicStationUrl = MutableStateFlow("https://privprint-shop-8888.pinggy.link")
    val publicStationUrl: StateFlow<String> = _publicStationUrl.asStateFlow()

    fun setPublicTunnelEnabled(enabled: Boolean) {
        _isPublicTunnelEnabled.value = enabled
        windowsTerminalServer.setPublicTunnelEnabled(enabled)
        prefs.edit().putBoolean("public_tunnel_enabled", enabled).apply()
    }

    private val _autoPrintOnAccept = MutableStateFlow(
        prefs.getBoolean("auto_print_on_accept", true)
    )
    val autoPrintOnAccept: StateFlow<Boolean> = _autoPrintOnAccept.asStateFlow()

    private val _environmentMode = MutableStateFlow(
        EnvironmentMode.valueOf(
            prefs.getString("environment_mode", EnvironmentMode.PRODUCTION.name) ?: EnvironmentMode.PRODUCTION.name
        )
    )
    val environmentMode: StateFlow<EnvironmentMode> = _environmentMode.asStateFlow()

    fun setEnvironmentMode(mode: EnvironmentMode) {
        _environmentMode.value = mode
        prefs.edit().putString("environment_mode", mode.name).apply()
        _userUiState.value = _userUiState.value.copy(
            toastMessage = "Switched API Environment to ${mode.label}"
        )
    }

    private val _nearbyShops = MutableStateFlow<List<NearbyShopDto>>(emptyList())
    val nearbyShops: StateFlow<List<NearbyShopDto>> = _nearbyShops.asStateFlow()

    fun fetchNearbyShops(lat: Double, lng: Double, radiusKm: Double = 25.0) {
        viewModelScope.launch {
            val list = repository.getNearbyShops(lat, lng, radiusKm)
            _nearbyShops.value = list
        }
    }

    fun selectNearbyShop(shopDto: NearbyShopDto) {
        val domainShop = Shop(
            id = shopDto.id,
            name = shopDto.name,
            address = shopDto.address,
            permanentQrPayload = shopDto.permanentQrPayload,
            isVerified = shopDto.isVerified,
            isOnline = shopDto.isOnline,
            supportedColor = shopDto.supportedColor,
            supportedDuplex = shopDto.supportedDuplex,
            queueCount = shopDto.activeQueueCount
        )
        connectToReadyShop(domainShop)
    }

    private fun connectToReadyShop(shop: Shop, qrPayload: String? = null) {
        val now = System.currentTimeMillis()
        val retryRemainingSeconds = ((shopSessionRetryUntilMillis - now + 999) / 1000).coerceAtLeast(0)
        if (retryRemainingSeconds > 0) {
            val message = "Please wait $retryRemainingSeconds seconds before scanning this shop again."
            _userUiState.value = _userUiState.value.copy(scannerError = message, toastMessage = message)
            return
        }
        if (shopConnectionInProgress) return

        shopConnectionInProgress = true
        viewModelScope.launch {
            try {
                if (repository.getShopPrintKeys(shop.id).isEmpty()) {
                    throw IOException(
                        "No Windows station encryption key is registered for this shop yet."
                    )
                }
                repository.createSession(shop.id, shop.name)
                val connectedShop = if (qrPayload == null) {
                    shop
                } else {
                    shop.copy(permanentQrPayload = qrPayload)
                }
                repository.saveShop(connectedShop)
                _userUiState.value = _userUiState.value.copy(
                    selectedShop = connectedShop,
                    scannerError = null,
                    toastMessage = null,
                    currentScreen = UserScreen.SHOP_CONNECTED
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val detail = e.message ?: "Windows station is not ready."
                val retryAfterSeconds = if (detail.contains("HTTP 429")) {
                    Regex("\"retry_after\"\\s*:\\s*(\\d+)")
                        .find(detail)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toLongOrNull()
                        ?: 45L
                } else {
                    0L
                }
                if (retryAfterSeconds > 0) {
                    shopSessionRetryUntilMillis = System.currentTimeMillis() + retryAfterSeconds * 1000
                }
                val message = if (retryAfterSeconds > 0) {
                    "Shop ${shop.id}: Too many QR connection attempts. Please wait $retryAfterSeconds seconds, then scan once."
                } else {
                    "Shop ${shop.id}: $detail"
                }
                _userUiState.value = _userUiState.value.copy(
                    scannerError = message,
                    toastMessage = message
                )
            } finally {
                shopConnectionInProgress = false
            }
        }
    }

    fun setAutoPrintOnAccept(enabled: Boolean) {
        _autoPrintOnAccept.value = enabled
        prefs.edit().putBoolean("auto_print_on_accept", enabled).apply()
    }

    init {
        windowsTerminalServer.start(8888)
        _windowsStationUrl.value = windowsTerminalServer.getStationUrl()
        _publicStationUrl.value = windowsTerminalServer.getPublicTunnelUrl()
        windowsTerminalServer.setPublicTunnelEnabled(_isPublicTunnelEnabled.value)

        viewModelScope.launch {
            repository.initializeSeedData()
            fetchNearbyShops(23.0225, 72.5714)
        }
    }

    fun setAppMode(mode: AppMode) {
        _currentMode.value = mode
    }

    // --- Authentication Actions ---

    fun authenticate(
        email: String,
        password: String,
        fullName: String,
        phone: String,
        role: UserRole,
        shopName: String = "",
        register: Boolean
    ): Boolean {
        val normalizedEmail = email.trim()
        if (_authLoginInProgress.value) return true
        if (normalizedEmail.isBlank() || password.isBlank()) {
            _authLoginError.value = "Enter your email and password."
            return false
        }
        if (register && fullName.isBlank()) {
            _authLoginError.value = "Enter your full name."
            return false
        }
        if (register && password.length < 8) {
            _authLoginError.value = "Password must be at least 8 characters."
            return false
        }
        if (register && role == UserRole.SHOP_OPERATOR && shopName.isBlank()) {
            _authLoginError.value = "Enter your shop name."
            return false
        }

        _authLoginError.value = null
        _authLoginInProgress.value = true
        viewModelScope.launch {
            val result = if (register) {
                repository.register(
                    email = normalizedEmail,
                    password = password,
                    fullName = fullName,
                    phoneNumber = phone,
                    role = role,
                    shopName = shopName
                )
            } else {
                repository.login(
                    identity = normalizedEmail,
                    secret = password,
                    role = role,
                    shopName = shopName
                )
            }

            when (result) {
                is com.example.privprint.data.auth.AuthResult.Success -> {
                    val nowPhone = phone.trim().ifBlank { result.user.phoneNumber.orEmpty() }
                    if (role == UserRole.USER) {
                        val displayName = fullName.ifBlank {
                            result.user.fullName?.takeIf { it.isNotBlank() }
                                ?: normalizedEmail.substringBefore("@")
                        }
                        _currentUser.value = AuthUser(name = displayName, phoneNumber = nowPhone)
                        _authState.value = AuthState.USER_LOGGED_IN
                        _currentMode.value = AppMode.USER
                        _pendingLoginRole.value = null
                        _userUiState.value = _userUiState.value.copy(
                            currentScreen = UserScreen.HOME,
                            toastMessage = "Welcome, $displayName! Secure session started."
                        )
                        prefs.edit()
                            .putString("auth_state", AuthState.USER_LOGGED_IN.name)
                            .putString("user_name", displayName)
                            .putString("user_phone", nowPhone)
                            .apply()
                    } else {
                        val shop = AuthShop(
                            shopId = result.user.shopId.orEmpty(),
                            shopName = shopName.trim().ifBlank { result.user.shopName.orEmpty() },
                            operatorName = fullName.ifBlank {
                                result.user.fullName?.takeIf { it.isNotBlank() }
                                    ?: normalizedEmail.substringBefore("@")
                            },
                            operatorPhone = nowPhone
                        )
                        _currentShopAuth.value = shop
                        _authState.value = AuthState.SHOP_LOGGED_IN
                        _currentMode.value = AppMode.SHOP
                        _pendingLoginRole.value = null
                        _shopUiState.value = _shopUiState.value.copy(
                            currentScreen = ShopScreen.DASHBOARD,
                            statusNotice = if (result.user.shopPendingApproval) {
                                "Shop account created. It is hidden until admin approval."
                            } else {
                                "Shop operator signed in: ${shop.shopName}"
                            }
                        )
                        prefs.edit()
                            .putString("auth_state", AuthState.SHOP_LOGGED_IN.name)
                            .putString("shop_id", shop.shopId)
                            .putString("shop_name", shop.shopName)
                            .putString("operator_name", shop.operatorName)
                            .putString("operator_phone", nowPhone)
                            .apply()
                        if (allPrinters.value.isEmpty()) {
                            val printerId = "PRN-${System.currentTimeMillis().toString().takeLast(6)}"
                            repository.savePrinter(
                                Printer(
                                    id = printerId,
                                    shopId = shop.shopId,
                                    name = "Xerox / Network Printer",
                                    model = "Windows or network-connected printer",
                                    isDefault = true,
                                    status = PrinterStatus.READY,
                                    paperStatus = PaperTrayStatus.FULL,
                                    tonerLevelPercent = 95,
                                    totalPrintedLifetime = 0
                                )
                            )
                        }
                    }
                    _authLoginInProgress.value = false
                }
                is com.example.privprint.data.auth.AuthResult.Failure -> {
                    _authLoginError.value = result.error
                    _authLoginInProgress.value = false
                }
            }
        }
        return true
    }

    fun savePrinter(
        name: String,
        model: String,
        paperStatus: PaperTrayStatus = PaperTrayStatus.FULL,
        tonerLevel: Int = 100,
        isDefault: Boolean = true
    ) {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) return
        val currentShopId = _currentShopAuth.value?.shopId ?: "SHOP-101"
        val printerId = "PRN-${System.currentTimeMillis().toString().takeLast(6)}"
        val printer = Printer(
            id = printerId,
            shopId = currentShopId,
            name = trimmedName,
            model = model.trim().ifBlank { "LaserJet Network / USB" },
            isDefault = isDefault,
            status = PrinterStatus.READY,
            paperStatus = paperStatus,
            tonerLevelPercent = tonerLevel.coerceIn(5, 100),
            totalPrintedLifetime = 0
        )
        viewModelScope.launch {
            repository.savePrinter(printer)
        }
    }

    fun deletePrinter(printerId: String) {
        viewModelScope.launch {
            repository.deletePrinter(printerId)
        }
    }

    fun openLogin(targetRole: AppMode? = null) {
        _pendingLoginRole.value = targetRole
        if (targetRole != null) {
            _currentMode.value = targetRole
        }
        _authState.value = AuthState.LOGGED_OUT
    }

    fun cancelLogin() {
        if (_currentUser.value != null && (_currentMode.value == AppMode.USER || _currentShopAuth.value == null)) {
            _authState.value = AuthState.USER_LOGGED_IN
            _currentMode.value = AppMode.USER
        } else if (_currentShopAuth.value != null) {
            _authState.value = AuthState.SHOP_LOGGED_IN
            _currentMode.value = AppMode.SHOP
        }
        _pendingLoginRole.value = null
    }

    fun logout() {
        _authState.value = AuthState.LOGGED_OUT
        _currentUser.value = null
        _currentShopAuth.value = null
        _pendingLoginRole.value = null
        _userUiState.value = UserUiState(currentScreen = UserScreen.HOME)
        _shopUiState.value = ShopUiState(currentScreen = ShopScreen.DASHBOARD)

        prefs.edit()
            .putString("auth_state", AuthState.LOGGED_OUT.name)
            .apply()
    }

    fun navigateToUserScreen(screen: UserScreen) {
        _userUiState.value = _userUiState.value.copy(currentScreen = screen, scannerError = null)
    }

    fun navigateToShopScreen(screen: ShopScreen) {
        _shopUiState.value = _shopUiState.value.copy(currentScreen = screen)
    }

    // --- QR Scanner Actions ---

    fun onScanShopQr(qrPayload: String) {
        val parsed = repository.parseShopQrPayload(qrPayload)
        if (parsed != null) {
            val (shopId, shopName) = parsed
            val matchingShop = allShops.value.find { it.id == shopId }
                ?: Shop(
                    id = shopId,
                    name = shopName,
                    address = "Verified Xerox Partner",
                    permanentQrPayload = qrPayload,
                    isVerified = true,
                    isOnline = true
                )
            connectToReadyShop(matchingShop, qrPayload)
        } else {
            _userUiState.value = _userUiState.value.copy(
                scannerError = "Invalid QR code. Please scan a verified PrivPrint shop QR code."
            )
        }
    }

    fun onManualCodeSubmit(code: String) {
        val trimmed = code.trim()
        val parsed = repository.parseShopQrPayload(trimmed)
        if (parsed != null) {
            onScanShopQr(trimmed)
            return
        }
        val shop = allShops.value.find { it.id.equals(trimmed, ignoreCase = true) }
        if (shop != null) {
            onScanShopQr(shop.permanentQrPayload)
        } else {
            _userUiState.value = _userUiState.value.copy(
                scannerError = "Shop ID or QR code '$trimmed' not recognized. Scan the QR code displayed by the shop."
            )
        }
    }

    fun setManualCodeInput(input: String) {
        _userUiState.value = _userUiState.value.copy(manualCodeInput = input)
    }

    // --- Document Selection ---

    fun selectDocument(doc: SelectedDocument) {
        selectDocuments(listOf(doc))
    }

    fun selectDocuments(docs: List<SelectedDocument>) {
        val merged = if (docs.size > 1) {
            runCatching { DocumentMerger.mergeDocuments(getApplication(), docs) }.getOrDefault(docs.firstOrNull())
        } else {
            docs.firstOrNull()
        }
        _userUiState.value = _userUiState.value.copy(
            selectedDocument = merged,
            selectedDocuments = docs,
            currentScreen = UserScreen.PRINT_SETTINGS
        )
    }

    fun addDocument(doc: SelectedDocument) {
        val currentList = _userUiState.value.selectedDocuments.toMutableList()
        if (!currentList.contains(doc)) {
            currentList.add(doc)
            val merged = if (currentList.size > 1) {
                runCatching { DocumentMerger.mergeDocuments(getApplication(), currentList) }.getOrDefault(currentList.firstOrNull())
            } else {
                currentList.firstOrNull()
            }
            _userUiState.value = _userUiState.value.copy(
                selectedDocument = merged,
                selectedDocuments = currentList
            )
        }
    }

    fun removeDocument(doc: SelectedDocument) {
        val currentList = _userUiState.value.selectedDocuments.toMutableList()
        currentList.remove(doc)
        val merged = if (currentList.size > 1) {
            runCatching { DocumentMerger.mergeDocuments(getApplication(), currentList) }.getOrDefault(currentList.firstOrNull())
        } else {
            currentList.firstOrNull()
        }
        _userUiState.value = _userUiState.value.copy(
            selectedDocument = merged,
            selectedDocuments = currentList
        )
    }

    fun clearDocuments() {
        _userUiState.value = _userUiState.value.copy(
            selectedDocument = null,
            selectedDocuments = emptyList()
        )
    }

    // --- Print Settings Updates ---

    fun updateCopies(delta: Int) {
        val current = _userUiState.value.printSettings.copies
        val updated = (current + delta).coerceIn(1, 20)
        _userUiState.value = _userUiState.value.copy(
            printSettings = _userUiState.value.printSettings.copy(copies = updated)
        )
    }

    fun updateColorMode(mode: ColorMode) {
        _userUiState.value = _userUiState.value.copy(
            printSettings = _userUiState.value.printSettings.copy(colorMode = mode)
        )
    }

    fun updatePaperSize(size: PaperSize) {
        _userUiState.value = _userUiState.value.copy(
            printSettings = _userUiState.value.printSettings.copy(paperSize = size)
        )
    }

    fun updateDuplex(mode: DuplexMode) {
        _userUiState.value = _userUiState.value.copy(
            printSettings = _userUiState.value.printSettings.copy(duplexMode = mode)
        )
    }

    // --- Job Submission & Execution ---

    fun confirmAndSubmitJob() {
        val session = activeSession.value
        val allDocs = _userUiState.value.selectedDocuments
        val doc = if (allDocs.size > 1) {
            runCatching { DocumentMerger.mergeDocuments(getApplication(), allDocs) }.getOrDefault(_userUiState.value.selectedDocument)
        } else {
            _userUiState.value.selectedDocument ?: allDocs.firstOrNull()
        }
        val settings = _userUiState.value.printSettings

        if (session == null || doc == null) {
            _userUiState.value = _userUiState.value.copy(toastMessage = "Missing session or document.")
            return
        }

        viewModelScope.launch {
            try {
                repository.submitPrintJob(
                    session = session,
                    documentName = doc.name,
                    documentBytes = doc.rawBytes,
                    pageCount = doc.pageCount,
                    settings = settings
                )
                _userUiState.value = _userUiState.value.copy(
                    currentScreen = UserScreen.ACTIVE_TRACKING,
                    toastMessage = "Document encrypted and securely sent to the print station."
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _userUiState.value = _userUiState.value.copy(
                    selectedDocument = null,
                    currentScreen = UserScreen.DOCUMENT_PICKER,
                    toastMessage = "File was not sent: ${e.message ?: "secure upload failed"}. Select it again to retry."
                )
            }
        }
    }

    // --- Shop Operator Actions ---

    fun startPrintingNextQueueJob() {
        val nextJob = activeQueue.value.firstOrNull { it.status == PrintJobStatus.QUEUED }
        if (nextJob != null) {
            printEngine.startPrintingJob(nextJob)
        } else {
            _shopUiState.value = _shopUiState.value.copy(statusNotice = "No pending jobs in queue.")
        }
    }

    fun printSpecificJob(job: PrintJob) {
        printEngine.startPrintingJob(job)
    }

    fun cancelJob(jobId: String) {
        printEngine.cancelActivePrinting(jobId)
    }

    fun attemptUnauthorizedCopy(jobId: String) {
        viewModelScope.launch {
            val result = printEngine.attemptUnauthorizedExtraCopy(jobId)
            _shopUiState.value = _shopUiState.value.copy(
                statusNotice = "Security Engine: Attempted extra copy -> Result: $result"
            )
        }
    }

    fun startNewPrint() {
        _userUiState.value = _userUiState.value.copy(
            selectedDocument = null,
            printSettings = PrintSettings(),
            currentScreen = if (_userUiState.value.selectedShop != null) UserScreen.DOCUMENT_PICKER else UserScreen.HOME,
            toastMessage = null
        )
    }

    // --- Privacy Center Actions ---

    fun emergencyRevokeSession() {
        val session = activeSession.value ?: return
        viewModelScope.launch {
            repository.revokeCurrentSession(session.sessionId)
            _userUiState.value = _userUiState.value.copy(
                selectedShop = null,
                selectedDocument = null,
                currentScreen = UserScreen.HOME,
                toastMessage = "Session and encryption keys shredded."
            )
        }
    }

    fun acceptAndDirectPrintJob(job: PrintJob) {
        viewModelScope.launch {
            repository.updateJobStatus(job.jobId, PrintJobStatus.PRINTING)
            printEngine.startPrintingJob(job)
            _shopUiState.value = _shopUiState.value.copy(
                statusNotice = "Order #${job.jobId.takeLast(4)} accepted! Directly printing to connected printer."
            )
        }
    }

    fun clearToast() {
        _userUiState.value = _userUiState.value.copy(toastMessage = null)
        _shopUiState.value = _shopUiState.value.copy(statusNotice = null)
    }

    override fun onCleared() {
        super.onCleared()
        windowsTerminalServer.stop()
    }
}
