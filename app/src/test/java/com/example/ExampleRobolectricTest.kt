package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.privprint.data.crypto.CryptoEngine
import com.example.privprint.data.local.CopyIncrementResult
import com.example.privprint.data.local.PrintJobEntity
import com.example.privprint.data.local.PrivPrintDatabase
import com.example.privprint.data.model.ColorMode
import com.example.privprint.data.model.DuplexMode
import com.example.privprint.data.model.PaperSize
import com.example.privprint.data.model.PrintJobStatus
import com.example.privprint.data.model.PrintSettings
import com.example.privprint.data.model.Shop
import com.example.privprint.data.repository.PrivPrintRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    private lateinit var database: PrivPrintDatabase
    private lateinit var repository: PrivPrintRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PrivPrintDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = PrivPrintRepository(database.privPrintDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testAppNameResourceMatchesPrivPrint() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("PrivPrint", appName)
    }

    @Test
    fun testAes256GcmEncryptionAndDecryption() {
        val samplePlaintext = "CONFIDENTIAL_XEROX_PAYLOAD_TEST_DATA".toByteArray(Charsets.UTF_8)
        val result = CryptoEngine.encryptDocument(samplePlaintext)

        assertNotNull(result.ciphertext)
        assertEquals(12, result.iv.size)
        assertTrue(result.keyFingerprint.isNotBlank())

        val decrypted = CryptoEngine.decryptDocument(
            ciphertext = result.ciphertext,
            iv = result.iv,
            keyBytes = result.ephemeralKey
        )

        assertEquals("CONFIDENTIAL_XEROX_PAYLOAD_TEST_DATA", String(decrypted, Charsets.UTF_8))
    }

    @Test
    fun testShopQrPayloadParsing() {
        val payload = Shop.createQrPayload("SHOP-101", "Apex Campus Xerox & Print")
        val parsed = repository.parseShopQrPayload(payload)

        assertNotNull(parsed)
        assertEquals("SHOP-101", parsed?.first)
        assertEquals("Apex Campus Xerox & Print", parsed?.second)
    }

    @Test
    fun testAtomicCopyEnforcementRejectsThirdCopyWhenAuthorizedForTwo() = runBlocking {
        val jobId = "PRV-2026-TEST01"
        val now = System.currentTimeMillis()

        val job = PrintJobEntity(
            jobId = jobId,
            sessionId = "SES-TEST",
            shopId = "SHOP-101",
            shopName = "Apex Xerox",
            documentName = "Confidential_Resume.pdf",
            documentSizeBytes = 1024,
            pageCount = 3,
            copiesAuthorized = 2,
            copiesPrinted = 0,
            colorMode = ColorMode.BLACK_AND_WHITE.name,
            paperSize = PaperSize.A4.name,
            orientation = "PORTRAIT",
            duplexMode = DuplexMode.SINGLE_SIDED.name,
            status = PrintJobStatus.QUEUED.name,
            encryptionAlgorithm = "AES-256-GCM",
            ivHex = "0102030405060708090a0b0c",
            keyFingerprint = "FINGERPRINT_123",
            createdAt = now,
            expiresAt = now + 900_000,
            completedAt = null,
            failureReason = null
        )

        database.privPrintDao().insertJob(job)

        // Consume copy 1
        val res1 = database.privPrintDao().incrementCopyCountAtomic(jobId)
        assertTrue(res1 is CopyIncrementResult.Success)
        assertEquals(1, (res1 as CopyIncrementResult.Success).currentCopies)
        assertEquals(false, res1.isCompleted)

        // Consume copy 2
        val res2 = database.privPrintDao().incrementCopyCountAtomic(jobId)
        assertTrue(res2 is CopyIncrementResult.Success)
        assertEquals(2, (res2 as CopyIncrementResult.Success).currentCopies)
        assertEquals(true, res2.isCompleted)

        // Attempt unauthorized copy 3 -> MUST strictly return LimitReached
        val res3 = database.privPrintDao().incrementCopyCountAtomic(jobId)
        assertTrue("Expected LimitReached but got $res3", res3 is CopyIncrementResult.LimitReached)
        assertEquals(2, (res3 as CopyIncrementResult.LimitReached).authorized)
    }

    @Test
    fun testMainActivityLaunchesSuccessfully() {
        val controller = org.robolectric.Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertNotNull(activity)
    }
}
