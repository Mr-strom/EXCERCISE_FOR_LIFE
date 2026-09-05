package com.example.cvassessment.sdk

import com.example.cvassessment.sdk.form.SquatFormRuleEngine
import com.example.cvassessment.sdk.form.SquatFormRules
import com.example.cvassessment.sdk.metrics.MetricsEngine
import com.example.cvassessment.sdk.metrics.RepMetrics
import com.example.cvassessment.sdk.pose.PoseLandmark
import com.example.cvassessment.sdk.pose.PoseLandmarkType
import com.example.cvassessment.sdk.spec.ExerciseConfig
import com.example.cvassessment.sdk.statemachine.ExercisePhase
import com.example.cvassessment.sdk.statemachine.RepBoundary
import com.example.cvassessment.sdk.statemachine.SquatGeometry
import com.example.cvassessment.sdk.statemachine.SquatStateMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 10 Required Acceptance Criteria Unit Tests for Squat Implementation.
 */
class SquatAssessmentTest {

    /**
     * Unit Test 1: Synthetic sequence of 5 clean squats -> 5 complete reps detected.
     */
    @Test
    fun testUnit1_FiveCleanSquatsDetected() {
        val stateMachine = SquatStateMachine()
        var timeMs = 1000L

        // Initial setup at TOP lockout (> 160°)
        stateMachine.processAngle(kneeAngle = 165.0f, hipAngle = 180.0f, timestampMs = timeMs)
        assertEquals(ExercisePhase.TOP, stateMachine.currentPhase)

        // Execute 5 clean reps
        for (rep in 1..5) {
            timeMs += 300L
            // Descent: 150° -> 130° -> 105° -> 90° (bottom target is <= 110°)
            val desc1 = stateMachine.processAngle(150.0f, 180.0f, timeMs)
            assertEquals("Should be descending in rep $rep", ExercisePhase.DESCENDING, desc1.phase)
            timeMs += 400L

            stateMachine.processAngle(125.0f, 180.0f, timeMs)
            timeMs += 400L

            val bottomState = stateMachine.processAngle(90.0f, 180.0f, timeMs)
            assertEquals("Should reach bottom in rep $rep", ExercisePhase.BOTTOM, bottomState.phase)
            timeMs += 400L

            // Ascent: 90° -> 120° -> 145° -> 165° (top lockout is >= 155°)
            val ascState = stateMachine.processAngle(120.0f, 180.0f, timeMs)
            assertEquals("Should be ascending in rep $rep", ExercisePhase.ASCENDING, ascState.phase)
            timeMs += 400L

            stateMachine.processAngle(145.0f, 180.0f, timeMs)
            timeMs += 400L

            val finishState = stateMachine.processAngle(165.0f, 180.0f, timeMs)
            assertEquals("Should return to top in rep $rep", ExercisePhase.TOP, finishState.phase)
            assertNotNull("Newly completed rep should be emitted in rep $rep", finishState.newlyCompletedRep)
            assertEquals(rep, finishState.completeRepCount)
            assertEquals(0, finishState.incompleteRepCount)

            timeMs += 500L
            stateMachine.processAngle(165.0f, 180.0f, timeMs)
        }

        assertEquals(5, stateMachine.completeReps.size)
        assertEquals(0, stateMachine.incompleteReps.size)

        for (i in 0 until 5) {
            val rep = stateMachine.completeReps[i]
            assertEquals(i + 1, rep.repIndex)
            assertTrue("Min knee angle should be <= 100°", rep.minElbowAngle <= 100.0f)
            assertTrue(rep.isComplete)
        }
    }

    /**
     * Unit Test 2: Sequence that reverses before reaching bottom tolerance ->
     * 1 incomplete rep, 0 complete.
     */
    @Test
    fun testUnit2_ReversalBeforeBottomYieldsIncompleteRep() {
        val stateMachine = SquatStateMachine()
        var timeMs = 1000L

        // Start at top (165°)
        stateMachine.processAngle(kneeAngle = 165.0f, hipAngle = 180.0f, timestampMs = timeMs)
        assertEquals(ExercisePhase.TOP, stateMachine.currentPhase)
        timeMs += 300L

        // Descends partially, but only reaches 125° (target bottom tolerance is <= 110°)
        stateMachine.processAngle(150.0f, 180.0f, timeMs)
        assertEquals(ExercisePhase.DESCENDING, stateMachine.currentPhase)
        timeMs += 300L

        stateMachine.processAngle(125.0f, 180.0f, timeMs)
        timeMs += 300L

        // Reverses upward (> 125° + 8° hysteresis = 133°)
        val ascState = stateMachine.processAngle(138.0f, 180.0f, timeMs)
        assertEquals(ExercisePhase.ASCENDING, ascState.phase)
        timeMs += 400L

        stateMachine.processAngle(150.0f, 180.0f, timeMs)
        timeMs += 400L

        // Returns to top lockout (165°)
        val finalState = stateMachine.processAngle(165.0f, 180.0f, timeMs)

        assertEquals("Complete reps must be 0", 0, finalState.completeRepCount)
        assertEquals("Incomplete reps must be 1", 1, finalState.incompleteRepCount)
        assertEquals(ExercisePhase.TOP, finalState.phase)

        val incomplete = finalState.incompleteReps.first()
        assertEquals(1, incomplete.attemptIndex)
        assertEquals(125.0f, incomplete.minElbowAngleAchieved, 0.01f)
        assertTrue(incomplete.reason.contains("Reversed before reaching bottom target"))
    }

    /**
     * Unit Test 3: Visibility gap mid-rep -> rep discarded, not falsely counted.
     */
    @Test
    fun testUnit3_VisibilityGapMidRepDiscardsAttempt() {
        val stateMachine = SquatStateMachine()
        var timeMs = 1000L

        // Rep 1: Clean complete squat
        stateMachine.processAngle(165.0f, 180.0f, timeMs, isVisibilitySufficient = true)
        timeMs += 300L
        stateMachine.processAngle(140.0f, 180.0f, timeMs, isVisibilitySufficient = true)
        timeMs += 300L
        stateMachine.processAngle(90.0f, 180.0f, timeMs, isVisibilitySufficient = true)
        timeMs += 300L
        stateMachine.processAngle(130.0f, 180.0f, timeMs, isVisibilitySufficient = true)
        timeMs += 300L
        val rep1State = stateMachine.processAngle(165.0f, 180.0f, timeMs, isVisibilitySufficient = true)
        assertEquals(1, rep1State.completeRepCount)
        assertEquals(0, rep1State.incompleteRepCount)
        timeMs += 500L

        // Rep 2: Descent starts, but mid-rep visibility gap occurs!
        stateMachine.processAngle(150.0f, 180.0f, timeMs, isVisibilitySufficient = true)
        timeMs += 300L
        stateMachine.processAngle(120.0f, 180.0f, timeMs, isVisibilitySufficient = true)
        timeMs += 300L

        // Visibility gap triggers mid-rep!
        val gapState = stateMachine.processAngle(95.0f, 180.0f, timeMs, isVisibilitySufficient = false)
        assertFalse("Rep should no longer be in progress after visibility drop", gapState.isRepInProgress)
        assertNull(gapState.newlyCompletedRep)
        assertNull(gapState.newlyDetectedIncompleteRep)
        timeMs += 400L

        // Visibility recovers back at top
        val recoverState = stateMachine.processAngle(165.0f, 180.0f, timeMs, isVisibilitySufficient = true)
        timeMs += 500L

        // Assert discarded: still exactly 1 complete rep and 0 incomplete reps
        assertEquals("Discarded attempt must not count as complete", 1, recoverState.completeRepCount)
        assertEquals("Discarded attempt must not count as incomplete", 0, recoverState.incompleteRepCount)

        // Rep 3: User performs clean rep after recovery
        stateMachine.processAngle(140.0f, 180.0f, timeMs, isVisibilitySufficient = true)
        timeMs += 300L
        stateMachine.processAngle(90.0f, 180.0f, timeMs, isVisibilitySufficient = true)
        timeMs += 300L
        stateMachine.processAngle(130.0f, 180.0f, timeMs, isVisibilitySufficient = true)
        timeMs += 300L
        val rep3State = stateMachine.processAngle(165.0f, 180.0f, timeMs, isVisibilitySufficient = true)

        assertEquals(2, rep3State.completeRepCount)
        assertEquals(0, rep3State.incompleteRepCount)
    }

    /**
     * Unit Test 4: ROM% hand-calculated verification for 3 known knee angles.
     *
     * Squat ROM definition per EXERCISE_SPEC.md:
     * startingAngle = 160°, target = 100°, denominator = (100 - 160) = -60
     * clamp((actual - starting) / (target - starting) * 100, 0, 100)
     *
     * 1. 100°: (100 - 160) / -60 * 100 = -60 / -60 * 100 = 100.0%
     * 2. 130°: (130 - 160) / -60 * 100 = -30 / -60 * 100 = 50.0%
     * 3. 160°: (160 - 160) / -60 * 100 = 0 / -60 * 100 = 0.0%
     * (Bonus: 85° deep squat clamps to 100.0%)
     */
    @Test
    fun testUnit4_RomHandCalculatedVerification() {
        val metricsEngine = MetricsEngine(ExerciseConfig.SQUAT)

        val rom100 = metricsEngine.calculateRomPercent(100.0f)
        assertEquals("ROM for 100° must be exactly 100%", 100.0f, rom100, 0.001f)

        val rom130 = metricsEngine.calculateRomPercent(130.0f)
        assertEquals("ROM for 130° must be exactly 50%", 50.0f, rom130, 0.001f)

        val rom160 = metricsEngine.calculateRomPercent(160.0f)
        assertEquals("ROM for 160° lockout must be exactly 0%", 0.0f, rom160, 0.001f)

        val rom85 = metricsEngine.calculateRomPercent(85.0f)
        assertEquals("ROM for 85° must clamp to 100%", 100.0f, rom85, 0.001f)
    }

    /**
     * Unit Test 5: TuT Factor hand-calculated verification for 3 known durations.
     *
     * Squat tutBaseline = 4.0s (2.0s down + 2.0s up).
     * tutFactor = actual_duration / tutBaseline
     *
     * 1. 2.0s: 2.0 / 4.0 = 0.50 (fast)
     * 2. 4.0s: 4.0 / 4.0 = 1.00 (exact baseline)
     * 3. 6.0s: 6.0 / 4.0 = 1.50 (slow / controlled)
     */
    @Test
    fun testUnit5_TutFactorHandCalculatedVerification() {
        val metricsEngine = MetricsEngine(ExerciseConfig.SQUAT)

        val tut2 = metricsEngine.calculateTutFactor(2.0f)
        assertEquals(0.50f, tut2, 0.001f)

        val tut4 = metricsEngine.calculateTutFactor(4.0f)
        assertEquals(1.00f, tut4, 0.001f)

        val tut6 = metricsEngine.calculateTutFactor(6.0f)
        assertEquals(1.50f, tut6, 0.001f)
    }

    /**
     * Unit Test 6: Knee valgus detection triggers correctly with synthetic
     * medial knee-x displacement, front-view scenario.
     */
    @Test
    fun testUnit6_KneeValgusDetectionTriggersInFrontView() {
        val engine = SquatFormRuleEngine()

        // Front-view landmarks:
        // Left hip: x=0.60, Right hip: x=0.40 -> hip width = 0.20, midline = 0.50
        // Left ankle: x=0.60, y=0.90
        // Left knee: moves inward to x=0.53, y=0.70
        // Medial deviation: |0.60 - 0.50| - |0.53 - 0.50| = 0.10 - 0.03 = 0.07
        // Ratio: 0.07 / 0.20 = 35% > 12% threshold
        val frontViewLandmarks = createSyntheticSquatLandmarks(
            leftHipX = 0.60f, rightHipX = 0.40f,
            leftKneeX = 0.53f, rightKneeX = 0.40f, // Left knee caved inward
            leftAnkleX = 0.60f, rightAnkleX = 0.40f
        )

        assertFalse("Should be recognized as front view", SquatGeometry.isSideView(frontViewLandmarks))

        // Feed sustained frames (>= 3 consecutive frames with confidence >= 0.6)
        var feedbackTriggered = false
        var time = 1000L

        for (i in 1..3) {
            val out = engine.evaluateFrame(
                kneeAngle = 110.0f,
                hipAngle = 120.0f,
                phase = ExercisePhase.DESCENDING,
                isRepInProgress = true,
                currentRepIndex = 1,
                timestampMs = time,
                confidence = 0.90f,
                landmarks = frontViewLandmarks
            )
            time += 200L

            assertEquals(1, out.activeErrors.size)
            assertEquals("knee_valgus", out.activeErrors.first().errorName)
            assertEquals(0.75f, out.activeErrors.first().severity, 0.01f)

            if (out.newFeedbackEvents.isNotEmpty()) {
                feedbackTriggered = true
                val event = out.newFeedbackEvents.first()
                assertEquals("knee_valgus", event.relatedError)
                assertEquals("Push your knees out.", event.message)
            }
        }

        assertTrue("Feedback must trigger once persistence (3 frames) is reached", feedbackTriggered)
        assertEquals(1, engine.allFeedbackEvents.size)
    }

    /**
     * Unit Test 7: Knee valgus detection correctly SKIPS when side-view is detected
     * (verify no false positive).
     */
    @Test
    fun testUnit7_KneeValgusSkipsInSideView() {
        val engine = SquatFormRuleEngine()

        // Profile / side-view landmarks:
        // Hips are close in X (x=0.50 vs x=0.53 -> hipWidth = 0.03 < 0.08)
        val sideViewLandmarks = createSyntheticSquatLandmarks(
            leftHipX = 0.50f, rightHipX = 0.53f,
            leftKneeX = 0.45f, rightKneeX = 0.46f,
            leftAnkleX = 0.50f, rightAnkleX = 0.52f
        )

        assertTrue("Should be recognized as side view", SquatGeometry.isSideView(sideViewLandmarks))

        // Feed 5 frames during descending phase with high confidence
        for (i in 1..5) {
            val out = engine.evaluateFrame(
                kneeAngle = 110.0f,
                hipAngle = 120.0f,
                phase = ExercisePhase.DESCENDING,
                isRepInProgress = true,
                currentRepIndex = 1,
                timestampMs = i * 200L,
                confidence = 0.95f,
                landmarks = sideViewLandmarks
            )

            // Assert NO knee_valgus error is produced
            assertFalse(
                "knee_valgus must NOT be flagged in side view",
                out.activeErrors.any { it.errorName == "knee_valgus" }
            )
            assertTrue("No feedback should be emitted in side view", out.newFeedbackEvents.isEmpty())
        }

        // Verify skip reason was recorded and session errors remain empty
        assertNotNull(engine.lastSkipReason)
        assertTrue(engine.lastSkipReason!!.contains("Side-view detected"))
        assertTrue("Total session errors must be empty", engine.allSessionErrors.isEmpty())
    }

    /**
     * Unit Test 8: Insufficient_depth triggers when romPercent < 60% at rep end.
     */
    @Test
    fun testUnit8_InsufficientDepthTriggersWhenRomBelowSixtyPercent() {
        val engine = SquatFormRuleEngine()

        // Shallow rep completed with romPercent = 45% (< 60%)
        val shallowRep = RepMetrics(
            repIndex = 1,
            romPercent = 45.0f,
            tutFactor = 1.0f,
            confidence = 0.90f,
            durationSec = 3.5f,
            minElbowAngle = 133.0f,
            startTimestampMs = 1000L,
            endTimestampMs = 4500L
        )

        val out = engine.evaluateFrame(
            kneeAngle = 165.0f,
            hipAngle = 180.0f,
            phase = ExercisePhase.TOP,
            isRepInProgress = false,
            currentRepIndex = 1,
            timestampMs = 4500L,
            confidence = 0.90f,
            completedRepMetrics = shallowRep
        )

        assertTrue("insufficient_depth must be flagged", out.activeErrors.any { it.errorName == "insufficient_depth" })
        assertEquals(1, out.newFeedbackEvents.size)

        val event = out.newFeedbackEvents.first()
        assertEquals("insufficient_depth", event.relatedError)
        assertEquals("Go lower.", event.message)
    }

    /**
     * Unit Test 9: Full end-to-end synthetic session — verify SessionResult
     * assembles correctly for Squat (mirroring Push-Up's Unit Test 5 in Prompt 1.6).
     */
    @Test
    fun testUnit9_FullEndToEndSyntheticSessionForSquat() {
        val analyzer = ExerciseAnalyzer("squat", "Squat")

        // 10-frame synthetic session:
        // Frame 1: Top position
        analyzer.analyzeSyntheticFrame(elbowAngle = 165f, hipLineAngle = 180f, timestampMs = 1000L)

        // Rep 1: Clean deep squat (reaches 90° <= 110°)
        analyzer.analyzeSyntheticFrame(elbowAngle = 130f, hipLineAngle = 120f, timestampMs = 2000L)
        analyzer.analyzeSyntheticFrame(elbowAngle = 90f, hipLineAngle = 100f, timestampMs = 3000L)
        analyzer.analyzeSyntheticFrame(elbowAngle = 165f, hipLineAngle = 180f, timestampMs = 4000L) // Rep 1 complete!

        // Rep 2: Clean squat
        analyzer.analyzeSyntheticFrame(elbowAngle = 125f, hipLineAngle = 120f, timestampMs = 5000L)
        analyzer.analyzeSyntheticFrame(elbowAngle = 95f, hipLineAngle = 100f, timestampMs = 6000L)
        analyzer.analyzeSyntheticFrame(elbowAngle = 165f, hipLineAngle = 180f, timestampMs = 7000L) // Rep 2 complete!

        // Attempt 3: Incomplete rep (reverses at 130° without reaching bottom tolerance)
        analyzer.analyzeSyntheticFrame(elbowAngle = 130f, hipLineAngle = 140f, timestampMs = 8000L)
        analyzer.analyzeSyntheticFrame(elbowAngle = 145f, hipLineAngle = 160f, timestampMs = 8500L)
        analyzer.analyzeSyntheticFrame(elbowAngle = 165f, hipLineAngle = 180f, timestampMs = 9000L) // Incomplete rep!

        val sessionResult = analyzer.getSessionResult()
        assertNotNull(sessionResult)

        assertEquals(ValidationStatus.VALID, sessionResult.status)
        assertEquals(2, sessionResult.completeReps)
        assertEquals(1, sessionResult.incompleteReps)
        assertNull("holdDurationSec must be null for dynamic squat", sessionResult.holdDurationSec)

        assertNotNull("romPercent must be computed", sessionResult.romPercent)
        assertNotNull("tutFactor must be computed", sessionResult.tutFactor)
        assertNotNull("avgRepDurationSec must be computed", sessionResult.avgRepDurationSec)
        assertNotNull("formFactor must be computed", sessionResult.formFactor)
    }

    /**
     * Unit Test 10: Verify NO shared engine code was modified.
     * Checks that the 5 base engine files have ZERO modifications relative to git HEAD.
     */
    @Test
    fun testUnit10_VerifyNoSharedEngineCodeModified() {
        val sharedEngineFiles = listOf(
            "sdk/src/main/kotlin/com/example/cvassessment/sdk/visibility/VisibilityGate.kt",
            "sdk/src/main/kotlin/com/example/cvassessment/sdk/statemachine/ExerciseStateMachine.kt",
            "sdk/src/main/kotlin/com/example/cvassessment/sdk/metrics/MetricsEngine.kt",
            "sdk/src/main/kotlin/com/example/cvassessment/sdk/form/FormRuleEngine.kt",
            "sdk/src/main/kotlin/com/example/cvassessment/sdk/output/OutputGate.kt"
        )

        for (filePath in sharedEngineFiles) {
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

    private fun createSyntheticSquatLandmarks(
        leftHipX: Float, rightHipX: Float,
        leftKneeX: Float, rightKneeX: Float,
        leftAnkleX: Float, rightAnkleX: Float
    ): List<PoseLandmark> {
        val landmarks = ArrayList<PoseLandmark>(33)
        for (i in 0..32) {
            when (i) {
                PoseLandmarkType.LEFT_SHOULDER -> landmarks.add(PoseLandmark(i, "LEFT_SHOULDER", leftHipX, 0.2f, 0f, 1.0f))
                PoseLandmarkType.RIGHT_SHOULDER -> landmarks.add(PoseLandmark(i, "RIGHT_SHOULDER", rightHipX, 0.2f, 0f, 1.0f))
                PoseLandmarkType.LEFT_HIP -> landmarks.add(PoseLandmark(i, "LEFT_HIP", leftHipX, 0.5f, 0f, 1.0f))
                PoseLandmarkType.RIGHT_HIP -> landmarks.add(PoseLandmark(i, "RIGHT_HIP", rightHipX, 0.5f, 0f, 1.0f))
                PoseLandmarkType.LEFT_KNEE -> landmarks.add(PoseLandmark(i, "LEFT_KNEE", leftKneeX, 0.7f, 0f, 1.0f))
                PoseLandmarkType.RIGHT_KNEE -> landmarks.add(PoseLandmark(i, "RIGHT_KNEE", rightKneeX, 0.7f, 0f, 1.0f))
                PoseLandmarkType.LEFT_ANKLE -> landmarks.add(PoseLandmark(i, "LEFT_ANKLE", leftAnkleX, 0.9f, 0f, 1.0f))
                PoseLandmarkType.RIGHT_ANKLE -> landmarks.add(PoseLandmark(i, "RIGHT_ANKLE", rightAnkleX, 0.9f, 0f, 1.0f))
                else -> landmarks.add(PoseLandmark(i, "LANDMARK_$i", 0.5f, 0.5f, 0f, 0.5f))
            }
        }
        return landmarks
    }
}
