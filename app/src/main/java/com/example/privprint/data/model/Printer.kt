package com.example.privprint.data.model

enum class PrinterStatus(val label: String) {
    READY("Ready"),
    PRINTING("Printing"),
    PAPER_JAM("Paper Jam"),
    LOW_INK("Low Toner/Ink"),
    OFFLINE("Offline")
}

enum class PaperTrayStatus(val label: String) {
    FULL("Tray Full"),
    LOW("Tray Low (< 50 sheets)"),
    EMPTY("Tray Empty")
}

data class Printer(
    val id: String,
    val shopId: String,
    val name: String,
    val model: String,
    val isDefault: Boolean,
    val status: PrinterStatus,
    val paperStatus: PaperTrayStatus,
    val tonerLevelPercent: Int,
    val totalPrintedLifetime: Int
)
