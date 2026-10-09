package com.example.privprint.ui.user

import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.privprint.data.model.ColorMode
import com.example.privprint.data.model.PrintSession
import com.example.privprint.data.model.PrintSettings
import com.example.privprint.data.model.SelectedDocument
import com.example.privprint.data.model.Shop
import com.example.privprint.ui.components.CornerRadiusButton
import com.example.privprint.ui.components.PrivPrintCard
import com.example.privprint.ui.components.PrivPrintErrorState
import com.example.privprint.ui.components.PrivPrintOutlinedButton
import com.example.privprint.ui.components.PrivPrintPrimaryButton
import com.example.privprint.ui.components.PrivPrintPrivacyIndicator
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintConfirmationScreen(
    shop: Shop,
    session: PrintSession?,
    document: SelectedDocument,
    settings: PrintSettings,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    isSubmitting: Boolean = false,
    errorMessage: String? = null
) {
    val pricePerPage = if (settings.colorMode == ColorMode.COLOR) 0.25 else 0.10
    val totalCost = document.pageCount * settings.copies * pricePerPage

    var localSubmitting by remember { mutableStateOf(false) }
    var remainingSeconds by remember { mutableIntStateOf(5) }

    // Synchronize local submitting state with external isSubmitting & errorMessage
    LaunchedEffect(isSubmitting, errorMessage) {
        if (!isSubmitting) {
            localSubmitting = false
        }
    }

    val submitting = isSubmitting || localSubmitting

    // 5-second countdown timer for visual feedback during sending
    LaunchedEffect(submitting) {
        if (submitting) {
            remainingSeconds = 5
            while (remainingSeconds > 1) {
                delay(1000L)
                remainingSeconds -= 1
            }
            delay(1000L)
            remainingSeconds = 0
        } else {
            remainingSeconds = 5
        }
    }

    val buttonText = when {
        !submitting -> if (errorMessage != null) "Retry Secure Print" else "Send Secure Print"
        remainingSeconds > 0 -> "Sending securely… Please wait (${remainingSeconds}s)"
        else -> "Sending securely… Please wait…"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Review your print",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        enabled = !submitting
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            PrivPrintPrivacyIndicator(text = "Protected Transmission")

            // Summary Card
            PrivPrintCard(
                containerColor = MaterialTheme.colorScheme.surface,
                elevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "Print Order Details",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    DetailItem(label = "Shop", value = shop.name)
                    DetailItem(label = "Document", value = document.name)
                    DetailItem(label = "Pages", value = "${document.pageCount} pages")
                    DetailItem(label = "Copies", value = "${settings.copies}")
                    DetailItem(label = "Color mode", value = if (settings.colorMode == ColorMode.COLOR) "Color" else "Black & White")
                    DetailItem(label = "Paper size", value = settings.paperSize.label)

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Estimated Total",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "$${String.format("%.2f", totalCost)}",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Trust & Security Notice
            PrivPrintCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = Color(0xFF059669),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Your documents are encrypted before sending",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (errorMessage != null) {
                PrivPrintErrorState(
                    title = "Print Request Failed",
                    message = errorMessage,
                    onRetry = {
                        if (!submitting) {
                            localSubmitting = true
                            onConfirm()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Action CTAs
            PrivPrintPrimaryButton(
                text = buttonText,
                icon = if (!submitting) (if (errorMessage != null) Icons.Default.Refresh else Icons.Default.Print) else null,
                onClick = {
                    if (!submitting) {
                        localSubmitting = true
                        onConfirm()
                    }
                },
                enabled = !submitting,
                isLoading = submitting,
                modifier = Modifier.fillMaxWidth(),
                testTag = "confirm_print_job_button"
            )

            PrivPrintOutlinedButton(
                text = "Back to Settings",
                onClick = onBack,
                enabled = !submitting,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DetailItem(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
