package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.privprint.data.model.ColorMode
import com.example.privprint.data.model.DuplexMode
import com.example.privprint.data.model.PaperSize
import com.example.privprint.data.model.PrintJob
import com.example.privprint.data.model.PrintJobStatus
import com.example.privprint.service.printer.AndroidPrintAdapter
import com.example.privprint.service.printer.HardwareJobStatus
import com.example.privprint.service.printer.NetworkPrinterAdapter
import com.example.privprint.service.printer.PrintSubmissionResult
import com.example.privprint.service.printer.PrinterHardwareStatus
import com.example.privprint.service.printer.TestPrinterAdapter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrinterAdapterTest {

    private fun createSampleJob(jobId: String): PrintJob {
        return PrintJob(
            jobId = jobId,
            sessionId = "SES-TEST",
            shopId = "SHOP-101",
            shopName = "Apex Print",
            documentName = "Contract.pdf",
            documentSizeBytes = 1024,
            pageCount = 3,
            copiesAuthorized = 1,
            copiesPrinted = 0,
            colorMode = ColorMode.BLACK_AND_WHITE,
            paperSize = PaperSize.A4,
            orientation = com.example.privprint.data.model.Orientation.PORTRAIT,
            duplexMode = DuplexMode.SINGLE_SIDED,
            status = PrintJobStatus.QUEUED,
            encryptionAlgorithm = "AES-256-GCM",
            ivHex = "0102030405060708090a0b0c",
            keyFingerprint = "KEYFP123",
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 900_000
        )
    }

    @Test
    fun testTestPrinterAdapterSubmissionAndStatus() = runBlocking {
        val adapter = TestPrinterAdapter()
        val job = createSampleJob("JOB-TEST-01")
        val samplePayload = "DECRYPTED_PAYLOAD_TEST".toByteArray()

        val submission = adapter.submitJob(job, samplePayload)
        assertTrue(submission is PrintSubmissionResult.Success)
        val success = submission as PrintSubmissionResult.Success
        assertEquals(3, success.pagesSpooling)

        val status = adapter.getJobStatus(success.hardwareJobId)
        assertEquals(HardwareJobStatus.COMPLETED, status)
    }

    @Test
    fun testTestPrinterAdapterFailureHandling() = runBlocking {
        val adapter = TestPrinterAdapter(simulatedFailure = true)
        val job = createSampleJob("JOB-TEST-FAIL")
        val samplePayload = "DECRYPTED_PAYLOAD_FAIL".toByteArray()

        val submission = adapter.submitJob(job, samplePayload)
        assertTrue(submission is PrintSubmissionResult.Failure)
        assertEquals("TEST_ERROR", (submission as PrintSubmissionResult.Failure).errorCode)
    }

    @Test
    fun testAndroidPrintAdapterSubmission() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val adapter = AndroidPrintAdapter(context)
        val job = createSampleJob("JOB-ANDROID-01")
        val samplePayload = "ANDROID_PRINT_SPOOL_DATA".toByteArray()

        val submission = adapter.submitJob(job, samplePayload)
        assertTrue(submission is PrintSubmissionResult.Success)
        val success = submission as PrintSubmissionResult.Success
        assertTrue(success.hardwareJobId.startsWith("HW-JOB-"))
        assertEquals(3, success.pagesSpooling)
    }

    @Test
    fun testNetworkPrinterAdapterDiscovery() = runBlocking {
        val adapter = NetworkPrinterAdapter()
        val devices = adapter.discover()
        assertTrue(devices.isNotEmpty())
        assertEquals("NET-XEROX-01", devices.first().id)
    }
}
