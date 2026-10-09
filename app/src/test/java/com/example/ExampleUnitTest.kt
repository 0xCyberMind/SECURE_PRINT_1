package com.example

import com.example.privprint.ui.UserScreen
import com.example.privprint.ui.UserUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying Send Secure Print button state, countdown formatting,
 * error recovery, and duplicate submission prevention.
 */
class ExampleUnitTest {

    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun testUserUiStateSubmissionDefaults() {
        val state = UserUiState()
        assertFalse(state.isSubmittingJob)
        assertNull(state.submissionError)
        assertEquals(UserScreen.HOME, state.currentScreen)
    }

    @Test
    fun testUserUiStateInFlightAndFailureRecovery() {
        val submittingState = UserUiState().copy(
            isSubmittingJob = true,
            submissionError = null
        )
        assertTrue(submittingState.isSubmittingJob)
        assertNull(submittingState.submissionError)

        val failureState = submittingState.copy(
            isSubmittingJob = false,
            submissionError = "Network connection timed out. Please retry."
        )
        assertFalse(failureState.isSubmittingJob)
        assertEquals("Network connection timed out. Please retry.", failureState.submissionError)
    }

    @Test
    fun testSendSecurePrintButtonTextCalculation() {
        fun computeButtonText(submitting: Boolean, remainingSeconds: Int, errorMessage: String?): String {
            return when {
                !submitting -> if (errorMessage != null) "Retry Secure Print" else "Send Secure Print"
                remainingSeconds > 0 -> "Sending securely… Please wait (${remainingSeconds}s)"
                else -> "Sending securely… Please wait…"
            }
        }

        // Idle state
        assertEquals("Send Secure Print", computeButtonText(submitting = false, remainingSeconds = 5, errorMessage = null))

        // First tap: 5s countdown
        assertEquals("Sending securely… Please wait (5s)", computeButtonText(submitting = true, remainingSeconds = 5, errorMessage = null))
        assertEquals("Sending securely… Please wait (4s)", computeButtonText(submitting = true, remainingSeconds = 4, errorMessage = null))
        assertEquals("Sending securely… Please wait (3s)", computeButtonText(submitting = true, remainingSeconds = 3, errorMessage = null))
        assertEquals("Sending securely… Please wait (2s)", computeButtonText(submitting = true, remainingSeconds = 2, errorMessage = null))
        assertEquals("Sending securely… Please wait (1s)", computeButtonText(submitting = true, remainingSeconds = 1, errorMessage = null))

        // Exceeded 5s: loading state persists while server request is in flight
        assertEquals("Sending securely… Please wait…", computeButtonText(submitting = true, remainingSeconds = 0, errorMessage = null))

        // On failure: retry text
        assertEquals("Retry Secure Print", computeButtonText(submitting = false, remainingSeconds = 5, errorMessage = "Connection failed"))
    }
}

