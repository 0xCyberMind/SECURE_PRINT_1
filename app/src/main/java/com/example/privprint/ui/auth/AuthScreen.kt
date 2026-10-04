package com.example.privprint.ui.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.privprint.ui.components.CornerRadiusButton
import com.example.privprint.ui.components.PrivPrintCard
import com.example.privprint.ui.components.PrivPrintPrimaryButton
import com.example.privprint.ui.components.maskedPhoneNumber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(
    initialTab: Int = 0,
    canCancel: Boolean = false,
    onCancel: () -> Unit = {},
    onAuthenticateUser: (
        email: String,
        password: String,
        fullName: String,
        phone: String,
        register: Boolean
    ) -> Boolean,
    onAuthenticateShop: (
        shopName: String,
        operatorName: String,
        email: String,
        password: String,
        phone: String,
        register: Boolean
    ) -> Boolean,
    loginInProgress: Boolean = false,
    loginError: String? = null,
    modifier: Modifier = Modifier
) {
    // 0 = Customer Login, 1 = Xerox Shop Operator Login
    var selectedTab by remember(initialTab) { mutableIntStateOf(initialTab) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            if (canCancel) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Cancel & Return to App",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Cancel & Return to App",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Spacer(modifier = Modifier.height(4.dp))
            }

            // App Brand Logo & Identity
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "PrivPrint",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "PrivPrint",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-0.5).sp
                            ),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFFECFDF5))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "ZERO-KNOWLEDGE",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF047857),
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                    Text(
                        text = "Encrypted Local Printing Gateway",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Segmented Role Selector
            RoleSegmentedTab(
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it }
            )

            // Dynamic Form depending on role
            AnimatedVisibility(
                visible = selectedTab == 0,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                CustomerPasswordAuthForm(
                    onAuthenticate = onAuthenticateUser,
                    loginInProgress = loginInProgress,
                    loginError = loginError,
                    onSwitchToShop = { selectedTab = 1 }
                )
            }

            AnimatedVisibility(
                visible = selectedTab == 1,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                ShopPasswordAuthForm(
                    onAuthenticate = onAuthenticateShop,
                    loginInProgress = loginInProgress,
                    loginError = loginError,
                    onSwitchToCustomer = { selectedTab = 0 }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun RoleSegmentedTab(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp)
        ) {
            // Customer Tab
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (selectedTab == 0) MaterialTheme.colorScheme.surface else Color.Transparent
                    )
                    .clickable { onTabSelected(0) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Print,
                        contentDescription = null,
                        tint = if (selectedTab == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Customer Login",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Medium
                        ),
                        color = if (selectedTab == 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Shop Operator Tab
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (selectedTab == 1) MaterialTheme.colorScheme.surface else Color.Transparent
                    )
                    .clickable { onTabSelected(1) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Storefront,
                        contentDescription = null,
                        tint = if (selectedTab == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Xerox Shop Login",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Medium
                        ),
                        color = if (selectedTab == 1) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun CustomerLoginForm(
    onLogin: (name: String, phone: String) -> Boolean,
    onVerifyOtp: (code: String) -> Boolean,
    onResetOtp: () -> Unit,
    loginInProgress: Boolean,
    loginError: String?,
    otpRequested: Boolean,
    onSwitchToShop: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    PrivPrintCard(
        elevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column {
                Text(
                    text = "Customer Sign-In",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Enter your name and mobile number to print privately at any partner Xerox shop.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Name Field
            if (!otpRequested) OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    errorMessage = null
                },
                label = { Text("Full Name") },
                placeholder = { Text("e.g. Alex Johnson") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = "Name",
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next
                ),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("user_name_input")
            )

            // Mobile Phone Field
            if (!otpRequested) OutlinedTextField(
                value = phone,
                onValueChange = {
                    phone = it
                    errorMessage = null
                },
                label = { Text("Mobile Phone Number") },
                placeholder = { Text("Include country code, e.g. +91 98765 43210") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Phone,
                        contentDescription = "Phone",
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Phone,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        focusManager.clearFocus()
                        if (name.isBlank() || phone.isBlank()) {
                            errorMessage = "Please enter both your name and phone number."
                        } else {
                            onLogin(name, phone)
                        }
                    }
                ),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("user_phone_input")
            )

            if (otpRequested) {
                Text(
                    text = "Enter the 6-digit code sent to ${maskedPhoneNumber(phone)}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = otp,
                    onValueChange = { value ->
                        otp = value.filter { it.isDigit() }.take(6)
                        errorMessage = null
                    },
                    label = { Text("Verification code") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                            onVerifyOtp(otp)
                        }
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("user_otp_input")
                )
            }

            val visibleError = errorMessage ?: loginError
            if (visibleError != null) {
                Text(
                    text = visibleError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            // Privacy Assurance Card
            Surface(
                color = Color(0xFFECFDF5),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = Color(0xFF047857),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Your phone number is sent securely to PrivPrint to deliver and verify your sign-in code.",
                        fontSize = 11.sp,
                        color = Color(0xFF065F46),
                        lineHeight = 16.sp
                    )
                }
            }

            // Primary Action
            PrivPrintPrimaryButton(
                text = if (otpRequested) "Verify & Continue" else "Send verification code",
                icon = if (otpRequested) Icons.Default.CheckCircle else Icons.Default.Print,
                onClick = {
                    focusManager.clearFocus()
                    if (otpRequested) {
                        val success = onVerifyOtp(otp)
                        if (!success) errorMessage = "Enter the 6-digit verification code."
                    } else if (name.isBlank() || phone.isBlank()) {
                        errorMessage = "Please enter both your full name and mobile phone number."
                    } else {
                        errorMessage = null
                        val success = onLogin(name, phone)
                        if (!success) {
                            errorMessage = "Invalid name or number. Please try again."
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !loginInProgress,
                isLoading = loginInProgress,
                testTag = "user_login_submit_btn"
            )

            if (otpRequested) {
                TextButton(
                    onClick = {
                        otp = ""
                        errorMessage = null
                        onResetOtp()
                    },
                    enabled = !loginInProgress,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text("Use a different phone number")
                }
            }

            // Bottom Switcher
            TextButton(
                onClick = onSwitchToShop,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(
                    text = "Are you a Xerox Shop Operator? Switch to Shop Login →",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun ShopOperatorLoginForm(
    onLogin: (shopName: String, operatorName: String, operatorPhone: String) -> Boolean,
    onVerifyOtp: (code: String) -> Boolean,
    onResetOtp: () -> Unit,
    loginInProgress: Boolean,
    loginError: String?,
    otpRequested: Boolean,
    onSwitchToCustomer: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    var shopName by remember { mutableStateOf("") }
    var operatorName by remember { mutableStateOf("") }
    var operatorPhone by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    PrivPrintCard(
        elevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column {
                Text(
                    text = "Xerox Shop Terminal Sign-In",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Authorized operator authentication for printer queues, permanent QR signage, and copy controls.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Shop Station Name Field
            OutlinedTextField(
                value = shopName,
                onValueChange = {
                    shopName = it
                    errorMessage = null
                },
                label = { Text("Shop / Store Name") },
                placeholder = { Text("e.g. Apex Campus Xerox & Print") },
                enabled = !otpRequested,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Storefront,
                        contentDescription = "Shop",
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("shop_name_input")
            )

            // Operator Name Field
            OutlinedTextField(
                value = operatorName,
                onValueChange = {
                    operatorName = it
                    errorMessage = null
                },
                label = { Text("Operator Name") },
                placeholder = { Text("e.g. Mike Operator") },
                enabled = !otpRequested,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Badge,
                        contentDescription = "Operator",
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("operator_name_input")
            )

            // Operator Mobile Phone Field
            OutlinedTextField(
                value = operatorPhone,
                onValueChange = {
                    operatorPhone = it
                    errorMessage = null
                },
                label = { Text("Operator Mobile Number") },
                placeholder = { Text("e.g. +1 (555) 018-8321") },
                enabled = !otpRequested,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Phone,
                        contentDescription = "Phone",
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Phone,
                    imeAction = ImeAction.Next
                ),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("operator_phone_input")
            )

            if (otpRequested) {
                Text(
                    text = "Enter the 6-digit code sent to ${maskedPhoneNumber(operatorPhone)}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = otp,
                    onValueChange = {
                        otp = it.filter(Char::isDigit).take(6)
                        errorMessage = null
                    },
                    label = { Text("SMS verification code") },
                    visualTransformation = PasswordVisualTransformation(),
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Verification code",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                            onVerifyOtp(otp)
                        }
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("shop_otp_input")
                )
                TextButton(
                    onClick = {
                        onResetOtp()
                        otp = ""
                        errorMessage = null
                    },
                    enabled = !loginInProgress,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Use a different phone number")
                }
            }

            val visibleError = loginError ?: errorMessage
            if (visibleError != null) {
                Text(
                    text = visibleError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            // Security Info Box
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Operator Guard: Authorized station access enforces strict copy limits and prevents document re-prints.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 16.sp
                    )
                }
            }

            // Primary Action
            PrivPrintPrimaryButton(
                text = if (otpRequested) "Verify Shop Phone" else "Send Verification Code",
                icon = Icons.Default.Storefront,
                onClick = {
                    focusManager.clearFocus()
                    if (loginInProgress) {
                        return@PrivPrintPrimaryButton
                    }
                    if (otpRequested) {
                        if (!onVerifyOtp(otp)) {
                            errorMessage = "Enter the 6-digit verification code."
                        }
                    } else {
                        val success = onLogin(shopName, operatorName, operatorPhone)
                        if (!success) {
                            errorMessage = "Enter your shop name, operator name, and phone number."
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                testTag = "shop_login_submit_btn",
                enabled = !loginInProgress,
                isLoading = loginInProgress
            )

            // Bottom Switcher
            TextButton(
                onClick = onSwitchToCustomer,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(
                    text = "Looking to print files as a customer? Switch to Customer Login →",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
