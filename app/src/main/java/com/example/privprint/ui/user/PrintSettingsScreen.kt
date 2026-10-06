package com.example.privprint.ui.user

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Remove
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
import androidx.compose.runtime.getValue
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
import com.example.privprint.data.model.DuplexMode
import com.example.privprint.data.model.PaperSize
import com.example.privprint.data.model.PrintSettings
import com.example.privprint.data.model.SelectedDocument
import com.example.privprint.ui.components.CornerRadiusButton
import com.example.privprint.ui.components.PrivPrintCard
import com.example.privprint.ui.components.PrivPrintPrimaryButton
import com.example.privprint.ui.components.PrivPrintSectionHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintSettingsScreen(
    document: SelectedDocument,
    settings: PrintSettings,
    onUpdateCopies: (Int) -> Unit,
    onUpdateColor: (ColorMode) -> Unit,
    onUpdatePaperSize: (PaperSize) -> Unit,
    onUpdateDuplex: (DuplexMode) -> Unit,
    onProceed: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    documents: List<SelectedDocument> = listOf(document)
) {
    var orientation by remember { mutableStateOf("Portrait") }
    var pageRangeOption by remember { mutableStateOf("All Pages") }

    val totalPages = documents.sumOf { it.pageCount }
    val pricePerPage = if (settings.colorMode == ColorMode.COLOR) 0.25 else 0.10
    val totalCost = totalPages * settings.copies * pricePerPage

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Print Settings",
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Selected Document Header
            PrivPrintCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (documents.size > 1) "${documents.size} Documents Selected" else document.name,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val totalBytes = documents.sumOf { it.sizeBytes }
                        val formattedTotalSize = when {
                            totalBytes < 1024 -> "$totalBytes B"
                            totalBytes < 1024 * 1024 -> "${totalBytes / 1024} KB"
                            else -> "%.1f MB".format(totalBytes.toDouble() / (1024 * 1024))
                        }
                        Text(
                            text = if (documents.size > 1) "$formattedTotalSize • $totalPages pages across batch" else "${document.formattedSize} • ${document.pageCount} pages",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Setting 1: Number of Copies
            PrivPrintCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Number of Copies",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Authorized output count",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { onUpdateCopies(-1) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Remove,
                                contentDescription = "Decrease copies",
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Text(
                            text = "${settings.copies}",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )

                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { onUpdateCopies(1) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Increase copies",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            // Setting 2: Color Mode (Color / Black & White)
            PrivPrintCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Color Mode",
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SelectionOptionChip(
                            title = "Black & White",
                            subtitle = "$0.10 / page",
                            isSelected = settings.colorMode == ColorMode.BLACK_AND_WHITE,
                            onClick = { onUpdateColor(ColorMode.BLACK_AND_WHITE) },
                            modifier = Modifier.weight(1f)
                        )
                        SelectionOptionChip(
                            title = "Color",
                            subtitle = "$0.25 / page",
                            isSelected = settings.colorMode == ColorMode.COLOR,
                            onClick = { onUpdateColor(ColorMode.COLOR) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Setting 3: Paper Size
            PrivPrintCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Paper Size",
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PaperSize.entries.forEach { size ->
                            SelectionOptionChip(
                                title = size.label.substringBefore(" ("),
                                subtitle = null,
                                isSelected = settings.paperSize == size,
                                onClick = { onUpdatePaperSize(size) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // Setting 4: Orientation & Duplex
            PrivPrintCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Layout & Sides",
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SelectionOptionChip(
                            title = "Single-Sided",
                            subtitle = null,
                            isSelected = settings.duplexMode == DuplexMode.SINGLE_SIDED,
                            onClick = { onUpdateDuplex(DuplexMode.SINGLE_SIDED) },
                            modifier = Modifier.weight(1f)
                        )
                        SelectionOptionChip(
                            title = "Double-Sided",
                            subtitle = null,
                            isSelected = settings.duplexMode == DuplexMode.DOUBLE_SIDED,
                            onClick = { onUpdateDuplex(DuplexMode.DOUBLE_SIDED) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // PRINT SUMMARY CARD as requested
            PrivPrintCard(
                containerColor = MaterialTheme.colorScheme.surface,
                elevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Print Summary",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    SummaryRow(label = "Document", value = document.name)
                    SummaryRow(label = "Pages", value = "${document.pageCount} pages")
                    SummaryRow(label = "Copies", value = "${settings.copies}")
                    SummaryRow(label = "Color mode", value = if (settings.colorMode == ColorMode.COLOR) "Color" else "Black & White")
                    SummaryRow(label = "Paper size", value = settings.paperSize.label)
                    SummaryRow(label = "Sides", value = if (settings.duplexMode == DuplexMode.SINGLE_SIDED) "Single-sided" else "Double-sided")

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 10.dp),
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
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Primary CTA: Print
            PrivPrintPrimaryButton(
                text = "Print",
                icon = Icons.Default.Print,
                onClick = onProceed,
                modifier = Modifier.fillMaxWidth(),
                testTag = "proceed_to_confirmation_button"
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SelectionOptionChip(
    title: String,
    subtitle: String?,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
            .border(
                width = 1.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable { onClick() }
            .padding(vertical = 10.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
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
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
