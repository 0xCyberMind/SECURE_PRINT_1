package com.example.privprint.data.auth

import com.example.privprint.data.api.models.UserRole
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class AuthenticatedUser(
    val userId: String,
    val role: UserRole,
    val shopId: String? = null,
    val deviceId: String? = null
)

data class TokenSession(
    val jti: String,
    val userId: String,
    val role: UserRole,
    val shopId: String?,
    val deviceId: String?,
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAt: Long,
    val refreshExpiresAt: Long,
    val isRevoked: Boolean = false
)

sealed class AuthResult {
    data class Success(val user: AuthenticatedUser, val accessToken: String, val refreshToken: String) : AuthResult()
    data class Failure(val error: String, val code: String) : AuthResult()
}

/**
 * Enterprise Auth & Token Lifecycle Manager.
 * Guarantees short-lived access tokens, refresh token rotation, JTI revocation,
 * and strict server/client role authorization.
 */
class AuthTokenManager {

    private val secureRandom = SecureRandom()

    // Store active token sessions: accessToken -> TokenSession
    private val activeSessions = ConcurrentHashMap<String, TokenSession>()

    // Store refresh tokens: refreshToken -> jti
    private val refreshTokens = ConcurrentHashMap<String, String>()

    // Explicit revoked tokens / JTIs
    private val revokedJtis = ConcurrentHashMap.newKeySet<String>()

    // Used refresh tokens (for reuse detection)
    private val consumedRefreshTokens = ConcurrentHashMap.newKeySet<String>()

    fun authenticate(
        identity: String,
        role: UserRole,
        shopId: String? = null,
        deviceId: String? = null
    ): AuthResult {
        val now = System.currentTimeMillis()
        val jti = UUID.randomUUID().toString()
        val accessToken = "pk_live_" + generateSecureToken()
        val refreshToken = "rt_live_" + generateSecureToken()

        val accessExpiresAt = now + (15 * 60 * 1000) // 15 mins
        val refreshExpiresAt = now + (7 * 24 * 60 * 60 * 1000) // 7 days

        val session = TokenSession(
            jti = jti,
            userId = identity,
            role = role,
            shopId = shopId,
            deviceId = deviceId,
            accessToken = accessToken,
            refreshToken = refreshToken,
            accessExpiresAt = accessExpiresAt,
            refreshExpiresAt = refreshExpiresAt
        )

        activeSessions[accessToken] = session
        refreshTokens[refreshToken] = jti

        return AuthResult.Success(
            user = AuthenticatedUser(identity, role, shopId, deviceId),
            accessToken = accessToken,
            refreshToken = refreshToken
        )
    }

    /**
     * Validates access token and verifies identity + role permissions.
     */
    fun validateToken(tokenHeader: String?, requiredRole: UserRole? = null): TokenValidationResult {
        if (tokenHeader.isNullOrBlank()) {
            return TokenValidationResult.Unauthorized("Missing authorization token")
        }

        val token = tokenHeader.removePrefix("Bearer ").trim()
        val session = activeSessions[token] ?: return TokenValidationResult.Unauthorized("Invalid or unknown access token")

        if (session.isRevoked || revokedJtis.contains(session.jti)) {
            return TokenValidationResult.Revoked("Token has been explicitly revoked")
        }

        if (System.currentTimeMillis() > session.accessExpiresAt) {
            return TokenValidationResult.Expired("Access token expired")
        }

        if (requiredRole != null && session.role != requiredRole && session.role != UserRole.ADMIN) {
            return TokenValidationResult.Forbidden("Role ${session.role} lacks required permission $requiredRole")
        }

        return TokenValidationResult.Valid(
            AuthenticatedUser(session.userId, session.role, session.shopId, session.deviceId)
        )
    }

    /**
     * Rotates refresh token securely. Detects token replay/theft.
     */
    fun rotateRefreshToken(oldRefreshToken: String): AuthResult {
        // Reuse detection
        if (consumedRefreshTokens.contains(oldRefreshToken)) {
            // Replay detected! Security compromise -> revoke everything associated
            val jti = refreshTokens.remove(oldRefreshToken)
            if (jti != null) revokedJtis.add(jti)
            return AuthResult.Failure("Refresh token reuse detected. Revoking session.", "REFRESH_TOKEN_REUSED")
        }

        val jti = refreshTokens[oldRefreshToken] ?: return AuthResult.Failure("Invalid refresh token", "INVALID_REFRESH_TOKEN")

        if (revokedJtis.contains(jti)) {
            return AuthResult.Failure("Session was previously revoked", "SESSION_REVOKED")
        }

        // Find session
        val currentSession = activeSessions.values.find { it.jti == jti && !it.isRevoked }
            ?: return AuthResult.Failure("Session expired or terminated", "SESSION_NOT_FOUND")

        if (System.currentTimeMillis() > currentSession.refreshExpiresAt) {
            revokedJtis.add(jti)
            return AuthResult.Failure("Refresh token expired", "REFRESH_EXPIRED")
        }

        // Mark old token as consumed
        refreshTokens.remove(oldRefreshToken)
        consumedRefreshTokens.add(oldRefreshToken)
        activeSessions.remove(currentSession.accessToken)

        // Issue new token pair (Rotation)
        val now = System.currentTimeMillis()
        val newAccessToken = "pk_live_" + generateSecureToken()
        val newRefreshToken = "rt_live_" + generateSecureToken()
        val newSession = currentSession.copy(
            accessToken = newAccessToken,
            refreshToken = newRefreshToken,
            accessExpiresAt = now + (15 * 60 * 1000)
        )

        activeSessions[newAccessToken] = newSession
        refreshTokens[newRefreshToken] = jti

        return AuthResult.Success(
            user = AuthenticatedUser(newSession.userId, newSession.role, newSession.shopId, newSession.deviceId),
            accessToken = newAccessToken,
            refreshToken = newRefreshToken
        )
    }

    /**
     * Explicit Logout: Invalidates access token, refresh token, and JTI.
     */
    fun revokeToken(tokenHeader: String?) {
        if (tokenHeader.isNullOrBlank()) return
        val token = tokenHeader.removePrefix("Bearer ").trim()
        val session = activeSessions.remove(token)
        if (session != null) {
            revokedJtis.add(session.jti)
            refreshTokens.remove(session.refreshToken)
        }
    }

    private fun generateSecureToken(): String {
        val bytes = ByteArray(24)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

sealed class TokenValidationResult {
    data class Valid(val user: AuthenticatedUser) : TokenValidationResult()
    data class Unauthorized(val message: String) : TokenValidationResult()
    data class Expired(val message: String) : TokenValidationResult()
    data class Forbidden(val message: String) : TokenValidationResult()
    data class Revoked(val message: String) : TokenValidationResult()
}
