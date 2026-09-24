package com.example.privprint.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.privprint.data.model.AuditEvent
import com.example.privprint.data.model.AuditEventType
import com.example.privprint.data.model.AuditSeverity
import com.example.privprint.data.model.ColorMode
import com.example.privprint.data.model.DuplexMode
import com.example.privprint.data.model.PaperSize
import com.example.privprint.data.model.PaperTrayStatus
import com.example.privprint.data.model.PrintJob
import com.example.privprint.data.model.PrintJobStatus
import com.example.privprint.data.model.PrintSession
import com.example.privprint.data.model.Printer
import com.example.privprint.data.model.PrinterStatus
import com.example.privprint.data.model.SessionStatus
import com.example.privprint.data.model.Shop

@Entity(tableName = "shops")
data class ShopEntity(
    @PrimaryKey val id: String,
    val name: String,
    val address: String,
    val permanentQrPayload: String,
    val isVerified: Boolean,
    val isOnline: Boolean,
    val supportedColor: Boolean,
    val supportedDuplex: Boolean
) {
    fun toDomain(queueCount: Int = 0): Shop = Shop(
        id = id,
        name = name,
        address = address,
        permanentQrPayload = permanentQrPayload,
        isVerified = isVerified,
        isOnline = isOnline,
        supportedColor = supportedColor,
        supportedDuplex = supportedDuplex,
        queueCount = queueCount
    )
}

@Entity(tableName = "printers")
data class PrinterEntity(
    @PrimaryKey val id: String,
    val shopId: String,
    val name: String,
    val model: String,
    val isDefault: Boolean,
    val status: String,
    val paperStatus: String,
    val tonerLevelPercent: Int,
    val totalPrintedLifetime: Int
) {
    fun toDomain(): Printer = Printer(
        id = id,
        shopId = shopId,
        name = name,
        model = model,
        isDefault = isDefault,
        status = runCatching { PrinterStatus.valueOf(status) }.getOrDefault(PrinterStatus.READY),
        paperStatus = runCatching { PaperTrayStatus.valueOf(paperStatus) }.getOrDefault(PaperTrayStatus.FULL),
        tonerLevelPercent = tonerLevelPercent,
        totalPrintedLifetime = totalPrintedLifetime
    )
}

@Entity(tableName = "print_jobs")
data class PrintJobEntity(
    @PrimaryKey val jobId: String,
    val sessionId: String,
    val shopId: String,
    val shopName: String,
    val documentName: String,
    val documentSizeBytes: Long,
    val pageCount: Int,
    val copiesAuthorized: Int,
    val copiesPrinted: Int,
    val colorMode: String,
    val paperSize: String,
    val orientation: String,
    val duplexMode: String,
    val status: String,
    val encryptionAlgorithm: String,
    val ivHex: String,
    val keyFingerprint: String,
    val createdAt: Long,
    val expiresAt: Long,
    val completedAt: Long?,
    val failureReason: String?
) {
    fun toDomain(): PrintJob = PrintJob(
        jobId = jobId,
        sessionId = sessionId,
        shopId = shopId,
        shopName = shopName,
        documentName = documentName,
        documentSizeBytes = documentSizeBytes,
        pageCount = pageCount,
        copiesAuthorized = copiesAuthorized,
        copiesPrinted = copiesPrinted,
        colorMode = runCatching { ColorMode.valueOf(colorMode) }.getOrDefault(ColorMode.BLACK_AND_WHITE),
        paperSize = runCatching { PaperSize.valueOf(paperSize) }.getOrDefault(PaperSize.A4),
        orientation = runCatching { com.example.privprint.data.model.Orientation.valueOf(orientation) }.getOrDefault(com.example.privprint.data.model.Orientation.PORTRAIT),
        duplexMode = runCatching { DuplexMode.valueOf(duplexMode) }.getOrDefault(DuplexMode.SINGLE_SIDED),
        status = runCatching { PrintJobStatus.valueOf(status) }.getOrDefault(PrintJobStatus.QUEUED),
        encryptionAlgorithm = encryptionAlgorithm,
        ivHex = ivHex,
        keyFingerprint = keyFingerprint,
        createdAt = createdAt,
        expiresAt = expiresAt,
        completedAt = completedAt,
        failureReason = failureReason
    )
}

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val sessionId: String,
    val shopId: String,
    val shopName: String,
    val token: String,
    val status: String,
    val createdAt: Long,
    val expiresAt: Long
) {
    fun toDomain(): PrintSession = PrintSession(
        sessionId = sessionId,
        shopId = shopId,
        shopName = shopName,
        token = token,
        status = runCatching { SessionStatus.valueOf(status) }.getOrDefault(SessionStatus.ACTIVE),
        createdAt = createdAt,
        expiresAt = expiresAt
    )
}

@Entity(tableName = "audit_events")
data class AuditEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val eventType: String,
    val jobId: String?,
    val shopId: String?,
    val details: String,
    val severity: String
) {
    fun toDomain(): AuditEvent = AuditEvent(
        id = id,
        timestamp = timestamp,
        eventType = runCatching { AuditEventType.valueOf(eventType) }.getOrDefault(AuditEventType.SESSION_CREATED),
        jobId = jobId,
        shopId = shopId,
        details = details,
        severity = runCatching { AuditSeverity.valueOf(severity) }.getOrDefault(AuditSeverity.INFO)
    )
}

fun Printer.toEntity(): PrinterEntity = PrinterEntity(
    id = id,
    shopId = shopId,
    name = name,
    model = model,
    isDefault = isDefault,
    status = status.name,
    paperStatus = paperStatus.name,
    tonerLevelPercent = tonerLevelPercent,
    totalPrintedLifetime = totalPrintedLifetime
)

fun Shop.toEntity(): ShopEntity = ShopEntity(
    id = id,
    name = name,
    address = address,
    permanentQrPayload = permanentQrPayload,
    isVerified = isVerified,
    isOnline = isOnline,
    supportedColor = supportedColor,
    supportedDuplex = supportedDuplex
)
