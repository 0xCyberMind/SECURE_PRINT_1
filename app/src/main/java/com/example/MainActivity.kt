package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.privprint.data.model.Shop
import com.example.privprint.ui.AppMode
import com.example.privprint.ui.AuthState
import com.example.privprint.ui.PrivPrintViewModel
import com.example.privprint.ui.ShopScreen
import com.example.privprint.ui.UserScreen
import com.example.privprint.ui.auth.AuthScreen
import com.example.privprint.ui.components.PrivPrintBottomNav
import com.example.privprint.ui.components.PrivPrintNavigationRail
import com.example.privprint.ui.components.PrivPrintShopNavigationRail
import com.example.privprint.ui.components.PrivPrintTopBar
import com.example.privprint.ui.shop.PermanentQrScreen
import com.example.privprint.ui.shop.ShopAuditScreen
import com.example.privprint.ui.shop.ShopDashboardScreen
import com.example.privprint.ui.shop.ShopPrintersScreen
import com.example.privprint.ui.shop.ShopQueueScreen
import com.example.privprint.ui.shop.ShopWindowsStationScreen
import com.example.privprint.ui.user.ActiveTrackingScreen
import com.example.privprint.ui.user.DocumentPickerScreen
import com.example.privprint.ui.user.HistoryScreen
import com.example.privprint.ui.user.NearbyShopsScreen
import com.example.privprint.ui.user.PrintConfirmationScreen
import com.example.privprint.ui.user.PrintSettingsScreen
import com.example.privprint.ui.user.PrivacyCenterScreen
import com.example.privprint.ui.user.QrScannerScreen
import com.example.privprint.ui.user.SettingsScreen
import com.example.privprint.ui.user.ShopConnectedScreen
import com.example.privprint.ui.user.UserHomeScreen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val viewModel: PrivPrintViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            MyApplicationTheme(themeMode = themeMode) {
                PrivPrintApp(viewModel)
            }
        }
    }
}

@Composable
fun PrivPrintApp(viewModel: PrivPrintViewModel) {
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()
    val currentShopAuth by viewModel.currentShopAuth.collectAsStateWithLifecycle()

    val currentMode by viewModel.currentMode.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val userUiState by viewModel.userUiState.collectAsStateWithLifecycle()
    val shopUiState by viewModel.shopUiState.collectAsStateWithLifecycle()

    val activeSession by viewModel.activeSession.collectAsStateWithLifecycle()
    val activeUserJob by viewModel.activeUserJob.collectAsStateWithLifecycle()
    val activeQueue by viewModel.activeQueue.collectAsStateWithLifecycle()
    val jobHistory by viewModel.jobHistory.collectAsStateWithLifecycle()
    val allShops by viewModel.allShops.collectAsStateWithLifecycle()
    val allPrinters by viewModel.allPrinters.collectAsStateWithLifecycle()
    val auditEvents by viewModel.auditEvents.collectAsStateWithLifecycle()
    val printProgress by viewModel.printProgress.collectAsStateWithLifecycle()
    val windowsStationUrl by viewModel.windowsStationUrl.collectAsStateWithLifecycle()
    val publicStationUrl by viewModel.publicStationUrl.collectAsStateWithLifecycle()
    val isPublicTunnelEnabled by viewModel.isPublicTunnelEnabled.collectAsStateWithLifecycle()
    val autoPrintOnAccept by viewModel.autoPrintOnAccept.collectAsStateWithLifecycle()
    val environmentMode by viewModel.environmentMode.collectAsStateWithLifecycle()
    val nearbyShops by viewModel.nearbyShops.collectAsStateWithLifecycle()

    val pendingLoginRole by viewModel.pendingLoginRole.collectAsStateWithLifecycle()
    val authLoginInProgress by viewModel.authLoginInProgress.collectAsStateWithLifecycle()
    val authLoginError by viewModel.authLoginError.collectAsStateWithLifecycle()
    val canCancelLogin = currentUser != null || currentShopAuth != null

    val snackbarHostState = remember { SnackbarHostState() }

    // Toast and notice handler
    LaunchedEffect(userUiState.toastMessage) {
        userUiState.toastMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearToast()
        }
    }

    LaunchedEffect(shopUiState.statusNotice) {
        shopUiState.statusNotice?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearToast()
        }
    }

    // Login Pages - reachable anytime from anywhere
    if (authState == AuthState.LOGGED_OUT) {
        AuthScreen(
            initialTab = 0,
            canCancel = canCancelLogin,
            onCancel = { viewModel.cancelLogin() },
            onAuthenticateUser = { email, password, fullName, phone, register ->
                viewModel.authenticate(
                    email = email,
                    password = password,
                    fullName = fullName,
                    phone = phone,
                    role = com.example.privprint.data.api.models.UserRole.USER,
                    register = register
                )
            },
            onAuthenticateShop = { _, _, _, _, _, _ -> false },
            loginInProgress = authLoginInProgress,
            loginError = authLoginError,
        )
        return
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWideScreen = maxWidth >= 640.dp

        Row(modifier = Modifier.fillMaxSize()) {
            if (isWideScreen) {
                if (currentMode == AppMode.USER) {
                    PrivPrintNavigationRail(
                        currentScreen = userUiState.currentScreen,
                        onNavigate = { viewModel.navigateToUserScreen(it) }
                    )
                }
            }

            Scaffold(
                topBar = {
                    PrivPrintTopBar(
                        currentMode = currentMode,
                        hasActiveSession = activeSession != null && !(activeSession?.isExpired ?: true),
                        currentUser = currentUser,
                        currentShopAuth = currentShopAuth,
                        onToggleMode = { },
                        onOpenLogin = { targetRole ->
                            viewModel.openLogin(targetRole)
                        },
                        onOpenPrivacyCenter = {
                            viewModel.setAppMode(AppMode.USER)
                            viewModel.navigateToUserScreen(UserScreen.PRIVACY_CENTER)
                        },
                        onLogout = {
                            viewModel.logout()
                        }
                    )
                },
                bottomBar = {
                    if (!isWideScreen && currentMode == AppMode.USER) {
                        PrivPrintBottomNav(
                            currentScreen = userUiState.currentScreen,
                            onNavigate = { viewModel.navigateToUserScreen(it) }
                        )
                    }
                },
                snackbarHost = { SnackbarHost(snackbarHostState) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    when (currentMode) {
                        AppMode.USER -> {
                            // Hardware back button support
                            BackHandler(enabled = userUiState.currentScreen != UserScreen.HOME) {
                                when (userUiState.currentScreen) {
                                    UserScreen.CONFIRMATION -> viewModel.navigateToUserScreen(UserScreen.PRINT_SETTINGS)
                                    UserScreen.PRINT_SETTINGS -> viewModel.navigateToUserScreen(UserScreen.DOCUMENT_PICKER)
                                    UserScreen.DOCUMENT_PICKER -> viewModel.navigateToUserScreen(UserScreen.SHOP_CONNECTED)
                                    UserScreen.SHOP_CONNECTED -> viewModel.navigateToUserScreen(UserScreen.HOME)
                                    UserScreen.NEARBY_SHOPS -> viewModel.navigateToUserScreen(UserScreen.HOME)
                                    UserScreen.QR_SCANNER -> viewModel.navigateToUserScreen(UserScreen.HOME)
                                    UserScreen.ACTIVE_TRACKING -> viewModel.navigateToUserScreen(UserScreen.HOME)
                                    UserScreen.HISTORY -> viewModel.navigateToUserScreen(UserScreen.HOME)
                                    UserScreen.PRIVACY_CENTER -> viewModel.navigateToUserScreen(UserScreen.HOME)
                                    UserScreen.SETTINGS -> viewModel.navigateToUserScreen(UserScreen.HOME)
                                    UserScreen.HOME -> Unit
                                }
                            }

                            when (userUiState.currentScreen) {
                                UserScreen.HOME -> UserHomeScreen(
                                    activeJob = activeUserJob,
                                    recentJobs = jobHistory,
                                    featuredShops = allShops,
                                    currentUser = currentUser,
                                    onNavigate = { viewModel.navigateToUserScreen(it) },
                                    onSelectShop = { shop ->
                                        viewModel.onScanShopQr(shop.permanentQrPayload)
                                    },
                                    onOpenLogin = { }
                                )

                                UserScreen.NEARBY_SHOPS -> NearbyShopsScreen(
                                    nearbyShops = nearbyShops,
                                    onFetchNearbyShops = { lat, lng, radius ->
                                        viewModel.fetchNearbyShops(lat, lng, radius)
                                    },
                                    onSelectNearbyShop = { shop ->
                                        viewModel.selectNearbyShop(shop)
                                    },
                                    onNavigateToQrScanner = { viewModel.navigateToUserScreen(UserScreen.QR_SCANNER) },
                                    onBack = { viewModel.navigateToUserScreen(UserScreen.HOME) }
                                )

                                UserScreen.QR_SCANNER -> QrScannerScreen(
                                    scannerError = userUiState.scannerError,
                                    manualCodeInput = userUiState.manualCodeInput,
                                    onManualCodeChange = { viewModel.setManualCodeInput(it) },
                                    onManualCodeSubmit = { viewModel.onManualCodeSubmit(it) },
                                    onQrScanned = { viewModel.onScanShopQr(it) },
                                    onBack = { viewModel.navigateToUserScreen(UserScreen.HOME) }
                                )

                                UserScreen.SHOP_CONNECTED -> {
                                    val shop = userUiState.selectedShop ?: allShops.firstOrNull() ?: Shop(
                                        id = "SHOP-101",
                                        name = "Apex Campus Xerox & Print",
                                        address = "Student Center",
                                        permanentQrPayload = Shop.createQrPayload("SHOP-101", "Apex Campus Xerox & Print")
                                    )
                                    ShopConnectedScreen(
                                        shop = shop,
                                        session = activeSession,
                                        onSelectDocumentClick = { viewModel.navigateToUserScreen(UserScreen.DOCUMENT_PICKER) },
                                        onDisconnect = {
                                            viewModel.emergencyRevokeSession()
                                        }
                                    )
                                }

                                UserScreen.DOCUMENT_PICKER -> DocumentPickerScreen(
                                    onDocumentSelected = { viewModel.selectDocument(it) },
                                    onDocumentsSelected = { viewModel.selectDocuments(it) },
                                    onBack = { viewModel.navigateToUserScreen(UserScreen.SHOP_CONNECTED) }
                                )

                                UserScreen.PRINT_SETTINGS -> {
                                    userUiState.selectedDocument?.let { doc ->
                                        PrintSettingsScreen(
                                            document = doc,
                                            documents = userUiState.selectedDocuments.ifEmpty { listOf(doc) },
                                            settings = userUiState.printSettings,
                                            onUpdateCopies = { viewModel.updateCopies(it) },
                                            onUpdateColor = { viewModel.updateColorMode(it) },
                                            onUpdatePaperSize = { viewModel.updatePaperSize(it) },
                                            onUpdateDuplex = { viewModel.updateDuplex(it) },
                                            onProceed = { viewModel.navigateToUserScreen(UserScreen.CONFIRMATION) },
                                            onBack = { viewModel.navigateToUserScreen(UserScreen.DOCUMENT_PICKER) }
                                        )
                                    } ?: run {
                                        viewModel.navigateToUserScreen(UserScreen.DOCUMENT_PICKER)
                                    }
                                }

                                UserScreen.CONFIRMATION -> {
                                    val shop = userUiState.selectedShop ?: allShops.firstOrNull() ?: Shop(
                                        id = "SHOP-101",
                                        name = "Apex Campus Xerox & Print",
                                        address = "Student Center",
                                        permanentQrPayload = Shop.createQrPayload("SHOP-101", "Apex Campus Xerox & Print")
                                    )
                                    userUiState.selectedDocument?.let { doc ->
                                        PrintConfirmationScreen(
                                            shop = shop,
                                            session = activeSession,
                                            document = doc,
                                            settings = userUiState.printSettings,
                                            onConfirm = { viewModel.confirmAndSubmitJob() },
                                            onBack = { viewModel.navigateToUserScreen(UserScreen.PRINT_SETTINGS) }
                                        )
                                    } ?: run {
                                        viewModel.navigateToUserScreen(UserScreen.DOCUMENT_PICKER)
                                    }
                                }

                                UserScreen.ACTIVE_TRACKING -> ActiveTrackingScreen(
                                    job = activeUserJob,
                                    printProgress = printProgress,
                                    onStartPrintingSimulation = { viewModel.printSpecificJob(it) },
                                    onCancelJob = { viewModel.cancelJob(it) },
                                    onBack = { viewModel.navigateToUserScreen(UserScreen.HOME) },
                                    onPrintAnother = { viewModel.startNewPrint() }
                                )

                                UserScreen.PRIVACY_CENTER -> PrivacyCenterScreen(
                                    session = activeSession,
                                    activeJob = activeUserJob,
                                    auditEvents = auditEvents,
                                    onEmergencyRevoke = { viewModel.emergencyRevokeSession() },
                                    onBack = { viewModel.navigateToUserScreen(UserScreen.HOME) }
                                )

                                UserScreen.HISTORY -> HistoryScreen(
                                    jobs = jobHistory,
                                    onBack = { viewModel.navigateToUserScreen(UserScreen.HOME) }
                                )

                                UserScreen.SETTINGS -> SettingsScreen(
                                    currentTheme = themeMode,
                                    currentUser = currentUser,
                                    environmentMode = environmentMode,
                                    onEnvironmentChange = { viewModel.setEnvironmentMode(it) },
                                    onThemeChange = { viewModel.setThemeMode(it) },
                                    onSwitchToShopMode = { },
                                    onResetSession = { viewModel.emergencyRevokeSession() },
                                    onOpenLogin = { viewModel.openLogin(null) },
                                    onLogout = { viewModel.logout() },
                                    onBack = { viewModel.navigateToUserScreen(UserScreen.HOME) }
                                )
                            }
                        }

                        AppMode.SHOP -> {
                            // Shop operator screens are only available in the Windows application.
                            // If customer mode somehow enters SHOP, redirect back to USER.
                            LaunchedEffect(Unit) {
                                viewModel.setAppMode(AppMode.USER)
                            }
                        }
                    }
                }
            }
        }
    }
}
