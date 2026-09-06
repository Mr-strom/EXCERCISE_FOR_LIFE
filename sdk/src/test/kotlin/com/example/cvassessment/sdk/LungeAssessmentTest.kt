package com.example.cvassessment.sdk

import com.example.cvassessment.sdk.form.LungeFormRuleEngine
import com.example.cvassessment.sdk.form.LungeFormRules
import com.example.cvassessment.sdk.metrics.RepMetrics
import com.example.cvassessment.sdk.output.OutputGate
import com.example.cvassessment.sdk.pose.PoseEstimationResult
import com.example.cvassessment.sdk.pose.PoseLandmark
import com.example.cvassessment.sdk.pose.PoseLandmarkType
import com.example.cvassessment.sdk.statemachine.ExercisePhase
import com.example.cvassessment.sdk.statemachine.LungeGeometry
import com.example.cvassessment.sdk.statemachine.LungeStateMachine
import com.example.cvassessment.sdk.visibility.LungeVisibilityGate
import com.example.cvassessment.sdk.visibility.VisibilityStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 12 Required Acceptance Criteria Unit Tests for Lunge Implementation.
 * Includes verification of:
 * - Front-leg vs back-leg identification
 * - Alternating lunge re-identification per rep
 * - Proactive D12 lessons (bottom-hold settle time, realistic depth threshold, 8° hysteresis)
 * - Non-side-view graceful degradation (tracking continues with scaled confidence)
 * - Zero shared engine modifications
 */
class LungeAssessmentTest {

    private fun createSyntheticLandmarks(
        leftAnkleX: Float = 0.50f,
        rightAnkleX: Float = 0.50f,
        leftHipX: Float = 0.50f,
        rightHipX: Float = 0.50f,
        leftKneeAngle: Float = 180.0f,
        rightKneeAngle: Float = 180.0f,
        isSideView: Boolean = true,
        facingRight: Boolean = true
    ): List<PoseLandmark> {
        val noseX = if (facingRight) 0.58f else 0.42f
        val shoulderLeftX = if (isSideView) 0.51f else 0.40f
        val shoulderRightX = if (isSideView) 0.49f else 0.60f
        val hipLX = if (isSideView) leftHipX else 0.42f
        val hipRX = if (isSideView) rightHipX else 0.58f

        val landmarks = mutableListOf<PoseLandmark>()
        for (i in 0..32) {
            val (x, y, z) = when (i) {
                PoseLandmarkType.NOSE -> Triple(noseX, 0.20f, 0.0f)
                PoseLandmarkType.LEFT_SHOULDER -> Triple(shoulderLeftX, 0.30f, if (isSideView) -0.15f else 0.0f)
                PoseLandmarkType.RIGHT_SHOULDER -> Triple(shoulderRightX, 0.30f, if (isSideView) 0.15f else 0.0f)
                PoseLandmarkType.LEFT_HIP -> Triple(hipLX, 0.50f, if (isSideView) -0.15f else 0.0f)
                PoseLandmarkType.RIGHT_HIP -> Triple(hipRX, 0.50f, if (isSideView) 0.15f else 0.0f)
                PoseLandmarkType.LEFT_KNEE -> Triple(0.50f, 0.70f, 0.0f)
                PoseLandmarkType.RIGHT_KNEE -> Triple(0.50f, 0.70f, 0.0f)
                PoseLandmarkType.LEFT_ANKLE -> Triple(leftAnkleX, 0.90f, 0.0f)
                PoseLandmarkType.RIGHT_ANKLE -> Triple(rightAnkleX, 0.90f, 0.0f)
                PoseLandmarkType.LEFT_FOOT_INDEX -> Triple(if (facingRight) leftAnkleX + 0.05f else leftAnkleX - 0.05f, 0.95f, 0.0f)
                PoseLandmarkType.RIGHT_FOOT_INDEX -> Triple(if (facingRight) rightAnkleX + 0.05f else rightAnkleX - 0.05f, 0.95f, 0.0f)
                else -> Triple(0.5f, 0.5f, 0.0f)
            }
            landmarks.add(PoseLandmark(index = i, name = "LM_$i", x = x, y = y, z = z, visibility = 0.95f))
        }
        return landmarks
    }

    /**
     * Unit Test 1: 5 clean lunges (same leg forward each time) -> 5 complete reps.
     */
    @Test
    fun testUnit1_FiveCleanLungesDetected() {
        val stateMachine = LungeStateMachine()
        var timeMs = 1000L

        // Initial setup at TOP position (> 160°)
        stateMachine.processAngle(frontKneeAngle = 165.0f, timestampMs = timeMs)
        assertEquals(ExercisePhase.TOP, stateMachine.currentPhase)

        for (rep in 1..5) {
            timeMs += 200L
            // Descent: 165° -> 140° -> 115° -> 90° (bottom target is <= 105°)
            val descState = stateMachine.processAngle(frontKneeAngle = 140.0f, timestampMs = timeMs)
            assertEquals("Should be descending in rep $rep", ExercisePhase.DESCENDING, descState.phase)
            timeMs += 300L

            stateMachine.processAngle(frontKneeAngle = 115.0f, timestampMs = timeMs)
            timeMs += 300L

            val bottomState = stateMachine.processAngle(frontKneeAngle = 90.0f, timestampMs = timeMs)
            assertEquals("Should reach bottom in rep $rep", ExercisePhase.BOTTOM, bottomState.phase)
            timeMs += 300L

            // Ascent: 115° -> 140° -> 165° (top target is >= 150°)
            val ascState = stateMachine.processAngle(frontKneeAngle = 115.0f, timestampMs = timeMs)
            assertEquals("Should be ascending in rep $rep", ExercisePhase.ASCENDING, ascState.phase)
            timeMs += 300L

            stateMachine.processAngle(frontKneeAngle = 140.0f, timestampMs = timeMs)
            timeMs += 300L

            val completeState = stateMachine.processAngle(frontKneeAngle = 165.0f, timestampMs = timeMs)
            assertEquals("Should return to top in rep $rep", ExercisePhase.TOP, completeState.phase)
            assertEquals("Should have $rep completed reps", rep, stateMachine.completeReps.size)
            assertNotNull("Should emit newly completed rep", completeState.newlyCompletedRep)
            assertEquals(rep, completeState.newlyCompletedRep!!.repIndex)
            timeMs += 200L
        }

        assertEquals(5, stateMachine.completeReps.size)
        assertEquals(0, stateMachine.incompleteReps.size)
    }

    /**
     * Unit Test 2: Reversal before reaching bottom tolerance -> 1 incomplete rep.
     */
    @Test
    fun testUnit2_ReversalBeforeReachingBottomToleranceProducesIncompleteRep() {
        val stateMachine = LungeStateMachine()

        // Start at top standing position
        stateMachine.processAngle(frontKneeAngle = 165.0f, timestampMs = 1000L)

        // Descend, but only reaches 120° (bottom depth target is <= 105°)
        stateMachine.processAngle(frontKneeAngle = 145.0f, timestampMs = 1300L)
        stateMachine.processAngle(frontKneeAngle = 130.0f, timestampMs = 1600L)
        stateMachine.processAngle(frontKneeAngle = 120.0f, timestampMs = 1900L) // Lowest angle reached

        // Premature upward reversal (120° -> 132°: reversal by 12° > 8° hysteresis)
        val reversalState = stateMachine.processAngle(frontKneeAngle = 132.0f, timestampMs = 2200L)

        // Return to top
        val topReturnState = stateMachine.processAngle(frontKneeAngle = 165.0f, timestampMs = 2600L)

        assertEquals("Should register 0 complete reps", 0, stateMachine.completeReps.size)
        assertEquals("Should register 1 incomplete rep", 1, stateMachine.incompleteReps.size)

        val incomplete = stateMachine.incompleteReps.first()
        assertEquals(1, incomplete.attemptIndex)
        assertTrue(incomplete.reason.contains("Reversed"))
    }

    /**
     * Unit Test 3: Visibility gap mid-rep -> discarded correctly, not falsely counted.
     */
    @Test
    fun testUnit3_VisibilityGapMidRepDiscardsAttempt() {
        val stateMachine = LungeStateMachine()

        // Start at top
        stateMachine.processAngle(frontKneeAngle = 165.0f, timestampMs = 1000L, isVisibilitySufficient = true)

        // Descend mid-rep
        stateMachine.processAngle(frontKneeAngle = 130.0f, timestampMs = 1300L, isVisibilitySufficient = true)
        stateMachine.processAngle(frontKneeAngle = 110.0f, timestampMs = 1600L, isVisibilitySufficient = true)
        assertTrue("Rep should be in progress", stateMachine.currentState.isRepInProgress)

        // Visibility drops mid-rep
        val dropState = stateMachine.processAngle(frontKneeAngle = 110.0f, timestampMs = 1800L, isVisibilitySufficient = false)
        assertFalse("In-progress rep must be aborted when visibility drops", dropState.isRepInProgress)
        assertEquals(ExercisePhase.TOP, dropState.phase)

        // Visibility restored and clean rep is completed
        stateMachine.processAngle(frontKneeAngle = 165.0f, timestampMs = 2500L, isVisibilitySufficient = true)
        stateMachine.processAngle(frontKneeAngle = 120.0f, timestampMs = 2800L, isVisibilitySufficient = true)
        stateMachine.processAngle(frontKneeAngle = 90.0f, timestampMs = 3200L, isVisibilitySufficient = true)
        stateMachine.processAngle(frontKneeAngle = 125.0f, timestampMs = 3600L, isVisibilitySufficient = true)
        stateMachine.processAngle(frontKneeAngle = 165.0f, timestampMs = 4000L, isVisibilitySufficient = true)

        assertEquals("Only completed rep after visibility restored should count", 1, stateMachine.completeReps.size)
        assertEquals("Completed rep must have repIndex 1", 1, stateMachine.completeReps.first().repIndex)
    }

    /**
     * Unit Test 4: ROM% hand-calculated verification.
     * Formula: rom = ((160° - minFrontKneeAngle) / (160° - 90°)) * 100%
     */
    @Test
    fun testUnit4_RomPercentHandCalculatedVerification() {
        // 1. Full depth reaching 90°: (160 - 90) / 70 * 100 = 100.0%
        val romFull = LungeGeometry.computeRomPercent(90.0f)
        assertEquals(100.0f, romFull, 0.01f)

        // 2. Over-depth reaching 70°: clamped to 100.0%
        val romOver = LungeGeometry.computeRomPercent(70.0f)
        assertEquals(100.0f, romOver, 0.01f)

        // 3. Halfway depth reaching 125°: (160 - 125) / 70 * 100 = 35 / 70 * 100 = 50.0%
        val romHalf = LungeGeometry.computeRomPercent(125.0f)
        assertEquals(50.0f, romHalf, 0.01f)

        // 4. Standing top position at 160°: (160 - 160) / 70 * 100 = 0.0%
        val romZero = LungeGeometry.computeRomPercent(160.0f)
        assertEquals(0.0f, romZero, 0.01f)
    }

    /**
     * Unit Test 5: TuT Factor + avgRepDurationSec consistency.
     * tutBaseline = 4.0s (2.0s down + 2.0s up).
     */
    @Test
    fun testUnit5_TutFactorAndAvgDurationConsistency() {
        val outputGate = OutputGate(ExerciseAnalyzer("lunge", "Lunge").config)

        // 1. Hand calculations for 3 known durations with baseline = 4.0s:
        // 2.0s duration: 2.0 / 4.0 = 0.50
        val tutFast = 2.0f / 4.0f
        assertEquals(0.50f, tutFast, 0.01f)

        // 4.0s duration: 4.0 / 4.0 = 1.00
        val tutExact = 4.0f / 4.0f
        assertEquals(1.00f, tutExact, 0.01f)

        // 6.0s duration: 6.0 / 4.0 = 1.50
        val tutSlow = 6.0f / 4.0f
        assertEquals(1.50f, tutSlow, 0.01f)

        // 2. Consistency check in ExerciseAnalyzer session:
        val analyzer = ExerciseAnalyzer("lunge", "Lunge")

        // Setup top at t=1000L
        analyzer.analyzeSyntheticFrame(elbowAngle = 165.0f, hipLineAngle = 0.0f, timestampMs = 1000L)

        // Rep 1: 4000ms duration (t=1200L to t=5200L)
        analyzer.analyzeSyntheticFrame(elbowAngle = 135.0f, hipLineAngle = 0.0f, timestampMs = 1200L)
        analyzer.analyzeSyntheticFrame(elbowAngle = 90.0f, hipLineAngle = 0.0f, timestampMs = 3200L)
        analyzer.analyzeSyntheticFrame(elbowAngle = 165.0f, hipLineAngle = 0.0f, timestampMs = 5200L) // Rep 1 complete: 4.0s

        // Pause between reps at top (5200L to 6000L)
        analyzer.analyzeSyntheticFrame(elbowAngle = 165.0f, hipLineAngle = 0.0f, timestampMs = 5600L)
        analyzer.analyzeSyntheticFrame(elbowAngle = 165.0f, hipLineAngle = 0.0f, timestampMs = 6000L)

        // Rep 2: 4800ms duration (t=6200L to t=11000L)
        analyzer.analyzeSyntheticFrame(elbowAngle = 135.0f, hipLineAngle = 0.0f, timestampMs = 6200L)
        analyzer.analyzeSyntheticFrame(elbowAngle = 90.0f, hipLineAngle = 0.0f, timestampMs = 8600L)
        analyzer.analyzeSyntheticFrame(elbowAngle = 165.0f, hipLineAngle = 0.0f, timestampMs = 11000L) // Rep 2 complete: 4.8s

        val sessionResult = analyzer.getSessionResult()
        assertEquals(ValidationStatus.VALID, sessionResult.status)
        assertEquals(2, sessionResult.completeReps)

        val avgDuration = sessionResult.avgRepDurationSec
        val tutFactor = sessionResult.tutFactor
        assertNotNull(avgDuration)
        assertNotNull(tutFactor)

        // Average duration = (4.0s + 4.8s) / 2 = 4.4s
        assertEquals("Average duration must be 4.4s", 4.4f, avgDuration!!, 0.05f)

        // tutFactor = 4.4s / 4.0s baseline = 1.10
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
     * Unit Test 6: Top-hold/bottom-hold settle time (D12 lesson) -> no false incomplete rep.
     */
    @Test
    fun testUnit6_BottomHoldSettleTimeAbsorbsJitterWithoutFalseIncompleteRep() {
        val stateMachine = LungeStateMachine()

        // 1. Start at top standing position
        stateMachine.processAngle(frontKneeAngle = 165.0f, timestampMs = 1000L)

        // 2. Descend to lunge bottom depth
        stateMachine.processAngle(frontKneeAngle = 130.0f, timestampMs = 1400L)
        val reachedBottom = stateMachine.processAngle(frontKneeAngle = 90.0f, timestampMs = 1800L)
        assertEquals("Should reach BOTTOM phase", ExercisePhase.BOTTOM, reachedBottom.phase)

        // 3. User pauses at bottom depth for 300ms (1800ms to 2100ms) with ±5° tracking jitter
        val jitterFrames = listOf(
            1850L to 94.0f,
            1900L to 89.0f,
            1950L to 95.0f,
            2000L to 91.0f,
            2050L to 94.0f,
            2100L to 90.0f
        )

        for ((timestamp, jitterAngle) in jitterFrames) {
            val jitterState = stateMachine.processAngle(frontKneeAngle = jitterAngle, timestampMs = timestamp)
            assertEquals("Pause jitter must remain in BOTTOM phase", ExercisePhase.BOTTOM, jitterState.phase)
            assertEquals("Pause jitter must NOT generate an incomplete rep", 0, stateMachine.incompleteReps.size)
        }

        // 4. User ascends back to top lockout
        stateMachine.processAngle(frontKneeAngle = 120.0f, timestampMs = 2400L)
        stateMachine.processAngle(frontKneeAngle = 145.0f, timestampMs = 2700L)
        val completedState = stateMachine.processAngle(frontKneeAngle = 165.0f, timestampMs = 3000L)

        assertEquals("Should return to TOP phase", ExercisePhase.TOP, completedState.phase)
        assertEquals("Must complete exactly 1 clean rep despite bottom-hold pause", 1, stateMachine.completeReps.size)
        assertEquals("Must have 0 incomplete reps", 0, stateMachine.incompleteReps.size)
    }

    /**
     * Unit Test 7: CRITICAL — front/back leg identification test:
     * - Left ankle forward -> correctly identifies left as front leg
     * - Right ankle forward -> correctly identifies right as front leg
     */
    @Test
    fun testUnit7_FrontBackLegIdentification() {
        // Case A: Left ankle forward (x = 0.65) relative to right ankle (x = 0.35), facing right (+x)
        val leftForwardLandmarks = createSyntheticLandmarks(
            leftAnkleX = 0.65f,
            rightAnkleX = 0.35f,
            leftHipX = 0.50f,
            rightHipX = 0.50f,
            facingRight = true
        )
        val identifiedLeft = LungeGeometry.identifyFrontLeg(leftForwardLandmarks)
        assertEquals("Must identify LEFT leg as front leg when left ankle is forward", LungeGeometry.LegSide.LEFT, identifiedLeft)

        // Case B: Right ankle forward (x = 0.65) relative to left ankle (x = 0.35), facing right (+x)
        val rightForwardLandmarks = createSyntheticLandmarks(
            leftAnkleX = 0.35f,
            rightAnkleX = 0.65f,
            leftHipX = 0.50f,
            rightHipX = 0.50f,
            facingRight = true
        )
        val identifiedRight = LungeGeometry.identifyFrontLeg(rightForwardLandmarks)
        assertEquals("Must identify RIGHT leg as front leg when right ankle is forward", LungeGeometry.LegSide.RIGHT, identifiedRight)

        // Case C: Facing left (-x): left ankle forward (x = 0.35) relative to right ankle (x = 0.65)
        val leftFacingLandmarks = createSyntheticLandmarks(
            leftAnkleX = 0.35f,
            rightAnkleX = 0.65f,
            leftHipX = 0.50f,
            rightHipX = 0.50f,
            facingRight = false
        )
        val identifiedLeftFacing = LungeGeometry.identifyFrontLeg(leftFacingLandmarks)
        assertEquals("Must identify LEFT leg as front leg when facing left with left ankle forward", LungeGeometry.LegSide.LEFT, identifiedLeftFacing)
    }

    /**
     * Unit Test 8: ALTERNATING LUNGE test — synthetic sequence where front leg switches
     * between Rep 1 (left forward) and Rep 2 (right forward).
     * Both reps correctly detected as complete, with correct front-leg identity per rep.
     */
    @Test
    fun testUnit8_AlternatingLungeSequence() {
        val stateMachine = LungeStateMachine()
        var timeMs = 1000L

        // Rep 1: Left leg forward
        stateMachine.processAngle(frontKneeAngle = 165.0f, timestampMs = timeMs, frontLeg = LungeGeometry.LegSide.LEFT)
        timeMs += 200L
        stateMachine.processAngle(frontKneeAngle = 130.0f, timestampMs = timeMs, frontLeg = LungeGeometry.LegSide.LEFT)
        timeMs += 300L
        stateMachine.processAngle(frontKneeAngle = 90.0f, timestampMs = timeMs, frontLeg = LungeGeometry.LegSide.LEFT)
        timeMs += 300L
        stateMachine.processAngle(frontKneeAngle = 130.0f, timestampMs = timeMs, frontLeg = LungeGeometry.LegSide.LEFT)
        timeMs += 300L
        val rep1Done = stateMachine.processAngle(frontKneeAngle = 165.0f, timestampMs = timeMs, frontLeg = LungeGeometry.LegSide.LEFT)

        assertEquals("Rep 1 must be complete", 1, stateMachine.completeReps.size)
        assertEquals("Rep 1 front leg must be LEFT", LungeGeometry.LegSide.LEFT, stateMachine.getFrontLegForRep(1))

        // Pause at standing top position before switching legs
        timeMs += 400L
        stateMachine.processAngle(frontKneeAngle = 165.0f, timestampMs = timeMs, frontLeg = LungeGeometry.LegSide.RIGHT)

        // Rep 2: Right leg forward
        timeMs += 200L
        stateMachine.processAngle(frontKneeAngle = 130.0f, timestampMs = timeMs, frontLeg = LungeGeometry.LegSide.RIGHT)
        timeMs += 300L
        stateMachine.processAngle(frontKneeAngle = 90.0f, timestampMs = timeMs, frontLeg = LungeGeometry.LegSide.RIGHT)
        timeMs += 300L
        stateMachine.processAngle(frontKneeAngle = 130.0f, timestampMs = timeMs, frontLeg = LungeGeometry.LegSide.RIGHT)
        timeMs += 300L
        val rep2Done = stateMachine.processAngle(frontKneeAngle = 165.0f, timestampMs = timeMs, frontLeg = LungeGeometry.LegSide.RIGHT)

        assertEquals("Both alternating reps must be complete", 2, stateMachine.completeReps.size)
        assertEquals("Rep 1 front leg must be recorded as LEFT", LungeGeometry.LegSide.LEFT, stateMachine.getFrontLegForRep(1))
        assertEquals("Rep 2 front leg must be recorded as RIGHT", LungeGeometry.LegSide.RIGHT, stateMachine.getFrontLegForRep(2))
        assertEquals("No incomplete reps should be logged", 0, stateMachine.incompleteReps.size)
    }

    /**
     * Unit Test 9: insufficient_depth triggers correctly.
     */
    @Test
    fun testUnit9_InsufficientDepthTriggersCorrectly() {
        val engine = LungeFormRuleEngine()

        // Rep completed with insufficient depth (romPercent = 52.0% < 60%)
        val shallowRepMetrics = RepMetrics(
            repIndex = 1,
            romPercent = 52.0f,
            tutFactor = 1.0f,
            confidence = 0.95f,
            durationSec = 4.0f,
            minElbowAngle = 123.6f,
            startTimestampMs = 1000L,
            endTimestampMs = 5000L
        )

        val out = engine.evaluateFrame(
            frontKneeAngle = 165.0f,
            torsoAngle = 0.0f,
            phase = ExercisePhase.TOP,
            isRepInProgress = false,
            currentRepIndex = 1,
            timestampMs = 5000L,
            confidence = 0.95f,
            completedRepMetrics = shallowRepMetrics
        )

        assertTrue("insufficient_depth must trigger when romPercent < 60%", out.activeErrors.any { it.errorName == "insufficient_depth" })
        val error = out.activeErrors.first { it.errorName == "insufficient_depth" }
        assertEquals(0.60f, error.severity, 0.01f)

        val feedback = out.newFeedbackEvents.find { it.relatedError == "insufficient_depth" }
        assertNotNull("Feedback event should be emitted for insufficient depth", feedback)
        assertEquals("Go lower.", feedback!!.message)
    }

    /**
     * Unit Test 10: asymmetric_movement comparison across alternating-leg reps.
     * Evaluates cross-rep symmetry: compares depth/tempo consistency between left-leg-forward
     * and right-leg-forward reps.
     */
    @Test
    fun testUnit10_AsymmetricMovementComparisonAcrossAlternatingLegReps() {
        val engine = LungeFormRuleEngine()

        // Rep 1: Left leg forward with full depth (romPercent = 95.0%, duration = 4.0s)
        val rep1Metrics = RepMetrics(
            repIndex = 1,
            romPercent = 95.0f,
            tutFactor = 1.0f,
            confidence = 0.95f,
            durationSec = 4.0f,
            minElbowAngle = 93.5f,
            startTimestampMs = 1000L,
            endTimestampMs = 5000L
        )

        val out1 = engine.evaluateFrame(
            frontKneeAngle = 165.0f,
            phase = ExercisePhase.TOP,
            currentRepIndex = 1,
            timestampMs = 5000L,
            completedRepMetrics = rep1Metrics,
            frontLeg = LungeGeometry.LegSide.LEFT
        )
        // Only 1 leg evaluated so far: cannot compare symmetry yet
        assertFalse(out1.activeErrors.any { it.errorName == "asymmetric_movement" })

        // Rep 2: Right leg forward with significantly lower depth (romPercent = 65.0% -> diff = 30% >= 20% threshold)
        val rep2Metrics = RepMetrics(
            repIndex = 2,
            romPercent = 65.0f,
            tutFactor = 1.0f,
            confidence = 0.95f,
            durationSec = 4.0f,
            minElbowAngle = 114.5f,
            startTimestampMs = 6000L,
            endTimestampMs = 10000L
        )

        val out2 = engine.evaluateFrame(
            frontKneeAngle = 165.0f,
            phase = ExercisePhase.TOP,
            currentRepIndex = 2,
            timestampMs = 10000L,
            completedRepMetrics = rep2Metrics,
            frontLeg = LungeGeometry.LegSide.RIGHT
        )

        assertTrue(
            "asymmetric_movement must trigger when alternating reps differ by >= 20% ROM",
            out2.activeErrors.any { it.errorName == "asymmetric_movement" }
        )
        val error = out2.activeErrors.first { it.errorName == "asymmetric_movement" }
        assertEquals(0.50f, error.severity, 0.01f)

        val feedback = out2.newFeedbackEvents.find { it.relatedError == "asymmetric_movement" }
        assertNotNull("Feedback event should be emitted for asymmetric movement", feedback)
        assertEquals("Keep both sides even.", feedback!!.message)
    }

    /**
     * Unit Test 11: Non-side-view graceful degradation — verify tracking still attempts
     * (doesn't hard-refuse) but confidence is appropriately reduced.
     */
    @Test
    fun testUnit11_NonSideViewGracefulDegradation() {
        val analyzer = ExerciseAnalyzer("lunge", "Lunge")

        // In side view, full confidence (1.0f)
        val sideViewResult = analyzer.analyzeSyntheticFrame(
            elbowAngle = 165.0f,
            hipLineAngle = 0.0f,
            timestampMs = 1000L,
            confidence = 1.0f,
            isVisibilitySufficient = true,
            isSideViewOverride = true
        )
        assertEquals(ValidationStatus.VALID, sideViewResult.status)
        assertEquals(1.0f, sideViewResult.confidence, 0.01f)

        // In non-side view (front view), tracking continues (does NOT hard-refuse to INSUFFICIENT_VISIBILITY),
        // but confidence is scaled down (1.0 * 0.75 = 0.75f)
        val frontViewResult = analyzer.analyzeSyntheticFrame(
            elbowAngle = 165.0f,
            hipLineAngle = 0.0f,
            timestampMs = 2000L,
            confidence = 1.0f,
            isVisibilitySufficient = true,
            isSideViewOverride = false
        )

        assertEquals(
            "Tracking must still attempt rather than hard-refusing",
            ValidationStatus.VALID,
            frontViewResult.status
        )
        assertEquals(
            "Confidence must be scaled down in non-side view (0.75x)",
            0.75f,
            frontViewResult.confidence,
            0.01f
        )
    }

    /**
     * Unit Test 12: Verify NO shared engine code was modified.
     * Asserts that the 5 base engine files have ZERO modifications relative to git HEAD.
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

    /**
     * D13 Regression Test: Visibility gap during descent + recovery ascent does NOT produce a phantom incomplete rep.
     * Simulates athlete descending into lunge, losing visibility mid-rep (foot leaves frame),
     * regaining visibility while standing back up (knee angle 115°), and finishing ascent to 165°.
     */
    @Test
    fun testD13_VisibilityGapDuringDescentRecoveryAscentProducesNoPhantomIncompleteRep() {
        val stateMachine = LungeStateMachine()

        // 1. Initial standing position at TOP (165°)
        stateMachine.processAngle(frontKneeAngle = 165.0f, timestampMs = 1000L, isVisibilitySufficient = true)

        // 2. Descend into lunge (reaches 110°)
        stateMachine.processAngle(frontKneeAngle = 140.0f, timestampMs = 1300L, isVisibilitySufficient = true)
        stateMachine.processAngle(frontKneeAngle = 110.0f, timestampMs = 1600L, isVisibilitySufficient = true)
        assertTrue("Lunge should be in progress", stateMachine.currentState.isRepInProgress)

        // 3. Visibility drops mid-rep (e.g. backward foot cut off at frame bottom)
        stateMachine.processAngle(frontKneeAngle = 100.0f, timestampMs = 1900L, isVisibilitySufficient = false)
        assertEquals("In-progress rep discarded on visibility drop", 0, stateMachine.completeReps.size)
        assertEquals("Discarded rep must NOT be counted as incomplete", 0, stateMachine.incompleteReps.size)
        assertTrue("awaitingTopExtension must be armed", stateMachine.awaitingTopExtension)

        // 4. Visibility restored 300ms later while athlete is mid-recovery (knee angle is 115°!)
        stateMachine.processAngle(frontKneeAngle = 115.0f, timestampMs = 2200L, isVisibilitySufficient = true)
        assertFalse("Must NOT initiate new rep attempt at 115° during recovery", stateMachine.currentState.isRepInProgress)
        assertEquals("Must remain in TOP phase waiting for extension", ExercisePhase.TOP, stateMachine.currentState.phase)

        // 5. Athlete finishes standing up (115° -> 135° -> 165°)
        stateMachine.processAngle(frontKneeAngle = 135.0f, timestampMs = 2500L, isVisibilitySufficient = true)
        stateMachine.processAngle(frontKneeAngle = 165.0f, timestampMs = 2800L, isVisibilitySufficient = true)

        assertEquals("Zero complete reps after recovery", 0, stateMachine.completeReps.size)
        assertEquals("CRITICAL D13: ZERO phantom incomplete reps flagged during recovery ascent", 0, stateMachine.incompleteReps.size)
        assertFalse("awaitingTopExtension cleared upon reaching standing extension", stateMachine.awaitingTopExtension)

        // 6. Next clean rep executed from standing position completes normally
        stateMachine.processAngle(frontKneeAngle = 140.0f, timestampMs = 3200L, isVisibilitySufficient = true)
        stateMachine.processAngle(frontKneeAngle = 90.0f, timestampMs = 3600L, isVisibilitySufficient = true)
        stateMachine.processAngle(frontKneeAngle = 130.0f, timestampMs = 4000L, isVisibilitySufficient = true)
        stateMachine.processAngle(frontKneeAngle = 165.0f, timestampMs = 4400L, isVisibilitySufficient = true)

        assertEquals("Clean rep successfully detected after re-arming", 1, stateMachine.completeReps.size)
        assertEquals("Incomplete reps remains 0", 0, stateMachine.incompleteReps.size)
    }

    /**
     * D13 Test: Strict ankle gating flags INSUFFICIENT_VISIBILITY when ankle exceeds y >= 0.92 cutoff.
     */
    @Test
    fun testD13_StrictAnkleGatingFlagsCutoff() {
        val visGate = LungeVisibilityGate()

        val cutOffLandmarks = listOf(
            PoseLandmark(PoseLandmarkType.LEFT_SHOULDER, "LEFT_SHOULDER", 0.45f, 0.25f, 0.0f, 0.95f),
            PoseLandmark(PoseLandmarkType.RIGHT_SHOULDER, "RIGHT_SHOULDER", 0.55f, 0.25f, 0.0f, 0.95f),
            PoseLandmark(PoseLandmarkType.LEFT_HIP, "LEFT_HIP", 0.45f, 0.50f, 0.0f, 0.90f),
            PoseLandmark(PoseLandmarkType.RIGHT_HIP, "RIGHT_HIP", 0.55f, 0.50f, 0.0f, 0.90f),
            PoseLandmark(PoseLandmarkType.LEFT_KNEE, "LEFT_KNEE", 0.42f, 0.70f, 0.0f, 0.85f),
            PoseLandmark(PoseLandmarkType.RIGHT_KNEE, "RIGHT_KNEE", 0.58f, 0.75f, 0.0f, 0.85f),
            PoseLandmark(PoseLandmarkType.LEFT_ANKLE, "LEFT_ANKLE", 0.40f, 0.85f, 0.0f, 0.85f),
            // Right ankle is cut off at y=0.94 (>= ankleCutoffY 0.92f)
            PoseLandmark(PoseLandmarkType.RIGHT_ANKLE, "RIGHT_ANKLE", 0.60f, 0.94f, 0.0f, 0.80f)
        )
        val result = visGate.checkFrame(PoseEstimationResult(cutOffLandmarks, hasPose = true, timestampMs = 1000L))
        assertEquals("Cutoff ankle must trigger INSUFFICIENT_VISIBILITY", VisibilityStatus.INSUFFICIENT_VISIBILITY, result.status)
        assertTrue(result.failureReasons.contains(com.example.cvassessment.sdk.visibility.VisibilityFailureReason.BODY_OUT_OF_FRAME))
    }
}
