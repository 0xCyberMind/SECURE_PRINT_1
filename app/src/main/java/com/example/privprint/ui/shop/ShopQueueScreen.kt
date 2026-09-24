package com.example.privprint.ui.shop

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.privprint.data.model.PrintJob
import com.example.privprint.data.model.PrintJobStatus
import com.example.privprint.ui.components.CornerRadiusButton
import com.example.privprint.ui.components.PrivPrintCard
import com.example.privprint.ui.components.PrivPrintEmptyState
import com.example.privprint.ui.components.PrivPrintOutlinedButton
import com.example.privprint.ui.components.PrivPrintPrimaryButton
import com.example.privprint.ui.components.PrivPrintStatusBadge

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShopQueueScreen(
    queue: List<PrintJob>,
    onPrintJob: (PrintJob) -> Unit,
    onAcceptAndPrint: (PrintJob) -> Unit = onPrintJob,
    onAttemptUnauthorizedCopy: (String) -> Unit,
    onCancelJob: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Shop Print Queue",
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
        if (queue.isEmpty()) {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center
            ) {
                PrivPrintEmptyState(
                    title = "No print jobs in queue",
                    subtitle = "When customers submit documents, they will appear here.",
                    icon = Icons.Default.ListAlt
                )
            }
        } else {
            LazyColumn(
                modifier = modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.background),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(queue) { job ->
                    PrivPrintCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            // Header row: Job ID and Status Badge
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "#${job.jobId.takeLast(4)}",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                PrivPrintStatusBadge(status = job.status)
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Job parameters required by operator: Format, Pages, Copies
                            Text(
                                text = "Format: ${if (job.documentName.endsWith(".pdf", ignoreCase = true)) "PDF" else "IMAGE"}",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${job.pageCount} pages • ${job.copiesAuthorized} copies authorized (${job.copiesPrinted} printed)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            // Action buttons based on state
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (job.status == PrintJobStatus.QUEUED || job.status == PrintJobStatus.PREPARING) {
                                    PrivPrintPrimaryButton(
                                        text = "Accept & Direct Print",
                                        icon = Icons.Default.Print,
                                        onClick = { onAcceptAndPrint(job) },
                                        modifier = Modifier.weight(1.3f)
                                    )
                                    PrivPrintOutlinedButton(
                                        text = "Cancel",
                                        icon = Icons.Default.Close,
                                        onClick = { onCancelJob(job.jobId) },
                                        modifier = Modifier.weight(0.7f)
                                    )
                                } else if (job.status == PrintJobStatus.PRINTING) {
                                    PrivPrintOutlinedButton(
                                        text = "Cancel Active Print",
                                        icon = Icons.Default.Close,
                                        onClick = { onCancelJob(job.jobId) },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                } else if (job.status == PrintJobStatus.COMPLETED) {
                                    // Security verification button: Test enforcing authorized copy limits
                                    OutlinedButton(
                                        onClick = { onAttemptUnauthorizedCopy(job.jobId) },
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Block,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Test Security: Attempt Extra Copy", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
