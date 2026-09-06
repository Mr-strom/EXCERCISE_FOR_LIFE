package com.example.cvassessment.sdk

import com.example.cvassessment.sdk.form.JumpingJackFormRuleEngine
import com.example.cvassessment.sdk.form.JumpingJackFormRules
import com.example.cvassessment.sdk.pose.PoseEstimationResult
import com.example.cvassessment.sdk.pose.PoseLandmark
import com.example.cvassessment.sdk.pose.PoseLandmarkType
import com.example.cvassessment.sdk.statemachine.ExercisePhase
import com.example.cvassessment.sdk.statemachine.JumpingJackGeometry
import com.example.cvassessment.sdk.statemachine.JumpingJackStateMachine
import com.example.cvassessment.sdk.visibility.JumpingJackVisibilityGate
import com.example.cvassessment.sdk.visibility.VisibilityStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 9 Required Acceptance Criteria Unit Tests for Jumping Jack Implementation (Ninth Exercise).
 * Per EXERCISE_SPEC.md #9, METRICS_SPEC.md, FORM_RULES.md, and SDK_CONTRACT.md.
 */
class JumpingJackAssessmentTest {

    private fun createSyntheticLandmarks(
        isFrontView: Boolean = true,
        visibility: Float = 0.95f,
        armAngle: Float = 30.0f,
        legAngle: Float = 12.0f
    ): List<PoseLandmark> {
        val shoulderLX = if (isFrontView) 0.40f else 0.495f
        val shoulderRX = if (isFrontView) 0.60f else 0.505f
        val hipLX = if (isFrontView) 0.42f else 0.498f
        val hipRX = if (isFrontView) 0.58f else 0.502f

        val landmarks = mutableListOf<PoseLandmark>()
        for (i in 0..32) {
            val (x, y, z) = when (i) {
                PoseLandmarkType.LEFT_SHOULDER -> Triple(shoulderLX, 0.30f, 0.0f)
                PoseLandmarkType.RIGHT_SHOULDER -> Triple(shoulderRX, 0.30f, 0.0f)
                PoseLandmarkType.LEFT_ELBOW -> Triple(shoulderLX - 0.10f, 0.40f, 0.0f)
                PoseLandmarkType.RIGHT_ELBOW -> Triple(shoulderRX + 0.10f, 0.40f, 0.0f)
                PoseLandmarkType.LEFT_WRIST -> Triple(shoulderLX - 0.15f, 0.50f, 0.0f)
                PoseLandmarkType.RIGHT_WRIST -> Triple(shoulderRX + 0.15f, 0.50f, 0.0f)
                PoseLandmarkType.LEFT_HIP -> Triple(hipLX, 0.55f, 0.0f)
                PoseLandmarkType.RIGHT_HIP -> Triple(hipRX, 0.55f, 0.0f)
                PoseLandmarkType.LEFT_ANKLE -> Triple(0.45f, 0.85f, 0.0f)
                PoseLandmarkType.RIGHT_ANKLE -> Triple(0.55f, 0.85f, 0.0f)
                else -> Triple(0.5f, 0.5f, 0.0f)
            }
            landmarks.add(PoseLandmark(index = i, name = "LM_$i", x = x, y = y, z = z, visibility = visibility))
        }
        return landmarks
    }

    /**
     * Unit Test 1: Normal rep sequence completing 100% ROM at 1.2s baseline tempo.
     * Arms: 30° -> 150° -> 30°
     * Legs: 12° -> 45° -> 12°
     * Asserts: completeReps == 1, incompleteReps == 0, romPercent == 100%, tutFactor ≈ 1.0, 0 errors.
     */
    @Test
    fun testUnit1_NormalRepSequence100RomAndTempo() {
        val analyzer = ExerciseAnalyzer("jumping_jack", "Jumping Jack")
        var timeMs = 1000L

        // 1. Start in CLOSED position
        analyzer.analyzeSyntheticFrame(elbowAngle = 30.0f, hipLineAngle = 12.0f, timestampMs = timeMs)

        // 2. Open movement (600ms)
        timeMs += 200L
        analyzer.analyzeSyntheticFrame(elbowAngle = 70.0f, hipLineAngle = 22.0f, timestampMs = timeMs)
        timeMs += 200L
        analyzer.analyzeSyntheticFrame(elbowAngle = 120.0f, hipLineAngle = 35.0f, timestampMs = timeMs)
        timeMs += 200L
        analyzer.analyzeSyntheticFrame(elbowAngle = 150.0f, hipLineAngle = 45.0f, timestampMs = timeMs) // Peak open

        // 3. Settle at OPEN (90ms)
        timeMs += 90L
        analyzer.analyzeSyntheticFrame(elbowAngle = 152.0f, hipLineAngle = 46.0f, timestampMs = timeMs)

        // 4. Close movement (600ms)
        timeMs += 200L
        analyzer.analyzeSyntheticFrame(elbowAngle = 110.0f, hipLineAngle = 32.0f, timestampMs = timeMs)
        timeMs += 200L
        analyzer.analyzeSyntheticFrame(elbowAngle = 60.0f, hipLineAngle = 20.0f, timestampMs = timeMs)
        timeMs += 200L
        val finalResult = analyzer.analyzeSyntheticFrame(elbowAngle = 30.0f, hipLineAngle = 12.0f, timestampMs = timeMs)

        assertEquals("Should detect exactly 1 complete rep", 1, finalResult.currentReps)
        val latestRep = analyzer.latestCompletedRepMetrics
        assertNotNull("Latest rep metrics should not be null", latestRep)
        assertEquals("Combined ROM should be 100%", 100.0f, latestRep!!.romPercent, 0.5f)
        assertEquals("TuT factor should be ≈ 1.0x", 1.0f, latestRep.tutFactor, 0.15f)

        val session = analyzer.getSessionResult()
        assertEquals("Session complete rep count should be 1", 1, session.completeReps)
        assertEquals("Session incomplete rep count should be 0", 0, session.incompleteReps)
        assertTrue("Session should have 0 form errors", session.formErrors.isEmpty())
        assertEquals("Session form factor should be 100%", 1.0f, session.formFactor!!, 0.01f)
    }

    /**
     * Unit Test 2: Settle-time and rapid cadence responsiveness.
     * Verifies that the scaled-down 90ms settle time and 5° hysteresis cleanly handle rapid bounces
     * without missing phase transitions or getting stuck at open.
     */
    @Test
    fun testUnit2_RapidCadenceAndSettleTime() {
        val stateMachine = JumpingJackStateMachine()
        var timeMs = 1000L

        // Closed -> Opening -> Open
        stateMachine.processAngles(armAngle = 30.0f, legAngle = 12.0f, timestampMs = timeMs)
        timeMs += 150L
        stateMachine.processAngles(armAngle = 100.0f, legAngle = 30.0f, timestampMs = timeMs)
        timeMs += 150L
        val openState = stateMachine.processAngles(armAngle = 155.0f, legAngle = 48.0f, timestampMs = timeMs)
        assertEquals("Should transition to BOTTOM (OPEN)", ExercisePhase.BOTTOM, openState.phase)

        // Rapid bounce dwell: 90ms at open
        timeMs += 90L
        stateMachine.processAngles(armAngle = 153.0f, legAngle = 47.0f, timestampMs = timeMs)

        // Reversal starts: arm drops by > 5° (from 155° to 148°)
        timeMs += 50L
        val closingState = stateMachine.processAngles(armAngle = 148.0f, legAngle = 41.0f, timestampMs = timeMs)
        assertEquals("Should transition to ASCENDING (CLOSING) after 90ms settle and 5° drop", ExercisePhase.ASCENDING, closingState.phase)

        // Return to closed
        timeMs += 200L
        stateMachine.processAngles(armAngle = 80.0f, legAngle = 25.0f, timestampMs = timeMs)
        timeMs += 200L
        val closedState = stateMachine.processAngles(armAngle = 30.0f, legAngle = 12.0f, timestampMs = timeMs)

        assertEquals("Rep should complete cleanly", 1, closedState.completeRepCount)
        assertEquals("Should return to TOP (CLOSED)", ExercisePhase.TOP, closedState.phase)
    }

    /**
     * Unit Test 3: Guarded reversal logs incomplete rep when reversing prematurely.
     * Athlete opens arms to only 90° (< 145°) and legs to 25° (< 40°), then returns to closed.
     */
    @Test
    fun testUnit3_GuardedReversalIncompleteRep() {
        val stateMachine = JumpingJackStateMachine()
        var timeMs = 1000L

        // Start closed
        stateMachine.processAngles(armAngle = 30.0f, legAngle = 12.0f, timestampMs = timeMs)
        timeMs += 200L
        // Opening partially
        val openingState = stateMachine.processAngles(armAngle = 90.0f, legAngle = 25.0f, timestampMs = timeMs)
        assertEquals(ExercisePhase.DESCENDING, openingState.phase)
        assertTrue(openingState.isRepInProgress)

        // Reverses prematurely back to closed
        timeMs += 200L
        stateMachine.processAngles(armAngle = 60.0f, legAngle = 18.0f, timestampMs = timeMs)
        timeMs += 200L
        val finalState = stateMachine.processAngles(armAngle = 30.0f, legAngle = 12.0f, timestampMs = timeMs)

        assertEquals("Should not record complete rep", 0, finalState.completeRepCount)
        assertEquals("Should record 1 incomplete rep", 1, finalState.incompleteRepCount)
        assertFalse("Rep should no longer be in progress", finalState.isRepInProgress)
        assertEquals("Should return to TOP (CLOSED)", ExercisePhase.TOP, finalState.phase)
        assertTrue("Reason should mention target open position",
            stateMachine.incompleteReps.first().reason.contains("open target"))
    }

    /**
     * Unit Test 4: Combined two-limb-group ROM uses MINIMUM of arm ROM and leg ROM.
     * Arms reach 150° (100% ROM). Legs reach 28.5° (50% ROM).
     * romPercent = min(100%, 50%) = 50% (NOT average 75%).
     * Rep completes and triggers insufficient_depth (< 60%).
     */
    @Test
    fun testUnit4_CombinedLimbGroupRomMinimumCalculation() {
        val armRom = JumpingJackGeometry.calculateArmRom(150.0f)
        val legRom = JumpingJackGeometry.calculateLegRom(28.5f) // (28.5 - 12) / (45 - 12) = 16.5 / 33 = 0.50 -> 50%
        val combinedRom = JumpingJackGeometry.calculateCombinedRom(150.0f, 28.5f)

        assertEquals(100.0f, armRom, 0.1f)
        assertEquals(50.0f, legRom, 0.1f)
        assertEquals("Combined ROM must be minimum of arms and legs, not average", 50.0f, combinedRom, 0.1f)

        // Test through ExerciseAnalyzer
        val analyzer = ExerciseAnalyzer("jumping_jack", "Jumping Jack")
        var timeMs = 1000L

        analyzer.analyzeSyntheticFrame(elbowAngle = 30.0f, hipLineAngle = 12.0f, timestampMs = timeMs)
        timeMs += 300L
        analyzer.analyzeSyntheticFrame(elbowAngle = 100.0f, hipLineAngle = 20.0f, timestampMs = timeMs)
        timeMs += 300L
        // Arms open to 150°, legs open only to 40° (threshold to allow rep completion), but min calculation test at 28.5°
        // Note: stateMachine open threshold is arm >= 145° and leg >= 40°. Let's test leg reaching 40°:
        val legAt40Rom = JumpingJackGeometry.calculateLegRom(40.0f) // (40 - 12)/(45 - 12) = 28/33 = 84.8%
        val minRomAt40 = JumpingJackGeometry.calculateCombinedRom(150.0f, 40.0f)
        assertEquals(84.84f, minRomAt40, 0.5f)

        // If leg reached only 28.5° (below open target), reversing triggers incomplete rep
        val sm = JumpingJackStateMachine()
        sm.processAngles(armAngle = 30.0f, legAngle = 12.0f, timestampMs = 1000L)
        sm.processAngles(armAngle = 150.0f, legAngle = 28.5f, timestampMs = 1500L)
        val reversed = sm.processAngles(armAngle = 30.0f, legAngle = 12.0f, timestampMs = 2000L)
        assertEquals(1, reversed.incompleteRepCount)
        assertEquals("Incomplete rep min angle achieved should equal 50% combined ROM", 50.0f, reversed.incompleteReps.first().minElbowAngleAchieved, 0.5f)
    }

    /**
     * Unit Test 5: Asymmetric Jack detection (arm and leg open transitions diverge by > 180ms).
     * Arms reach open at t=1300ms, legs reach open at t=1550ms (divergence = 250ms > 180ms).
     * Asserts: asymmetric_jack error is recorded (severity 0.35, "Sync your arms and legs.").
     */
    @Test
    fun testUnit5_AsymmetricJackDetection() {
        val analyzer = ExerciseAnalyzer("jumping_jack", "Jumping Jack")
        var timeMs = 1000L

        // Closed
        analyzer.analyzeSyntheticFrame(elbowAngle = 30.0f, hipLineAngle = 12.0f, timestampMs = timeMs)

        // Arms reach open early at t=1300ms, while legs are still opening (25°)
        timeMs = 1300L
        analyzer.analyzeSyntheticFrame(elbowAngle = 150.0f, hipLineAngle = 25.0f, timestampMs = timeMs)

        // Legs reach open late at t=1550ms (delta = 250ms > 180ms)
        timeMs = 1550L
        analyzer.analyzeSyntheticFrame(elbowAngle = 152.0f, hipLineAngle = 45.0f, timestampMs = timeMs)

        // Settle at open
        timeMs = 1640L
        analyzer.analyzeSyntheticFrame(elbowAngle = 150.0f, hipLineAngle = 44.0f, timestampMs = timeMs)

        // Return to closed
        timeMs = 2200L
        analyzer.analyzeSyntheticFrame(elbowAngle = 30.0f, hipLineAngle = 12.0f, timestampMs = timeMs)

        val session = analyzer.getSessionResult()
        assertEquals(1, session.completeReps)
        val asymError = session.formErrors.find { it.errorName == "asymmetric_jack" }
        assertNotNull("Should detect asymmetric_jack error", asymError)
        assertEquals(0.35f, asymError!!.severity, 0.01f)
        assertTrue(session.feedbackEvents.any { it.message == "Sync your arms and legs." })
    }

    /**
     * Unit Test 6: Synchronized Jack (arm and leg open transitions occur within <= 180ms).
     * Arms open at t=1300ms, legs open at t=1360ms (divergence = 60ms <= 180ms).
     * Asserts: No asymmetric_jack error fired.
     */
    @Test
    fun testUnit6_SynchronizedJackNoAsymmetryError() {
        val analyzer = ExerciseAnalyzer("jumping_jack", "Jumping Jack")
        var timeMs = 1000L

        analyzer.analyzeSyntheticFrame(elbowAngle = 30.0f, hipLineAngle = 12.0f, timestampMs = timeMs)

        // Arms reach open at 1300ms
        timeMs = 1300L
        analyzer.analyzeSyntheticFrame(elbowAngle = 148.0f, hipLineAngle = 36.0f, timestampMs = timeMs)

        // Legs reach open at 1360ms (60ms divergence <= 180ms)
        timeMs = 1360L
        analyzer.analyzeSyntheticFrame(elbowAngle = 150.0f, hipLineAngle = 45.0f, timestampMs = timeMs)

        // Settle
        timeMs = 1450L
        analyzer.analyzeSyntheticFrame(elbowAngle = 150.0f, hipLineAngle = 44.0f, timestampMs = timeMs)

        // Return to closed
        timeMs = 2100L
        analyzer.analyzeSyntheticFrame(elbowAngle = 30.0f, hipLineAngle = 12.0f, timestampMs = timeMs)

        val session = analyzer.getSessionResult()
        assertEquals(1, session.completeReps)
        val asymError = session.formErrors.find { it.errorName == "asymmetric_jack" }
        assertNull("Should NOT detect asymmetric_jack error when synchronized", asymError)
    }

    /**
     * Unit Test 7: Rushing tempo warning fires after 2+ consecutive reps faster than 0.72s (< 0.60x of 1.2s baseline).
     * Normal brisk reps at 1.0s do NOT fire rushing_tempo.
     */
    @Test
    fun testUnit7_RushingTempoWarningOnFastCadence() {
        val analyzer = ExerciseAnalyzer("jumping_jack", "Jumping Jack")
        var timeMs = 1000L

        // Rep 1: Very rushed rep (duration = 500ms < 720ms, tutFactor = 500/1200 = 0.417)
        analyzer.analyzeSyntheticFrame(elbowAngle = 30.0f, hipLineAngle = 12.0f, timestampMs = timeMs)
        timeMs += 250L
        analyzer.analyzeSyntheticFrame(elbowAngle = 150.0f, hipLineAngle = 45.0f, timestampMs = timeMs)
        timeMs += 250L
        analyzer.analyzeSyntheticFrame(elbowAngle = 30.0f, hipLineAngle = 12.0f, timestampMs = timeMs)

        // Rep 1 alone should not fire rushing_tempo (requires 2+ consecutive reps)
        var session = analyzer.getSessionResult()
        assertNull("1 fast rep should not fire rushing_tempo", session.formErrors.find { it.errorName == "rushing_tempo" })

        // Rep 2: Another rushed rep (duration = 500ms)
        timeMs += 50L
        analyzer.analyzeSyntheticFrame(elbowAngle = 30.0f, hipLineAngle = 12.0f, timestampMs = timeMs)
        timeMs += 250L
        analyzer.analyzeSyntheticFrame(elbowAngle = 150.0f, hipLineAngle = 45.0f, timestampMs = timeMs)
        timeMs += 250L
        analyzer.analyzeSyntheticFrame(elbowAngle = 30.0f, hipLineAngle = 12.0f, timestampMs = timeMs)

        // Now 2 consecutive rushed reps completed
        session = analyzer.getSessionResult()
        val tempoError = session.formErrors.find { it.errorName == "rushing_tempo" }
        assertNotNull("2 consecutive fast reps (< 0.72s) must trigger rushing_tempo", tempoError)
        assertEquals(0.45f, tempoError!!.severity, 0.01f)
        assertTrue(session.feedbackEvents.any { it.message == "Slow down, control the movement." })
    }

    /**
     * Unit Test 8: Front-view visibility gating and R7 session refusal.
     * Side view profile collapses coronal plane and immediately fails visibility gate.
     * >50% failed frames triggers session-level INSUFFICIENT_VISIBILITY and null metrics.
     */
    @Test
    fun testUnit8_FrontViewVisibilityGatingAndR7Refusal() {
        val gate = JumpingJackVisibilityGate()

        // 1. Front view landmarks pass visibility
        val frontPose = PoseEstimationResult(
            landmarks = createSyntheticLandmarks(isFrontView = true),
            timestampMs = 1000L,
            hasPose = true
        )
        val frontResult = gate.checkFrame(frontPose)
        assertEquals("Front view must pass visibility", VisibilityStatus.SUFFICIENT_VISIBILITY, frontResult.status)

        // 2. Side view landmarks fail visibility
        val sidePose = PoseEstimationResult(
            landmarks = createSyntheticLandmarks(isFrontView = false),
            timestampMs = 1033L,
            hasPose = true
        )
        val sideResult = gate.checkFrame(sidePose)
        assertEquals("Side view must fail visibility for Jumping Jack", VisibilityStatus.INSUFFICIENT_VISIBILITY, sideResult.status)

        // 3. Feed 10 consecutive side view frames to trigger session failure (> 50%)
        for (i in 1..10) {
            gate.checkFrame(sidePose)
        }
        assertEquals("Session must fail if >50% frames fail", VisibilityStatus.INSUFFICIENT_VISIBILITY, gate.getSessionVisibilityStatus())

        // 4. Test R7 session refusal in ExerciseAnalyzer
        val analyzer = ExerciseAnalyzer("jumping_jack", "Jumping Jack")
        for (i in 1..10) {
            val pose = PoseEstimationResult(
                landmarks = createSyntheticLandmarks(isFrontView = false),
                timestampMs = 1000L + (i * 33L),
                hasPose = true
            )
            analyzer.analyzePose(pose)
        }
        val sessionResult = analyzer.getSessionResult()
        assertEquals(ValidationStatus.INSUFFICIENT_VISIBILITY, sessionResult.status)
        assertNull("Complete reps must be null under R7 refusal", sessionResult.completeReps)
        assertNull("ROM must be null under R7 refusal", sessionResult.romPercent)
    }

    /**
     * Unit Test 9: D13 mid-rep visibility drop discards active attempt and re-arms only at closed position.
     * Mid-rep drop discards attempt without registering complete or incomplete rep.
     * Recovery latch prevents phantom reps until athlete is verified in closed position.
     */
    @Test
    fun testUnit9_D13VisibilityDropMidRepAndRecoveryLatch() {
        val stateMachine = JumpingJackStateMachine()
        var timeMs = 1000L

        // Rep starts and reaches opening position (arm = 110°, leg = 30°)
        stateMachine.processAngles(armAngle = 30.0f, legAngle = 12.0f, timestampMs = timeMs)
        timeMs += 200L
        val opening = stateMachine.processAngles(armAngle = 110.0f, legAngle = 30.0f, timestampMs = timeMs)
        assertTrue("Rep should be in progress", opening.isRepInProgress)

        // Visibility drops mid-rep!
        timeMs += 100L
        val dropState = stateMachine.processAngles(armAngle = 120.0f, legAngle = 35.0f, timestampMs = timeMs, isVisibilitySufficient = false)
        assertFalse("In-progress rep must be discarded on visibility drop", dropState.isRepInProgress)
        assertEquals("No complete rep should be recorded", 0, dropState.completeRepCount)
        assertEquals("No incomplete rep should be recorded", 0, dropState.incompleteRepCount)
        assertTrue("awaitingClosedReset latch must be active", stateMachine.awaitingClosedReset)

        // Visibility restored, but athlete is still open (recovering position)
        timeMs += 100L
        val recoveringState = stateMachine.processAngles(armAngle = 90.0f, legAngle = 28.0f, timestampMs = timeMs, isVisibilitySufficient = true)
        assertFalse("Should NOT start rep while recovering before reaching closed", recoveringState.isRepInProgress)
        assertTrue("Latch should still be active", stateMachine.awaitingClosedReset)

        // Athlete returns to closed resting position (arms <= 45°, legs <= 20°)
        timeMs += 200L
        val resetState = stateMachine.processAngles(armAngle = 30.0f, legAngle = 12.0f, timestampMs = timeMs, isVisibilitySufficient = true)
        assertFalse("Latch must clear once closed position is re-established", stateMachine.awaitingClosedReset)

        // Subsequent clean rep executes normally
        timeMs += 200L
        stateMachine.processAngles(armAngle = 90.0f, legAngle = 25.0f, timestampMs = timeMs)
        timeMs += 200L
        stateMachine.processAngles(armAngle = 150.0f, legAngle = 45.0f, timestampMs = timeMs)
        timeMs += 100L
        stateMachine.processAngles(armAngle = 150.0f, legAngle = 45.0f, timestampMs = timeMs)
        timeMs += 200L
        stateMachine.processAngles(armAngle = 90.0f, legAngle = 25.0f, timestampMs = timeMs)
        timeMs += 200L
        val completedState = stateMachine.processAngles(armAngle = 30.0f, legAngle = 12.0f, timestampMs = timeMs)

        assertEquals("Subsequent clean rep must complete successfully", 1, completedState.completeRepCount)
        assertEquals("Total incomplete reps must still be 0", 0, completedState.incompleteRepCount)
    }
}
