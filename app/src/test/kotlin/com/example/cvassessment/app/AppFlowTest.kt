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

    @Test
    fun testFramingGuideRequiredLandmarks() {
        val expectedIndices = listOf(11, 12, 13, 14, 15, 16, 23, 24, 27, 28)
        assertEquals(10, expectedIndices.size)

        val expectedNames = listOf(
            "LEFT_SHOULDER", "RIGHT_SHOULDER",
            "LEFT_ELBOW", "RIGHT_ELBOW",
            "LEFT_WRIST", "RIGHT_WRIST",
            "LEFT_HIP", "RIGHT_HIP",
            "LEFT_ANKLE", "RIGHT_ANKLE"
        )

        val config = com.example.cvassessment.sdk.spec.ExerciseConfig.PUSH_UP
        assertEquals(expectedIndices, config.requiredLandmarkIndices)

        expectedIndices.forEachIndexed { i, idx ->
            val name = com.example.cvassessment.sdk.pose.PoseLandmarkType.getName(idx)
            assertEquals(expectedNames[i], name)
        }
    }

    @Test
    fun testFramingGuideColorCodingRules() {
        fun getColorForVisibility(visibility: Float): String {
            return when {
                visibility >= 0.60f -> "GREEN"
                visibility >= 0.40f -> "YELLOW"
                else -> "RED"
            }
        }

        assertEquals("GREEN", getColorForVisibility(0.85f))
        assertEquals("GREEN", getColorForVisibility(0.60f))
        assertEquals("YELLOW", getColorForVisibility(0.59f))
        assertEquals("YELLOW", getColorForVisibility(0.45f))
        assertEquals("YELLOW", getColorForVisibility(0.40f))
        assertEquals("RED", getColorForVisibility(0.39f))
        assertEquals("RED", getColorForVisibility(0.0f))
    }

    @Test
    fun testFramingGuideStepTransitions() {
        fun evaluateStep(
            hasPose: Boolean,
            visibleRequiredCount: Int,
            isCentered: Boolean,
            allAboveThreshold: Boolean,
            elapsedMs: Long
        ): Int {
            return if (!hasPose || visibleRequiredCount < 6) {
                1
            } else if (!isCentered) {
                2
            } else if (!allAboveThreshold || elapsedMs < 2000L) {
                3
            } else {
                4
            }
        }

        // Step 1: User stands in frame but not fully visible
        assertEquals(1, evaluateStep(hasPose = false, visibleRequiredCount = 0, isCentered = false, allAboveThreshold = false, elapsedMs = 0L))
        assertEquals(1, evaluateStep(hasPose = true, visibleRequiredCount = 4, isCentered = true, allAboveThreshold = false, elapsedMs = 0L))

        // Step 2: User is visible but off-center
        assertEquals(2, evaluateStep(hasPose = true, visibleRequiredCount = 8, isCentered = false, allAboveThreshold = false, elapsedMs = 0L))

        // Step 3: Centered and full body in frame, but checking stability (< 2000ms)
        assertEquals(3, evaluateStep(hasPose = true, visibleRequiredCount = 10, isCentered = true, allAboveThreshold = true, elapsedMs = 1500L))

        // Step 4: All required landmarks >= 0.6 for 2+ seconds sustained
        assertEquals(4, evaluateStep(hasPose = true, visibleRequiredCount = 10, isCentered = true, allAboveThreshold = true, elapsedMs = 2100L))
    }

    private fun createLandmark(index: Int, x: Float, y: Float, visibility: Float): com.example.cvassessment.sdk.pose.PoseLandmark {
        return com.example.cvassessment.sdk.pose.PoseLandmark(
            index = index,
            name = com.example.cvassessment.sdk.pose.PoseLandmarkType.getName(index),
            x = x,
            y = y,
            z = 0.0f,
            visibility = visibility
        )
    }

    @Test
    fun testPositionGuidanceFullBodyVisible() {
        val landmarks = listOf(
            createLandmark(11, 0.45f, 0.30f, 0.90f),
            createLandmark(12, 0.55f, 0.30f, 0.90f),
            createLandmark(13, 0.40f, 0.40f, 0.85f),
            createLandmark(14, 0.60f, 0.40f, 0.85f),
            createLandmark(15, 0.38f, 0.50f, 0.80f),
            createLandmark(16, 0.62f, 0.50f, 0.80f),
            createLandmark(23, 0.46f, 0.55f, 0.90f),
            createLandmark(24, 0.54f, 0.55f, 0.90f),
            createLandmark(27, 0.47f, 0.80f, 0.85f),
            createLandmark(28, 0.53f, 0.80f, 0.85f)
        )

        val result = com.example.cvassessment.app.ui.PositionGuidanceEvaluator.evaluate(landmarks, hasPose = true)
        assertEquals("Full body visible", result.guidanceMessage)
        org.junit.Assert.assertFalse(result.isWarning)
        assertTrue(result.lowConfidenceIndices.isEmpty())
    }

    @Test
    fun testPositionGuidanceMoveLeftHipOutOfFrame() {
        val landmarks = listOf(
            createLandmark(11, 0.75f, 0.30f, 0.90f),
            createLandmark(12, 0.82f, 0.30f, 0.90f),
            createLandmark(13, 0.70f, 0.40f, 0.85f),
            createLandmark(14, 0.84f, 0.40f, 0.85f),
            createLandmark(15, 0.68f, 0.50f, 0.80f),
            createLandmark(16, 0.85f, 0.50f, 0.80f),
            createLandmark(23, 0.80f, 0.55f, 0.90f),
            // Right hip drifting towards right edge (X = 0.89 > 0.85)
            createLandmark(24, 0.89f, 0.55f, 0.90f),
            createLandmark(27, 0.78f, 0.80f, 0.85f),
            createLandmark(28, 0.84f, 0.80f, 0.85f)
        )

        val result = com.example.cvassessment.app.ui.PositionGuidanceEvaluator.evaluate(landmarks, hasPose = true)
        assertEquals("Move left — hip out of frame", result.guidanceMessage)
        assertTrue(result.isWarning)
    }

    @Test
    fun testPositionGuidanceMoveCloserArmsTooFar() {
        val landmarks = listOf(
            createLandmark(11, 0.48f, 0.40f, 0.90f),
            createLandmark(12, 0.52f, 0.40f, 0.90f),
            createLandmark(13, 0.47f, 0.45f, 0.85f),
            createLandmark(14, 0.53f, 0.45f, 0.85f),
            createLandmark(15, 0.46f, 0.50f, 0.80f),
            createLandmark(16, 0.54f, 0.50f, 0.80f),
            createLandmark(23, 0.48f, 0.52f, 0.90f),
            createLandmark(24, 0.52f, 0.52f, 0.90f),
            // Ankles at y = 0.60 -> bodySpan = 0.60 - 0.40 = 0.20 (< 0.28)
            createLandmark(27, 0.48f, 0.60f, 0.85f),
            createLandmark(28, 0.52f, 0.60f, 0.85f)
        )

        val result = com.example.cvassessment.app.ui.PositionGuidanceEvaluator.evaluate(landmarks, hasPose = true)
        assertEquals("Move closer — arms too far", result.guidanceMessage)
        assertTrue(result.isWarning)
    }

    @Test
    fun testInsufficientVisibilityWhyLeftAnkleConfidenceDropped() {
        val landmarks = listOf(
            createLandmark(11, 0.45f, 0.30f, 0.90f),
            createLandmark(12, 0.55f, 0.30f, 0.90f),
            createLandmark(13, 0.40f, 0.40f, 0.85f),
            createLandmark(14, 0.60f, 0.40f, 0.85f),
            createLandmark(15, 0.38f, 0.50f, 0.80f),
            createLandmark(16, 0.62f, 0.50f, 0.80f),
            createLandmark(23, 0.46f, 0.55f, 0.90f),
            createLandmark(24, 0.54f, 0.55f, 0.90f),
            // Left ankle confidence dropped below 0.40 (to 0.25f)
            createLandmark(27, 0.47f, 0.80f, 0.25f),
            createLandmark(28, 0.53f, 0.80f, 0.85f)
        )

        val result = com.example.cvassessment.app.ui.PositionGuidanceEvaluator.evaluate(landmarks, hasPose = true)
        assertEquals("Why: Left ankle confidence dropped", result.insufficientWhyMessage)
        assertTrue(result.lowConfidenceIndices.contains(27))
        assertEquals("Adjust position — Left ankle confidence dropping", result.guidanceMessage)
        assertTrue(result.isWarning)
    }

    @Test
    fun testInsufficientVisibilityWhyJointMovedOutOfFrame() {
        val landmarks = listOf(
            createLandmark(11, 0.45f, 0.30f, 0.90f),
            createLandmark(12, 0.55f, 0.30f, 0.90f),
            createLandmark(13, 0.40f, 0.40f, 0.85f),
            createLandmark(14, 0.60f, 0.40f, 0.85f),
            createLandmark(15, 0.38f, 0.50f, 0.80f),
            createLandmark(16, 0.62f, 0.50f, 0.80f),
            createLandmark(23, 0.46f, 0.55f, 0.90f),
            createLandmark(24, 0.54f, 0.55f, 0.90f),
            // Left ankle moved past bottom edge (y = 0.97 > 0.95)
            createLandmark(27, 0.47f, 0.97f, 0.30f),
            createLandmark(28, 0.53f, 0.80f, 0.85f)
        )

        val result = com.example.cvassessment.app.ui.PositionGuidanceEvaluator.evaluate(landmarks, hasPose = true)
        assertEquals("Why: Left ankle moved out of frame", result.insufficientWhyMessage)
        assertTrue(result.lowConfidenceIndices.contains(27))
    }

    @Test
    fun testSetupAnalysisEvaluatorOptimalSetup() {
        val evaluator = com.example.cvassessment.app.ui.SetupAnalysisEvaluator()
        val sampleLandmarks = listOf(
            createLandmark(11, 0.45f, 0.30f, 0.88f),
            createLandmark(12, 0.55f, 0.30f, 0.88f),
            createLandmark(13, 0.40f, 0.40f, 0.85f),
            createLandmark(14, 0.60f, 0.40f, 0.85f),
            createLandmark(15, 0.38f, 0.50f, 0.80f),
            createLandmark(16, 0.62f, 0.50f, 0.80f),
            createLandmark(23, 0.46f, 0.55f, 0.90f),
            createLandmark(24, 0.54f, 0.55f, 0.90f),
            createLandmark(27, 0.47f, 0.80f, 0.85f),
            createLandmark(28, 0.53f, 0.80f, 0.85f)
        )

        // Simulate 7 seconds of frames (~200 frames)
        for (i in 1..200) {
            evaluator.recordSample(sampleLandmarks, hasPose = true)
        }

        val result = evaluator.evaluate()
        assertTrue("Setup should be good", result.isGood)
        assertEquals("Great! We can see you clearly.", result.headline)
        assertTrue("Start button must always be enabled after analysis", result.canStartAnyway)
        assertTrue("Overall confidence should be >= 0.50", result.overallConfidence >= 0.50f)
    }

    @Test
    fun testSetupAnalysisEvaluatorMoveBackWhenFeetCutOff() {
        val evaluator = com.example.cvassessment.app.ui.SetupAnalysisEvaluator()
        val cutOffLandmarks = listOf(
            createLandmark(11, 0.45f, 0.30f, 0.88f),
            createLandmark(12, 0.55f, 0.30f, 0.88f),
            createLandmark(13, 0.40f, 0.40f, 0.85f),
            createLandmark(14, 0.60f, 0.40f, 0.85f),
            createLandmark(15, 0.38f, 0.50f, 0.80f),
            createLandmark(16, 0.62f, 0.50f, 0.80f),
            createLandmark(23, 0.46f, 0.55f, 0.90f),
            createLandmark(24, 0.54f, 0.55f, 0.90f),
            // Ankles at y = 0.95 (> 0.90 frame boundary)
            createLandmark(27, 0.47f, 0.95f, 0.85f),
            createLandmark(28, 0.53f, 0.95f, 0.85f)
        )

        for (i in 1..200) {
            evaluator.recordSample(cutOffLandmarks, hasPose = true)
        }

        val result = evaluator.evaluate()
        org.junit.Assert.assertFalse("Setup is not good when feet cut off", result.isGood)
        assertEquals("Move back — we can't see your full body", result.headline)
        assertTrue("Button must still allow Start Anyway option", result.canStartAnyway)
    }

    @Test
    fun testSetupAnalysisEvaluatorRightArmLowVisibility() {
        val evaluator = com.example.cvassessment.app.ui.SetupAnalysisEvaluator()
        val rightArmLowLandmarks = listOf(
            createLandmark(11, 0.45f, 0.30f, 0.88f),
            createLandmark(12, 0.55f, 0.30f, 0.30f), // Right shoulder low
            createLandmark(13, 0.40f, 0.40f, 0.85f),
            createLandmark(14, 0.60f, 0.40f, 0.25f), // Right elbow low
            createLandmark(15, 0.38f, 0.50f, 0.80f),
            createLandmark(16, 0.62f, 0.50f, 0.20f), // Right wrist low
            createLandmark(23, 0.46f, 0.55f, 0.90f),
            createLandmark(24, 0.54f, 0.55f, 0.90f),
            createLandmark(27, 0.47f, 0.80f, 0.85f),
            createLandmark(28, 0.53f, 0.80f, 0.85f)
        )

        for (i in 1..200) {
            evaluator.recordSample(rightArmLowLandmarks, hasPose = true)
        }

        val result = evaluator.evaluate()
        org.junit.Assert.assertFalse("Setup should flag limb issue", result.isGood)
        assertEquals("We can't see your right arm — try adjusting your angle", result.headline)
        assertTrue(result.rightArmScore < 0.40f)
        assertTrue("Button must still allow Start Anyway option", result.canStartAnyway)
    }

    @Test
    fun testSetupAnalysisEvaluatorMoveToBetterLighting() {
        val evaluator = com.example.cvassessment.app.ui.SetupAnalysisEvaluator()
        // All landmarks uniformly low visibility ~0.35f
        val darkLandmarks = listOf(
            createLandmark(11, 0.45f, 0.30f, 0.35f),
            createLandmark(12, 0.55f, 0.30f, 0.35f),
            createLandmark(13, 0.40f, 0.40f, 0.34f),
            createLandmark(14, 0.60f, 0.40f, 0.34f),
            createLandmark(15, 0.38f, 0.50f, 0.32f),
            createLandmark(16, 0.62f, 0.50f, 0.32f),
            createLandmark(23, 0.46f, 0.55f, 0.36f),
            createLandmark(24, 0.54f, 0.55f, 0.36f),
            createLandmark(27, 0.47f, 0.80f, 0.33f),
            createLandmark(28, 0.53f, 0.80f, 0.33f)
        )

        for (i in 1..200) {
            evaluator.recordSample(darkLandmarks, hasPose = true)
        }

        val result = evaluator.evaluate()
        org.junit.Assert.assertFalse("Setup should flag lighting", result.isGood)
        assertEquals("Move to better lighting", result.headline)
        assertTrue(result.canStartAnyway)
    }

    @Test
    fun testSetupAnalysisEvaluatorNoPersonDetected() {
        val evaluator = com.example.cvassessment.app.ui.SetupAnalysisEvaluator()

        // 200 empty frames
        for (i in 1..200) {
            evaluator.recordSample(emptyList(), hasPose = false)
        }

        val result = evaluator.evaluate()
        org.junit.Assert.assertFalse("Setup should fail with no person", result.isGood)
        assertEquals("Step into frame — no person detected", result.headline)
        assertTrue(result.canStartAnyway)
    }
}
