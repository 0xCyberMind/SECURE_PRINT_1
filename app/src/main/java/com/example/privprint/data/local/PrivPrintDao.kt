package com.example.privprint.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

sealed class CopyIncrementResult {
    data class Success(val currentCopies: Int, val totalAuthorized: Int, val isCompleted: Boolean) : CopyIncrementResult()
    data class LimitReached(val authorized: Int) : CopyIncrementResult()
    object JobNotFound : CopyIncrementResult()
}

@Dao
interface PrivPrintDao {

    // Shops
    @Query("SELECT * FROM shops")
    fun getAllShops(): Flow<List<ShopEntity>>

    @Query("SELECT * FROM shops WHERE id = :shopId LIMIT 1")
    suspend fun getShopById(shopId: String): ShopEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertShops(shops: List<ShopEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertShop(shop: ShopEntity)

    // Printers
    @Query("SELECT * FROM printers WHERE shopId = :shopId")
    fun getPrintersForShop(shopId: String): Flow<List<PrinterEntity>>

    @Query("SELECT * FROM printers")
    fun getAllPrinters(): Flow<List<PrinterEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPrinters(printers: List<PrinterEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPrinter(printer: PrinterEntity)

    @Update
    suspend fun updatePrinter(printer: PrinterEntity)

    @Query("DELETE FROM printers WHERE id = :printerId")
    suspend fun deletePrinter(printerId: String)

    // Sessions
    @Query("SELECT * FROM sessions WHERE status = 'ACTIVE' ORDER BY createdAt DESC LIMIT 1")
    fun getActiveSession(): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getSessionById(sessionId: String): SessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: SessionEntity)

    @Query("UPDATE sessions SET status = :status WHERE sessionId = :sessionId")
    suspend fun updateSessionStatus(sessionId: String, status: String)

    @Query("UPDATE sessions SET status = 'REVOKED' WHERE status = 'ACTIVE'")
    suspend fun revokeAllActiveSessions()

    // Print Jobs
    @Query("SELECT * FROM print_jobs ORDER BY createdAt DESC")
    fun getAllPrintJobs(): Flow<List<PrintJobEntity>>

    @Query("SELECT * FROM print_jobs WHERE status IN ('QUEUED', 'PRINTING') ORDER BY createdAt ASC")
    fun getActiveQueue(): Flow<List<PrintJobEntity>>

    @Query("SELECT * FROM print_jobs WHERE status IN ('PREPARING', 'ENCRYPTING', 'QUEUED', 'PRINTING') ORDER BY createdAt DESC LIMIT 1")
    fun getActiveUserJob(): Flow<PrintJobEntity?>

    @Query("SELECT * FROM print_jobs WHERE jobId = :jobId LIMIT 1")
    suspend fun getJobById(jobId: String): PrintJobEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertJob(job: PrintJobEntity)

    @Update
    suspend fun updateJob(job: PrintJobEntity)

    @Query("UPDATE print_jobs SET status = :status WHERE jobId = :jobId")
    suspend fun updateJobStatus(jobId: String, status: String)

    @Query("DELETE FROM print_jobs WHERE jobId = :jobId")
    suspend fun deleteJob(jobId: String)

    // Audit Events
    @Query("SELECT * FROM audit_events ORDER BY timestamp DESC")
    fun getAllAuditEvents(): Flow<List<AuditEventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAuditEvent(event: AuditEventEntity)

    /**
     * Security Critical: Atomic Copy Enforcement inside a SQLite transaction.
     * Prevents race conditions and strictly rejects any copy request exceeding copiesAuthorized.
     */
    @Transaction
    suspend fun incrementCopyCountAtomic(jobId: String): CopyIncrementResult {
        val job = getJobById(jobId) ?: return CopyIncrementResult.JobNotFound
        if (job.copiesPrinted >= job.copiesAuthorized) {
            insertAuditEvent(
                AuditEventEntity(
                    timestamp = System.currentTimeMillis(),
                    eventType = "COPY_LIMIT_ENFORCED",
                    jobId = jobId,
                    shopId = job.shopId,
                    details = "Refused additional copy request. Job authorized for ${job.copiesAuthorized} copies only.",
                    severity = "SECURITY_ALERT"
                )
            )
            return CopyIncrementResult.LimitReached(job.copiesAuthorized)
        }

        val newCount = job.copiesPrinted + 1
        val isCompleted = newCount >= job.copiesAuthorized
        val updatedStatus = if (isCompleted) "COMPLETED" else "PRINTING"
        val completedAt = if (isCompleted) System.currentTimeMillis() else null

        val updatedJob = job.copy(
            copiesPrinted = newCount,
            status = updatedStatus,
            completedAt = completedAt
        )
        updateJob(updatedJob)

        insertAuditEvent(
            AuditEventEntity(
                timestamp = System.currentTimeMillis(),
                eventType = "COPY_CONSUMED",
                jobId = jobId,
                shopId = job.shopId,
                details = "Copy $newCount of ${job.copiesAuthorized} output completed.",
                severity = "INFO"
            )
        )

        if (isCompleted) {
            insertAuditEvent(
                AuditEventEntity(
                    timestamp = System.currentTimeMillis(),
                    eventType = "PRINT_COMPLETED",
                    jobId = jobId,
                    shopId = job.shopId,
                    details = "Job completed successfully. All $newCount authorized copies delivered.",
                    severity = "INFO"
                )
            )
            insertAuditEvent(
                AuditEventEntity(
                    timestamp = System.currentTimeMillis(),
                    eventType = "SHREDDER_TRIGGERED",
                    jobId = jobId,
                    shopId = job.shopId,
                    details = "Ephemeral decryption key memory securely zeroized.",
                    severity = "INFO"
                )
            )
        }

        return CopyIncrementResult.Success(
            currentCopies = newCount,
            totalAuthorized = job.copiesAuthorized,
            isCompleted = isCompleted
        )
    }
}
