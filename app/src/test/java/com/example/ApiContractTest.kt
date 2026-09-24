package com.example

import com.example.privprint.data.api.models.CleanupState
import com.example.privprint.data.api.models.CreateJobRequest
import com.example.privprint.data.api.models.CreateSessionRequest
import com.example.privprint.data.api.models.ErrorCode
import com.example.privprint.data.api.models.HealthResponse
import com.example.privprint.data.api.models.InitUploadRequest
import com.example.privprint.data.api.models.JobResponse
import com.example.privprint.data.api.models.LoginRequest
import com.example.privprint.data.api.models.LoginResponse
import com.example.privprint.data.api.models.UserRole
import com.example.privprint.data.model.ColorMode
import com.example.privprint.data.model.DuplexMode
import com.example.privprint.data.model.PaperSize
import com.example.privprint.data.model.PrintJobStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ApiContractTest {

    @Test
    fun testCanonicalApiModelsDataIntegrity() {
        val health = HealthResponse(status = "healthy", apiVersion = "v1")
        assertEquals("healthy", health.status)
        assertEquals("v1", health.apiVersion)

        val loginReq = LoginRequest(
            identity = "shop_operator_1",
            role = UserRole.SHOP_OPERATOR,
            secret = "pass_hash_secure"
        )
        assertEquals(UserRole.SHOP_OPERATOR, loginReq.role)

        val sessionReq = CreateSessionRequest(
            shopId = "SHOP-101",
            pairingNonce = "NONCE-999",
            clientFingerprint = "FP-CLIENT-1"
        )
        assertEquals("SHOP-101", sessionReq.shopId)

        val uploadReq = InitUploadRequest(
            sessionId = "SES-100",
            filename = "Confidential.pdf",
            mimeType = "application/pdf",
            ciphertextSizeBytes = 4096,
            sha256Checksum = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ivHex = "0102030405060708090a0b0c"
        )
        assertEquals("application/pdf", uploadReq.mimeType)

        val jobReq = CreateJobRequest(
            sessionId = "SES-100",
            shopId = "SHOP-101",
            storageObjectId = "OBJ-123",
            documentName = "Confidential.pdf",
            pageCount = 4,
            copiesAuthorized = 2,
            colorMode = ColorMode.BLACK_AND_WHITE,
            paperSize = PaperSize.A4,
            orientation = com.example.privprint.data.model.Orientation.PORTRAIT,
            duplexMode = DuplexMode.SINGLE_SIDED,
            ivHex = "0102030405060708090a0b0c",
            keyFingerprint = "FINGERPRINT_AES"
        )
        assertEquals(2, jobReq.copiesAuthorized)

        val jobResp = JobResponse(
            jobId = "PRV-2026-ABC",
            sessionId = "SES-100",
            shopId = "SHOP-101",
            shopName = "Apex Print",
            documentName = "Confidential.pdf",
            pageCount = 4,
            copiesAuthorized = 2,
            copiesPrinted = 0,
            status = PrintJobStatus.QUEUED,
            cleanupState = CleanupState.CLEANUP_PENDING,
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 900_000
        )
        assertEquals("PRV-2026-ABC", jobResp.jobId)
        assertEquals(CleanupState.CLEANUP_PENDING, jobResp.cleanupState)
    }

    @Test
    fun testStandardizedErrorCodesCompleteness() {
        assertNotNull(ErrorCode.valueOf("AUTH_INVALID"))
        assertNotNull(ErrorCode.valueOf("COPY_LIMIT_REACHED"))
        assertNotNull(ErrorCode.valueOf("SESSION_EXPIRED"))
        assertNotNull(ErrorCode.valueOf("SESSION_REVOKED"))
        assertNotNull(ErrorCode.valueOf("PRINTER_OFFLINE"))
    }
}
