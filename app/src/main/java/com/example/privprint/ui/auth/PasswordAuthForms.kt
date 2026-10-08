package com.example.privprint.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.privprint.ui.components.PrivPrintCard
import com.example.privprint.ui.components.PrivPrintPrimaryButton

@Composable
internal fun CustomerPasswordAuthForm(
    onAuthenticate: (email: String, password: String, fullName: String, phone: String, register: Boolean) -> Boolean,
    loginInProgress: Boolean,
    loginError: String?,
    onSwitchToShop: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    var registering by remember { mutableStateOf(false) }
    var fullName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    PrivPrintCard(elevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = if (registering) "Create Customer Account" else "Welcome to PrivPrint",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = if (registering) {
                    "Create a new account with your email and password."
                } else {
                    "Sign in to your PrivPrint account"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (registering) {
                OutlinedTextField(
                    value = fullName,
                    onValueChange = { fullName = it; errorMessage = null },
                    label = { Text("Full Name") },
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("user_name_input")
                )
            }
            OutlinedTextField(
                value = email,
                onValueChange = { email = it; errorMessage = null },
                label = { Text("Email") },
                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().testTag("user_email_input")
            )
            if (registering) {
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it; errorMessage = null },
                    label = { Text("Mobile Number (optional)") },
                    leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Phone,
                        imeAction = ImeAction.Next
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("user_phone_input")
                )
            }
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; errorMessage = null },
                label = { Text("Password") },
                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = {
                    focusManager.clearFocus()
                    submitCustomerAuth(
                        email, password, fullName, phone, registering,
                        onAuthenticate, { errorMessage = it }
                    )
                }),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().testTag("user_password_input")
            )

            val visibleError = errorMessage ?: loginError
            if (visibleError != null) {
                Text(visibleError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

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
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Passwords are verified by the PrivPrint account service.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            PrivPrintPrimaryButton(
                text = if (registering) "Create account" else "Sign in",
                icon = Icons.Default.Print,
                onClick = {
                    focusManager.clearFocus()
                    submitCustomerAuth(
                        email, password, fullName, phone, registering,
                        onAuthenticate, { errorMessage = it }
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !loginInProgress,
                isLoading = loginInProgress,
                testTag = "user_login_submit_btn"
            )

            TextButton(
                onClick = {
                    registering = !registering
                    errorMessage = null
                },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(if (registering) "Already have an account? Sign in" else "New to PrivPrint? Create an account")
            }
        }
    }
}

private fun submitCustomerAuth(
    email: String,
    password: String,
    fullName: String,
    phone: String,
    registering: Boolean,
    onAuthenticate: (String, String, String, String, Boolean) -> Boolean,
    onError: (String) -> Unit
) {
    if (!email.contains('@') || email.substringAfter('@').let { it.isBlank() || !it.contains('.') }) {
        onError("Enter a valid email address.")
    } else if (password.isBlank()) {
        onError("Enter your password.")
    } else if (registering && fullName.isBlank()) {
        onError("Enter your full name.")
    } else if (registering && password.length < 8) {
        onError("Password must be at least 8 characters.")
    } else {
        onError("")
        onAuthenticate(email.trim(), password, fullName.trim(), phone.trim(), registering)
    }
}

@Composable
internal fun ShopPasswordAuthForm(
    onAuthenticate: (
        shopName: String,
        operatorName: String,
        email: String,
        password: String,
        phone: String,
        register: Boolean
    ) -> Boolean,
    loginInProgress: Boolean,
    loginError: String?,
    onSwitchToCustomer: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    var registering by remember { mutableStateOf(false) }
    var shopName by remember { mutableStateOf("") }
    var operatorName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    PrivPrintCard(elevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = if (registering) "Create Xerox Shop Account" else "Xerox Shop Sign-In",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = if (registering) {
                    "Create a new operator account and shop profile."
                } else {
                    "Sign in with the email and password for your shop operator account."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = shopName,
                onValueChange = { shopName = it; errorMessage = null },
                label = { Text("Shop / Store Name (for new setup)") },
                leadingIcon = { Icon(Icons.Default.Storefront, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().testTag("shop_name_input")
            )

            if (registering) {
                OutlinedTextField(
                    value = operatorName,
                    onValueChange = { operatorName = it; errorMessage = null },
                    label = { Text("Operator Name") },
                    leadingIcon = { Icon(Icons.Default.Badge, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("operator_name_input")
                )
            }
            OutlinedTextField(
                value = email,
                onValueChange = { email = it; errorMessage = null },
                label = { Text("Email") },
                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().testTag("operator_email_input")
            )
            if (registering) {
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it; errorMessage = null },
                    label = { Text("Operator Mobile Number (optional)") },
                    leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Phone,
                        imeAction = ImeAction.Next
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("operator_phone_input")
                )
            }
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; errorMessage = null },
                label = { Text("Password") },
                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = {
                    focusManager.clearFocus()
                    submitShopAuth(
                        shopName, operatorName, email, password, phone, registering,
                        onAuthenticate, { errorMessage = it }
                    )
                }),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().testTag("operator_password_input")
            )

            val visibleError = errorMessage?.takeIf { it.isNotBlank() } ?: loginError
            if (visibleError != null) {
                Text(visibleError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
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
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Operator access is tied to the shop owner account.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            PrivPrintPrimaryButton(
                text = if (registering) "Create shop account" else "Sign in",
                icon = Icons.Default.Storefront,
                onClick = {
                    focusManager.clearFocus()
                    submitShopAuth(
                        shopName, operatorName, email, password, phone, registering,
                        onAuthenticate, { errorMessage = it }
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !loginInProgress,
                isLoading = loginInProgress,
                testTag = "shop_login_submit_btn"
            )
            TextButton(
                onClick = {
                    registering = !registering
                    errorMessage = null
                },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(if (registering) "Already have an account? Sign in" else "New shop operator? Create account")
            }
            TextButton(
                onClick = onSwitchToCustomer,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text("Are you a customer? Switch to Customer Login →", textAlign = TextAlign.Center)
            }
        }
    }
}

private fun submitShopAuth(
    shopName: String,
    operatorName: String,
    email: String,
    password: String,
    phone: String,
    registering: Boolean,
    onAuthenticate: (String, String, String, String, String, Boolean) -> Boolean,
    onError: (String) -> Unit
) {
    if (!email.contains('@') || email.substringAfter('@').let { it.isBlank() || !it.contains('.') }) {
        onError("Enter a valid email address.")
    } else if (password.isBlank()) {
        onError("Enter your password.")
    } else if (registering && (shopName.isBlank() || operatorName.isBlank())) {
        onError("Enter the shop name and operator name.")
    } else if (registering && password.length < 8) {
        onError("Password must be at least 8 characters.")
    } else {
        onError("")
        onAuthenticate(
            shopName.trim(),
            operatorName.trim(),
            email.trim(),
            password,
            phone.trim(),
            registering
        )
    }
}
