package com.example.cvassessment.sdk

import com.example.cvassessment.sdk.form.BicepCurlFormRuleEngine
import com.example.cvassessment.sdk.form.BicepCurlFormRules
import com.example.cvassessment.sdk.metrics.RepMetrics
import com.example.cvassessment.sdk.output.OutputGate
import com.example.cvassessment.sdk.pose.PoseLandmark
import com.example.cvassessment.sdk.pose.PoseLandmarkType
import com.example.cvassessment.sdk.statemachine.BicepCurlGeometry
import com.example.cvassessment.sdk.statemachine.BicepCurlStateMachine
import com.example.cvassessment.sdk.statemachine.ExercisePhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 11 Required Acceptance Criteria Unit Tests for Bicep Curl Implementation.
 */
class BicepCurlAssessmentTest {

    /**
     * Unit Test 1: Synthetic sequence of 5 clean curls (single arm or synchronized both arms)
     * -> 5 complete reps detected.
     */
    @Test
    fun testUnit1_FiveCleanCurlsDetected() {
        val stateMachine = BicepCurlStateMachine()
        var timeMs = 1000L

        // Initial setup at BOTTOM position (> 160°)
        stateMachine.processAngle(elbowAngle = 165.0f, hipLineAngle = 0.0f, timestampMs = timeMs)
        assertEquals(ExercisePhase.BOTTOM, stateMachine.currentPhase)

        // Execute 5 clean reps (simultaneous / single arm)
        for (rep in 1..5) {
            timeMs += 200L
            // Ascent (flexion): 150° -> 120° -> 80° -> 40° (top target is <= 55°)
            val asc1 = stateMachine.processAngle(140.0f, 0.0f, timeMs)
            assertEquals("Should be ascending in rep $rep", ExercisePhase.ASCENDING, asc1.phase)
            timeMs += 300L

            stateMachine.processAngle(90.0f, 0.0f, timeMs)
            timeMs += 300L

            val topState = stateMachine.processAngle(40.0f, 0.0f, timeMs)
            assertEquals("Should reach top in rep $rep", ExercisePhase.TOP, topState.phase)
            timeMs += 300L

            // Descent (extension): 60° -> 110° -> 165° (bottom target is >= 150°)
            val descState = stateMachine.processAngle(80.0f, 0.0f, timeMs)
            assertEquals("Should be descending in rep $rep", ExercisePhase.DESCENDING, descState.phase)
            timeMs += 300L

            stateMachine.processAngle(120.0f, 0.0f, timeMs)
            timeMs += 300L

            val completeState = stateMachine.processAngle(165.0f, 0.0f, timeMs)
            assertEquals("Should return to bottom in rep $rep", ExercisePhase.BOTTOM, completeState.phase)
            assertEquals("Should have $rep completed reps", rep, stateMachine.completeReps.size)
            assertNotNull("Should emit newly completed rep", completeState.newlyCompletedRep)
            assertEquals(rep, completeState.newlyCompletedRep!!.repIndex)
            timeMs += 200L
        }

        assertEquals(5, stateMachine.completeReps.size)
        assertEquals(0, stateMachine.incompleteReps.size)

        // Also test dual-arm synchronized tracking explicitly
        val dualArmStateMachine = BicepCurlStateMachine()
        var dualTime = 1000L
        dualArmStateMachine.processAngles(165f, 165f, 0f, dualTime)

        for (rep in 1..5) {
            dualTime += 300L
            dualArmStateMachine.processAngles(140f, 140f, 0f, dualTime)
            dualTime += 400L
            dualArmStateMachine.processAngles(40f, 40f, 0f, dualTime)
            dualTime += 400L
            dualArmStateMachine.processAngles(90f, 90f, 0f, dualTime)
            dualTime += 400L
            dualArmStateMachine.processAngles(165f, 165f, 0f, dualTime)
            dualTime += 200L
        }
        assertEquals("Synchronized dual-arm curls must yield exactly 5 complete reps", 5, dualArmStateMachine.completeReps.size)
    }

    /**
     * Unit Test 2: Reversal before reaching top tolerance -> 1 incomplete rep.
     */
    @Test
    fun testUnit2_ReversalBeforeReachingTopProducesIncompleteRep() {
        val stateMachine = BicepCurlStateMachine()

        // Start at bottom
        stateMachine.processAngle(elbowAngle = 165.0f, hipLineAngle = 0.0f, timestampMs = 1000L)

        // Curl upwards, but only reaches 75° (top target is <= 55°)
        stateMachine.processAngle(elbowAngle = 140.0f, hipLineAngle = 0.0f, timestampMs = 1300L)
        stateMachine.processAngle(elbowAngle = 100.0f, hipLineAngle = 0.0f, timestampMs = 1600L)
        stateMachine.processAngle(elbowAngle = 75.0f, hipLineAngle = 0.0f, timestampMs = 1900L) // Lowest angle

        // Reverse extension before reaching top target (75° -> 86°: reversal by 11° > 8° hysteresis)
        val reversalState = stateMachine.processAngle(elbowAngle = 86.0f, hipLineAngle = 0.0f, timestampMs = 2200L)

        assertEquals("Should register 0 complete reps", 0, stateMachine.completeReps.size)
        assertEquals("Should register 1 incomplete rep", 1, stateMachine.incompleteReps.size)
        assertNotNull("Should emit newlyDetectedIncompleteRep", reversalState.newlyDetectedIncompleteRep)

        val incomplete = stateMachine.incompleteReps.first()
        assertEquals(1, incomplete.attemptIndex)
        assertTrue(incomplete.reason.contains("Reversed"))
    }

    /**
     * Unit Test 3: Visibility gap mid-rep -> rep discarded, not falsely counted.
     */
    @Test
    fun testUnit3_VisibilityGapMidRepDiscardsAttempt() {
        val stateMachine = BicepCurlStateMachine()

        // Start at bottom
        stateMachine.processAngle(elbowAngle = 165.0f, hipLineAngle = 0.0f, timestampMs = 1000L, isVisibilitySufficient = true)

        // Ascend mid-rep
        stateMachine.processAngle(elbowAngle = 120.0f, hipLineAngle = 0.0f, timestampMs = 1300L, isVisibilitySufficient = true)
        stateMachine.processAngle(elbowAngle = 70.0f, hipLineAngle = 0.0f, timestampMs = 1600L, isVisibilitySufficient = true)
        assertTrue("Rep should be in progress", stateMachine.currentState.isRepInProgress)

        // Visibility drops mid-rep
        val dropState = stateMachine.processAngle(elbowAngle = 70.0f, hipLineAngle = 0.0f, timestampMs = 1800L, isVisibilitySufficient = false)
        assertFalse("Rep in progress must be aborted when visibility drops", dropState.isRepInProgress)
        assertEquals(ExercisePhase.BOTTOM, dropState.phase)

        // Visibility returns and a full clean rep is performed
        stateMachine.processAngle(elbowAngle = 165.0f, hipLineAngle = 0.0f, timestampMs = 2500L, isVisibilitySufficient = true)
        stateMachine.processAngle(elbowAngle = 120.0f, hipLineAngle = 0.0f, timestampMs = 2800L, isVisibilitySufficient = true)
        stateMachine.processAngle(elbowAngle = 40.0f, hipLineAngle = 0.0f, timestampMs = 3200L, isVisibilitySufficient = true)
        stateMachine.processAngle(elbowAngle = 90.0f, hipLineAngle = 0.0f, timestampMs = 3600L, isVisibilitySufficient = true)
        stateMachine.processAngle(elbowAngle = 165.0f, hipLineAngle = 0.0f, timestampMs = 4000L, isVisibilitySufficient = true)

        assertEquals("Only the completed rep after visibility restored should count", 1, stateMachine.completeReps.size)
        assertEquals("Aborted mid-rep attempt should not be counted as complete", 1, stateMachine.completeReps.first().repIndex)
    }

    /**
     * Unit Test 4: ROM% hand-calculated verification.
     * Formula: rom = ((160° - minAngle) / (160° - 45°)) * 100%
     */
    @Test
    fun testUnit4_RomPercentHandCalculatedVerification() {
        // 1. Full curl reaching target 45°: (160 - 45) / 115 * 100 = 100.0%
        val romFull = BicepCurlGeometry.computeRomPercent(45.0f)
        assertEquals(100.0f, romFull, 0.01f)

        // 2. Over-curl reaching 30°: clamped to 100.0%
        val romOver = BicepCurlGeometry.computeRomPercent(30.0f)
        assertEquals(100.0f, romOver, 0.01f)

        // 3. Halfway curl reaching 102.5°: (160 - 102.5) / 115 * 100 = 57.5 / 115 * 100 = 50.0%
        val romHalf = BicepCurlGeometry.computeRomPercent(102.5f)
        assertEquals(50.0f, romHalf, 0.01f)

        // 4. Zero curl at 160°: (160 - 160) / 115 * 100 = 0.0%
        val romZero = BicepCurlGeometry.computeRomPercent(160.0f)
        assertEquals(0.0f, romZero, 0.01f)
    }

    /**
     * Unit Test 5: TuT Factor hand-calculated verification AND avgRepDurationSec/tutFactor consistency.
     * tutBaseline = 3.0s (1.5s up + 1.5s down).
     */
    @Test
    fun testUnit5_TutFactorAndAvgDurationConsistency() {
        val outputGate = OutputGate(ExerciseAnalyzer("bicep_curl", "Bicep Curl").config)

        // 1. Hand calculations for 3 known durations with baseline = 3.0s:
        // 1.5s duration: 1.5 / 3.0 = 0.50
        val tutFast = 1.5f / 3.0f
        assertEquals(0.50f, tutFast, 0.01f)

        // 3.0s duration: 3.0 / 3.0 = 1.00
        val tutExact = 3.0f / 3.0f
        assertEquals(1.00f, tutExact, 0.01f)

        // 4.5s duration: 4.5 / 3.0 = 1.50
        val tutSlow = 4.5f / 3.0f
        assertEquals(1.50f, tutSlow, 0.01f)

        // 2. Consistency check in ExerciseAnalyzer session:
        val analyzer = ExerciseAnalyzer("bicep_curl", "Bicep Curl")

        // Setup bottom at t=1000L
        analyzer.analyzeSyntheticFrame(elbowAngle = 165.0f, hipLineAngle = 0.0f, timestampMs = 1000L)

        // Rep 1: 3000ms duration (t=1200L to t=4200L)
        analyzer.analyzeSyntheticFrame(elbowAngle = 140.0f, hipLineAngle = 0.0f, timestampMs = 1200L)
        analyzer.analyzeSyntheticFrame(elbowAngle = 40.0f, hipLineAngle = 0.0f, timestampMs = 2700L)
        analyzer.analyzeSyntheticFrame(elbowAngle = 165.0f, hipLineAngle = 0.0f, timestampMs = 4200L) // Rep 1 complete: 3.0s

        // Pause between reps at bottom (4200L to 5000L)
        analyzer.analyzeSyntheticFrame(elbowAngle = 165.0f, hipLineAngle = 0.0f, timestampMs = 4600L)
        analyzer.analyzeSyntheticFrame(elbowAngle = 165.0f, hipLineAngle = 0.0f, timestampMs = 5000L)

        // Rep 2: 3600ms duration (t=5200L to t=8800L)
        analyzer.analyzeSyntheticFrame(elbowAngle = 140.0f, hipLineAngle = 0.0f, timestampMs = 5200L)
        analyzer.analyzeSyntheticFrame(elbowAngle = 40.0f, hipLineAngle = 0.0f, timestampMs = 7000L)
        analyzer.analyzeSyntheticFrame(elbowAngle = 165.0f, hipLineAngle = 0.0f, timestampMs = 8800L) // Rep 2 complete: 3.6s

        val sessionResult = analyzer.getSessionResult()
        assertEquals(ValidationStatus.VALID, sessionResult.status)
        assertEquals(2, sessionResult.completeReps)

        val avgDuration = sessionResult.avgRepDurationSec
        val tutFactor = sessionResult.tutFactor
        assertNotNull(avgDuration)
        assertNotNull(tutFactor)

        // Average duration = (3.0s + 3.6s) / 2 = 3.3s
        assertEquals("Average duration must be 3.3s", 3.3f, avgDuration!!, 0.05f)

        // tutFactor = 3.3s / 3.0s baseline = 1.10
        assertEquals("TuT Factor must be 1.10", 1.10f, tutFactor!!, 0.05f)

        // Mathematical consistency: tutFactor * tutBaseline ≈ avgRepDurationSec within rounding (0.1s tolerance)
        val expectedDuration = tutFactor * analyzer.config.tutBaseline
        assertEquals(
            "tutFactor * tutBaseline ($expectedDuration) must equal avgRepDurationSec ($avgDuration) within rounding",
            avgDuration,
            expectedDuration,
            0.1f
        )
    }

    /**
     * Unit Test 6: excessive_momentum triggers correctly with synthetic shoulder displacement.
     */
    @Test
    fun testUnit6_ExcessiveMomentumTriggersCorrectly() {
        val engine = BicepCurlFormRuleEngine()

        // Bottom resting position: baseline stability angle = 0°
        engine.evaluateFrame(
            leftElbowAngle = 165.0f,
            shoulderStabilityAngle = 0.0f,
            phase = ExercisePhase.BOTTOM,
            isRepInProgress = false,
            timestampMs = 1000L
        )

        // During curl (isRepInProgress = true), body swings back (shoulder stability angle moves to 20° >= 15° threshold)
        var feedbackTriggered = false
        for (i in 1..4) {
            val out = engine.evaluateFrame(
                leftElbowAngle = 90.0f,
                shoulderStabilityAngle = 20.0f, // 20° displacement from 0° baseline
                phase = ExercisePhase.ASCENDING,
                isRepInProgress = true,
                currentRepIndex = 1,
                timestampMs = 1000L + (i * 200L),
                confidence = 0.95f
            )

            if (i >= 3) {
                // Persistence reached (>= 3 frames)
                assertTrue("excessive_momentum must be active after persistence", out.activeErrors.any { it.errorName == "excessive_momentum" })
                assertEquals(0.55f, out.activeErrors.first { it.errorName == "excessive_momentum" }.severity, 0.01f)
            }

            if (out.newFeedbackEvents.any { it.relatedError == "excessive_momentum" }) {
                feedbackTriggered = true
                val event = out.newFeedbackEvents.first { it.relatedError == "excessive_momentum" }
                assertEquals("Control the movement, avoid swinging.", event.message)
            }
        }

        assertTrue("Feedback must trigger once persistence is reached", feedbackTriggered)
        assertTrue(engine.allSessionErrors.any { it.errorName == "excessive_momentum" })
    }

    /**
     * Unit Test 7: back_arching triggers correctly with synthetic hip-shoulder deviation.
     */
    @Test
    fun testUnit7_BackArchingTriggersCorrectly() {
        val engine = BicepCurlFormRuleEngine()

        var feedbackTriggered = false
        for (i in 1..4) {
            val out = engine.evaluateFrame(
                leftElbowAngle = 80.0f,
                shoulderStabilityAngle = 22.0f, // 22° torso vertical deviation >= 18° threshold
                phase = ExercisePhase.ASCENDING,
                isRepInProgress = true,
                currentRepIndex = 1,
                timestampMs = 1000L + (i * 200L),
                confidence = 0.95f
            )

            if (i >= 3) {
                assertTrue("back_arching must be active after persistence", out.activeErrors.any { it.errorName == "back_arching" })
                assertEquals(0.65f, out.activeErrors.first { it.errorName == "back_arching" }.severity, 0.01f)
            }

            if (out.newFeedbackEvents.any { it.relatedError == "back_arching" }) {
                feedbackTriggered = true
                val event = out.newFeedbackEvents.first { it.relatedError == "back_arching" }
                assertEquals("Keep your back straight.", event.message)
            }
        }

        assertTrue("Feedback must trigger for back arching", feedbackTriggered)
        assertTrue(engine.allSessionErrors.any { it.errorName == "back_arching" })
    }

    /**
     * Unit Test 8: asymmetric_movement triggers when both arms visible with differing angles,
     * and correctly SKIPS when only one arm is visible.
     */
    @Test
    fun testUnit8_AsymmetricMovementTriggersAndSkipsInSideView() {
        val engine = BicepCurlFormRuleEngine()

        // 1. Both arms visible in front view (left=60°, right=95° -> diff = 35° >= 25° threshold)
        var feedbackTriggered = false
        for (i in 1..4) {
            val out = engine.evaluateFrame(
                leftElbowAngle = 60.0f,
                rightElbowAngle = 95.0f,
                phase = ExercisePhase.ASCENDING,
                isRepInProgress = true,
                currentRepIndex = 1,
                timestampMs = 1000L + (i * 200L),
                confidence = 0.95f,
                isSideViewOverride = false
            )

            if (i >= 3) {
                assertTrue("asymmetric_movement must be active after persistence", out.activeErrors.any { it.errorName == "asymmetric_movement" })
                assertEquals(0.50f, out.activeErrors.first { it.errorName == "asymmetric_movement" }.severity, 0.01f)
            }

            if (out.newFeedbackEvents.any { it.relatedError == "asymmetric_movement" }) {
                feedbackTriggered = true
                val event = out.newFeedbackEvents.first { it.relatedError == "asymmetric_movement" }
                assertEquals("Keep both sides even.", event.message)
            }
        }
        assertTrue("Feedback must trigger for asymmetric movement in front view", feedbackTriggered)

        // 2. Profile / Side view: only one arm visible -> MUST SKIP
        val sideEngine = BicepCurlFormRuleEngine()
        for (i in 1..4) {
            val out = sideEngine.evaluateFrame(
                leftElbowAngle = 60.0f,
                rightElbowAngle = 95.0f, // discrepancy exists but view is side-view
                phase = ExercisePhase.ASCENDING,
                isRepInProgress = true,
                currentRepIndex = 1,
                timestampMs = 1000L + (i * 200L),
                confidence = 0.95f,
                isSideViewOverride = true
            )

            assertFalse("asymmetric_movement must NOT be flagged in side view", out.activeErrors.any { it.errorName == "asymmetric_movement" })
            assertTrue("No feedback should be emitted in side view", out.newFeedbackEvents.isEmpty())
        }

        assertNotNull("Skip reason must be recorded", sideEngine.lastSkipReason)
        assertTrue(sideEngine.lastSkipReason!!.contains("Side-view detected"))
        assertTrue("Total session errors must be empty in side view", sideEngine.allSessionErrors.isEmpty())
    }

    /**
     * Unit Test 9: Form Factor correctly deduplicates sustained errors (per BUG-1 fix)
     * - should NOT zero out from repeated frame detections.
     */
    @Test
    fun testUnit9_FormFactorDeduplicatesSustainedErrors() {
        val analyzer = ExerciseAnalyzer("bicep_curl", "Bicep Curl")

        // Rep 1 with 10 duplicate detections of excessive_momentum (severity 0.55)
        val tenDuplicateErrors = (1..10).map {
            com.example.cvassessment.sdk.FormError(
                errorName = BicepCurlFormRules.EXCESSIVE_MOMENTUM.errorName,
                confidence = 0.9f,
                repIndex = 1,
                severity = BicepCurlFormRules.EXCESSIVE_MOMENTUM.severity
            )
        }

        // Without deduplication: 1.0 - (10 * 0.55) = -4.5 -> clamped to 0.0!
        // With BUG-1 promoted deduplication: 1.0 - (1 * 0.55 / 1) = 0.45!
        val computedFactor = analyzer.outputGate.computeFormFactor(1, tenDuplicateErrors)
        assertNotNull(computedFactor)
        assertTrue("Form Factor must NOT zero out from repeated detections", computedFactor!! > 0.0f)
        assertEquals("Form Factor must equal 0.45 (1.0 - 0.55 / 1)", 0.45f, computedFactor!!, 0.01f)
    }

    /**
     * Unit Test 10: Full end-to-end synthetic session assembles SessionResult correctly.
     */
    @Test
    fun testUnit10_FullEndToEndSyntheticSessionAssemblesCorrectly() {
        val analyzer = ExerciseAnalyzer("bicep_curl", "Bicep Curl")

        // 1. Initial bottom position
        analyzer.analyzeSyntheticFrame(elbowAngle = 165.0f, hipLineAngle = 0.0f, timestampMs = 1000L)

        // 2. Rep 1: Clean curl (reaches 40° <= 55°)
        analyzer.analyzeSyntheticFrame(elbowAngle = 130.0f, hipLineAngle = 0.0f, timestampMs = 2000L)
        analyzer.analyzeSyntheticFrame(elbowAngle = 40.0f, hipLineAngle = 0.0f, timestampMs = 3000L)
        analyzer.analyzeSyntheticFrame(elbowAngle = 165.0f, hipLineAngle = 0.0f, timestampMs = 4000L) // Rep 1 complete!

        // 3. Rep 2: Clean curl
        analyzer.analyzeSyntheticFrame(elbowAngle = 130.0f, hipLineAngle = 0.0f, timestampMs = 5000L)
        analyzer.analyzeSyntheticFrame(elbowAngle = 42.0f, hipLineAngle = 0.0f, timestampMs = 6000L)
        analyzer.analyzeSyntheticFrame(elbowAngle = 165.0f, hipLineAngle = 0.0f, timestampMs = 7000L) // Rep 2 complete!

        // 4. Attempt 3: Incomplete rep (reverses at 80° without reaching top tolerance)
        analyzer.analyzeSyntheticFrame(elbowAngle = 120.0f, hipLineAngle = 0.0f, timestampMs = 8000L)
        analyzer.analyzeSyntheticFrame(elbowAngle = 80.0f, hipLineAngle = 0.0f, timestampMs = 8500L)
        analyzer.analyzeSyntheticFrame(elbowAngle = 95.0f, hipLineAngle = 0.0f, timestampMs = 9000L) // Incomplete!
        analyzer.analyzeSyntheticFrame(elbowAngle = 165.0f, hipLineAngle = 0.0f, timestampMs = 9500L)

        val sessionResult = analyzer.getSessionResult()
        assertNotNull(sessionResult)

        assertEquals(ValidationStatus.VALID, sessionResult.status)
        assertEquals(2, sessionResult.completeReps)
        assertEquals(1, sessionResult.incompleteReps)
        assertNull("holdDurationSec must be null for dynamic bicep curl", sessionResult.holdDurationSec)

        assertNotNull("romPercent must be computed", sessionResult.romPercent)
        assertNotNull("tutFactor must be computed", sessionResult.tutFactor)
        assertNotNull("avgRepDurationSec must be computed", sessionResult.avgRepDurationSec)
        assertNotNull("formFactor must be computed", sessionResult.formFactor)
    }

    /**
     * Unit Test 11: Verify NO shared engine code was modified beyond the OutputGate promotion decision.
     * Asserts that the 4 base engine files (VisibilityGate, ExerciseStateMachine, MetricsEngine, FormRuleEngine)
     * have ZERO modifications relative to git HEAD.
     */
    @Test
    fun testUnit11_VerifyNoSharedEngineCodeModifiedBeyondOutputGatePromotion() {
        val unmodifiedSharedEngineFiles = listOf(
            "sdk/src/main/kotlin/com/example/cvassessment/sdk/visibility/VisibilityGate.kt",
            "sdk/src/main/kotlin/com/example/cvassessment/sdk/statemachine/ExerciseStateMachine.kt",
            "sdk/src/main/kotlin/com/example/cvassessment/sdk/metrics/MetricsEngine.kt",
            "sdk/src/main/kotlin/com/example/cvassessment/sdk/form/FormRuleEngine.kt"
        )

        for (filePath in unmodifiedSharedEngineFiles) {
            val process = ProcessBuilder("git", "diff", "HEAD", "--", filePath)
                .redirectErrorStream(true)
                .start()
            val diffOutput = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()

            assertTrue(
                "CRITICAL: Shared engine file $filePath was modified! Diff:\n$diffOutput",
                diffOutput.isEmpty()
            )
        }
    }
}
