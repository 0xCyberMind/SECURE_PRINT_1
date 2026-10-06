package com.example.privprint.ui.user

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Clear
import com.example.privprint.data.model.SelectedDocument
import com.example.privprint.ui.components.CornerRadiusButton
import com.example.privprint.ui.components.PrivPrintCard
import com.example.privprint.ui.components.PrivPrintOutlinedButton
import com.example.privprint.ui.components.PrivPrintPrimaryButton
import com.example.privprint.ui.components.PrivPrintSectionHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentPickerScreen(
    onDocumentSelected: (SelectedDocument) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onDocumentsSelected: ((List<SelectedDocument>) -> Unit)? = null
) {
    val context = LocalContext.current
    var selectedDocs by remember { mutableStateOf<List<SelectedDocument>>(emptyList()) }
    var validationError by remember { mutableStateOf<String?>(null) }

    // Multi-File Document Picker
    val docPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri>? ->
        if (!uris.isNullOrEmpty()) {
            val parsedDocs = uris.map { parseDocumentFromUri(context, it) }
            val combined = (selectedDocs + parsedDocs).distinctBy { it.name }
            if (combined.size > 10) {
                validationError = "Maximum 10 files allowed in a single print batch."
                selectedDocs = combined.take(10)
            } else {
                val totalBytes = combined.sumOf { it.sizeBytes }
                if (totalBytes > 50 * 1024 * 1024) {
                    validationError = "Total batch size exceeds 50 MB limit."
                } else {
                    validationError = null
                }
                selectedDocs = combined
            }
        }
    }

    // Sample documents for instant test drive
    val sampleDocuments = remember {
        listOf(
            SelectedDocument(
                name = "Final_Project_Report.pdf",
                mimeType = "application/pdf",
                sizeBytes = 1024 * 740, // 740 KB
                pageCount = 6,
                rawBytes = "PDF-DUMMY-SAMPLE-CONTENT-ACADEMIC-REPORT-740KB".toByteArray()
            ),
            SelectedDocument(
                name = "Study_Notes_Chapter3.pdf",
                mimeType = "application/pdf",
                sizeBytes = 1024 * 320, // 320 KB
                pageCount = 3,
                rawBytes = "PDF-DUMMY-SAMPLE-NOTES-320KB".toByteArray()
            ),
            SelectedDocument(
                name = "Identity_Card_Scan.jpg",
                mimeType = "image/jpeg",
                sizeBytes = 1024 * 180, // 180 KB
                pageCount = 1,
                rawBytes = "IMG-DUMMY-SCAN-DATA-180KB".toByteArray()
            )
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (selectedDocs.size > 1) "Selected Batch (${selectedDocs.size} files)" else "Select Documents",
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
                actions = {
                    if (selectedDocs.isNotEmpty()) {
                        IconButton(onClick = {
                            selectedDocs = emptyList()
                            validationError = null
                        }) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear All"
                            )
                        }
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
            if (validationError != null) {
                PrivPrintCard(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = validationError!!,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // Selected Batch Cards (if documents are chosen)
            if (selectedDocs.isNotEmpty()) {
                val totalBytes = selectedDocs.sumOf { it.sizeBytes }
                val totalPages = selectedDocs.sumOf { it.pageCount }
                val formattedTotalSize = when {
                    totalBytes < 1024 -> "$totalBytes B"
                    totalBytes < 1024 * 1024 -> "${totalBytes / 1024} KB"
                    else -> "%.1f MB".format(totalBytes.toDouble() / (1024 * 1024))
                }

                PrivPrintCard(
                    containerColor = MaterialTheme.colorScheme.surface,
                    elevation = 1.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Ready to Print (${selectedDocs.size} file${if (selectedDocs.size > 1) "s" else ""})",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "$formattedTotalSize total • $totalPages total pages",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Ready",
                                tint = Color(0xFF059669),
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Individual Document Items in Batch
                        selectedDocs.forEachIndexed { index, doc ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (doc.mimeType.contains("pdf")) Icons.Default.PictureAsPdf else Icons.Default.Description,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${index + 1}. ${doc.name}",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${doc.formattedSize} • ${doc.pageCount} page${if (doc.pageCount > 1) "s" else ""}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        selectedDocs = selectedDocs.filterNot { it == doc }
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Remove file",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            PrivPrintOutlinedButton(
                                text = "Add More",
                                icon = Icons.Default.Add,
                                onClick = { docPickerLauncher.launch("*/*") },
                                modifier = Modifier.weight(1f)
                            )

                            PrivPrintPrimaryButton(
                                text = "Continue",
                                icon = Icons.AutoMirrored.Filled.ArrowForward,
                                onClick = {
                                    if (selectedDocs.isNotEmpty()) {
                                        onDocumentsSelected?.invoke(selectedDocs) ?: onDocumentSelected(selectedDocs.first())
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                testTag = "proceed_to_confirmation_button"
                            )
                        }
                    }
                }
            } else {
                // Primary Multi-File Picker Card
                PrivPrintCard(
                    onClick = { docPickerLauncher.launch("*/*") },
                    elevation = 1.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.FileOpen,
                                contentDescription = "Open Documents",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "Choose Document(s) to Print",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Select one or multiple files (PDF, DOCX, Images)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(18.dp))

                        PrivPrintPrimaryButton(
                            text = "Browse Files (Multi-Select)",
                            icon = Icons.Default.AttachFile,
                            onClick = { docPickerLauncher.launch("*/*") },
                            modifier = Modifier.fillMaxWidth(),
                            testTag = "open_system_file_picker_button"
                        )
                    }
                }
            }


            // Quick Sample Documents for test execution
            PrivPrintSectionHeader(title = "Or select a test document:")

            sampleDocuments.forEach { doc ->
                val isSelected = selectedDocs.contains(doc)
                PrivPrintCard(
                    onClick = {
                        if (isSelected) {
                            selectedDocs = selectedDocs.filterNot { it == doc }
                        } else {
                            if (selectedDocs.size < 10) {
                                selectedDocs = selectedDocs + doc
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (doc.mimeType.contains("pdf")) Icons.Default.PictureAsPdf else Icons.Default.Description,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = doc.name,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${doc.formattedSize} • ${doc.pageCount} pages",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                if (isSelected) {
                                    selectedDocs = selectedDocs.filterNot { it == doc }
                                } else {
                                    if (selectedDocs.size < 10) {
                                        selectedDocs = selectedDocs + doc
                                    }
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = if (isSelected) "Remove" else "Add",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isSelected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DocumentInfoRow(label: String, value: String) {
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

private fun parseDocumentFromUri(context: Context, uri: Uri): SelectedDocument {
    var name = "Document"
    var size = 0L

    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (nameIndex != -1) name = cursor.getString(nameIndex) ?: "Document"
            if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
        }
    }

    val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
    val bytes = try {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: "DUMMY-SAMPLE-BYTES".toByteArray()
    } catch (e: Exception) {
        "FALLBACK-DOCUMENT-BYTES".toByteArray()
    }

    val pageCount = if (mimeType.contains("pdf", ignoreCase = true) || name.endsWith(".pdf", ignoreCase = true)) {
        runCatching {
            val temp = File.createTempFile("page_count_", ".pdf", context.cacheDir)
            temp.writeBytes(bytes)
            val pfd = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            val count = renderer.pageCount
            renderer.close()
            pfd.close()
            temp.delete()
            count
        }.getOrDefault(1)
    } else if (mimeType.startsWith("image/") || name.endsWith(".jpg", ignoreCase = true) || name.endsWith(".png", ignoreCase = true) || name.endsWith(".jpeg", ignoreCase = true)) {
        1
    } else {
        1
    }

    return SelectedDocument(
        name = name,
        mimeType = mimeType,
        sizeBytes = if (size > 0) size else bytes.size.toLong(),
        pageCount = pageCount,
        rawBytes = bytes
    )
}
