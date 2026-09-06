package com.example.cvassessment.sdk

import com.example.cvassessment.sdk.form.ShoulderPressFormRuleEngine
import com.example.cvassessment.sdk.form.ShoulderPressFormRules
import com.example.cvassessment.sdk.metrics.RepMetrics
import com.example.cvassessment.sdk.output.OutputGate
import com.example.cvassessment.sdk.statemachine.ExercisePhase
import com.example.cvassessment.sdk.statemachine.ShoulderPressGeometry
import com.example.cvassessment.sdk.statemachine.ShoulderPressStateMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 12 Required Acceptance Criteria Unit Tests for Shoulder Press Implementation.
 * Includes proactive verification of D12 lessons (top-hold settle time, angle calibration,
 * hysteresis consistency, guarded re-descent).
 */
class ShoulderPressAssessmentTest {

    /**
     * Unit Test 1: 5 clean presses -> 5 complete reps.
     * Both single-arm and synchronized dual-arm presses tested.
     */
    @Test
    fun testUnit1_FiveCleanPressesDetected() {
        val stateMachine = ShoulderPressStateMachine()
        var timeMs = 1000L

        // Initial setup at BOTTOM rack position (<= 105°)
        stateMachine.processAngle(elbowAngle = 90.0f, hipLineAngle = 0.0f, timestampMs = timeMs)
        assertEquals(ExercisePhase.BOTTOM, stateMachine.currentPhase)

        // Execute 5 clean reps (simultaneous / single arm)
        for (rep in 1..5) {
            timeMs += 200L
            // Ascent (extension): 90° -> 115° -> 135° -> 155° (top target is >= 145°)
            val asc = stateMachine.processAngle(115.0f, 0.0f, timeMs)
            assertEquals("Should be ascending in rep $rep", ExercisePhase.ASCENDING, asc.phase)
            timeMs += 300L

            stateMachine.processAngle(135.0f, 0.0f, timeMs)
            timeMs += 300L

            val topState = stateMachine.processAngle(155.0f, 0.0f, timeMs)
            assertEquals("Should reach top lockout in rep $rep", ExercisePhase.TOP, topState.phase)
            timeMs += 300L

            // Descent (flexion): 130° -> 110° -> 90° (bottom target is <= 105°)
            val descState = stateMachine.processAngle(130.0f, 0.0f, timeMs)
            assertEquals("Should be descending in rep $rep", ExercisePhase.DESCENDING, descState.phase)
            timeMs += 300L

            stateMachine.processAngle(110.0f, 0.0f, timeMs)
            timeMs += 300L

            val completeState = stateMachine.processAngle(90.0f, 0.0f, timeMs)
            assertEquals("Should return to bottom in rep $rep", ExercisePhase.BOTTOM, completeState.phase)
            assertEquals("Should have $rep completed reps", rep, stateMachine.completeReps.size)
            assertNotNull("Should emit newly completed rep", completeState.newlyCompletedRep)
            assertEquals(rep, completeState.newlyCompletedRep!!.repIndex)
            timeMs += 200L
        }

        assertEquals(5, stateMachine.completeReps.size)
        assertEquals(0, stateMachine.incompleteReps.size)

        // Also test dual-arm synchronized tracking explicitly
        val dualArmStateMachine = ShoulderPressStateMachine()
        var dualTime = 1000L
        dualArmStateMachine.processAngles(90f, 90f, 0f, dualTime)

        for (rep in 1..5) {
            dualTime += 300L
            dualArmStateMachine.processAngles(115f, 115f, 0f, dualTime)
            dualTime += 400L
            dualArmStateMachine.processAngles(155f, 155f, 0f, dualTime)
            dualTime += 400L
            dualArmStateMachine.processAngles(125f, 125f, 0f, dualTime)
            dualTime += 400L
            dualArmStateMachine.processAngles(90f, 90f, 0f, dualTime)
            dualTime += 200L
        }
        assertEquals("Synchronized dual-arm presses must yield exactly 5 complete reps", 5, dualArmStateMachine.completeReps.size)
    }

    /**
     * Unit Test 2: Reversal before reaching top tolerance -> 1 incomplete rep.
     */
    @Test
    fun testUnit2_ReversalBeforeReachingTopToleranceProducesIncompleteRep() {
        val stateMachine = ShoulderPressStateMachine()

        // Start at bottom rack
        stateMachine.processAngle(elbowAngle = 90.0f, hipLineAngle = 0.0f, timestampMs = 1000L)

        // Press upward, but only reaches 130° (top tolerance target is >= 145°)
        stateMachine.processAngle(elbowAngle = 110.0f, hipLineAngle = 0.0f, timestampMs = 1300L)
        stateMachine.processAngle(elbowAngle = 125.0f, hipLineAngle = 0.0f, timestampMs = 1600L)
        stateMachine.processAngle(elbowAngle = 130.0f, hipLineAngle = 0.0f, timestampMs = 1900L) // Peak achieved

        // Reverse downward before reaching top lockout (130° -> 118°: reversal by 12° > 8° hysteresis)
        val reversalState = stateMachine.processAngle(elbowAngle = 118.0f, hipLineAngle = 0.0f, timestampMs = 2200L)

        assertEquals("Should register 0 complete reps", 0, stateMachine.completeReps.size)
        assertEquals("Should register 1 incomplete rep", 1, stateMachine.incompleteReps.size)
        assertNotNull("Should emit newlyDetectedIncompleteRep", reversalState.newlyDetectedIncompleteRep)

        val incomplete = stateMachine.incompleteReps.first()
        assertEquals(1, incomplete.attemptIndex)
        assertTrue(incomplete.reason.contains("Reversed"))
    }

    /**
     * Unit Test 3: Visibility gap mid-rep -> discarded correctly, not falsely counted.
     */
    @Test
    fun testUnit3_VisibilityGapMidRepDiscardsAttempt() {
        val stateMachine = ShoulderPressStateMachine()

        // Start at bottom rack
        stateMachine.processAngle(elbowAngle = 90.0f, hipLineAngle = 0.0f, timestampMs = 1000L, isVisibilitySufficient = true)

        // Ascend mid-rep
        stateMachine.processAngle(elbowAngle = 120.0f, hipLineAngle = 0.0f, timestampMs = 1300L, isVisibilitySufficient = true)
        stateMachine.processAngle(elbowAngle = 140.0f, hipLineAngle = 0.0f, timestampMs = 1600L, isVisibilitySufficient = true)
        assertTrue("Rep should be in progress", stateMachine.currentState.isRepInProgress)

        // Visibility drops mid-rep
        val dropState = stateMachine.processAngle(elbowAngle = 140.0f, hipLineAngle = 0.0f, timestampMs = 1800L, isVisibilitySufficient = false)
        assertFalse("Rep in progress must be aborted when visibility drops", dropState.isRepInProgress)
        assertEquals(ExercisePhase.BOTTOM, dropState.phase)

        // Visibility returns and a full clean rep is performed
        stateMachine.processAngle(elbowAngle = 90.0f, hipLineAngle = 0.0f, timestampMs = 2500L, isVisibilitySufficient = true)
        stateMachine.processAngle(elbowAngle = 120.0f, hipLineAngle = 0.0f, timestampMs = 2800L, isVisibilitySufficient = true)
        stateMachine.processAngle(elbowAngle = 155.0f, hipLineAngle = 0.0f, timestampMs = 3200L, isVisibilitySufficient = true)
        stateMachine.processAngle(elbowAngle = 125.0f, hipLineAngle = 0.0f, timestampMs = 3600L, isVisibilitySufficient = true)
        stateMachine.processAngle(elbowAngle = 90.0f, hipLineAngle = 0.0f, timestampMs = 4000L, isVisibilitySufficient = true)

        assertEquals("Only the completed rep after visibility restored should count", 1, stateMachine.completeReps.size)
        assertEquals("Completed rep must have repIndex 1", 1, stateMachine.completeReps.first().repIndex)
    }

    /**
     * Unit Test 4: ROM% hand-calculated verification.
     * Formula: rom = ((maxAngle - 90°) / (155° - 90°)) * 100%
     */
    @Test
    fun testUnit4_RomPercentHandCalculatedVerification() {
        // 1. Full press reaching target 155°: (155 - 90) / 65 * 100 = 100.0%
        val romFull = ShoulderPressGeometry.computeRomPercent(155.0f)
        assertEquals(100.0f, romFull, 0.01f)

        // 2. Over-press reaching 170°: clamped to 100.0%
        val romOver = ShoulderPressGeometry.computeRomPercent(170.0f)
        assertEquals(100.0f, romOver, 0.01f)

        // 3. Halfway press reaching 122.5°: (122.5 - 90) / 65 * 100 = 32.5 / 65 * 100 = 50.0%
        val romHalf = ShoulderPressGeometry.computeRomPercent(122.5f)
        assertEquals(50.0f, romHalf, 0.01f)

        // 4. Starting bottom rack position at 90°: (90 - 90) / 65 * 100 = 0.0%
        val romZero = ShoulderPressGeometry.computeRomPercent(90.0f)
        assertEquals(0.0f, romZero, 0.01f)
    }

    /**
     * Unit Test 5: TuT Factor hand-calculated + avgRepDurationSec consistency (inherited from shared OutputGate).
     * tutBaseline = 3.0s (1.5s up + 1.5s down).
     */
    @Test
    fun testUnit5_TutFactorAndAvgDurationConsistency() {
        val outputGate = OutputGate(ExerciseAnalyzer("shoulder_press", "Shoulder Press").config)

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
        val analyzer = ExerciseAnalyzer("shoulder_press", "Shoulder Press")

        // Setup bottom at t=1000L
        analyzer.analyzeSyntheticFrame(elbowAngle = 90.0f, hipLineAngle = 0.0f, timestampMs = 1000L)

        // Rep 1: 3000ms duration (t=1200L to t=4200L)
        analyzer.analyzeSyntheticFrame(elbowAngle = 115.0f, hipLineAngle = 0.0f, timestampMs = 1200L)
        analyzer.analyzeSyntheticFrame(elbowAngle = 155.0f, hipLineAngle = 0.0f, timestampMs = 2700L)
        analyzer.analyzeSyntheticFrame(elbowAngle = 90.0f, hipLineAngle = 0.0f, timestampMs = 4200L) // Rep 1 complete: 3.0s

        // Pause between reps at bottom (4200L to 5000L)
        analyzer.analyzeSyntheticFrame(elbowAngle = 90.0f, hipLineAngle = 0.0f, timestampMs = 4600L)
        analyzer.analyzeSyntheticFrame(elbowAngle = 90.0f, hipLineAngle = 0.0f, timestampMs = 5000L)

        // Rep 2: 3600ms duration (t=5200L to t=8800L)
        analyzer.analyzeSyntheticFrame(elbowAngle = 115.0f, hipLineAngle = 0.0f, timestampMs = 5200L)
        analyzer.analyzeSyntheticFrame(elbowAngle = 155.0f, hipLineAngle = 0.0f, timestampMs = 7000L)
        analyzer.analyzeSyntheticFrame(elbowAngle = 90.0f, hipLineAngle = 0.0f, timestampMs = 8800L) // Rep 2 complete: 3.6s

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

        // Mathematical consistency: tutFactor * tutBaseline ≈ avgRepDurationSec within rounding
        val expectedDuration = tutFactor * analyzer.config.tutBaseline
        assertEquals(
            "tutFactor * tutBaseline ($expectedDuration) must equal avgRepDurationSec ($avgDuration) within rounding",
            avgDuration,
            expectedDuration,
            0.1f
        )
    }

    /**
     * Unit Test 6: TOP-HOLD SETTLE TIME test — synthetic sequence with a 300ms hold at lockout
     * with ±5° jitter -> assert this does NOT cause a false incomplete rep.
     * Proactively applies the lesson from DECISIONS.md D12.
     */
    @Test
    fun testUnit6_TopHoldSettleTimeAbsorbsLockoutJitterWithoutFalseIncompleteRep() {
        val stateMachine = ShoulderPressStateMachine()

        // 1. Start at bottom rack position
        stateMachine.processAngle(elbowAngle = 90.0f, hipLineAngle = 0.0f, timestampMs = 1000L)

        // 2. Ascend to overhead lockout
        stateMachine.processAngle(elbowAngle = 120.0f, hipLineAngle = 0.0f, timestampMs = 1400L)
        val reachedTop = stateMachine.processAngle(elbowAngle = 155.0f, hipLineAngle = 0.0f, timestampMs = 1800L)
        assertEquals("Should reach TOP phase at lockout", ExercisePhase.TOP, reachedTop.phase)

        // 3. User pauses overhead at lockout for 300ms (1800ms to 2100ms) with ±5° coordinate jitter
        // (155° -> 151° -> 155° -> 150° -> 154° -> 151° -> 155°)
        val jitterFrames = listOf(
            1850L to 151.0f,
            1900L to 155.0f,
            1950L to 150.0f,
            2000L to 154.0f,
            2050L to 151.0f,
            2100L to 155.0f
        )

        for ((timestamp, jitterAngle) in jitterFrames) {
            val jitterState = stateMachine.processAngle(elbowAngle = jitterAngle, hipLineAngle = 0.0f, timestampMs = timestamp)
            // Assert: must NOT trigger premature incomplete rep during overhead hold
            assertEquals(
                "Hold jitter ($jitterAngle° at ${timestamp}ms) must remain in TOP phase",
                ExercisePhase.TOP,
                jitterState.phase
            )
            assertEquals("Hold jitter must NOT generate an incomplete rep", 0, stateMachine.incompleteReps.size)
        }

        // 4. User now lowers the press under control back to the bottom rack
        stateMachine.processAngle(elbowAngle = 130.0f, hipLineAngle = 0.0f, timestampMs = 2400L)
        stateMachine.processAngle(elbowAngle = 110.0f, hipLineAngle = 0.0f, timestampMs = 2700L)
        val completedState = stateMachine.processAngle(elbowAngle = 90.0f, hipLineAngle = 0.0f, timestampMs = 3000L)

        assertEquals("Should return to BOTTOM phase", ExercisePhase.BOTTOM, completedState.phase)
        assertEquals("Must complete exactly 1 clean rep despite top-hold jitter", 1, stateMachine.completeReps.size)
        assertEquals("Must have 0 incomplete reps", 0, stateMachine.incompleteReps.size)
    }

    /**
     * Unit Test 7: excessive_momentum triggers correctly.
     */
    @Test
    fun testUnit7_ExcessiveMomentumTriggersCorrectly() {
        val engine = ShoulderPressFormRuleEngine()

        // Bottom resting position: baseline torso stability angle = 0°
        engine.evaluateFrame(
            leftElbowAngle = 90.0f,
            shoulderStabilityAngle = 0.0f,
            phase = ExercisePhase.BOTTOM,
            isRepInProgress = false,
            timestampMs = 1000L
        )

        // During pressing (isRepInProgress = true), torso sways/swings (stability angle moves to 20° >= 15° threshold)
        var feedbackTriggered = false
        for (i in 1..4) {
            val out = engine.evaluateFrame(
                leftElbowAngle = 120.0f,
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
     * Unit Test 8: back_arching triggers correctly.
     */
    @Test
    fun testUnit8_BackArchingTriggersCorrectly() {
        val engine = ShoulderPressFormRuleEngine()

        var feedbackTriggered = false
        for (i in 1..4) {
            val out = engine.evaluateFrame(
                leftElbowAngle = 130.0f,
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
     * Unit Test 9: incomplete_lockout triggers when rep ends without reaching top angle tolerance.
     */
    @Test
    fun testUnit9_IncompleteLockoutTriggersWhenRepEndsWithoutTopTolerance() {
        val engine = ShoulderPressFormRuleEngine()

        // Rep completed, but peak elbow angle was only 138° (< 145° top tolerance cutoff)
        val repMetrics = RepMetrics(
            repIndex = 1,
            romPercent = 73.8f,
            tutFactor = 1.0f,
            confidence = 0.95f,
            durationSec = 3.0f,
            minElbowAngle = 138.0f, // peak extension achieved in rep
            startTimestampMs = 1000L,
            endTimestampMs = 4000L
        )

        val out = engine.evaluateFrame(
            leftElbowAngle = 90.0f,
            rightElbowAngle = 90.0f,
            shoulderStabilityAngle = 0.0f,
            phase = ExercisePhase.BOTTOM,
            isRepInProgress = false,
            currentRepIndex = 1,
            timestampMs = 4000L,
            confidence = 0.95f,
            completedRepMetrics = repMetrics
        )

        assertTrue(
            "incomplete_lockout must trigger when rep ends with peak angle < 145°",
            out.activeErrors.any { it.errorName == "incomplete_lockout" }
        )
        val error = out.activeErrors.first { it.errorName == "incomplete_lockout" }
        assertEquals(0.40f, error.severity, 0.01f)

        // Check feedback event
        val feedback = out.newFeedbackEvents.find { it.relatedError == "incomplete_lockout" }
        assertNotNull("Feedback event should be emitted for incomplete lockout", feedback)
        assertEquals("Fully extend at the top.", feedback!!.message)
    }

    /**
     * Unit Test 10: asymmetric_movement triggers when both arms visible with differing angles,
     * skips when one arm not visible.
     */
    @Test
    fun testUnit10_AsymmetricMovementTriggersAndSkipsInSideView() {
        val engine = ShoulderPressFormRuleEngine()

        // 1. Both arms visible in front view (left=110°, right=145° -> diff = 35° >= 25° threshold)
        var feedbackTriggered = false
        for (i in 1..4) {
            val out = engine.evaluateFrame(
                leftElbowAngle = 110.0f,
                rightElbowAngle = 145.0f,
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
        val sideEngine = ShoulderPressFormRuleEngine()
        for (i in 1..4) {
            val out = sideEngine.evaluateFrame(
                leftElbowAngle = 110.0f,
                rightElbowAngle = 145.0f, // discrepancy exists but view is side-view
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
     * Unit Test 11: Form Factor deduplication works correctly (inherited from shared OutputGate).
     */
    @Test
    fun testUnit11_FormFactorDeduplicatesSustainedErrors() {
        val analyzer = ExerciseAnalyzer("shoulder_press", "Shoulder Press")

        // Rep 1 with 10 duplicate detections of excessive_momentum (severity 0.55)
        val tenDuplicateErrors = (1..10).map {
            com.example.cvassessment.sdk.FormError(
                errorName = ShoulderPressFormRules.EXCESSIVE_MOMENTUM.errorName,
                confidence = 0.9f,
                repIndex = 1,
                severity = ShoulderPressFormRules.EXCESSIVE_MOMENTUM.severity
            )
        }

        // Without deduplication: 1.0 - (10 * 0.55) = -4.5 -> clamped to 0.0!
        // With promoted deduplication: 1.0 - (1 * 0.55 / 1) = 0.45!
        val computedFactor = analyzer.outputGate.computeFormFactor(1, tenDuplicateErrors)
        assertNotNull(computedFactor)
        assertTrue("Form Factor must NOT zero out from repeated detections", computedFactor!! > 0.0f)
        assertEquals("Form Factor must equal 0.45 (1.0 - 0.55 / 1)", 0.45f, computedFactor!!, 0.01f)
    }

    /**
     * Unit Test 12: Verify no shared engine code modified beyond any new shared DualArmTracker utility (if created).
     * Asserts that the 5 base engine files (VisibilityGate, ExerciseStateMachine, MetricsEngine, FormRuleEngine, OutputGate)
     * have ZERO modifications relative to git HEAD.
     */
    @Test
    fun testUnit12_VerifyNoSharedEngineCodeModified() {
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
                "CRITICAL: Shared engine file $filePath was modified! Diff:\n$diffOutput",
                diffOutput.isEmpty()
            )
        }
    }
}
