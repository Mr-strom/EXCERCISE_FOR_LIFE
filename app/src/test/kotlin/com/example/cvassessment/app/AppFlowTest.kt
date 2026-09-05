package com.example.cvassessment.app

import com.example.cvassessment.sdk.FeedbackEvent
import com.example.cvassessment.sdk.FormError
import com.example.cvassessment.sdk.SessionResult
import com.example.cvassessment.sdk.ValidationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests verifying the 4-screen flow data management and UI mapping logic.
 */
class AppFlowTest {

    @Before
    fun setUp() {
        SessionDataHolder.clear()
    }

    @Test
    fun testSessionDataHolderLifecycle() {
        SessionDataHolder.selectedExerciseId = "push_up"
        SessionDataHolder.selectedExerciseName = "Push-Up"

        val sampleResult = SessionResult(
            status = ValidationStatus.VALID,
            confidence = 0.96f,
            completeReps = 12,
            incompleteReps = 1,
            holdDurationSec = null,
            avgRepDurationSec = 2.1f,
            romPercent = 91.0f,
            tutFactor = 1.00f,
            formFactor = 0.75f,
            formErrors = listOf(
                FormError("hips_dropping", 0.82f, 7, 0.7f)
            ),
            feedbackEvents = listOf(
                FeedbackEvent("Keep your hips up.", 1720003345000L, "hips_dropping")
            )
        )

        SessionDataHolder.latestResult = sampleResult

        assertNotNull(SessionDataHolder.latestResult)
        assertEquals("push_up", SessionDataHolder.selectedExerciseId)
        assertEquals("Push-Up", SessionDataHolder.selectedExerciseName)
        assertEquals(ValidationStatus.VALID, SessionDataHolder.latestResult!!.status)
        assertEquals(12, SessionDataHolder.latestResult!!.completeReps)
        assertEquals(1, SessionDataHolder.latestResult!!.formErrors.size)

        SessionDataHolder.clear()
        assertNull(SessionDataHolder.latestResult)
    }

    @Test
    fun testInsufficientVisibilityResultMapping() {
        // Reproduce INSUFFICIENT_VISIBILITY scenario (R7 rule)
        val insufficientResult = SessionResult(
            status = ValidationStatus.INSUFFICIENT_VISIBILITY,
            confidence = 0.0f,
            completeReps = null,
            incompleteReps = null,
            holdDurationSec = null,
            avgRepDurationSec = null,
            romPercent = null,
            tutFactor = null,
            formFactor = null,
            formErrors = emptyList(),
            feedbackEvents = emptyList()
        )

        SessionDataHolder.latestResult = insufficientResult

        assertEquals(ValidationStatus.INSUFFICIENT_VISIBILITY, SessionDataHolder.latestResult!!.status)
        assertEquals(0.0f, SessionDataHolder.latestResult!!.confidence, 0.001f)
        assertNull(SessionDataHolder.latestResult!!.completeReps)
        assertNull(SessionDataHolder.latestResult!!.incompleteReps)
        assertNull(SessionDataHolder.latestResult!!.romPercent)
        assertNull(SessionDataHolder.latestResult!!.tutFactor)
        assertNull(SessionDataHolder.latestResult!!.formFactor)
        assertTrue(SessionDataHolder.latestResult!!.formErrors.isEmpty())
        assertTrue(SessionDataHolder.latestResult!!.feedbackEvents.isEmpty())
    }

    @Test
    fun testIntentExtrasConstantsContract() {
        assertEquals("EXTRA_EXERCISE_ID", StartCameraActivity.EXTRA_EXERCISE_ID)
        assertEquals("EXTRA_EXERCISE_NAME", StartCameraActivity.EXTRA_EXERCISE_NAME)
        assertEquals("EXTRA_EXERCISE_ID", LiveAnalysisActivity.EXTRA_EXERCISE_ID)
        assertEquals("EXTRA_EXERCISE_NAME", LiveAnalysisActivity.EXTRA_EXERCISE_NAME)
        assertEquals("EXTRA_LENS_FACING", LiveAnalysisActivity.EXTRA_LENS_FACING)
        assertEquals("EXTRA_EXERCISE_NAME", ResultsActivity.EXTRA_EXERCISE_NAME)
        assertEquals("EXTRA_SESSION_RESULT", ResultsActivity.EXTRA_SESSION_RESULT)
    }
}
