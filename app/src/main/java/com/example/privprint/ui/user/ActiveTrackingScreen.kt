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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.privprint.data.model.PrintJob
import com.example.privprint.data.model.PrintJobStatus
import com.example.privprint.service.PrintingProgressState
import com.example.privprint.ui.components.CornerRadiusButton
import com.example.privprint.ui.components.PrivPrintCard
import com.example.privprint.ui.components.PrivPrintErrorState
import com.example.privprint.ui.components.PrivPrintOutlinedButton
import com.example.privprint.ui.components.PrivPrintPrimaryButton
import com.example.privprint.ui.components.PrivPrintPrivacyIndicator
import com.example.privprint.ui.components.PrivPrintStatusBadge
import com.example.privprint.ui.components.PRIVATE_DOCUMENT_LABEL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveTrackingScreen(
    job: PrintJob?,
    printProgress: PrintingProgressState,
    onStartPrintingSimulation: (PrintJob) -> Unit,
    onCancelJob: (String) -> Unit,
    onBack: () -> Unit,
    onPrintAnother: () -> Unit = onBack,
    modifier: Modifier = Modifier
) {
    val timeFormat = SimpleDateFormat("h:mm a, MMM d", Locale.getDefault())

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (job?.status == PrintJobStatus.COMPLETED) "Print Completed" else "Print Status",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (job == null) {
                PrivPrintErrorState(
                    title = "No active print found",
                    message = "We couldn't locate an active print session. Please check your print history or start a new print.",
                    onRetry = onBack
                )
                return@Column
            }

            // Case 1: SUCCESS SCREEN
            if (job.status == PrintJobStatus.COMPLETED) {
                Spacer(modifier = Modifier.height(16.dp))

                // Large Success Indicator
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFECFDF5)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Success",
                        tint = Color(0xFF059669),
                        modifier = Modifier.size(42.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Print completed",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Your documents have been printed successfully.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Supporting Information Card
                PrivPrintCard(
                    containerColor = MaterialTheme.colorScheme.surface,
                    elevation = 1.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(
                            text = "Print Summary",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        DetailRow(label = "Shop", value = job.shopName)
                        DetailRow(label = "Copies", value = "${job.copiesPrinted} copies")
                        DetailRow(label = "Time", value = timeFormat.format(Date(job.createdAt)))
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Actions: Done & Print another
                PrivPrintPrimaryButton(
                    text = "Done",
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth()
                )

                PrivPrintOutlinedButton(
                    text = "Print Another",
                    icon = Icons.Default.Add,
                    onClick = onPrintAnother,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))
                return@Column
            }

            // Case 2: FAILED
            if (job.status == PrintJobStatus.FAILED) {
                PrivPrintErrorState(
                    title = "Something went wrong",
                    message = "We couldn't complete your print. Please try again.",
                    onRetry = { onStartPrintingSimulation(job) }
                )
                Spacer(modifier = Modifier.height(8.dp))
                PrivPrintOutlinedButton(
                    text = "Back to Home",
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth()
                )
                return@Column
            }

            // Case 3: CANCELLED
            if (job.status == PrintJobStatus.CANCELLED) {
                PrivPrintCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Print cancelled",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "The print session was cancelled and temporary data was removed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                PrivPrintPrimaryButton(
                    text = "Done",
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth()
                )
                return@Column
            }

            // Case 4: REAL-TIME PRINTING OR QUEUED
            PrivPrintPrivacyIndicator(text = "Protected Print Session")

            // Real-Time Print Status Card
            PrivPrintCard(
                containerColor = MaterialTheme.colorScheme.surface,
                elevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = when (job.status) {
                                PrintJobStatus.PREPARING -> "Documents prepared"
                                PrintJobStatus.QUEUED -> "Sending to shop"
                                PrintJobStatus.PRINTING -> "Printing your documents"
                                else -> "Printing"
                            },
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        PrivPrintStatusBadge(status = job.status)
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = job.shopName,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Progress info: page counts & subtle linear progress indicator
                    val progressFraction = if (printProgress.totalPages > 0) {
                        printProgress.currentPage.toFloat() / printProgress.totalPages.toFloat()
                    } else if (job.copiesAuthorized > 0) {
                        job.copiesPrinted.toFloat() / job.copiesAuthorized.toFloat()
                    } else 0.3f

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (printProgress.isPrinting) {
                                "${printProgress.currentPage} / ${printProgress.totalPages} pages"
                            } else {
                                "${job.copiesPrinted} / ${job.copiesAuthorized} copies"
                            },
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${(progressFraction * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    LinearProgressIndicator(
                        progress = { progressFraction.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "Document: $PRIVATE_DOCUMENT_LABEL",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Quick Execution Action for Testing / Operator Simulation
            if (job.status == PrintJobStatus.QUEUED || job.status == PrintJobStatus.PREPARING) {
                PrivPrintPrimaryButton(
                    text = "Start Output on Printer",
                    icon = Icons.Default.PlayArrow,
                    onClick = { onStartPrintingSimulation(job) },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Cancel Job Button
            PrivPrintOutlinedButton(
                text = "Cancel Print",
                icon = Icons.Default.Close,
                onClick = { onCancelJob(job.jobId) },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
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
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
