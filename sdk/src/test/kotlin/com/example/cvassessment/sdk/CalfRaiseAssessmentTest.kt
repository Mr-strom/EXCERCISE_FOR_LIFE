package com.example.cvassessment.sdk

import com.example.cvassessment.sdk.metrics.RepMetrics
import com.example.cvassessment.sdk.pose.PoseEstimationResult
import com.example.cvassessment.sdk.pose.PoseLandmark
import com.example.cvassessment.sdk.pose.PoseLandmarkType
import com.example.cvassessment.sdk.statemachine.CalfRaiseGeometry
import com.example.cvassessment.sdk.statemachine.CalfRaiseStateMachine
import com.example.cvassessment.sdk.statemachine.ExercisePhase
import com.example.cvassessment.sdk.visibility.CalfRaiseVisibilityGate
import com.example.cvassessment.sdk.visibility.VisibilityStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 11 Required Acceptance Criteria Unit Tests for Calf Raise Implementation.
 * Per EXERCISE_SPEC.md, DECISIONS.md D7, D12, and D13.
 */
class CalfRaiseAssessmentTest {

    private fun createSyntheticLandmarks(
        isSideView: Boolean = true,
        heelY: Float = 0.90f,
        visibility: Float = 0.95f
    ): List<PoseLandmark> {
        val shoulderLeftX = if (isSideView) 0.51f else 0.40f
        val shoulderRightX = if (isSideView) 0.49f else 0.60f
        val hipLX = if (isSideView) 0.50f else 0.42f
        val hipRX = if (isSideView) 0.50f else 0.58f

        val landmarks = mutableListOf<PoseLandmark>()
        for (i in 0..32) {
            val (x, y, z) = when (i) {
                PoseLandmarkType.LEFT_SHOULDER -> Triple(shoulderLeftX, 0.30f, if (isSideView) -0.15f else 0.0f)
                PoseLandmarkType.RIGHT_SHOULDER -> Triple(shoulderRightX, 0.30f, if (isSideView) 0.15f else 0.0f)
                PoseLandmarkType.LEFT_HIP -> Triple(hipLX, 0.50f, if (isSideView) -0.15f else 0.0f)
                PoseLandmarkType.RIGHT_HIP -> Triple(hipRX, 0.50f, if (isSideView) 0.15f else 0.0f)
                PoseLandmarkType.LEFT_KNEE -> Triple(0.50f, 0.70f, 0.0f)
                PoseLandmarkType.RIGHT_KNEE -> Triple(0.50f, 0.70f, 0.0f)
                PoseLandmarkType.LEFT_ANKLE -> Triple(0.50f, heelY - 0.02f, 0.0f)
                PoseLandmarkType.RIGHT_ANKLE -> Triple(0.50f, heelY - 0.02f, 0.0f)
                PoseLandmarkType.LEFT_HEEL -> Triple(0.48f, heelY, 0.0f)
                PoseLandmarkType.RIGHT_HEEL -> Triple(0.48f, heelY, 0.0f)
                PoseLandmarkType.LEFT_FOOT_INDEX -> Triple(0.52f, heelY + 0.02f, 0.0f)
                PoseLandmarkType.RIGHT_FOOT_INDEX -> Triple(0.52f, heelY + 0.02f, 0.0f)
                else -> Triple(0.5f, 0.5f, 0.0f)
            }
            landmarks.add(PoseLandmark(index = i, name = "LM_$i", x = x, y = y, z = z, visibility = visibility))
        }
        return landmarks
    }

    /**
     * Unit Test 1: Baseline calibration correctly captures resting heel position from a stable initial sequence.
     */
    @Test
    fun testUnit1_BaselineCalibrationCapturesRestingHeel() {
        val stateMachine = CalfRaiseStateMachine()
        assertFalse("Should not be calibrated initially", stateMachine.isCalibrated)

        var timeMs = 1000L
        val restingHeelY = 0.880f

        // Feed 10 stable frames over 1000ms
        for (i in 1..10) {
            val jitter = if (i % 2 == 0) 0.001f else -0.001f
            stateMachine.processHeelY(heelY = restingHeelY + jitter, timestampMs = timeMs)
            timeMs += 100L
        }

        assertTrue("Baseline calibration must succeed on stable standing sequence", stateMachine.isCalibrated)
        assertEquals("Calibrated baseline Y must match resting heel position", restingHeelY, stateMachine.baselineHeelY, 0.005f)
    }

    /**
     * Unit Test 2: First rep sets personal_max_reference, ROM% for that rep = 100%.
     */
    @Test
    fun testUnit2_FirstRepSetsPersonalMaxReferenceAnd100Rom() {
        val stateMachine = CalfRaiseStateMachine()
        stateMachine.calibrate(baselineY = 0.88f)
        assertNull("Personal max reference should be null before any reps", stateMachine.personalMaxReference)

        var timeMs = 1000L
        // Rep 1: Baseline 0.88 -> Rise to 0.78 (elevation = 0.10) -> Descend to 0.88
        stateMachine.processHeelY(heelY = 0.88f, timestampMs = timeMs) // BOTTOM
        timeMs += 300L
        stateMachine.processHeelY(heelY = 0.84f, timestampMs = timeMs) // ASCENDING (elev = 0.04)
        timeMs += 300L
        stateMachine.processHeelY(heelY = 0.78f, timestampMs = timeMs) // TOP (elev = 0.10)
        timeMs += 300L
        stateMachine.processHeelY(heelY = 0.83f, timestampMs = timeMs) // DESCENDING
        timeMs += 300L
        stateMachine.processHeelY(heelY = 0.88f, timestampMs = timeMs) // Return to BOTTOM

        assertEquals("Should complete 1 rep", 1, stateMachine.completeReps.size)
        assertNotNull("Personal max reference must be set by first rep", stateMachine.personalMaxReference)
        assertEquals("Personal max reference should match peak elevation (0.10f)", 0.10f, stateMachine.personalMaxReference!!, 0.005f)

        val rep1Metrics = stateMachine.latestCompletedRepMetrics
        assertNotNull("RepMetrics must be recorded", rep1Metrics)
        assertEquals("First rep must have ROM% = 100%", 100.0f, rep1Metrics!!.romPercent, 0.1f)
    }

    /**
     * Unit Test 3: Subsequent rep with LOWER elevation than reference -> ROM% < 100%, correctly proportional.
     */
    @Test
    fun testUnit3_SubsequentRepWithLowerElevationScoresProportionally() {
        val stateMachine = CalfRaiseStateMachine()
        stateMachine.calibrate(baselineY = 0.88f)

        var timeMs = 1000L
        // Rep 1 establishes reference = 0.10
        stateMachine.processHeelY(0.88f, timeMs); timeMs += 300L
        stateMachine.processHeelY(0.78f, timeMs); timeMs += 300L
        stateMachine.processHeelY(0.88f, timeMs); timeMs += 500L
        assertEquals(1, stateMachine.completeReps.size)
        assertEquals(0.10f, stateMachine.personalMaxReference!!, 0.005f)

        // Rep 2 achieves peak heelY = 0.81 (elevation = 0.88 - 0.81 = 0.07)
        stateMachine.processHeelY(0.88f, timeMs); timeMs += 300L
        stateMachine.processHeelY(0.84f, timeMs); timeMs += 300L
        stateMachine.processHeelY(0.81f, timeMs); timeMs += 300L // Peak elev = 0.07
        stateMachine.processHeelY(0.85f, timeMs); timeMs += 300L
        stateMachine.processHeelY(0.88f, timeMs) // Complete Rep 2

        assertEquals(2, stateMachine.completeReps.size)
        // Reference must remain 0.10f
        assertEquals(0.10f, stateMachine.personalMaxReference!!, 0.005f)

        val rep2Metrics = stateMachine.latestCompletedRepMetrics
        assertNotNull(rep2Metrics)
        // Expected ROM = (0.07 / 0.10) * 100 = 70.0%
        assertEquals("Rep 2 ROM% must be proportional (70%)", 70.0f, rep2Metrics!!.romPercent, 0.5f)
    }

    /**
     * Unit Test 4: Subsequent rep with HIGHER elevation than current reference ->
     * personal_max_reference updates, that rep's ROM% = 100%, but PREVIOUS reps'
     * recorded ROM% values remain unchanged (not retroactively altered).
     */
    @Test
    fun testUnit4_HigherElevationUpdatesReferenceNonRetroactively() {
        val stateMachine = CalfRaiseStateMachine()
        stateMachine.calibrate(baselineY = 0.88f)

        var timeMs = 1000L
        // Rep 1: elev = 0.10 (heel 0.78) -> ROM 100%
        stateMachine.processHeelY(0.88f, timeMs); timeMs += 200L
        stateMachine.processHeelY(0.78f, timeMs); timeMs += 200L
        stateMachine.processHeelY(0.88f, timeMs); timeMs += 400L

        // Rep 2: elev = 0.07 (heel 0.81) -> ROM 70%
        stateMachine.processHeelY(0.88f, timeMs); timeMs += 200L
        stateMachine.processHeelY(0.81f, timeMs); timeMs += 200L
        stateMachine.processHeelY(0.88f, timeMs); timeMs += 400L

        assertEquals(0.10f, stateMachine.personalMaxReference!!, 0.005f)
        assertEquals(100.0f, stateMachine.allRepMetrics[0].romPercent, 0.1f)
        assertEquals(70.0f, stateMachine.allRepMetrics[1].romPercent, 0.5f)

        // Rep 3: elev = 0.12 (heel 0.76) > 0.10 -> personalMaxReference should update to 0.12!
        stateMachine.processHeelY(0.88f, timeMs); timeMs += 200L
        stateMachine.processHeelY(0.82f, timeMs); timeMs += 200L
        stateMachine.processHeelY(0.76f, timeMs); timeMs += 200L // Peak elev = 0.12
        stateMachine.processHeelY(0.82f, timeMs); timeMs += 200L
        stateMachine.processHeelY(0.88f, timeMs) // Complete Rep 3

        assertEquals(3, stateMachine.completeReps.size)
        assertEquals("Personal max reference must update to highest elevation (0.12f)", 0.12f, stateMachine.personalMaxReference!!, 0.005f)

        // Rep 3 scored against new max (0.12 / 0.12 = 100%)
        assertEquals("Rep 3 must score 100% against new reference", 100.0f, stateMachine.allRepMetrics[2].romPercent, 0.1f)

        // CRITICAL CHECK: Past reps must NOT be retroactively altered
        assertEquals("Rep 1 recorded ROM% must remain unchanged (100%)", 100.0f, stateMachine.allRepMetrics[0].romPercent, 0.1f)
        assertEquals("Rep 2 recorded ROM% must remain unchanged (70%)", 70.0f, stateMachine.allRepMetrics[1].romPercent, 0.5f)
    }

    /**
     * Unit Test 5: 5 clean calf raises -> 5 complete reps.
     */
    @Test
    fun testUnit5_FiveCleanCalfRaisesDetected() {
        val stateMachine = CalfRaiseStateMachine()
        stateMachine.calibrate(baselineY = 0.88f)

        var timeMs = 1000L
        for (rep in 1..5) {
            // BOTTOM -> ASCENDING -> TOP -> DESCENDING -> BOTTOM
            stateMachine.processHeelY(0.88f, timeMs); timeMs += 200L
            val asc = stateMachine.processHeelY(0.84f, timeMs); timeMs += 200L
            val top = stateMachine.processHeelY(0.78f, timeMs); timeMs += 200L
            assertEquals("Rep $rep should reach TOP", ExercisePhase.TOP, top.phase)

            val desc = stateMachine.processHeelY(0.83f, timeMs); timeMs += 200L
            val bottom = stateMachine.processHeelY(0.88f, timeMs); timeMs += 400L

            assertEquals("Rep $rep should complete", rep, stateMachine.completeReps.size)
        }

        assertEquals(5, stateMachine.completeReps.size)
        assertEquals(0, stateMachine.incompleteReps.size)
    }

    /**
     * Unit Test 6: Reversal before reaching rise threshold -> 1 incomplete rep.
     */
    @Test
    fun testUnit6_ReversalBeforeRiseThresholdYieldsIncompleteRep() {
        val stateMachine = CalfRaiseStateMachine()
        stateMachine.calibrate(baselineY = 0.88f)

        var timeMs = 1000L
        // Heel lifts slightly from 0.88 to 0.865 (elev = 0.015, below rise threshold 0.04)
        stateMachine.processHeelY(0.88f, timeMs); timeMs += 200L
        val asc = stateMachine.processHeelY(0.865f, timeMs); timeMs += 200L

        // Reverses downward back to baseline 0.88
        stateMachine.processHeelY(0.88f, timeMs)

        assertEquals("No complete reps should be counted", 0, stateMachine.completeReps.size)
        assertEquals("Premature reversal should log 1 incomplete rep", 1, stateMachine.incompleteReps.size)
        assertTrue("Incomplete reason should cite rise threshold", stateMachine.incompleteReps[0].reason.contains("rise threshold"))
    }

    /**
     * Unit Test 7: Visibility gap mid-rep with re-arming guard (D13 pattern) ->
     * discarded correctly, no phantom incomplete rep on recovery.
     */
    @Test
    fun testUnit7_VisibilityGapMidRepWithReArmingGuard() {
        val stateMachine = CalfRaiseStateMachine()
        stateMachine.calibrate(baselineY = 0.88f)

        var timeMs = 1000L
        // Start rep: lifts toward top (elev = 0.06, heel = 0.82)
        stateMachine.processHeelY(0.88f, timeMs, isVisibilitySufficient = true); timeMs += 200L
        stateMachine.processHeelY(0.82f, timeMs, isVisibilitySufficient = true); timeMs += 200L

        // Foot leaves frame / visibility drops mid-rep
        stateMachine.processHeelY(0.82f, timeMs, isVisibilitySufficient = false); timeMs += 200L
        assertTrue("State machine must enter guarded awaitingBaselineReturn state", stateMachine.awaitingBaselineReturn)
        assertEquals("Mid-rep visibility loss must not log complete rep", 0, stateMachine.completeReps.size)
        assertEquals("Mid-rep visibility loss must not log incomplete rep", 0, stateMachine.incompleteReps.size)

        // Visibility restored while user is still in the air recovering (heel = 0.84)
        stateMachine.processHeelY(0.84f, timeMs, isVisibilitySufficient = true); timeMs += 200L
        assertTrue("Guard must remain active during recovery descent", stateMachine.awaitingBaselineReturn)
        assertEquals("No incomplete rep should be logged during recovery", 0, stateMachine.incompleteReps.size)

        // User finishes descending back to resting baseline (heel = 0.88)
        stateMachine.processHeelY(0.88f, timeMs, isVisibilitySufficient = true)
        assertFalse("Guard should clear once baseline return is confirmed", stateMachine.awaitingBaselineReturn)

        assertEquals("Final complete rep count must be 0", 0, stateMachine.completeReps.size)
        assertEquals("Recovery must not spawn phantom incomplete rep", 0, stateMachine.incompleteReps.size)
    }

    /**
     * Unit Test 8: Non-side-view detection -> returns INSUFFICIENT_VISIBILITY
     * (hard requirement, not graceful degradation — contrast with Lunge's test 11).
     */
    @Test
    fun testUnit8_NonSideViewReturnsInsufficientVisibility() {
        val visibilityGate = CalfRaiseVisibilityGate()
        val analyzer = ExerciseAnalyzer("calf_raise", "Calf Raise")

        // 1. Side view check -> SUFFICIENT_VISIBILITY
        val sideViewLandmarks = createSyntheticLandmarks(isSideView = true)
        val sidePose = PoseEstimationResult(sideViewLandmarks, 1000L, true)
        val sideVisResult = visibilityGate.checkFrame(sidePose)
        assertEquals("Side view must have SUFFICIENT_VISIBILITY", VisibilityStatus.SUFFICIENT_VISIBILITY, sideVisResult.status)

        // 2. Front/non-side view check -> INSUFFICIENT_VISIBILITY (hard requirement)
        val frontViewLandmarks = createSyntheticLandmarks(isSideView = false)
        val frontPose = PoseEstimationResult(frontViewLandmarks, 2000L, true)
        val frontVisResult = visibilityGate.checkFrame(frontPose)
        assertEquals("Non-side view for Calf Raise must return INSUFFICIENT_VISIBILITY", VisibilityStatus.INSUFFICIENT_VISIBILITY, frontVisResult.status)

        // 3. ExerciseAnalyzer synthetic frame non-side-view override
        val frontFrameResult = analyzer.analyzeSyntheticFrame(
            elbowAngle = 0.88f,
            hipLineAngle = 0.0f,
            timestampMs = 3000L,
            confidence = 1.0f,
            isVisibilitySufficient = true,
            isSideViewOverride = false
        )
        assertEquals("Calf Raise must hard-refuse to INSUFFICIENT_VISIBILITY on non-side view", ValidationStatus.INSUFFICIENT_VISIBILITY, frontFrameResult.status)
        assertNull("Instant ROM must be stripped on INSUFFICIENT_VISIBILITY", frontFrameResult.instantRomPercent)
        assertNull("Current reps must be stripped on INSUFFICIENT_VISIBILITY", frontFrameResult.currentReps)
    }

    /**
     * Unit Test 9: TuT Factor + avgRepDurationSec consistency (2.0s baseline).
     */
    @Test
    fun testUnit9_TutFactorAndAvgRepDurationSecConsistency() {
        val analyzer = ExerciseAnalyzer("calf_raise", "Calf Raise")
        analyzer.calfRaiseStateMachine.calibrate(baselineY = 0.88f)

        // Setup baseline at t=500L
        analyzer.analyzeSyntheticFrame(elbowAngle = 0.88f, hipLineAngle = 0.0f, timestampMs = 500L)

        // Rep 1: exactly 2.0s duration (t=1000L to t=3000L)
        analyzer.analyzeSyntheticFrame(elbowAngle = 0.84f, hipLineAngle = 0.0f, timestampMs = 1000L) // starts ascent at 1000L
        analyzer.analyzeSyntheticFrame(elbowAngle = 0.78f, hipLineAngle = 0.0f, timestampMs = 2000L) // top at 2000L
        analyzer.analyzeSyntheticFrame(elbowAngle = 0.88f, hipLineAngle = 0.0f, timestampMs = 3000L) // return to baseline at 3000L (2.0s complete)

        // Pause at baseline between reps
        analyzer.analyzeSyntheticFrame(elbowAngle = 0.88f, hipLineAngle = 0.0f, timestampMs = 3500L)

        // Rep 2: exactly 2.0s duration (t=4000L to t=6000L)
        analyzer.analyzeSyntheticFrame(elbowAngle = 0.84f, hipLineAngle = 0.0f, timestampMs = 4000L) // starts ascent at 4000L
        analyzer.analyzeSyntheticFrame(elbowAngle = 0.78f, hipLineAngle = 0.0f, timestampMs = 5000L) // top at 5000L
        analyzer.analyzeSyntheticFrame(elbowAngle = 0.88f, hipLineAngle = 0.0f, timestampMs = 6000L) // return to baseline at 6000L (2.0s complete)

        val sessionResult = analyzer.getSessionResult()
        assertEquals(ValidationStatus.VALID, sessionResult.status)
        assertEquals(2, sessionResult.completeReps)
        assertNotNull(sessionResult.avgRepDurationSec)
        assertNotNull(sessionResult.tutFactor)

        assertEquals("avgRepDurationSec should be 2.0s", 2.0f, sessionResult.avgRepDurationSec!!, 0.1f)
        assertEquals("tutFactor should be 1.0 (2.0s / 2.0s baseline)", 1.0f, sessionResult.tutFactor!!, 0.05f)

        // Consistency formula check: tutFactor * 2.0s ≈ avgRepDurationSec
        assertEquals(sessionResult.avgRepDurationSec!!, sessionResult.tutFactor!! * 2.0f, 0.1f)
    }

    /**
     * Unit Test 10: Top-hold settle time doesn't cause false incomplete (D12 lesson).
     */
    @Test
    fun testUnit10_TopHoldSettleTimeProtection() {
        val stateMachine = CalfRaiseStateMachine()
        stateMachine.calibrate(baselineY = 0.88f)

        var timeMs = 1000L
        // Rise to top
        stateMachine.processHeelY(0.88f, timeMs); timeMs += 300L
        stateMachine.processHeelY(0.83f, timeMs); timeMs += 300L
        stateMachine.processHeelY(0.78f, timeMs); timeMs += 100L

        // Hold at top for 400ms with jitter between 0.779 and 0.783
        stateMachine.processHeelY(0.783f, timeMs); timeMs += 100L
        stateMachine.processHeelY(0.779f, timeMs); timeMs += 100L
        stateMachine.processHeelY(0.782f, timeMs); timeMs += 100L
        stateMachine.processHeelY(0.780f, timeMs); timeMs += 200L

        assertEquals("Top phase must be maintained during hold without premature exit", ExercisePhase.TOP, stateMachine.currentPhase)

        // Descend to baseline
        stateMachine.processHeelY(0.84f, timeMs); timeMs += 200L
        stateMachine.processHeelY(0.88f, timeMs)

        assertEquals("Rep with top hold must complete cleanly", 1, stateMachine.completeReps.size)
        assertEquals("No false incomplete rep should be generated by top hold", 0, stateMachine.incompleteReps.size)
    }

    /**
     * Unit Test 11: Verify NO shared engine code was modified.
     * Asserts that the 5 base engine files have ZERO modifications relative to git HEAD.
     */
    @Test
    fun testUnit11_VerifyNoSharedEngineCodeModified() {
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
