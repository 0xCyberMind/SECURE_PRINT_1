package com.example.privprint.data.model

enum class PrintJobStatus {
    PREPARING,
    ENCRYPTING,
    QUEUED,
    PRINTING,
    COMPLETED,
    FAILED,
    CANCELLED
}

enum class ColorMode(val label: String) {
    BLACK_AND_WHITE("Black & White"),
    COLOR("Color")
}

enum class PaperSize(val label: String) {
    A4("A4 (210 x 297 mm)"),
    LETTER("Letter (8.5 x 11 in)"),
    LEGAL("Legal (8.5 x 14 in)")
}

enum class Orientation(val label: String) {
    PORTRAIT("Portrait"),
    LANDSCAPE("Landscape")
}

enum class DuplexMode(val label: String) {
    SINGLE_SIDED("Single-Sided"),
    DOUBLE_SIDED("Double-Sided (Flip Long Edge)")
}

data class PrintSettings(
    val copies: Int = 1,
    val pageRange: String = "All Pages",
    val colorMode: ColorMode = ColorMode.BLACK_AND_WHITE,
    val paperSize: PaperSize = PaperSize.A4,
    val orientation: Orientation = Orientation.PORTRAIT,
    val duplexMode: DuplexMode = DuplexMode.SINGLE_SIDED,
    val collation: Boolean = true
)

data class PrintJob(
    val jobId: String,
    val sessionId: String,
    val shopId: String,
    val shopName: String,
    val documentName: String,
    val documentSizeBytes: Long,
    val pageCount: Int,
    val copiesAuthorized: Int,
    val copiesPrinted: Int,
    val colorMode: ColorMode,
    val paperSize: PaperSize,
    val orientation: Orientation,
    val duplexMode: DuplexMode,
    val status: PrintJobStatus,
    val encryptionAlgorithm: String = "AES-256-GCM",
    val ivHex: String,
    val keyFingerprint: String,
    val createdAt: Long,
    val expiresAt: Long,
    val completedAt: Long? = null,
    val failureReason: String? = null
)
