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
            email = "shop_operator_1@privprint.com",
            password = "pass_hash_secure"
        )
        assertEquals("shop_operator_1@privprint.com", loginReq.email)

        val sessionReq = CreateSessionRequest(
            shopId = "SHOP-101",
            pairingNonce = "NONCE-999",
            clientFingerprint = "FP-CLIENT-1"
        )
        assertEquals("SHOP-101", sessionReq.shopId)

        val uploadReq = InitUploadRequest(
            sessionId = "SES-100",
            filename = "Confidential.pdf",
            fileSizeBytes = 4096,
            mimeType = "application/pdf",
            sha256Hash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ivHex = "0102030405060708090a0b0c",
            keyFingerprint = "FINGERPRINT_AES",
            copiesAuthorized = 2
        )
        assertEquals("application/pdf", uploadReq.mimeType)
        assertEquals(4096, uploadReq.fileSizeBytes)

        val jobReq = CreateJobRequest(
            shopId = "SHOP-101",
            sessionId = "SES-100",
            documentId = "DOC-123",
            pageCount = 4,
            requestedCopies = 2,
            colorMode = "MONOCHROME",
            paperSize = PaperSize.A4.name,
            orientation = com.example.privprint.data.model.Orientation.PORTRAIT.name,
            duplexMode = "SIMPLEX"
        )
        assertEquals(2, jobReq.requestedCopies)

        // Server JobResponse (snake_case JSON) with client compatibility aliases
        val jobResp = JobResponse(
            id = "PRV-2026-ABC",
            shopId = "SHOP-101",
            sessionId = "SES-100",
            documentId = "DOC-456",
            pageCount = 4,
            requestedCopies = 2,
            completedCopies = 0,
            status = PrintJobStatus.QUEUED.name
        )
        assertEquals("PRV-2026-ABC", jobResp.jobId)
        assertEquals(2, jobResp.copiesAuthorized)
        assertEquals(0, jobResp.copiesPrinted)
        assertEquals("QUEUED", jobResp.status)
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
