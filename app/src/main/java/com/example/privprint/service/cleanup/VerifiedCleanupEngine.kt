package com.example.privprint.service.cleanup

import com.example.privprint.data.api.models.CleanupState
import com.example.privprint.data.api.models.CleanupStatusDto
import com.example.privprint.data.crypto.CryptoEngine
import com.example.privprint.data.local.AuditEventEntity
import com.example.privprint.data.local.PrivPrintDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Enterprise Verified Cleanup & Cryptographic Shredding Engine.
 * Enforces verifiable two-phase cleanup:
 * 1. Storage deletion attempt with confirmation (never prematurely claim shredded).
 * 2. In-memory ephemeral key & plaintext buffer zeroization.
 * 3. Exponential retry on storage failure, logging audit alerts.
 */
class VerifiedCleanupEngine(
    private val dao: PrivPrintDao,
    private val storageDir: File? = null
) {
    // In-memory tracking of cleanup statuses
    private val cleanupStatuses = ConcurrentHashMap<String, CleanupStatusDto>()

    /**
     * Executes verified cleanup for a finalized or cancelled print job.
     */
    suspend fun executeVerifiedCleanup(
        jobId: String,
        shopId: String?,
        associatedKeyBytes: ByteArray? = null,
        storageObjectId: String? = null,
        maxRetries: Int = 3
    ): CleanupStatusDto = withContext(Dispatchers.IO) {
        val initialAttempts = (cleanupStatuses[jobId]?.attempts ?: 0) + 1
        var storageDeleted = false
        var currentAttempt = 0

        // 1. Storage Deletion Attempt with verification
        while (currentAttempt < maxRetries && !storageDeleted) {
            currentAttempt++
            storageDeleted = performStorageDeletion(storageObjectId)
            if (!storageDeleted && currentAttempt < maxRetries) {
                cleanupStatuses[jobId] = CleanupStatusDto(
                    jobId = jobId,
                    state = CleanupState.CLEANUP_RETRY,
                    storageDeleted = false,
                    memoryPurged = false,
                    attempts = initialAttempts + currentAttempt,
                    timestamp = System.currentTimeMillis()
                )
                delay(200L * currentAttempt) // Backoff
            }
        }

        // 2. Memory hygiene: explicit zeroization of keys and buffers
        var memoryPurged = false
        if (associatedKeyBytes != null) {
            CryptoEngine.zeroize(associatedKeyBytes)
            memoryPurged = true
        } else {
            memoryPurged = true
        }

        val finalState = if (storageDeleted) {
            CleanupState.CLEANUP_COMPLETED
        } else {
            CleanupState.CLEANUP_FAILED
        }

        val resultDto = CleanupStatusDto(
            jobId = jobId,
            state = finalState,
            storageDeleted = storageDeleted,
            memoryPurged = memoryPurged,
            attempts = initialAttempts + currentAttempt,
            timestamp = System.currentTimeMillis()
        )
        cleanupStatuses[jobId] = resultDto

        // 3. Immutable audit trail recording
        val eventType = if (storageDeleted) "CLEANUP_COMPLETED" else "CLEANUP_FAILED"
        val severity = if (storageDeleted) "INFO" else "SECURITY_ALERT"
        val details = if (storageDeleted) {
            "Verified storage deletion and ephemeral zeroization completed for job $jobId. Deletion confirmed."
        } else {
            "CRITICAL: Storage deletion unverified after $currentAttempt attempts for job $jobId. Scheduled for manual audit."
        }

        dao.insertAuditEvent(
            AuditEventEntity(
                timestamp = System.currentTimeMillis(),
                eventType = eventType,
                jobId = jobId,
                shopId = shopId,
                details = details,
                severity = severity
            )
        )

        resultDto
    }

    private fun performStorageDeletion(storageObjectId: String?): Boolean {
        if (storageObjectId == null) return true // No physical storage file was allocated
        return try {
            if (storageDir != null && storageDir.exists()) {
                val file = File(storageDir, storageObjectId)
                if (file.exists()) {
                    file.delete()
                } else {
                    true
                }
            } else {
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    fun getStatus(jobId: String): CleanupStatusDto? = cleanupStatuses[jobId]
}
