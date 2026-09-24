package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.privprint.data.local.CopyIncrementResult
import com.example.privprint.data.local.PrintJobEntity
import com.example.privprint.data.local.PrivPrintDatabase
import com.example.privprint.data.model.ColorMode
import com.example.privprint.data.model.DuplexMode
import com.example.privprint.data.model.PaperSize
import com.example.privprint.data.model.PrintJobStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CopyConcurrencyTest {

    private lateinit var database: PrivPrintDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PrivPrintDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testTenConcurrentRequestsWithTwoAuthorizedCopiesOnlyAllowsTwo() = runBlocking {
        val jobId = "PRV-CONCURRENT-01"
        val now = System.currentTimeMillis()

        // Setup job authorized for exactly 2 copies
        val job = PrintJobEntity(
            jobId = jobId,
            sessionId = "SES-CONCURRENT",
            shopId = "SHOP-101",
            shopName = "Apex Print Center",
            documentName = "Strict_NDA.pdf",
            documentSizeBytes = 2048,
            pageCount = 2,
            copiesAuthorized = 2,
            copiesPrinted = 0,
            colorMode = ColorMode.BLACK_AND_WHITE.name,
            paperSize = PaperSize.A4.name,
            orientation = "PORTRAIT",
            duplexMode = DuplexMode.SINGLE_SIDED.name,
            status = PrintJobStatus.QUEUED.name,
            encryptionAlgorithm = "AES-256-GCM",
            ivHex = "0102030405060708090a0b0c",
            keyFingerprint = "KEY_FP_CONCURRENT",
            createdAt = now,
            expiresAt = now + 900_000,
            completedAt = null,
            failureReason = null
        )
        database.privPrintDao().insertJob(job)

        val successCount = AtomicInteger(0)
        val limitReachedCount = AtomicInteger(0)

        // Launch 10 concurrent requests simultaneously across Dispatchers.IO
        val concurrentTasks = (1..10).map {
            async(Dispatchers.IO) {
                val result = database.privPrintDao().incrementCopyCountAtomic(jobId)
                when (result) {
                    is CopyIncrementResult.Success -> successCount.incrementAndGet()
                    is CopyIncrementResult.LimitReached -> limitReachedCount.incrementAndGet()
                    is CopyIncrementResult.JobNotFound -> {}
                }
            }
        }

        concurrentTasks.awaitAll()

        // Strict validation: Exactly 2 succeed, exactly 8 rejected!
        assertEquals("Exactly 2 copies should have succeeded", 2, successCount.get())
        assertEquals("Exactly 8 requests should have been rejected with LimitReached", 8, limitReachedCount.get())

        // Confirm database record matches
        val finalJob = database.privPrintDao().getJobById(jobId)
        assertEquals(2, finalJob?.copiesPrinted)
        assertEquals("COMPLETED", finalJob?.status)
    }
}
