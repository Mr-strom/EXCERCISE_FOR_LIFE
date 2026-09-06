package com.example.cvassessment.sdk

import com.example.cvassessment.sdk.form.PlankFormRuleEngine
import com.example.cvassessment.sdk.form.PlankFormRules
import com.example.cvassessment.sdk.pose.PoseLandmark
import com.example.cvassessment.sdk.pose.PoseLandmarkType
import com.example.cvassessment.sdk.statemachine.PlankGeometry
import com.example.cvassessment.sdk.statemachine.PlankPhase
import com.example.cvassessment.sdk.statemachine.PlankStateMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 10 Required Acceptance Criteria Unit Tests for Plank Implementation (First Static Hold).
 * Per EXERCISE_SPEC.md #7, METRICS_SPEC.md §2, §4, §5, FORM_RULES.md, and SDK_CONTRACT.md.
 */
class PlankAssessmentTest {

    private fun createSyntheticLandmarks(
        hipLineAngle: Float = 180.0f,
        visibility: Float = 0.95f
    ): List<PoseLandmark> {
        val landmarks = mutableListOf<PoseLandmark>()
        // Shoulder at (0.2, 0.7), Ankle at (0.8, 0.7)
        // Straight line expectedLineY at x=0.5 is 0.7.
        // For sagging (< 180°), hip y > 0.7. For piking (> 180°), hip y < 0.7.
        val hipY = when {
            hipLineAngle < 170.0f -> 0.75f // sag
            hipLineAngle > 190.0f -> 0.65f // pike
            else -> 0.70f // straight
        }

        for (i in 0..32) {
            val (x, y, z) = when (i) {
                PoseLandmarkType.LEFT_SHOULDER -> Triple(0.20f, 0.70f, 0.0f)
                PoseLandmarkType.RIGHT_SHOULDER -> Triple(0.20f, 0.70f, 0.0f)
                PoseLandmarkType.LEFT_ELBOW -> Triple(0.20f, 0.85f, 0.0f)
                PoseLandmarkType.RIGHT_ELBOW -> Triple(0.20f, 0.85f, 0.0f)
                PoseLandmarkType.LEFT_HIP -> Triple(0.50f, hipY, 0.0f)
                PoseLandmarkType.RIGHT_HIP -> Triple(0.50f, hipY, 0.0f)
                PoseLandmarkType.LEFT_ANKLE -> Triple(0.80f, 0.70f, 0.0f)
                PoseLandmarkType.RIGHT_ANKLE -> Triple(0.80f, 0.70f, 0.0f)
                else -> Triple(0.5f, 0.5f, 0.0f)
            }
            landmarks.add(PoseLandmark(index = i, name = "LM_$i", x = x, y = y, z = z, visibility = visibility))
        }
        return landmarks
    }

    /**
     * Unit Test 1: Hold_start detected when hip_line_angle enters tolerance range (180° ± 15°).
     */
    @Test
    fun testUnit1_HoldStartDetectedWhenEnteringTolerance() {
        val stateMachine = PlankStateMachine()
        assertEquals("Initial phase must be NOT_STARTED", PlankPhase.NOT_STARTED, stateMachine.plankPhase)

        var timeMs = 1000L

        // Angle outside tolerance (145° sag) -> should not start hold
        stateMachine.processAngle(145.0f, timeMs, true)
        assertEquals("Should remain NOT_STARTED when outside tolerance", PlankPhase.NOT_STARTED, stateMachine.plankPhase)

        timeMs += 100L
        // Angle enters tolerance range (175° is within [165°, 195°])
        stateMachine.processAngle(175.0f, timeMs, true)
        assertEquals("Should transition to HOLD_START on entering tolerance", PlankPhase.HOLD_START, stateMachine.plankPhase)

        timeMs += 100L
        // Sustained within tolerance -> moves to HOLDING
        stateMachine.processAngle(178.0f, timeMs, true)
        assertEquals("Should transition to HOLDING on subsequent frame", PlankPhase.HOLDING, stateMachine.plankPhase)
    }

    /**
     * Unit Test 2: Hold duration accumulates correctly across a sustained hold.
     * 30 seconds of stable tracking -> holdDurationSec ≈ 30.0s.
     */
    @Test
    fun testUnit2_HoldDurationAccumulatesCorrectlyAcrossSustainedHold() {
        val stateMachine = PlankStateMachine()
        var timeMs = 1000L

        // Enter hold
        stateMachine.processAngle(180.0f, timeMs, true) // HOLD_START

        // Feed 30 seconds of stable tracking at ~30 fps (33ms intervals, 900 frames)
        for (i in 1..900) {
            timeMs += 33L
            val jitter = if (i % 2 == 0) 1.0f else -1.0f
            stateMachine.processAngle(180.0f + jitter, timeMs, true)
        }

        assertEquals("Should be in HOLDING phase", PlankPhase.HOLDING, stateMachine.plankPhase)
        // 900 * 33ms = 29,700ms ≈ 29.7s
        assertEquals("Hold duration should accumulate to ≈30.0s", 29.7f, stateMachine.holdDurationSec, 0.5f)
    }

    /**
     * Unit Test 3: Visibility gap during a good hold -> timer PAUSES, does NOT reset to zero,
     * resumes correctly when visibility returns (verifying pause vs dynamic discard).
     */
    @Test
    fun testUnit3_VisibilityGapPausesTimerWithoutResetting() {
        val stateMachine = PlankStateMachine()
        var timeMs = 1000L

        // Hold for 10 seconds (1000ms to 11000ms)
        stateMachine.processAngle(180.0f, timeMs, true)
        for (i in 1..100) {
            timeMs += 100L
            stateMachine.processAngle(180.0f, timeMs, true)
        }
        val holdBeforeGap = stateMachine.holdDurationSec
        assertEquals("Hold should reach 10.0s before gap", 10.0f, holdBeforeGap, 0.2f)

        // Visibility gap for 5 seconds (11000ms to 16000ms)
        for (i in 1..50) {
            timeMs += 100L
            stateMachine.processAngle(180.0f, timeMs, false) // isVisibilitySufficient = false
        }

        assertTrue("Timer must be paused during tracking gap", stateMachine.isPaused)
        assertEquals("Hold duration must NOT reset to 0 during tracking gap", holdBeforeGap, stateMachine.holdDurationSec, 0.01f)

        // Visibility returns for another 10 seconds (16000ms to 26000ms)
        for (i in 1..100) {
            timeMs += 100L
            stateMachine.processAngle(180.0f, timeMs, true)
        }

        assertFalse("Timer must resume when visibility returns", stateMachine.isPaused)
        // Total hold duration: 10s + 10s = 20s (gap is excluded, accumulated hold is preserved!)
        assertEquals("Hold duration must resume and reach ≈20.0s", 20.0f, stateMachine.holdDurationSec, 0.5f)
    }

    /**
     * Unit Test 4: Hold_end correctly triggers when hip_line_angle deviates beyond tolerance
     * for longer than the grace period.
     */
    @Test
    fun testUnit4_HoldEndTriggersOnSustainedDeviationBeyondGracePeriod() {
        val stateMachine = PlankStateMachine(gracePeriodMs = 1000L, minDeviationFramesToFail = 6)
        var timeMs = 1000L

        // Enter hold and maintain for 5s
        stateMachine.processAngle(180.0f, timeMs, true)
        for (i in 1..50) {
            timeMs += 100L
            stateMachine.processAngle(180.0f, timeMs, true)
        }
        assertEquals(PlankPhase.HOLDING, stateMachine.plankPhase)
        val holdAtDeviationStart = stateMachine.holdDurationSec

        // Angle drops to 150° (sagging beyond 15° tolerance) and persists for 1400ms (> 1000ms grace period)
        for (i in 1..14) {
            timeMs += 100L
            stateMachine.processAngle(150.0f, timeMs, true)
        }

        assertEquals("HOLD_END must trigger after sustained deviation", PlankPhase.HOLD_END, stateMachine.plankPhase)
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
     * Unit Test 5: ROM% (postural deviation formula) hand-calculated verification
     * for at least 3 known hip_line_angle values.
     * Formula: clamp((180 - abs(180 - angle)) / 180 * 100, 0, 100)
     */
    @Test
    fun testUnit5_RomPosturalDeviationFormulaHandCalculation() {
        // Value 1: Perfect straight line (180.0°) -> 100.0%
        val rom180 = PlankGeometry.calculatePosturalRom(180.0f)
        assertEquals("180° must yield 100% ROM", 100.0f, rom180, 0.01f)

        // Value 2: 162.0° (18° deviation) -> (180 - 18) / 180 * 100 = 162 / 180 * 100 = 90.0%
        val rom162 = PlankGeometry.calculatePosturalRom(162.0f)
        assertEquals("162° must yield 90% ROM", 90.0f, rom162, 0.01f)

        // Value 3: 144.0° (36° deviation) -> (180 - 36) / 180 * 100 = 144 / 180 * 100 = 80.0%
        val rom144 = PlankGeometry.calculatePosturalRom(144.0f)
        assertEquals("144° must yield 80% ROM", 80.0f, rom144, 0.01f)

        // Value 4: 198.0° (18° piking deviation) -> (180 - 18) / 180 * 100 = 90.0%
        val rom198 = PlankGeometry.calculatePosturalRom(198.0f)
        assertEquals("198° must yield 90% ROM", 90.0f, rom198, 0.01f)
    }

    /**
     * Unit Test 6: TuT Factor (consistency formula) hand-calculated verification:
     * tutFactor = clamp(1 - (stddev / max_allowed_stddev), 0, 1.5)
     * Low variance over hold -> TuT close to 1.0; high variance -> lower TuT.
     */
    @Test
    fun testUnit6_TutFactorConsistencyFormulaHandCalculation() {
        val maxStddev = 15.0f

        // Case A: Perfect consistency (stddev = 0.0) -> TuT = 1.00
        val stableSamples = listOf(180.0f, 180.0f, 180.0f, 180.0f, 180.0f)
        val tutStable = PlankGeometry.calculateTutFactor(stableSamples, maxStddev)
        assertEquals("Zero stddev must yield 1.00 TuT factor", 1.00f, tutStable, 0.01f)

        // Case B: Small wobble (samples: 178.5°, 181.5° -> mean 180.0°, deviations ±1.5°, variance = 2.25, stddev = 1.5°)
        // tutFactor = 1 - (1.5 / 15.0) = 1 - 0.10 = 0.90
        val lowVarianceSamples = listOf(178.5f, 181.5f, 178.5f, 181.5f)
        val tutLowVariance = PlankGeometry.calculateTutFactor(lowVarianceSamples, maxStddev)
        assertEquals("1.5° stddev must yield 0.90 TuT factor", 0.90f, tutLowVariance, 0.02f)

        // Case C: Significant wobble (samples with higher stddev e.g. ~9.0°)
        // tutFactor = 1 - (9.0 / 15.0) = 1 - 0.60 = 0.40
        val highVarianceSamples = listOf(171.0f, 189.0f, 171.0f, 189.0f) // stddev = 9.0°
        val tutHighVariance = PlankGeometry.calculateTutFactor(highVarianceSamples, maxStddev)
        assertEquals("9.0° stddev must yield 0.40 TuT factor", 0.40f, tutHighVariance, 0.02f)

        assertTrue("Low variance TuT ($tutLowVariance) must be higher than high variance TuT ($tutHighVariance)",
            tutLowVariance > tutHighVariance)
    }

    /**
     * Unit Test 7: postural_break triggers correctly on a brief wobble that recovers (doesn't end hold)
     * vs hold_end triggers on a sustained deviation.
     */
    @Test
    fun testUnit7_PosturalBreakOnBriefWobbleVsHoldEndOnSustainedDeviation() {
        val stateMachine = PlankStateMachine(gracePeriodMs = 1000L, minDeviationFramesToFail = 6)
        val formEngine = PlankFormRuleEngine()

        var timeMs = 1000L

        // Hold stably for 3s
        stateMachine.processAngle(180.0f, timeMs, true)
        for (i in 1..30) {
            timeMs += 100L
            val state = stateMachine.processAngle(180.0f, timeMs, true)
            formEngine.evaluateFrame(180.0f, state.isRepInProgress, timeMs, 0.95f, true)
        }
        assertEquals(PlankPhase.HOLDING, stateMachine.plankPhase)

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

        // Assert: Hold did NOT end on brief wobble!
        assertEquals("Hold must remain active after brief wobble recovery", PlankPhase.HOLDING, stateMachine.plankPhase)
        assertFalse("Hold must not be ended", stateMachine.isHoldEnded)

        // Assert: postural_break was recorded
        val posturalBreakErrors = formEngine.allSessionErrors.filter { it.errorName == PlankFormRules.POSTURAL_BREAK.errorName }
        assertTrue("postural_break error must be recorded on brief wobble", posturalBreakErrors.isNotEmpty())

        // Now trigger sustained deviation for 1500ms (> gracePeriodMs)
        for (i in 1..15) {
            timeMs += 100L
            stateMachine.processAngle(150.0f, timeMs, true)
        }

        assertEquals("Sustained deviation must trigger HOLD_END", PlankPhase.HOLD_END, stateMachine.plankPhase)
    }

    /**
     * Unit Test 8: SessionResult correctly shows completeReps=null, incompleteReps=null,
     * avgRepDurationSec=null, holdDurationSec=<value> for Plank specifically (SDK_CONTRACT.md static_hold shape).
     */
    @Test
    fun testUnit8_SessionResultStaticHoldContractShape() {
        val analyzer = ExerciseAnalyzer("plank", "Plank")

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
     * Unit Test 9: Confirm hips_dropping and hips_piking also apply correctly to Plank
     * (shared error definitions from FORM_RULES.md catalog, reused across Push-Up and Plank).
     */
    @Test
    fun testUnit9_HipsDroppingAndHipsPikingApplyToPlank() {
        val formEngine = PlankFormRuleEngine(persistenceFrames = 3)
        var timeMs = 1000L

        // 1. Test hips_dropping: hip_line_angle < 165° for >= 3 frames
        for (i in 1..3) {
            timeMs += 100L
            formEngine.evaluateFrame(hipLineAngle = 155.0f, isHoldInProgress = true, timestampMs = timeMs, confidence = 0.95f)
        }

        val hipsDropping = formEngine.allSessionErrors.filter { it.errorName == PlankFormRules.HIPS_DROPPING.errorName }
        assertTrue("hips_dropping error must be logged for Plank", hipsDropping.isNotEmpty())
        assertEquals("Severity for hips_dropping must be 0.7", 0.7f, hipsDropping.first().severity, 0.01f)

        val hipsDroppingFeedback = formEngine.allFeedbackEvents.filter { it.relatedError == PlankFormRules.HIPS_DROPPING.errorName }
        assertTrue("Feedback event for hips_dropping must be emitted", hipsDroppingFeedback.isNotEmpty())
        assertEquals("Keep your hips up.", hipsDroppingFeedback.first().message)

        // Advance past cooldown
        timeMs += 5000L

        // 2. Test hips_piking: hip_line_angle > 195° for >= 3 frames
        for (i in 1..3) {
            timeMs += 100L
            formEngine.evaluateFrame(hipLineAngle = 205.0f, isHoldInProgress = true, timestampMs = timeMs, confidence = 0.95f)
        }

        val hipsPiking = formEngine.allSessionErrors.filter { it.errorName == PlankFormRules.HIPS_PIKING.errorName }
        assertTrue("hips_piking error must be logged for Plank", hipsPiking.isNotEmpty())
        assertEquals("Severity for hips_piking must be 0.5", 0.5f, hipsPiking.first().severity, 0.01f)

        val hipsPikingFeedback = formEngine.allFeedbackEvents.filter { it.relatedError == PlankFormRules.HIPS_PIKING.errorName }
        assertTrue("Feedback event for hips_piking must be emitted", hipsPikingFeedback.isNotEmpty())
        assertEquals("Lower your hips slightly.", hipsPikingFeedback.first().message)
    }

    /**
     * Unit Test 10: Verify NO shared engine code was modified.
     * Asserts that the 5 base engine files have ZERO modifications relative to git HEAD.
     */
    @Test
    fun testUnit10_VerifyNoSharedEngineCodeModified() {
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
