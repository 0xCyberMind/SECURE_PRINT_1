package com.example.privprint.ui.shop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.SwitchAccount
import androidx.compose.material3.TextButton
import com.example.privprint.ui.AppMode
import com.example.privprint.data.model.PrintJob
import com.example.privprint.data.model.PrintJobStatus
import com.example.privprint.data.model.Printer
import com.example.privprint.data.model.Shop
import com.example.privprint.service.PrintingProgressState
import com.example.privprint.ui.AuthShop
import com.example.privprint.ui.ShopScreen
import com.example.privprint.ui.components.CornerRadiusButton
import com.example.privprint.ui.components.PrivPrintCard
import com.example.privprint.ui.components.PrivPrintEmptyState
import com.example.privprint.ui.components.PrivPrintOutlinedButton
import com.example.privprint.ui.components.PrivPrintPrimaryButton
import com.example.privprint.ui.components.PrivPrintSectionHeader
import com.example.privprint.ui.components.PrivPrintStatusBadge
import com.example.privprint.ui.components.PRIVATE_DOCUMENT_LABEL
import com.example.privprint.ui.components.maskedPhoneNumber
import com.example.privprint.ui.components.QrCodeCanvas

@Composable
fun ShopDashboardScreen(
    currentShop: Shop,
    queue: List<PrintJob>,
    printers: List<Printer>,
    printProgress: PrintingProgressState,
    currentShopAuth: AuthShop? = null,
    serverUrl: String = "http://localhost:8888",
    autoPrintEnabled: Boolean = true,
    onToggleAutoPrint: (Boolean) -> Unit = {},
    onAcceptAndPrint: (PrintJob) -> Unit = {},
    onNavigate: (ShopScreen) -> Unit,
    onPrintNext: () -> Unit,
    onPrintJob: (PrintJob) -> Unit,
    onCancelJob: (String) -> Unit,
    onOpenLogin: (AppMode?) -> Unit = {},
    onLogout: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val activeJob = queue.firstOrNull { it.status == PrintJobStatus.PRINTING }
        ?: queue.firstOrNull { it.status == PrintJobStatus.QUEUED }
    val isPrinting = printProgress.isPrinting || activeJob?.status == PrintJobStatus.PRINTING
    val defaultPrinter = printers.firstOrNull { it.isDefault } ?: printers.firstOrNull()

    val waitingCount = queue.count { it.status == PrintJobStatus.QUEUED || it.status == PrintJobStatus.PREPARING }
    val completedCount = queue.count { it.status == PrintJobStatus.COMPLETED }
    val failedCount = queue.count { it.status == PrintJobStatus.FAILED }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Station Operator Identity Banner
        if (currentShopAuth != null) {
            item {
                PrivPrintCard(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    elevation = 0.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "Station Operator",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${maskedPhoneNumber(currentShopAuth.operatorPhone)} • OTP Verified",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { onOpenLogin(null) }) {
                                Icon(
                                    imageVector = Icons.Default.SwitchAccount,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Switch Login",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            TextButton(onClick = onLogout) {
                                Icon(
                                    imageVector = Icons.Default.ExitToApp,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Sign Out",
                                    color = MaterialTheme.colorScheme.error,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }

        // Shop Permanent Counter QR Card
        item {
            PrivPrintCard(
                onClick = { onNavigate(ShopScreen.PERMANENT_QR) },
                elevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(64.dp)) {
                        QrCodeCanvas(payload = currentShop.permanentQrPayload)
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = currentShop.name,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Verified",
                                tint = Color(0xFF059669),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Text(
                            text = "Counter Permanent QR • ID: ${currentShop.id}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Tap to display counter signage",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = "Expand QR",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Windows PC Desktop Station & Direct Printer Card
        item {
            PrivPrintCard(
                onClick = { onNavigate(ShopScreen.WINDOWS_STATION) },
                containerColor = MaterialTheme.colorScheme.surface,
                elevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.DesktopWindows,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Windows PC Station & Direct Spooler",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF059669))
                                )
                            }
                            Text(
                                text = "Open on Windows: $serverUrl",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = "Open Windows Station Settings",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Direct Auto-Print on Accept Quick Toggle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = null,
                                tint = if (autoPrintEnabled) Color(0xFF059669) else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Direct Auto-Print on Order Accept",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Switch(
                            checked = autoPrintEnabled,
                            onCheckedChange = onToggleAutoPrint,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    }
                }
            }
        }

        // Operational Status Metrics (Printer Online, Queue count)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Printer Status Card
                PrivPrintCard(
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigate(ShopScreen.PRINTERS) }
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Printer",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when {
                                            defaultPrinter == null -> Color.Gray
                                            defaultPrinter.status == com.example.privprint.data.model.PrinterStatus.READY -> Color(0xFF059669)
                                            else -> Color(0xFFDC2626)
                                        }
                                    )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = when {
                                    defaultPrinter == null -> "Not Configured"
                                    defaultPrinter.status == com.example.privprint.data.model.PrinterStatus.READY -> "Online"
                                    else -> "Offline"
                                },
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = defaultPrinter?.name ?: "Tap to add printer",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = if (defaultPrinter != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Queue Count Card
                PrivPrintCard(
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigate(ShopScreen.QUEUE) }
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Queue",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "$waitingCount jobs waiting",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "$completedCount done • $failedCount failed",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Active Print Execution Card
        item {
            PrivPrintCard(
                containerColor = MaterialTheme.colorScheme.surface,
                elevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Active Print Execution",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (activeJob != null) {
                            PrivPrintStatusBadge(status = activeJob.status)
                        }
                    }

                    if (activeJob != null) {
                        Column {
                            Text(
                                text = "Job #${activeJob.jobId.takeLast(4)} • $PRIVATE_DOCUMENT_LABEL",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${activeJob.pageCount} pages • ${activeJob.copiesAuthorized} copies authorized",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        val progressVal = if (printProgress.totalPages > 0) {
                            printProgress.currentPage.toFloat() / printProgress.totalPages.toFloat()
                        } else 0f

                        LinearProgressIndicator(
                            progress = { progressVal.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (activeJob.status != PrintJobStatus.PRINTING) {
                                PrivPrintPrimaryButton(
                                    text = "Accept & Direct Print",
                                    icon = Icons.Default.Print,
                                    onClick = { onAcceptAndPrint(activeJob) },
                                    modifier = Modifier.weight(1.3f)
                                )
                            }
                            PrivPrintOutlinedButton(
                                text = "Cancel Job",
                                icon = Icons.Default.Stop,
                                onClick = { onCancelJob(activeJob.jobId) },
                                modifier = Modifier.weight(0.9f)
                            )
                        }
                    } else {
                        Text(
                            text = "No active print job currently running.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        PrivPrintPrimaryButton(
                            text = "Print Next in Queue",
                            icon = Icons.Default.PlayArrow,
                            onClick = onPrintNext,
                            enabled = waitingCount > 0,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        // Secondary Shortcuts: Full Queue, Printers, Audit
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PrivPrintOutlinedButton(
                    text = "Full Queue ($waitingCount)",
                    icon = Icons.Default.ListAlt,
                    onClick = { onNavigate(ShopScreen.QUEUE) },
                    modifier = Modifier.weight(1f)
                )
                PrivPrintOutlinedButton(
                    text = "Security Audit",
                    icon = Icons.Default.Security,
                    onClick = { onNavigate(ShopScreen.AUDIT) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Recent Queue Preview
        item {
            PrivPrintSectionHeader(
                title = "Print Queue",
                actionText = "Manage Queue",
                onActionClick = { onNavigate(ShopScreen.QUEUE) }
            )
        }

        val queuePreview = queue.filter { it.status == PrintJobStatus.QUEUED || it.status == PrintJobStatus.PRINTING }
        if (queuePreview.isEmpty()) {
            item {
                PrivPrintCard(modifier = Modifier.fillMaxWidth()) {
                    PrivPrintEmptyState(
                        title = "Queue is clear",
                        subtitle = "Incoming customer print jobs will appear here in real-time.",
                        icon = Icons.Default.Print
                    )
                }
            }
        } else {
            items(queuePreview.take(4)) { job ->
                PrivPrintCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "#${job.jobId.takeLast(4)} • $PRIVATE_DOCUMENT_LABEL",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${job.pageCount} pages • ${job.copiesAuthorized} copies",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (job.status == PrintJobStatus.QUEUED || job.status == PrintJobStatus.PREPARING) {
                            Button(
                                onClick = { onAcceptAndPrint(job) },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF059669),
                                    contentColor = Color.White
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Print,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Accept & Print", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            PrivPrintStatusBadge(status = job.status)
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
