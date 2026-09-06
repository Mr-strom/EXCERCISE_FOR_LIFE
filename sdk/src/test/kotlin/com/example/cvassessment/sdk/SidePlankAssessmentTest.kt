package com.example.cvassessment.sdk

import com.example.cvassessment.sdk.form.SidePlankFormRuleEngine
import com.example.cvassessment.sdk.form.SidePlankFormRules
import com.example.cvassessment.sdk.pose.PoseLandmark
import com.example.cvassessment.sdk.pose.PoseLandmarkType
import com.example.cvassessment.sdk.statemachine.SidePlankGeometry
import com.example.cvassessment.sdk.statemachine.SidePlankPhase
import com.example.cvassessment.sdk.statemachine.SidePlankStateMachine
import com.example.cvassessment.sdk.statemachine.SidePlankSupportSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 9 Required Acceptance Criteria Unit Tests for Side Plank Implementation (Eighth Exercise).
 * Per EXERCISE_SPEC.md #8, METRICS_SPEC.md §2, §4, §5, FORM_RULES.md, and SDK_CONTRACT.md.
 */
class SidePlankAssessmentTest {

    private fun createSyntheticLandmarks(
        bodyLineAngle: Float = 180.0f,
        supportSide: SidePlankSupportSide = SidePlankSupportSide.LEFT,
        visibility: Float = 0.95f
    ): List<PoseLandmark> {
        val landmarks = mutableListOf<PoseLandmark>()

        val hipY = when {
            bodyLineAngle < 170.0f -> 0.75f // sagging toward floor
            bodyLineAngle > 190.0f -> 0.65f // piking toward ceiling
            else -> 0.70f // straight
        }

        for (i in 0..32) {
            val (x, y, z) = if (supportSide == SidePlankSupportSide.LEFT) {
                when (i) {
                    PoseLandmarkType.LEFT_SHOULDER -> Triple(0.20f, 0.70f, 0.0f)
                    PoseLandmarkType.RIGHT_SHOULDER -> Triple(0.20f, 0.60f, 0.0f)
                    PoseLandmarkType.LEFT_ELBOW -> Triple(0.20f, 0.85f, 0.0f) // support on ground
                    PoseLandmarkType.RIGHT_ELBOW -> Triple(0.25f, 0.55f, 0.0f) // non-support elevated
                    PoseLandmarkType.LEFT_HIP -> Triple(0.50f, hipY, 0.0f)
                    PoseLandmarkType.RIGHT_HIP -> Triple(0.50f, hipY - 0.08f, 0.0f)
                    PoseLandmarkType.LEFT_ANKLE -> Triple(0.80f, 0.70f, 0.0f)
                    PoseLandmarkType.RIGHT_ANKLE -> Triple(0.80f, 0.65f, 0.0f)
                    else -> Triple(0.5f, 0.5f, 0.0f)
                }
            } else {
                when (i) {
                    PoseLandmarkType.RIGHT_SHOULDER -> Triple(0.20f, 0.70f, 0.0f)
                    PoseLandmarkType.LEFT_SHOULDER -> Triple(0.20f, 0.60f, 0.0f)
                    PoseLandmarkType.RIGHT_ELBOW -> Triple(0.20f, 0.85f, 0.0f) // support on ground
                    PoseLandmarkType.LEFT_ELBOW -> Triple(0.25f, 0.55f, 0.0f) // non-support elevated
                    PoseLandmarkType.RIGHT_HIP -> Triple(0.50f, hipY, 0.0f)
                    PoseLandmarkType.LEFT_HIP -> Triple(0.50f, hipY - 0.08f, 0.0f)
                    PoseLandmarkType.RIGHT_ANKLE -> Triple(0.80f, 0.70f, 0.0f)
                    PoseLandmarkType.LEFT_ANKLE -> Triple(0.80f, 0.65f, 0.0f)
                    else -> Triple(0.5f, 0.5f, 0.0f)
                }
            }
            landmarks.add(PoseLandmark(index = i, name = "LM_$i", x = x, y = y, z = z, visibility = visibility))
        }
        return landmarks
    }

    /**
     * Unit Test 1: Clean 30s side plank hold accumulates duration correctly.
     * 30 seconds of stable tracking at 30 fps -> holdDurationSec ≈ 30.0s.
     */
    @Test
    fun testUnit1_CleanSidePlankHoldAccumulatesDurationCorrectly() {
        val stateMachine = SidePlankStateMachine()
        var timeMs = 1000L

        // Enter hold at 180°
        stateMachine.processAngle(180.0f, timeMs, true, SidePlankSupportSide.LEFT) // HOLD_START

        // Feed 30 seconds of stable tracking at ~30 fps (33ms intervals, 900 frames)
        for (i in 1..900) {
            timeMs += 33L
            val jitter = if (i % 2 == 0) 1.0f else -1.0f
            stateMachine.processAngle(180.0f + jitter, timeMs, true)
        }

        assertEquals("Should be in HOLDING phase", SidePlankPhase.HOLDING, stateMachine.sidePlankPhase)
        assertEquals("Hold duration should accumulate to ≈30.0s", 29.7f, stateMachine.holdDurationSec, 0.5f)
    }

    /**
     * Unit Test 2: Sustained lateral deviation -> HOLD_END triggers, timer stops.
     */
    @Test
    fun testUnit2_SustainedLateralDeviationTriggersHoldEnd() {
        val stateMachine = SidePlankStateMachine(gracePeriodMs = 1000L, minDeviationFramesToFail = 6)
        var timeMs = 1000L

        // Enter hold and maintain for 5s
        stateMachine.processAngle(180.0f, timeMs, true, SidePlankSupportSide.LEFT)
        for (i in 1..50) {
            timeMs += 100L
            stateMachine.processAngle(180.0f, timeMs, true)
        }
        assertEquals(SidePlankPhase.HOLDING, stateMachine.sidePlankPhase)

        // Lateral angle drops to 150° (sagging beyond 15° tolerance) and persists for 1400ms (> 1000ms grace period)
        for (i in 1..14) {
            timeMs += 100L
            stateMachine.processAngle(150.0f, timeMs, true)
        }

        assertEquals("HOLD_END must trigger after sustained lateral deviation", SidePlankPhase.HOLD_END, stateMachine.sidePlankPhase)
        assertTrue("isHoldEnded must be true", stateMachine.isHoldEnded)
        val finalizedHoldDuration = stateMachine.holdDurationSec

        // Subsequent frames must not increase hold duration
        timeMs += 500L
        stateMachine.processAngle(180.0f, timeMs, true)
        timeMs += 500L
        stateMachine.processAngle(180.0f, timeMs, true)

        assertEquals("Finalized hold duration must remain fixed", finalizedHoldDuration, stateMachine.holdDurationSec, 0.01f)
    }

    /**
     * Unit Test 3: Brief wobble -> postural_break fires, timer continues.
     */
    @Test
    fun testUnit3_BriefWobbleFiresPosturalBreakAndTimerContinues() {
        val stateMachine = SidePlankStateMachine(gracePeriodMs = 1000L, minDeviationFramesToFail = 6)
        val formEngine = SidePlankFormRuleEngine()

        var timeMs = 1000L

        // Hold stably for 3s
        stateMachine.processAngle(180.0f, timeMs, true, SidePlankSupportSide.LEFT)
        for (i in 1..30) {
            timeMs += 100L
            val state = stateMachine.processAngle(180.0f, timeMs, true)
            formEngine.evaluateFrame(180.0f, state.isRepInProgress, timeMs, 0.95f, true)
        }
        assertEquals(SidePlankPhase.HOLDING, stateMachine.sidePlankPhase)

        // Brief wobble for 2 frames (200ms < 1000ms grace period) at 155°
        timeMs += 100L
        var state = stateMachine.processAngle(155.0f, timeMs, true)
        formEngine.evaluateFrame(155.0f, state.isRepInProgress, timeMs, 0.95f, true)

        timeMs += 100L
        state = stateMachine.processAngle(155.0f, timeMs, true)
        formEngine.evaluateFrame(155.0f, state.isRepInProgress, timeMs, 0.95f, true)

        // Wobble recovers to 180°!
        timeMs += 100L
        state = stateMachine.processAngle(180.0f, timeMs, true)
        formEngine.evaluateFrame(180.0f, state.isRepInProgress, timeMs, 0.95f, true, isWobbleRecovered = stateMachine.lastWobbleDetected)

        // Assert: Hold did NOT end on brief wobble
        assertEquals("Hold must remain active after brief wobble recovery", SidePlankPhase.HOLDING, stateMachine.sidePlankPhase)
        assertFalse("Hold must not be ended", stateMachine.isHoldEnded)

        // Assert: postural_break was recorded
        val posturalBreakErrors = formEngine.allSessionErrors.filter { it.errorName == SidePlankFormRules.POSTURAL_BREAK.errorName }
        assertTrue("postural_break error must be recorded on brief wobble", posturalBreakErrors.isNotEmpty())
    }

    /**
     * Unit Test 4: Visibility gap pauses (not resets) the timer.
     */
    @Test
    fun testUnit4_VisibilityGapPausesWithoutResettingTimer() {
        val stateMachine = SidePlankStateMachine()
        var timeMs = 1000L

        // Hold for 10 seconds
        stateMachine.processAngle(180.0f, timeMs, true, SidePlankSupportSide.LEFT)
        for (i in 1..100) {
            timeMs += 100L
            stateMachine.processAngle(180.0f, timeMs, true)
        }
        val holdBeforeGap = stateMachine.holdDurationSec
        assertEquals("Hold should reach 10.0s before gap", 10.0f, holdBeforeGap, 0.2f)

        // Visibility gap for 5 seconds
        for (i in 1..50) {
            timeMs += 100L
            stateMachine.processAngle(180.0f, timeMs, false) // isVisibilitySufficient = false
        }

        assertTrue("Timer must be paused during tracking gap", stateMachine.isPaused)
        assertEquals("Hold duration must NOT reset to 0 during tracking gap", holdBeforeGap, stateMachine.holdDurationSec, 0.01f)

        // Visibility returns for another 10 seconds
        for (i in 1..100) {
            timeMs += 100L
            stateMachine.processAngle(180.0f, timeMs, true)
        }

        assertFalse("Timer must resume when visibility returns", stateMachine.isPaused)
        assertEquals("Hold duration must resume and reach ≈20.0s", 20.0f, stateMachine.holdDurationSec, 0.5f)
    }

    /**
     * Unit Test 5: Postural ROM% hand-calculated verification for at least 3 known body_line_angle values.
     * Formula: clamp((180 - abs(180 - angle)) / 180 * 100, 0, 100)
     */
    @Test
    fun testUnit5_PosturalRomHandCalculatedVerification() {
        // Value 1: 180.0° -> 100.0%
        val rom180 = SidePlankGeometry.calculatePosturalRom(180.0f)
        assertEquals("180° must yield 100% ROM", 100.0f, rom180, 0.01f)

        // Value 2: 162.0° (18° deviation) -> (180 - 18) / 180 * 100 = 90.0%
        val rom162 = SidePlankGeometry.calculatePosturalRom(162.0f)
        assertEquals("162° must yield 90% ROM", 90.0f, rom162, 0.01f)

        // Value 3: 144.0° (36° deviation) -> (180 - 36) / 180 * 100 = 80.0%
        val rom144 = SidePlankGeometry.calculatePosturalRom(144.0f)
        assertEquals("144° must yield 80% ROM", 80.0f, rom144, 0.01f)

        // Value 4: 198.0° (18° piking deviation) -> (180 - 18) / 180 * 100 = 90.0%
        val rom198 = SidePlankGeometry.calculatePosturalRom(198.0f)
        assertEquals("198° must yield 90% ROM", 90.0f, rom198, 0.01f)
    }

    /**
     * Unit Test 6: TuT Factor (consistency formula) hand-calculated verification:
     * tutFactor = clamp(1 - (stddev / max_allowed_stddev), 0, 1.5)
     */
    @Test
    fun testUnit6_TutFactorHandCalculatedVerification() {
        val maxStddev = 15.0f

        // Case A: Perfect consistency (stddev = 0.0) -> TuT = 1.00
        val stableSamples = listOf(180.0f, 180.0f, 180.0f, 180.0f, 180.0f)
        val tutStable = SidePlankGeometry.calculateTutFactor(stableSamples, maxStddev)
        assertEquals("Zero stddev must yield 1.00 TuT factor", 1.00f, tutStable, 0.01f)

        // Case B: Small wobble (samples: 178.5°, 181.5° -> stddev = 1.5°)
        // tutFactor = 1 - (1.5 / 15.0) = 0.90
        val lowVarianceSamples = listOf(178.5f, 181.5f, 178.5f, 181.5f)
        val tutLowVariance = SidePlankGeometry.calculateTutFactor(lowVarianceSamples, maxStddev)
        assertEquals("1.5° stddev must yield 0.90 TuT factor", 0.90f, tutLowVariance, 0.02f)

        // Case C: Significant wobble (stddev = 9.0°)
        // tutFactor = 1 - (9.0 / 15.0) = 0.40
        val highVarianceSamples = listOf(171.0f, 189.0f, 171.0f, 189.0f)
        val tutHighVariance = SidePlankGeometry.calculateTutFactor(highVarianceSamples, maxStddev)
        assertEquals("9.0° stddev must yield 0.40 TuT factor", 0.40f, tutHighVariance, 0.02f)

        assertTrue(tutLowVariance > tutHighVariance)
    }

    /**
     * Unit Test 7: Support-side detection (left vs right) works correctly.
     */
    @Test
    fun testUnit7_SupportSideDetectionWorksCorrectly() {
        // 1. Synthetic frame with LEFT side contacting ground (left elbow/shoulder/hip lower, higher y)
        val leftPlankLandmarks = createSyntheticLandmarks(180.0f, SidePlankSupportSide.LEFT)
        val detectedLeft = SidePlankGeometry.detectSupportSide(leftPlankLandmarks)
        assertEquals("Must detect LEFT support side when left elbow/shoulder/hip are closer to floor",
            SidePlankSupportSide.LEFT, detectedLeft)

        // 2. Synthetic frame with RIGHT side contacting ground (right elbow/shoulder/hip lower, higher y)
        val rightPlankLandmarks = createSyntheticLandmarks(180.0f, SidePlankSupportSide.RIGHT)
        val detectedRight = SidePlankGeometry.detectSupportSide(rightPlankLandmarks)
        assertEquals("Must detect RIGHT support side when right elbow/shoulder/hip are closer to floor",
            SidePlankSupportSide.RIGHT, detectedRight)
    }

    /**
     * Unit Test 8: Static hold contract shape correct (completeReps=null, etc.) per SDK_CONTRACT.md.
     */
    @Test
    fun testUnit8_StaticHoldContractShapeCorrect() {
        val analyzer = ExerciseAnalyzer("side_plank", "Side Plank")
        var timeMs = 1000L

        // Feed 15 seconds of synthetic frames at 180°
        analyzer.analyzeSyntheticFrame(
            elbowAngle = 180.0f,
            hipLineAngle = 180.0f,
            timestampMs = timeMs,
            confidence = 0.95f,
            isVisibilitySufficient = true
        )

        for (i in 1..150) {
            timeMs += 100L
            analyzer.analyzeSyntheticFrame(
                elbowAngle = 180.0f,
                hipLineAngle = 180.0f,
                timestampMs = timeMs,
                confidence = 0.95f,
                isVisibilitySufficient = true
            )
        }

        val sessionResult = analyzer.getSessionResult()

        assertEquals("Session status must be VALID", ValidationStatus.VALID, sessionResult.status)
        assertNull("completeReps must be strictly NULL for static_hold per SDK_CONTRACT.md", sessionResult.completeReps)
        assertNull("incompleteReps must be strictly NULL for static_hold per SDK_CONTRACT.md", sessionResult.incompleteReps)
        assertNull("avgRepDurationSec must be strictly NULL for static_hold per SDK_CONTRACT.md", sessionResult.avgRepDurationSec)

        assertNotNull("holdDurationSec must NOT be null for static_hold", sessionResult.holdDurationSec)
        assertTrue("holdDurationSec must accumulate to ≈15s, was: ${sessionResult.holdDurationSec}", sessionResult.holdDurationSec!! >= 14.5f)

        assertNotNull("romPercent must not be null for valid session", sessionResult.romPercent)
        assertEquals("romPercent should be 100% for 180° hold", 100.0f, sessionResult.romPercent!!, 1.0f)

        assertNotNull("tutFactor must not be null for valid session", sessionResult.tutFactor)
        assertEquals("tutFactor should be 1.00 for stable hold", 1.00f, sessionResult.tutFactor!!, 0.05f)

        assertNotNull("formFactor must not be null", sessionResult.formFactor)
        assertEquals("formFactor should be 1.00 when no errors occurred", 1.00f, sessionResult.formFactor!!, 0.01f)
    }

    /**
     * Unit Test 9: Verify no shared engine code modified.
     * Asserts that the 5 base engine files have ZERO modifications relative to git HEAD.
     */
    @Test
    fun testUnit9_VerifyNoSharedEngineCodeModified() {
        val unmodifiedSharedEngineFiles = listOf(
            "sdk/src/main/kotlin/com/example/cvassessment/sdk/visibility/VisibilityGate.kt",
            "sdk/src/main/kotlin/com/example/cvassessment/sdk/statemachine/ExerciseStateMachine.kt",
            "sdk/src/main/kotlin/com/example/cvassessment/sdk/metrics/MetricsEngine.kt",
            "sdk/src/main/kotlin/com/example/cvassessment/sdk/form/FormRuleEngine.kt",
            "sdk/src/main/kotlin/com/example/cvassessment/sdk/output/OutputGate.kt"
        )

        for (filePath in unmodifiedSharedEngineFiles) {
            val process = ProcessBuilder("git", "diff", "HEAD", "--", filePath)
                .redirectErrorStream(true)
                .start()
            val diffOutput = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()

            assertTrue(
                "Shared engine file $filePath must have zero diff relative to HEAD! Diff: $diffOutput",
                diffOutput.isEmpty()
            )
        }
    }
}
