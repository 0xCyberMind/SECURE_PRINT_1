package com.example

import com.example.privprint.data.api.models.UserRole
import com.example.privprint.data.auth.AuthResult
import com.example.privprint.data.auth.AuthTokenManager
import com.example.privprint.data.auth.TokenValidationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AuthSecurityTest {

    private lateinit var authManager: AuthTokenManager

    @Before
    fun setUp() {
        authManager = AuthTokenManager()
    }

    @Test
    fun testAuthenticationIssuesValidTokensAndEnforcesRoles() {
        val authResult = authManager.authenticate("user_alice", UserRole.USER)
        assertTrue(authResult is AuthResult.Success)
        val success = authResult as AuthResult.Success
        assertEquals("user_alice", success.user.userId)
        assertEquals(UserRole.USER, success.user.role)

        // Validate token with required role USER
        val validation = authManager.validateToken("Bearer " + success.accessToken, UserRole.USER)
        assertTrue(validation is TokenValidationResult.Valid)

        // Validate token with required role ADMIN -> MUST reject with Forbidden
        val adminValidation = authManager.validateToken("Bearer " + success.accessToken, UserRole.ADMIN)
        assertTrue(adminValidation is TokenValidationResult.Forbidden)
    }

    @Test
    fun testRefreshTokenRotationAndReplayPrevention() {
        val authResult = authManager.authenticate("operator_bob", UserRole.SHOP_OPERATOR, shopId = "SHOP-101")
        val success = authResult as AuthResult.Success
        val initialRefreshToken = success.refreshToken

        // First rotation: valid
        val rotateResult1 = authManager.rotateRefreshToken(initialRefreshToken)
        assertTrue(rotateResult1 is AuthResult.Success)
        val newRefresh = (rotateResult1 as AuthResult.Success).refreshToken

        // Attempting to reuse old refresh token -> Replay attack detected!
        val reuseResult = authManager.rotateRefreshToken(initialRefreshToken)
        assertTrue(reuseResult is AuthResult.Failure)
        assertEquals("REFRESH_TOKEN_REUSED", (reuseResult as AuthResult.Failure).code)
    }

    @Test
    fun testLogoutRevokesActiveTokens() {
        val authResult = authManager.authenticate("device_hp", UserRole.PRINT_DEVICE, deviceId = "PRN-HP-01")
        val success = authResult as AuthResult.Success

        // Token valid before logout
        val beforeLogout = authManager.validateToken("Bearer " + success.accessToken)
        assertTrue(beforeLogout is TokenValidationResult.Valid)

        // Logout
        authManager.revokeToken("Bearer " + success.accessToken)

        // Token rejected after logout
        val afterLogout = authManager.validateToken("Bearer " + success.accessToken)
        assertTrue(afterLogout is TokenValidationResult.Unauthorized)
    }

    @Test
    fun testShopOperatorCannotAccessAdminEndpoints() {
        val shopAuth = authManager.authenticate("shop_clerk", UserRole.SHOP_OPERATOR)
        val success = shopAuth as AuthResult.Success

        val result = authManager.validateToken("Bearer " + success.accessToken, UserRole.ADMIN)
        assertTrue(result is TokenValidationResult.Forbidden)
    }
}
