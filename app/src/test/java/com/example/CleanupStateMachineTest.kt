package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.privprint.data.api.models.CleanupState
import com.example.privprint.data.local.PrivPrintDatabase
import com.example.privprint.service.cleanup.VerifiedCleanupEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CleanupStateMachineTest {

    private lateinit var database: PrivPrintDatabase
    private lateinit var cleanupEngine: VerifiedCleanupEngine
    private lateinit var testStorageDir: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PrivPrintDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        testStorageDir = File(context.cacheDir, "test_secure_storage")
        testStorageDir.mkdirs()
        cleanupEngine = VerifiedCleanupEngine(database.privPrintDao(), testStorageDir)
    }

    @After
    fun tearDown() {
        database.close()
        testStorageDir.deleteRecursively()
    }

    @Test
    fun testSuccessfulVerifiedStorageDeletionCompletesCleanup() = runBlocking {
        val storageFile = File(testStorageDir, "doc_ciphertext_123.bin")
        storageFile.writeText("ENCRYPTED_STORAGE_PAYLOAD")
        assertTrue(storageFile.exists())

        val ephemeralKey = ByteArray(32) { 0xFF.toByte() }

        val result = cleanupEngine.executeVerifiedCleanup(
            jobId = "JOB-CLEANUP-01",
            shopId = "SHOP-101",
            associatedKeyBytes = ephemeralKey,
            storageObjectId = "doc_ciphertext_123.bin"
        )

        assertEquals(CleanupState.CLEANUP_COMPLETED, result.state)
        assertTrue("Storage file should have been verified deleted", result.storageDeleted)
        assertTrue("Ephemeral key memory must be zeroized", result.memoryPurged)
        assertTrue("Storage file should no longer exist", !storageFile.exists())

        // Verify ephemeral key bytes were zeroized
        assertTrue(ephemeralKey.all { it == 0.toByte() })
    }

    @Test
    fun testCleanupIsIdempotent() = runBlocking {
        val result1 = cleanupEngine.executeVerifiedCleanup(
            jobId = "JOB-IDEMP-01",
            shopId = "SHOP-101",
            storageObjectId = null
        )
        assertEquals(CleanupState.CLEANUP_COMPLETED, result1.state)

        // Second execution should succeed idempotently
        val result2 = cleanupEngine.executeVerifiedCleanup(
            jobId = "JOB-IDEMP-01",
            shopId = "SHOP-101",
            storageObjectId = null
        )
        assertEquals(CleanupState.CLEANUP_COMPLETED, result2.state)
    }
}
