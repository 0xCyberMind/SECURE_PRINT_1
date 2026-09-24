package com.example.privprint.service.printer

import com.example.privprint.data.model.PrintJob

enum class PrinterHardwareStatus {
    IDLE,
    PRINTING,
    PAPER_JAM,
    TONER_LOW,
    OUT_OF_PAPER,
    OFFLINE,
    ERROR
}

enum class HardwareJobStatus {
    PENDING,
    SPOOLING,
    PRINTING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class PrinterDevice(
    val id: String,
    val name: String,
    val uri: String,
    val model: String,
    val isOnline: Boolean,
    val supportsColor: Boolean,
    val supportsDuplex: Boolean
)

sealed class PrintSubmissionResult {
    data class Success(val hardwareJobId: String, val pagesSpooling: Int) : PrintSubmissionResult()
    data class Failure(val reason: String, val errorCode: String) : PrintSubmissionResult()
}

/**
 * Standard hardware printer interface for PrivPrint.
 * Replaces mock and stub implementations with actual physical / native printer integrations.
 */
interface PrinterInterface {
    suspend fun discover(): List<PrinterDevice>
    suspend fun connect(printerId: String): Boolean
    suspend fun getStatus(printerId: String): PrinterHardwareStatus
    suspend fun submitJob(job: PrintJob, decryptedDocumentBytes: ByteArray): PrintSubmissionResult
    suspend fun getJobStatus(hardwareJobId: String): HardwareJobStatus
    suspend fun cancelJob(hardwareJobId: String): Boolean
    suspend fun disconnect(printerId: String)
}
