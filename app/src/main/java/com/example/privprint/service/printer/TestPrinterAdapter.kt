package com.example.privprint.service.printer

import com.example.privprint.data.crypto.CryptoEngine
import com.example.privprint.data.model.PrintJob

/**
 * Controlled Test Adapter used exclusively during automated unit and integration tests.
 * Never instantiated in production release builds.
 */
class TestPrinterAdapter(
    var simulatedFailure: Boolean = false,
    var simulatedHardwareStatus: PrinterHardwareStatus = PrinterHardwareStatus.IDLE
) : PrinterInterface {

    val submittedJobs = mutableListOf<String>()

    override suspend fun discover(): List<PrinterDevice> {
        return listOf(
            PrinterDevice(
                id = "TEST-PRN-01",
                name = "Virtual Test Laser Spooler",
                uri = "test://spooler",
                model = "Test-MFP-Virtual",
                isOnline = true,
                supportsColor = true,
                supportsDuplex = true
            )
        )
    }

    override suspend fun connect(printerId: String): Boolean = !simulatedFailure

    override suspend fun getStatus(printerId: String): PrinterHardwareStatus = simulatedHardwareStatus

    override suspend fun submitJob(
        job: PrintJob,
        decryptedDocumentBytes: ByteArray
    ): PrintSubmissionResult {
        if (simulatedFailure) {
            return PrintSubmissionResult.Failure("Simulated paper jam or offline error", "TEST_ERROR")
        }
        submittedJobs.add(job.jobId)
        CryptoEngine.zeroize(decryptedDocumentBytes)
        return PrintSubmissionResult.Success("TEST-HW-${job.jobId}", job.pageCount)
    }

    override suspend fun getJobStatus(hardwareJobId: String): HardwareJobStatus {
        return if (simulatedFailure) HardwareJobStatus.FAILED else HardwareJobStatus.COMPLETED
    }

    override suspend fun cancelJob(hardwareJobId: String): Boolean = true

    override suspend fun disconnect(printerId: String) {}
}
