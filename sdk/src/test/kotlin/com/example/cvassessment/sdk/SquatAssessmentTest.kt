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
     * Checks that the core engine files (excluding OutputGate.kt which was intentionally promoted in Phase F)
     * have ZERO modifications relative to git HEAD.
     */
    @Test
    fun testUnit10_VerifyNoSharedEngineCodeModified() {
        val sharedEngineFiles = listOf(
            "sdk/src/main/kotlin/com/example/cvassessment/sdk/visibility/VisibilityGate.kt",
            "sdk/src/main/kotlin/com/example/cvassessment/sdk/statemachine/ExerciseStateMachine.kt",
            "sdk/src/main/kotlin/com/example/cvassessment/sdk/metrics/MetricsEngine.kt",
            "sdk/src/main/kotlin/com/example/cvassessment/sdk/form/FormRuleEngine.kt"
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

    /**
     * Unit Test 11: Verify avgRepDurationSec and tutFactor are mathematically consistent
     * (tutFactor * tutBaseline ≈ avgRepDurationSec, within rounding).
     */
    @Test
    fun testUnit11_MathematicalConsistencyBetweenAvgDurationAndTutFactor() {
        val analyzer = ExerciseAnalyzer("squat", "Squat")

        // Initial setup at TOP lockout (> 160°)
        analyzer.analyzeSyntheticFrame(elbowAngle = 165.0f, hipLineAngle = 180.0f, timestampMs = 1000L)

        // Rep 1: 3400ms duration (from t=1200L descent start to t=4600L top completion)
        analyzer.analyzeSyntheticFrame(elbowAngle = 150.0f, hipLineAngle = 180.0f, timestampMs = 1200L) // Descent starts
        analyzer.analyzeSyntheticFrame(elbowAngle = 120.0f, hipLineAngle = 160.0f, timestampMs = 2000L)
        analyzer.analyzeSyntheticFrame(elbowAngle = 90.0f, hipLineAngle = 120.0f, timestampMs = 2800L) // Bottom
        analyzer.analyzeSyntheticFrame(elbowAngle = 130.0f, hipLineAngle = 160.0f, timestampMs = 3600L) // Ascent
        analyzer.analyzeSyntheticFrame(elbowAngle = 165.0f, hipLineAngle = 180.0f, timestampMs = 4600L) // Rep 1 complete: duration = 3400ms = 3.4s

        // Standing at top between reps from 4600L to 6000L (pause must NOT bleed into Rep 2)
        analyzer.analyzeSyntheticFrame(elbowAngle = 165.0f, hipLineAngle = 180.0f, timestampMs = 5000L)
        analyzer.analyzeSyntheticFrame(elbowAngle = 165.0f, hipLineAngle = 180.0f, timestampMs = 6000L)

        // Rep 2: 3800ms duration (from t=6200L descent start to t=10000L top completion)
        analyzer.analyzeSyntheticFrame(elbowAngle = 150.0f, hipLineAngle = 180.0f, timestampMs = 6200L) // Descent starts
        analyzer.analyzeSyntheticFrame(elbowAngle = 115.0f, hipLineAngle = 150.0f, timestampMs = 7200L)
        analyzer.analyzeSyntheticFrame(elbowAngle = 92.0f, hipLineAngle = 120.0f, timestampMs = 8200L) // Bottom
        analyzer.analyzeSyntheticFrame(elbowAngle = 135.0f, hipLineAngle = 160.0f, timestampMs = 9200L) // Ascent
        analyzer.analyzeSyntheticFrame(elbowAngle = 165.0f, hipLineAngle = 180.0f, timestampMs = 10000L) // Rep 2 complete: duration = 3800ms = 3.8s

        val sessionResult = analyzer.getSessionResult()
        assertEquals(ValidationStatus.VALID, sessionResult.status)
        assertEquals(2, sessionResult.completeReps)

        val avgDuration = sessionResult.avgRepDurationSec
        val tutFactor = sessionResult.tutFactor
        assertNotNull("avgRepDurationSec must not be null", avgDuration)
        assertNotNull("tutFactor must not be null", tutFactor)

        // Average duration = (3.4s + 3.8s) / 2 = 3.6s
        assertEquals("Average duration must be 3.6s", 3.6f, avgDuration!!, 0.05f)

        // tutFactor = 3.6s / 4.0s baseline = 0.90
        assertEquals("TuT Factor must be 0.90", 0.90f, tutFactor!!, 0.05f)

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
     * Unit Test 12: Synthetic rep with ONE sustained error condition across 10 frames ->
     * assert formErrors list may show it, but Form Factor calculation only counts it ONCE for that rep.
     */
    @Test
    fun testUnit12_SustainedErrorAcrossTenFramesCountsOnceInFormFactor() {
        val analyzer = ExerciseAnalyzer("squat", "Squat")

        // Top lockout
        analyzer.analyzeSyntheticFrame(elbowAngle = 165.0f, hipLineAngle = 180.0f, timestampMs = 1000L)

        // Rep 1: Starts descending, and experiences continuous excessive_lean (hipAngle = 60° < 65°) across 10 consecutive frames
        var time = 1200L
        for (i in 1..10) {
            val kneeAngle = 150.0f - (i * 6.0f) // Goes down to 90° (reaches bottom depth)
            analyzer.analyzeSyntheticFrame(
                elbowAngle = kneeAngle.coerceAtLeast(90.0f),
                hipLineAngle = 60.0f, // Continuous excessive forward lean across all 10 frames
                timestampMs = time
            )
            time += 200L
        }

        // Ascends back up with good posture
        analyzer.analyzeSyntheticFrame(elbowAngle = 130.0f, hipLineAngle = 160.0f, timestampMs = time)
        time += 300L
        analyzer.analyzeSyntheticFrame(elbowAngle = 165.0f, hipLineAngle = 180.0f, timestampMs = time) // Rep complete

        val sessionResult = analyzer.getSessionResult()
        assertEquals(1, sessionResult.completeReps)
        assertTrue("formErrors must record the detected error", sessionResult.formErrors.any { it.errorName == "excessive_lean" })

        // excessive_lean severity = 0.50
        // If counted 10 times: 1 - (10 * 0.50) = -4.0 -> clamped to 0.0
        // Because it counts ONCE per rep: 1 - (1 * 0.50 / 1) = 0.50!
        assertNotNull(sessionResult.formFactor)
        assertEquals("Form Factor must count sustained error exactly once (1.0 - 0.5 = 0.50)", 0.50f, sessionResult.formFactor!!, 0.01f)

        // Also explicitly verify that even if a list containing 10 detection instances is fed to computeFormFactor:
        val tenDuplicateErrors = (1..10).map {
            FormError(
                errorName = "excessive_lean",
                confidence = 0.9f,
                repIndex = 1,
                severity = 0.50f
            )
        }
        val computedFactor = analyzer.squatOutputGate.computeFormFactor(1, tenDuplicateErrors)
        assertEquals(
            "computeFormFactor must deduplicate multiple frame detections to count once per rep",
            0.50f,
            computedFactor!!,
            0.01f
        )
    }

    /**
     * Unit Test 13: Verify Form Factor for a rep with confidence=0.84, ROM=100%, and one moderate-severity error
     * is NOT zero — recalculate what it should reasonably be and assert against that.
     */
    @Test
    fun testUnit13_FormFactorForModerateSeverityErrorNotZero() {
        val analyzer = ExerciseAnalyzer("squat", "Squat")

        // 1 complete rep, 84% confidence, 100% ROM, and 1 moderate-severity error (excessive_lean, severity = 0.50)
        // Formula: formFactor = 1 - (weighted_sum_of_active_form_error_severities / max_possible_severity)
        // For 1 completed rep with severity 0.50:
        // Expected Form Factor = 1.0 - (0.50 / 1) = 0.50 (50%)
        val singleError = FormError(
            errorName = SquatFormRules.EXCESSIVE_LEAN.errorName,
            confidence = 0.84f,
            repIndex = 1,
            severity = SquatFormRules.EXCESSIVE_LEAN.severity // 0.50f
        )

        val sessionResult = analyzer.squatOutputGate.buildSessionResult(
            status = ValidationStatus.VALID,
            confidence = 0.84f,
            completeReps = 1,
            incompleteReps = 0,
            avgRepDurationSec = 3.5f,
            romPercent = 100.0f,
            tutFactor = 0.88f,
            formErrors = listOf(singleError)
        )

        assertNotNull("Form Factor must not be null", sessionResult.formFactor)
        assertTrue("Form Factor must NOT be zero for a single moderate-severity error", sessionResult.formFactor!! > 0.0f)
        assertEquals(
            "Form Factor should reasonably be 0.50 (1.0 - 0.50 / 1)",
            0.50f,
            sessionResult.formFactor!!,
            0.001f
        )

        // Also verify via computeFormFactor directly
        val repScore = analyzer.squatOutputGate.computeFormFactor(1, listOf(singleError))
        assertEquals(0.50f, repScore!!, 0.001f)
    }

    /**
     * Unit Test 14 (Regression for D12): Natural bottom-hold pause (0.5-1.0s) with landmark tracking jitter
     * must NOT trigger false incomplete rep detections or mid-rep duration resets.
     */
    @Test
    fun testUnit14_NaturalBottomPauseDoesNotTriggerIncompleteRep() {
        val stateMachine = SquatStateMachine()
        var timeMs = 1000L

        // Initial setup at TOP lockout (> 160°)
        stateMachine.processAngle(kneeAngle = 165.0f, hipAngle = 180.0f, timestampMs = timeMs)
        assertEquals(ExercisePhase.TOP, stateMachine.currentPhase)

        // Rep 1: Descent starts at t=1300ms
        timeMs = 1300L
        stateMachine.processAngle(150.0f, 180.0f, timeMs)
        assertEquals(ExercisePhase.DESCENDING, stateMachine.currentPhase)

        timeMs = 1600L
        stateMachine.processAngle(130.0f, 180.0f, timeMs)

        // Reaches parallel depth at t=1900ms (113.0° <= 115.0° target depth)
        timeMs = 1900L
        val bottomEnter = stateMachine.processAngle(113.0f, 180.0f, timeMs)
        assertEquals(ExercisePhase.BOTTOM, bottomEnter.phase)

        // Natural 800ms bottom-hold pause (t=1900ms to t=2700ms) with ±4° to 8° BlazePose tracking noise
        timeMs = 2100L
        stateMachine.processAngle(114.5f, 180.0f, timeMs)
        assertEquals("Should stay in BOTTOM during slight wobble", ExercisePhase.BOTTOM, stateMachine.currentPhase)

        timeMs = 2300L
        stateMachine.processAngle(119.5f, 180.0f, timeMs) // Jitter spike of +6.5°

        timeMs = 2500L
        stateMachine.processAngle(108.0f, 180.0f, timeMs) // Sinks deeper / jitter stabilizes

        timeMs = 2700L
        stateMachine.processAngle(112.0f, 180.0f, timeMs) // Pause concludes

        // User ascends out of bottom hole: 112° -> 132° -> 150° -> 165°
        timeMs = 3000L
        val ascState = stateMachine.processAngle(132.0f, 180.0f, timeMs)
        assertEquals(ExercisePhase.ASCENDING, ascState.phase)

        timeMs = 3300L
        stateMachine.processAngle(150.0f, 180.0f, timeMs)

        timeMs = 3600L
        val finishState = stateMachine.processAngle(165.0f, 180.0f, timeMs)

        // Verifications: Exactly 1 clean rep completed, 0 incomplete reps
        assertEquals(ExercisePhase.TOP, finishState.phase)
        assertEquals("Must complete exactly 1 rep", 1, finishState.completeRepCount)
        assertEquals("Must NOT trigger any incomplete reps during natural pause", 0, finishState.incompleteRepCount)
        assertNotNull("Must emit newlyCompletedRep", finishState.newlyCompletedRep)

        val rep = finishState.completeReps.first()
        assertEquals(1, rep.repIndex)
        // Duration should be measured from t=1300ms descent start to t=3600ms top lockout = 2300ms
        assertEquals(2300L, rep.durationMs)
        assertEquals(108.0f, rep.minElbowAngle, 0.01f)
        assertTrue(rep.isComplete)
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
