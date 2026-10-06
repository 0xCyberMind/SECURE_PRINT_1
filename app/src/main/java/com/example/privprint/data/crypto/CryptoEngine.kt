package com.example.privprint.data.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Arrays
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.spec.MGF1ParameterSpec

/**
 * Production-grade AES-256-GCM Cryptographic Engine.
 *
 * Enforces:
 * - 256-bit AES symmetric keys generated per print job (ephemeral).
 * - Fresh 96-bit (12 bytes) IV / nonce generated per encryption via SecureRandom.
 * - Authenticated encryption with 128-bit GCM authentication tag.
 * - SHA-256 key fingerprinting (the raw key is NEVER logged or stored in DB).
 * - Zeroization utility to wipe plaintext and key material from memory after use.
 */
object CryptoEngine {
    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH_BYTES = 12
    private const val GCM_TAG_LENGTH_BITS = 128

    private val secureRandom = SecureRandom()

    data class EncryptionResult(
        val ciphertext: ByteArray,
        val iv: ByteArray,
        val keyFingerprint: String,
        val ephemeralKey: ByteArray // kept in memory only for the authorized session
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as EncryptionResult
            return ciphertext.contentEquals(other.ciphertext) &&
                    iv.contentEquals(other.iv) &&
                    keyFingerprint == other.keyFingerprint
        }

        override fun hashCode(): Int {
            var result = ciphertext.contentHashCode()
            result = 31 * result + iv.contentHashCode()
            result = 31 * result + keyFingerprint.hashCode()
            return result
        }

        fun zeroizeKey() {
            Arrays.fill(ephemeralKey, 0.toByte())
        }
    }

    /**
     * Generates a fresh 256-bit AES secret key.
     */
    fun generateEphemeralKey(): SecretKey {
        val keyGen = KeyGenerator.getInstance(ALGORITHM)
        keyGen.init(256, secureRandom)
        return keyGen.generateKey()
    }

    /**
     * Computes the SHA-256 hex fingerprint of a key or payload.
     * Safe to log and display in audit trails without exposing the key.
     */
    fun computeSha256Fingerprint(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(bytes)
        return hash.joinToString("") { "%02x".format(it) }.take(16).uppercase()
    }

    /**
     * Computes the full 64-character lowercase SHA-256 hex digest of a payload.
     * Sent to the backend as `sha256_hash` for ciphertext integrity validation.
     */
    fun computeSha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    /**
     * Encrypts document bytes using AES-256-GCM with a fresh random IV.
     */
    fun encryptDocument(
        plaintext: ByteArray,
        key: SecretKey = generateEphemeralKey()
    ): EncryptionResult {
        val iv = ByteArray(GCM_IV_LENGTH_BYTES)
        secureRandom.nextBytes(iv)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec)

        val ciphertext = cipher.doFinal(plaintext)
        val keyBytes = key.encoded
        val fingerprint = computeSha256Fingerprint(keyBytes)

        return EncryptionResult(
            ciphertext = ciphertext,
            iv = iv,
            keyFingerprint = fingerprint,
            ephemeralKey = keyBytes
        )
    }

    /**
     * Decrypts ciphertext using AES-256-GCM with the provided IV and key.
     * Validates authenticity tag.
     */
    fun decryptDocument(
        ciphertext: ByteArray,
        iv: ByteArray,
        keyBytes: ByteArray
    ): ByteArray {
        val keySpec = SecretKeySpec(keyBytes, ALGORITHM)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        return cipher.doFinal(ciphertext)
    }

    /**
     * Wraps a per-document AES key for one registered Windows station.
     */
    fun wrapDocumentKeyForStation(keyBytes: ByteArray, publicKeyBase64: String): String {
        val publicKeyBytes = Base64.getDecoder().decode(publicKeyBase64)
        val publicKey = KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(publicKeyBytes))
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            publicKey,
            OAEPParameterSpec(
                "SHA-256",
                "MGF1",
                MGF1ParameterSpec.SHA256,
                PSource.PSpecified.DEFAULT
            )
        )
        return Base64.getEncoder().encodeToString(cipher.doFinal(keyBytes))
    }

    /**
     * Safely clears sensitive byte arrays in memory.
     */
    fun zeroize(vararg byteArrays: ByteArray?) {
        for (arr in byteArrays) {
            if (arr != null) {
                Arrays.fill(arr, 0.toByte())
            }
        }
    }

    data class BatchFileEncryptionResult(
        val documentName: String,
        val mimeType: String,
        val pageCount: Int,
        val originalSizeBytes: Long,
        val encryptionResult: EncryptionResult
    )

    data class BatchEncryptionResult(
        val batchId: String,
        val files: List<BatchFileEncryptionResult>
    ) {
        fun zeroizeAllKeys() {
            files.forEach { it.encryptionResult.zeroizeKey() }
        }
    }

    /**
     * Encrypts a collection of documents in a batch, generating an independent 256-bit AES
     * key and fresh 96-bit IV for every single document.
     */
    fun encryptBatch(
        documents: List<com.example.privprint.data.model.SelectedDocument>,
        batchId: String = "BAT-${java.util.UUID.randomUUID().toString().take(8).uppercase()}"
    ): BatchEncryptionResult {
        val encryptedFiles = documents.map { doc ->
            val encResult = encryptDocument(doc.rawBytes)
            BatchFileEncryptionResult(
                documentName = doc.name,
                mimeType = doc.mimeType,
                pageCount = doc.pageCount,
                originalSizeBytes = doc.sizeBytes,
                encryptionResult = encResult
            )
        }
        return BatchEncryptionResult(
            batchId = batchId,
            files = encryptedFiles
        )
    }
}

