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

    @Test
    fun testActionableInsufficientVisibilityMessaging() {
        // 1. No person detected
        val noPersonResult = com.example.cvassessment.app.ui.PositionGuidanceEvaluator.evaluate(
            landmarks = emptyList(),
            hasPose = false
        )
        assertEquals("Can't see you clearly — step into camera view", noPersonResult.actionableInsufficientMessage)

        // 2. Feet cut off (ankle y = 0.95 > 0.88)
        val feetCutOff = listOf(
            createLandmark(11, 0.45f, 0.30f, 0.90f),
            createLandmark(12, 0.55f, 0.30f, 0.90f),
            createLandmark(13, 0.40f, 0.40f, 0.85f),
            createLandmark(14, 0.60f, 0.40f, 0.85f),
            createLandmark(15, 0.38f, 0.50f, 0.80f),
            createLandmark(16, 0.62f, 0.50f, 0.80f),
            createLandmark(23, 0.46f, 0.55f, 0.90f),
            createLandmark(24, 0.54f, 0.55f, 0.90f),
            createLandmark(27, 0.47f, 0.95f, 0.85f),
            createLandmark(28, 0.53f, 0.95f, 0.85f)
        )
        val feetResult = com.example.cvassessment.app.ui.PositionGuidanceEvaluator.evaluate(
            landmarks = feetCutOff,
            hasPose = true
        )
        assertEquals("Can't see you clearly — step back a bit", feetResult.actionableInsufficientMessage)

        // 3. Moved too far right (hip x = 0.92 > 0.85)
        val movedOutRight = listOf(
            createLandmark(11, 0.82f, 0.30f, 0.90f),
            createLandmark(12, 0.90f, 0.30f, 0.90f),
            createLandmark(13, 0.80f, 0.40f, 0.85f),
            createLandmark(14, 0.92f, 0.40f, 0.85f),
            createLandmark(15, 0.78f, 0.50f, 0.80f),
            createLandmark(16, 0.94f, 0.50f, 0.80f),
            createLandmark(23, 0.85f, 0.55f, 0.90f),
            createLandmark(24, 0.92f, 0.55f, 0.90f),
            createLandmark(27, 0.86f, 0.80f, 0.85f),
            createLandmark(28, 0.92f, 0.80f, 0.85f)
        )
        val centerResult = com.example.cvassessment.app.ui.PositionGuidanceEvaluator.evaluate(
            landmarks = movedOutRight,
            hasPose = true
        )
        assertEquals("Can't see you clearly — move toward center", centerResult.actionableInsufficientMessage)
    }

    @Test
    fun testExerciseAnalyzerCompletedRepMetricsAndFormScore() {
        val analyzer = com.example.cvassessment.sdk.ExerciseAnalyzer("push_up", "Push-Up")
        assertNull(analyzer.latestCompletedRepMetrics)
        assertTrue(analyzer.allRepMetrics.isEmpty())
        assertEquals(100, analyzer.getRepFormScore(1))

        // Simulate 1 complete rep: top (165°) -> bottom (85°) -> top (165°)
        var time = 1000L
        analyzer.analyzeSyntheticFrame(165.0f, 175.0f, time)
        time += 500
        analyzer.analyzeSyntheticFrame(130.0f, 175.0f, time)
        time += 500
        analyzer.analyzeSyntheticFrame(85.0f, 175.0f, time)
        time += 500
        analyzer.analyzeSyntheticFrame(130.0f, 175.0f, time)
        time += 500
        val finishResult = analyzer.analyzeSyntheticFrame(165.0f, 175.0f, time)

        assertEquals(1, finishResult.currentReps)
        assertNotNull(analyzer.latestCompletedRepMetrics)
        assertEquals(1, analyzer.latestCompletedRepMetrics!!.repIndex)
        assertTrue("ROM should be >= 90%", analyzer.latestCompletedRepMetrics!!.romPercent >= 90f)
        assertEquals(100, analyzer.getRepFormScore(1))
    }

    @Test
    fun testLiveAnalysisActivityConstants() {
        assertEquals("com.example.cvassessment.TEST_FORM_ERROR", LiveAnalysisActivity.ACTION_TEST_FORM_ERROR)
        assertEquals("EXTRA_SIMULATE_FORM_ERROR", LiveAnalysisActivity.EXTRA_SIMULATE_FORM_ERROR)
    }

    @Test
    fun testTtsFeedbackControllerSuccessfulLifecycleAndLogging() {
        val spokenMessages = mutableListOf<String>()
        val logOutput = mutableListOf<String>()

        val controller = com.example.cvassessment.app.ui.TtsFeedbackController(
            speakDelegate = { text ->
                spokenMessages.add(text)
                android.speech.tts.TextToSpeech.SUCCESS
            },
            setLanguageDelegate = {
                android.speech.tts.TextToSpeech.LANG_AVAILABLE
            },
            logInfo = { logOutput.add("INFO: $it") },
            logWarn = { logOutput.add("WARN: $it") },
            logError = { logOutput.add("ERROR: $it") }
        )

        // 1. Initially not initialized
        org.junit.Assert.assertFalse(controller.isInitialized)

        // 2. onInit succeeds
        controller.onInit(android.speech.tts.TextToSpeech.SUCCESS)
        assertTrue(controller.isInitialized)
        assertTrue(logOutput.any { it.contains("TTS initialized successfully (status: SUCCESS)") })

        // 3. speak() called when initialized
        val spoken = controller.speak("Keep your hips up.")
        assertTrue(spoken)
        assertEquals(listOf("Keep your hips up."), spokenMessages)
        assertTrue(logOutput.any { it == "INFO: TTS speaking: Keep your hips up." })
    }

    @Test
    fun testTtsFeedbackControllerQueuesMessageBeforeInitCompletes() {
        val spokenMessages = mutableListOf<String>()
        val logOutput = mutableListOf<String>()

        val controller = com.example.cvassessment.app.ui.TtsFeedbackController(
            speakDelegate = { text ->
                spokenMessages.add(text)
                android.speech.tts.TextToSpeech.SUCCESS
            },
            setLanguageDelegate = {
                android.speech.tts.TextToSpeech.LANG_AVAILABLE
            },
            logInfo = { logOutput.add("INFO: $it") },
            logWarn = { logOutput.add("WARN: $it") },
            logError = { logOutput.add("ERROR: $it") }
        )

        // 1. Form error occurs BEFORE onInit callback fires
        val spokenPremature = controller.speak("Keep your hips up.")
        org.junit.Assert.assertFalse("Should not speak before init", spokenPremature)
        assertEquals("Keep your hips up.", controller.pendingMessage)
        assertTrue(spokenMessages.isEmpty())
        assertTrue(logOutput.any { it.contains("TTS not initialized yet. Queuing message: Keep your hips up.") })

        // 2. onInit callback completes asynchronously
        controller.onInit(android.speech.tts.TextToSpeech.SUCCESS)

        // 3. Verify queued message was automatically spoken and logged
        assertTrue(controller.isInitialized)
        assertNull(controller.pendingMessage)
        assertEquals(listOf("Keep your hips up."), spokenMessages)
        assertTrue(logOutput.any { it == "INFO: TTS speaking: Keep your hips up." })
    }

    @Test
    fun testTtsFeedbackControllerHandlesInitErrorExplicitly() {
        val logOutput = mutableListOf<String>()

        val controller = com.example.cvassessment.app.ui.TtsFeedbackController(
            speakDelegate = { android.speech.tts.TextToSpeech.SUCCESS },
            setLanguageDelegate = { android.speech.tts.TextToSpeech.LANG_AVAILABLE },
            logInfo = { logOutput.add("INFO: $it") },
            logWarn = { logOutput.add("WARN: $it") },
            logError = { logOutput.add("ERROR: $it") }
        )

        controller.onInit(android.speech.tts.TextToSpeech.ERROR)
        org.junit.Assert.assertFalse(controller.isInitialized)
        assertTrue(logOutput.any { it.contains("TTS initialization failed with code: -1 (TextToSpeech.ERROR)") })
    }

    /**
     * Acceptance Test: Stickman Indicator 3-tier color mapping:
     * - Green: >= 0.60
     * - Yellow: 0.40 <= score < 0.60
     * - Red: < 0.40
     */
    @Test
    fun testStickmanColorMappingThreeTiers() {
        // Without pose detected -> NEUTRAL
        assertEquals(
            com.example.cvassessment.app.ui.StickmanIndicatorView.ColorTier.NEUTRAL,
            com.example.cvassessment.app.ui.StickmanIndicatorView.evaluateColorTier(0.9f, hasPose = false)
        )

        // Tier 1: Green (>= 0.60)
        assertEquals(
            com.example.cvassessment.app.ui.StickmanIndicatorView.ColorTier.GOOD,
            com.example.cvassessment.app.ui.StickmanIndicatorView.evaluateColorTier(0.85f, hasPose = true)
        )
        assertEquals(
            com.example.cvassessment.app.ui.StickmanIndicatorView.ColorTier.GOOD,
            com.example.cvassessment.app.ui.StickmanIndicatorView.evaluateColorTier(0.60f, hasPose = true)
        )

        // Tier 2: Yellow (0.40 <= score < 0.60)
        assertEquals(
            com.example.cvassessment.app.ui.StickmanIndicatorView.ColorTier.WARNING,
            com.example.cvassessment.app.ui.StickmanIndicatorView.evaluateColorTier(0.59f, hasPose = true)
        )
        assertEquals(
            com.example.cvassessment.app.ui.StickmanIndicatorView.ColorTier.WARNING,
            com.example.cvassessment.app.ui.StickmanIndicatorView.evaluateColorTier(0.50f, hasPose = true)
        )
        assertEquals(
            com.example.cvassessment.app.ui.StickmanIndicatorView.ColorTier.WARNING,
            com.example.cvassessment.app.ui.StickmanIndicatorView.evaluateColorTier(0.40f, hasPose = true)
        )

        // Tier 3: Red (< 0.40)
        assertEquals(
            com.example.cvassessment.app.ui.StickmanIndicatorView.ColorTier.BAD,
            com.example.cvassessment.app.ui.StickmanIndicatorView.evaluateColorTier(0.39f, hasPose = true)
        )
        assertEquals(
            com.example.cvassessment.app.ui.StickmanIndicatorView.ColorTier.BAD,
            com.example.cvassessment.app.ui.StickmanIndicatorView.evaluateColorTier(0.15f, hasPose = true)
        )
        assertEquals(
            com.example.cvassessment.app.ui.StickmanIndicatorView.ColorTier.BAD,
            com.example.cvassessment.app.ui.StickmanIndicatorView.evaluateColorTier(0.0f, hasPose = true)
        )
    }

    /**
     * Acceptance Test: Screen 2 setup analysis for at least one exercise per category:
     * - Upper Body: Push-Up
     * - Lower Body: Squat
     * - Core / Static: Plank
     * - Functional / Dynamic: Jumping Jack
     */
    @Test
    fun testScreen2SetupAnalysisAcrossFourCategories() {
        val cleanSideLandmarks = listOf(
            createLandmark(0, 0.48f, 0.20f, 0.90f),  // Nose
            createLandmark(11, 0.47f, 0.30f, 0.90f), // Left shoulder
            createLandmark(12, 0.53f, 0.30f, 0.88f), // Right shoulder (narrow = 0.06 side profile)
            createLandmark(13, 0.45f, 0.40f, 0.85f),
            createLandmark(14, 0.55f, 0.40f, 0.85f),
            createLandmark(15, 0.44f, 0.50f, 0.80f),
            createLandmark(16, 0.56f, 0.50f, 0.80f),
            createLandmark(23, 0.48f, 0.55f, 0.90f),
            createLandmark(24, 0.52f, 0.55f, 0.90f),
            createLandmark(25, 0.48f, 0.68f, 0.90f), // Left knee
            createLandmark(26, 0.52f, 0.68f, 0.90f), // Right knee
            createLandmark(27, 0.48f, 0.82f, 0.85f),
            createLandmark(28, 0.52f, 0.82f, 0.85f)
        )

        val cleanFrontLandmarks = listOf(
            createLandmark(0, 0.50f, 0.20f, 0.90f),  // Nose
            createLandmark(11, 0.38f, 0.30f, 0.90f), // Left shoulder (wide = 0.24 front profile)
            createLandmark(12, 0.62f, 0.30f, 0.90f), // Right shoulder
            createLandmark(13, 0.35f, 0.40f, 0.85f),
            createLandmark(14, 0.65f, 0.40f, 0.85f),
            createLandmark(15, 0.32f, 0.50f, 0.80f),
            createLandmark(16, 0.68f, 0.50f, 0.80f),
            createLandmark(23, 0.42f, 0.55f, 0.90f),
            createLandmark(24, 0.58f, 0.55f, 0.90f),
            createLandmark(25, 0.42f, 0.68f, 0.90f), // Left knee
            createLandmark(26, 0.58f, 0.68f, 0.90f), // Right knee
            createLandmark(27, 0.42f, 0.82f, 0.85f),
            createLandmark(28, 0.58f, 0.82f, 0.85f)
        )

        // 1. Upper Body: Push-Up
        val pushUpEvaluator = com.example.cvassessment.app.ui.SetupAnalysisEvaluator("push_up")
        for (i in 1..100) pushUpEvaluator.recordSample(cleanSideLandmarks, hasPose = true)
        val pushUpResult = pushUpEvaluator.evaluate()
        assertTrue("Push-Up side view should be good", pushUpResult.isGood)
        assertEquals("Great! We can see you clearly.", pushUpResult.headline)
        assertTrue(pushUpResult.canStartAnyway)

        // 2. Lower Body: Squat
        val squatEvaluator = com.example.cvassessment.app.ui.SetupAnalysisEvaluator("squat")
        for (i in 1..100) squatEvaluator.recordSample(cleanSideLandmarks, hasPose = true)
        val squatResult = squatEvaluator.evaluate()
        assertTrue("Squat setup should be good", squatResult.isGood)
        assertEquals("Great! We can see you clearly.", squatResult.headline)
        assertTrue(squatResult.canStartAnyway)

        // 3. Core / Static: Plank
        val plankEvaluator = com.example.cvassessment.app.ui.SetupAnalysisEvaluator("plank")
        for (i in 1..100) plankEvaluator.recordSample(cleanSideLandmarks, hasPose = true)
        val plankResult = plankEvaluator.evaluate()
        assertTrue("Plank side view should be good", plankResult.isGood)
        assertEquals("Great! We can see you clearly.", plankResult.headline)
        assertTrue(plankResult.canStartAnyway)

        // 4. Functional / Dynamic: Jumping Jack
        val jackEvaluator = com.example.cvassessment.app.ui.SetupAnalysisEvaluator("jumping_jack")
        for (i in 1..100) jackEvaluator.recordSample(cleanFrontLandmarks, hasPose = true)
        val jackResult = jackEvaluator.evaluate()
        assertTrue("Jumping Jack front view should be good", jackResult.isGood)
        assertEquals("Great! We can see you clearly.", jackResult.headline)
        assertTrue(jackResult.canStartAnyway)
    }

    /**
     * Acceptance Test: "Start Anyway" button distinction for hard view requirements.
     * - Hard view requirement violated -> "Start Anyway" is BLOCKED (canStartAnyway = false).
     * - Non-strict or general borderline quality -> "Start Anyway" is OFFERED (canStartAnyway = true).
     */
    @Test
    fun testStartAnywayDistinctionForHardViewRequirements() {
        val frontLandmarksWide = listOf(
            createLandmark(0, 0.50f, 0.20f, 0.90f),
            createLandmark(11, 0.38f, 0.30f, 0.90f), // shoulderWidth = 0.24 (> 0.18 front profile)
            createLandmark(12, 0.62f, 0.30f, 0.90f),
            createLandmark(13, 0.35f, 0.40f, 0.85f),
            createLandmark(14, 0.65f, 0.40f, 0.85f),
            createLandmark(15, 0.32f, 0.50f, 0.80f),
            createLandmark(16, 0.68f, 0.50f, 0.80f),
            createLandmark(23, 0.42f, 0.55f, 0.90f),
            createLandmark(24, 0.58f, 0.55f, 0.90f),
            createLandmark(25, 0.42f, 0.68f, 0.90f),
            createLandmark(26, 0.58f, 0.68f, 0.90f),
            createLandmark(27, 0.42f, 0.82f, 0.85f),
            createLandmark(28, 0.58f, 0.82f, 0.85f)
        )

        val sideLandmarksNarrow = listOf(
            createLandmark(0, 0.50f, 0.20f, 0.90f),
            createLandmark(11, 0.47f, 0.30f, 0.90f), // shoulderWidth = 0.05 (< 0.10 side profile)
            createLandmark(12, 0.52f, 0.30f, 0.90f),
            createLandmark(13, 0.45f, 0.40f, 0.85f),
            createLandmark(14, 0.55f, 0.40f, 0.85f),
            createLandmark(15, 0.44f, 0.50f, 0.80f),
            createLandmark(16, 0.56f, 0.50f, 0.80f),
            createLandmark(23, 0.48f, 0.55f, 0.90f),
            createLandmark(24, 0.52f, 0.55f, 0.90f),
            createLandmark(25, 0.48f, 0.68f, 0.90f),
            createLandmark(26, 0.52f, 0.68f, 0.90f),
            createLandmark(27, 0.48f, 0.82f, 0.85f),
            createLandmark(28, 0.52f, 0.82f, 0.85f)
        )

        // 1. Calf Raise: Hard SIDE requirement. Given front landmarks -> Start Anyway BLOCKED
        val calfEvaluator = com.example.cvassessment.app.ui.SetupAnalysisEvaluator("calf_raise")
        for (i in 1..100) calfEvaluator.recordSample(frontLandmarksWide, hasPose = true)
        val calfResult = calfEvaluator.evaluate()
        org.junit.Assert.assertFalse("Calf Raise facing front must not pass", calfResult.isGood)
        assertEquals("Turn sideways for this exercise", calfResult.headline)
        org.junit.Assert.assertFalse("Start Anyway must be BLOCKED when hard view requirement is violated", calfResult.canStartAnyway)

        // 2. Jumping Jack: Hard FRONT requirement. Given side landmarks -> Start Anyway BLOCKED
        val jackEvaluator = com.example.cvassessment.app.ui.SetupAnalysisEvaluator("jumping_jack")
        for (i in 1..100) jackEvaluator.recordSample(sideLandmarksNarrow, hasPose = true)
        val jackResult = jackEvaluator.evaluate()
        org.junit.Assert.assertFalse("Jumping Jack facing sideways must not pass", jackResult.isGood)
        assertEquals("Turn to face the camera", jackResult.headline)
        org.junit.Assert.assertFalse("Start Anyway must be BLOCKED when hard view requirement is violated", jackResult.canStartAnyway)

        // 3. Push-Up: Side view preferred (non-strict). Given front landmarks -> Start Anyway ALLOWED
        val pushUpEvaluator = com.example.cvassessment.app.ui.SetupAnalysisEvaluator("push_up")
        // Low confidence + front view
        val borderlineLandmarks = frontLandmarksWide.map { createLandmark(it.index, it.x, it.y, 0.55f) }
        for (i in 1..100) pushUpEvaluator.recordSample(borderlineLandmarks, hasPose = true)
        val pushUpResult = pushUpEvaluator.evaluate()
        org.junit.Assert.assertFalse(pushUpResult.isGood)
        assertTrue("Start Anyway must be ALLOWED for preferred exercises with borderline conditions", pushUpResult.canStartAnyway)
    }

    /**
     * Acceptance Test: Live orientation hints during 7s analysis countdown.
     */
    @Test
    fun testLiveOrientationHintsDuringAnalysis() {
        val frontLandmarks = listOf(
            createLandmark(11, 0.38f, 0.30f, 0.85f),
            createLandmark(12, 0.62f, 0.30f, 0.85f) // width = 0.24
        )
        val sideLandmarks = listOf(
            createLandmark(11, 0.47f, 0.30f, 0.85f),
            createLandmark(12, 0.52f, 0.30f, 0.85f) // width = 0.05
        )

        // Calf Raise requires side view -> front landmarks trigger hint
        val calfEvaluator = com.example.cvassessment.app.ui.SetupAnalysisEvaluator("calf_raise")
        assertEquals("Turn sideways for this exercise", calfEvaluator.getLiveOrientationHint(frontLandmarks))
        assertNull(calfEvaluator.getLiveOrientationHint(sideLandmarks))

        // Jumping Jack requires front view -> side landmarks trigger hint
        val jackEvaluator = com.example.cvassessment.app.ui.SetupAnalysisEvaluator("jumping_jack")
        assertEquals("Face the camera for this exercise", jackEvaluator.getLiveOrientationHint(sideLandmarks))
        assertNull(jackEvaluator.getLiveOrientationHint(frontLandmarks))
    }

    /**
     * Acceptance Test: Screen 3 orientation-aware insufficient visibility messages.
     */
    @Test
    fun testScreen3OrientationAwareInsufficientVisibility() {
        val frontLandmarks = listOf(
            createLandmark(11, 0.38f, 0.30f, 0.85f),
            createLandmark(12, 0.62f, 0.30f, 0.85f),
            createLandmark(23, 0.40f, 0.55f, 0.85f),
            createLandmark(24, 0.60f, 0.55f, 0.85f),
            createLandmark(27, 0.40f, 0.80f, 0.85f),
            createLandmark(28, 0.60f, 0.80f, 0.85f)
        )
        val sideLandmarks = listOf(
            createLandmark(11, 0.47f, 0.30f, 0.85f),
            createLandmark(12, 0.52f, 0.30f, 0.85f),
            createLandmark(23, 0.48f, 0.55f, 0.85f),
            createLandmark(24, 0.52f, 0.55f, 0.85f),
            createLandmark(27, 0.48f, 0.80f, 0.85f),
            createLandmark(28, 0.52f, 0.80f, 0.85f)
        )

        // Calf raise with front view
        val calfGuidance = com.example.cvassessment.app.ui.PositionGuidanceEvaluator.evaluate(
            landmarks = frontLandmarks,
            hasPose = true,
            exerciseId = "calf_raise"
        )
        assertEquals("Can't see you clearly — turn sideways", calfGuidance.actionableInsufficientMessage)

        // Jumping Jack with side view
        val jackGuidance = com.example.cvassessment.app.ui.PositionGuidanceEvaluator.evaluate(
            landmarks = sideLandmarks,
            hasPose = true,
            exerciseId = "jumping_jack"
        )
        assertEquals("Can't see you clearly — face the camera", jackGuidance.actionableInsufficientMessage)
    }
}
