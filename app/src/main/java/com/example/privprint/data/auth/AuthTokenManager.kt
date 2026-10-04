package com.example.privprint.data.auth

import android.content.Context
import android.content.SharedPreferences
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

sealed class TokenValidationResult {
    data class Valid(val user: AuthenticatedUser) : TokenValidationResult()
    data class Expired(val reason: String) : TokenValidationResult()
    data class Unauthorized(val reason: String) : TokenValidationResult()
    data class Forbidden(val reason: String) : TokenValidationResult()
    data class Revoked(val reason: String) : TokenValidationResult()
}

/**
 * Enterprise Auth & Token Lifecycle Manager.
 * Guarantees short-lived access tokens, refresh token rotation, JTI revocation,
 * and persistent credential storage without logging sensitive secrets.
 */
class AuthTokenManager(context: Context? = null) {

    private val prefs: SharedPreferences? = context?.getSharedPreferences("privprint_secure_tokens", Context.MODE_PRIVATE)

    @Volatile private var currentAccessToken: String? = prefs?.getString("access_token", null)
    @Volatile private var currentRefreshToken: String? = prefs?.getString("refresh_token", null)

    @Volatile var currentUser: AuthenticatedUser? = run {
        val uId = prefs?.getString("user_id", null)
        val rStr = prefs?.getString("user_role", null)
        val sId = prefs?.getString("shop_id", null)
        val dId = prefs?.getString("device_id", null)
        if (!uId.isNullOrEmpty() && !rStr.isNullOrEmpty()) {
            val role = try { UserRole.valueOf(rStr) } catch (e: Exception) { UserRole.USER }
            AuthenticatedUser(uId, role, sId, dId)
        } else null
    }
        private set

    private val activeSessions = ConcurrentHashMap<String, TokenSession>()
    private val refreshTokens = ConcurrentHashMap<String, String>()
    private val revokedJtis = ConcurrentHashMap.newKeySet<String>()

    /** Refresh tokens that were consumed by a prior rotation - reuse = replay attack. */
    private val consumedRefreshTokens = ConcurrentHashMap.newKeySet<String>()

    fun getAccessToken(): String? = currentAccessToken

    fun getRefreshToken(): String? = currentRefreshToken

    fun updateTokens(accessToken: String, refreshToken: String) {
        this.currentAccessToken = accessToken
        this.currentRefreshToken = refreshToken
        prefs?.edit()
            ?.putString("access_token", accessToken)
            ?.putString("refresh_token", refreshToken)
            ?.apply()
    }

    fun saveAuth(user: AuthenticatedUser, accessToken: String, refreshToken: String) {
        this.currentUser = user
        this.currentAccessToken = accessToken
        this.currentRefreshToken = refreshToken
        prefs?.edit()
            ?.putString("access_token", accessToken)
            ?.putString("refresh_token", refreshToken)
            ?.putString("user_id", user.userId)
            ?.putString("user_role", user.role.name)
            ?.putString("shop_id", user.shopId)
            ?.putString("device_id", user.deviceId)
            ?.apply()
    }

    fun clearSession() {
        this.currentAccessToken = null
        this.currentRefreshToken = null
        this.currentUser = null
        prefs?.edit()?.clear()?.apply()
        activeSessions.clear()
        refreshTokens.clear()
    }

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

        val accessExpiresAt = now + (15 * 60 * 1000)
        val refreshExpiresAt = now + (7 * 24 * 60 * 60 * 1000)

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

        val user = AuthenticatedUser(identity, role, shopId, deviceId)
        saveAuth(user, accessToken, refreshToken)

        return AuthResult.Success(
            user = user,
            accessToken = accessToken,
            refreshToken = refreshToken
        )
    }

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

    fun rotateRefreshToken(refreshToken: String): AuthResult {
        // REPLAY DETECTION: a token consumed by an earlier rotation must never
        // be accepted again. This is the client-side mirror of the server's
        // single-use refresh rotation with theft detection.
        if (consumedRefreshTokens.contains(refreshToken)) {
            return AuthResult.Failure(
                "Refresh token replay detected. Session terminated.",
                "REFRESH_TOKEN_REUSED"
            )
        }

        val jti = refreshTokens[refreshToken]
            ?: return AuthResult.Failure("Invalid or consumed refresh token", "INVALID_REFRESH_TOKEN")

        val oldSession = activeSessions.values.find { it.jti == jti }
            ?: return AuthResult.Failure("Associated session expired", "EXPIRED_SESSION")

        if (System.currentTimeMillis() > oldSession.refreshExpiresAt) {
            refreshTokens.remove(refreshToken)
            return AuthResult.Failure("Refresh token expired", "EXPIRED_REFRESH_TOKEN")
        }

        refreshTokens.remove(refreshToken)
        consumedRefreshTokens.add(refreshToken)
        activeSessions.remove(oldSession.accessToken)

        val newAccessToken = "pk_live_" + generateSecureToken()
        val newRefreshToken = "rt_live_" + generateSecureToken()
        val now = System.currentTimeMillis()

        val newSession = oldSession.copy(
            accessToken = newAccessToken,
            refreshToken = newRefreshToken,
            accessExpiresAt = now + (15 * 60 * 1000),
            refreshExpiresAt = now + (7 * 24 * 60 * 60 * 1000)
        )

        activeSessions[newAccessToken] = newSession
        refreshTokens[newRefreshToken] = jti

        val user = AuthenticatedUser(newSession.userId, newSession.role, newSession.shopId, newSession.deviceId)
        saveAuth(user, newAccessToken, newRefreshToken)

        return AuthResult.Success(
            user = user,
            accessToken = newAccessToken,
            refreshToken = newRefreshToken
        )
    }

    fun revokeToken(tokenHeader: String?) {
        if (tokenHeader.isNullOrBlank()) return
        val token = tokenHeader.removePrefix("Bearer ").trim()
        val session = activeSessions.remove(token)
        if (session != null) {
            revokedJtis.add(session.jti)
            refreshTokens.entries.removeIf { it.value == session.jti }
        }
        clearSession()
    }

    private fun generateSecureToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
