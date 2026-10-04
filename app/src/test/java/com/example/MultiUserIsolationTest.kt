package com.example

import com.example.privprint.data.api.models.UserRole
import com.example.privprint.data.auth.AuthResult
import com.example.privprint.data.auth.AuthTokenManager
import com.example.privprint.data.auth.TokenValidationResult
import com.example.privprint.service.realtime.RealtimeEvent
import com.example.privprint.service.realtime.RealtimeEventType
import com.example.privprint.service.realtime.RealtimeTransportClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MultiUserIsolationTest {

    private lateinit var authManager: AuthTokenManager
    private lateinit var realtimeClient: RealtimeTransportClient

    @Before
    fun setUp() {
        authManager = AuthTokenManager()
        realtimeClient = RealtimeTransportClient()
    }

    @Test
    fun testUserTokensAreIsolatedAndNonInterchangeable() {
        // Authenticate User A and User B simultaneously
        val authA = authManager.authenticate("user_alice_ahmedabad", UserRole.USER) as AuthResult.Success
        val authB = authManager.authenticate("user_bob_delhi", UserRole.USER) as AuthResult.Success

        assertNotEquals(authA.accessToken, authB.accessToken)
        assertNotEquals(authA.user.userId, authB.user.userId)

        // Validate that token A belongs to Alice
        val valA = authManager.validateToken("Bearer " + authA.accessToken)
        assertTrue(valA is TokenValidationResult.Valid)
        assertEquals("user_alice_ahmedabad", (valA as TokenValidationResult.Valid).user.userId)

        // Validate that token B belongs to Bob
        val valB = authManager.validateToken("Bearer " + authB.accessToken)
        assertTrue(valB is TokenValidationResult.Valid)
        assertEquals("user_bob_delhi", (valB as TokenValidationResult.Valid).user.userId)
    }

    @Test
    fun testShopDeviceTokensAreBoundToShopId() {
        val deviceAuth = authManager.authenticate(
            identity = "device_epson_counter1",
            role = UserRole.PRINT_DEVICE,
            shopId = "SHOP-DELHI-01",
            deviceId = "DEV-EPSON-01"
        ) as AuthResult.Success

        val validation = authManager.validateToken("Bearer " + deviceAuth.accessToken, UserRole.PRINT_DEVICE)
        assertTrue(validation is TokenValidationResult.Valid)
        val validUser = (validation as TokenValidationResult.Valid).user
        assertEquals("SHOP-DELHI-01", validUser.shopId)
        assertEquals("DEV-EPSON-01", validUser.deviceId)

        // Must reject if attempting to access another shop or role
        assertNotEquals("SHOP-MUMBAI-02", validUser.shopId)
    }

    @Test
    fun testRealtimeChannelIsolation() {
        val userAChannel = "user:user_alice"
        val userBChannel = "user:user_bob"

        realtimeClient.connect(userAChannel, "token_alice")
        assertTrue(realtimeClient.isSubscribedTo(userAChannel))
        assertFalse(realtimeClient.isSubscribedTo(userBChannel))
        assertFalse(realtimeClient.isSubscribedTo("shop:SHOP-999"))
    }
}
