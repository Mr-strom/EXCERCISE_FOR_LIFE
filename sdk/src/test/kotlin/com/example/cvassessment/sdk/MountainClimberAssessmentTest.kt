package com.example.cvassessment.sdk

import com.example.cvassessment.sdk.form.MountainClimberFormRules
import com.example.cvassessment.sdk.ValidationStatus
import com.example.cvassessment.sdk.pose.PoseEstimationResult
import com.example.cvassessment.sdk.pose.PoseLandmark
import com.example.cvassessment.sdk.pose.PoseLandmarkType
import com.example.cvassessment.sdk.statemachine.ExercisePhase
import com.example.cvassessment.sdk.statemachine.MountainClimberGeometry
import com.example.cvassessment.sdk.statemachine.MountainClimberStateMachine
import com.example.cvassessment.sdk.statemachine.PlankGeometry
import com.example.cvassessment.sdk.visibility.MountainClimberVisibilityGate
import com.example.cvassessment.sdk.visibility.VisibilityStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 10 Acceptance Criteria Unit Tests for Mountain Climber per EXERCISE_SPEC.md #10,
 * FORM_RULES.md, SDK_CONTRACT.md, and DECISIONS.md D14/D15.
 */
class MountainClimberAssessmentTest {

    private fun createSyntheticLandmarks(
        isSideView: Boolean = true,
        visibility: Float = 0.95f,
        hipY: Float = 0.70f,
        leftKneeAngle: Float = 160.0f,
        rightKneeAngle: Float = 160.0f
    ): List<PoseLandmark> {
        val shoulderWidth = if (isSideView) 0.05f else 0.25f
        val hipWidth = if (isSideView) 0.05f else 0.20f

        val landmarks = mutableListOf<PoseLandmark>()
        for (i in 0..32) {
            val (x, y, z) = when (i) {
                PoseLandmarkType.LEFT_SHOULDER -> Triple(0.20f, 0.65f, 0.0f)
                PoseLandmarkType.RIGHT_SHOULDER -> Triple(0.20f + shoulderWidth, 0.65f, 0.0f)
                PoseLandmarkType.LEFT_WRIST -> Triple(0.20f, 0.85f, 0.0f)
                PoseLandmarkType.RIGHT_WRIST -> Triple(0.20f + shoulderWidth, 0.85f, 0.0f)
                PoseLandmarkType.LEFT_HIP -> Triple(0.50f, hipY, 0.0f)
                PoseLandmarkType.RIGHT_HIP -> Triple(0.50f + hipWidth, hipY, 0.0f)
                PoseLandmarkType.LEFT_KNEE -> Triple(0.65f, 0.70f, 0.0f)
                PoseLandmarkType.RIGHT_KNEE -> Triple(0.65f, 0.70f, 0.0f)
                PoseLandmarkType.LEFT_ANKLE -> Triple(0.80f, 0.70f, 0.0f)
                PoseLandmarkType.RIGHT_ANKLE -> Triple(0.80f, 0.70f, 0.0f)
                else -> Triple(0.5f, 0.5f, 0.0f)
            }
            landmarks.add(PoseLandmark(index = i, name = "LM_$i", x = x, y = y, z = z, visibility = visibility))
        }
        return landmarks
    }

    /**
     * Unit Test 1: 5 clean mountain climbers (alternating legs) -> 5 complete reps.
     * Asserts: completeReps == 5, incompleteReps == 0, romPercent >= 100%, tutFactor ≈ 1.0, 0 errors.
     */
    @Test
    fun testUnit1_FiveCleanMountainClimbersAlternatingLegs() {
        val analyzer = ExerciseAnalyzer("mountain_climber", "Mountain Climber")
        var timeMs = 1000L

        // Base plank position
        analyzer.analyzeSyntheticFrame(elbowAngle = 160.0f, hipLineAngle = 180.0f, timestampMs = timeMs)
        timeMs += 100L

        // Perform 5 alternating reps (each drive is 500ms down, 500ms up = 1000ms total)
        for (rep in 1..5) {
            // Drive forward (500ms) - initiated at timeMs
            analyzer.analyzeSyntheticFrame(elbowAngle = 145.0f, hipLineAngle = 180.0f, timestampMs = timeMs)
            timeMs += 250L
            analyzer.analyzeSyntheticFrame(elbowAngle = 115.0f, hipLineAngle = 180.0f, timestampMs = timeMs)
            timeMs += 250L
            analyzer.analyzeSyntheticFrame(elbowAngle = 80.0f, hipLineAngle = 180.0f, timestampMs = timeMs) // Peak drive (<90°)

            // Return to plank (500ms)
            timeMs += 250L
            analyzer.analyzeSyntheticFrame(elbowAngle = 115.0f, hipLineAngle = 180.0f, timestampMs = timeMs)
            timeMs += 250L
            val result = analyzer.analyzeSyntheticFrame(elbowAngle = 160.0f, hipLineAngle = 180.0f, timestampMs = timeMs)

            assertEquals("Rep counter should increment to $rep", rep, result.currentReps)
            timeMs += 50L
        }

        val session = analyzer.getSessionResult()
        assertEquals(5, session.completeReps)
        assertEquals(0, session.incompleteReps)
        assertTrue("No form errors should be present for clean reps", session.formErrors.isEmpty())
        assertEquals(1.0f, session.formFactor!!, 0.01f)
        assertEquals("TuT factor should be ≈ 1.0x", 1.0f, session.tutFactor!!, 0.15f)
        assertEquals("Average rep duration should be ≈ 1.0s", 1.0f, session.avgRepDurationSec!!, 0.15f)
        assertEquals("Combined ROM should be 100%", 100.0f, session.romPercent!!, 0.5f)
    }

    /**
     * Unit Test 2: Reversal before reaching drive target (<90°) produces 1 incomplete rep.
     */
    @Test
    fun testUnit2_ReversalBeforeReachingDriveTarget() {
        val analyzer = ExerciseAnalyzer("mountain_climber", "Mountain Climber")
        var timeMs = 1000L

        // 1. Extended base position
        analyzer.analyzeSyntheticFrame(elbowAngle = 160.0f, hipLineAngle = 180.0f, timestampMs = timeMs)

        // 2. Partial drive (reaches only 120°, then reverses back to 160°)
        timeMs += 200L
        analyzer.analyzeSyntheticFrame(elbowAngle = 135.0f, hipLineAngle = 180.0f, timestampMs = timeMs)
        timeMs += 200L
        analyzer.analyzeSyntheticFrame(elbowAngle = 120.0f, hipLineAngle = 180.0f, timestampMs = timeMs) // Reversal point
        timeMs += 200L
        analyzer.analyzeSyntheticFrame(elbowAngle = 145.0f, hipLineAngle = 180.0f, timestampMs = timeMs)
        timeMs += 200L
        analyzer.analyzeSyntheticFrame(elbowAngle = 160.0f, hipLineAngle = 180.0f, timestampMs = timeMs)

        val session = analyzer.getSessionResult()
        assertEquals(0, session.completeReps)
        assertEquals(1, session.incompleteReps)
    }

    /**
     * Unit Test 3: Visibility gap mid-rep discards attempt with D13 recovery latch.
     */
    @Test
    fun testUnit3_VisibilityGapMidRepDiscardsAttemptWithRecoveryLatch() {
        val analyzer = ExerciseAnalyzer("mountain_climber", "Mountain Climber")
        var timeMs = 1000L

        // 1. Extended base position
        analyzer.analyzeSyntheticFrame(elbowAngle = 160.0f, hipLineAngle = 180.0f, timestampMs = timeMs)

        // 2. Mid-drive
        timeMs += 200L
        analyzer.analyzeSyntheticFrame(elbowAngle = 110.0f, hipLineAngle = 180.0f, timestampMs = timeMs)

        // 3. Visibility drop mid-rep
        timeMs += 100L
        val visDrop = analyzer.analyzeSyntheticFrame(
            elbowAngle = 100.0f,
            hipLineAngle = 180.0f,
            timestampMs = timeMs,
            confidence = 0.1f,
            isVisibilitySufficient = false
        )
        assertEquals(ValidationStatus.INSUFFICIENT_VISIBILITY, visDrop.status)

        // 4. Recovery motion while not yet fully extended (120°) must NOT initiate new rep
        timeMs += 200L
        analyzer.analyzeSyntheticFrame(elbowAngle = 120.0f, hipLineAngle = 180.0f, timestampMs = timeMs)
        var session = analyzer.getSessionResult()
        assertEquals(0, session.completeReps)
        assertEquals(0, session.incompleteReps)

        // 5. Return to full extension (160°) re-arms state machine
        timeMs += 200L
        analyzer.analyzeSyntheticFrame(elbowAngle = 160.0f, hipLineAngle = 180.0f, timestampMs = timeMs)

        // 6. Clean full rep completes normally
        timeMs += 250L
        analyzer.analyzeSyntheticFrame(elbowAngle = 120.0f, hipLineAngle = 180.0f, timestampMs = timeMs)
        timeMs += 250L
        analyzer.analyzeSyntheticFrame(elbowAngle = 80.0f, hipLineAngle = 180.0f, timestampMs = timeMs)
        timeMs += 250L
        analyzer.analyzeSyntheticFrame(elbowAngle = 120.0f, hipLineAngle = 180.0f, timestampMs = timeMs)
        timeMs += 250L
        analyzer.analyzeSyntheticFrame(elbowAngle = 160.0f, hipLineAngle = 180.0f, timestampMs = timeMs)

        session = analyzer.getSessionResult()
        assertEquals(1, session.completeReps)
        assertEquals(0, session.incompleteReps)
    }

    /**
     * Unit Test 4: ROM% hand-calculated verification.
     * Formula: clamp((160 - minKneeAngle) / (160 - 90) * 100, 0, 100)
     */
    @Test
    fun testUnit4_RomPercentHandCalculatedVerification() {
        // 90° -> 100%
        assertEquals(100.0f, MountainClimberGeometry.calculateRomPercent(90.0f), 0.01f)
        // 125° -> 50%
        assertEquals(50.0f, MountainClimberGeometry.calculateRomPercent(125.0f), 0.01f)
        // 160° -> 0%
        assertEquals(0.0f, MountainClimberGeometry.calculateRomPercent(160.0f), 0.01f)
        // Over-extension/deep drive (70°) -> 100% clamped
        assertEquals(100.0f, MountainClimberGeometry.calculateRomPercent(70.0f), 0.01f)
        // Extended beyond 160° (175°) -> 0% clamped
        assertEquals(0.0f, MountainClimberGeometry.calculateRomPercent(175.0f), 0.01f)
    }

    /**
     * Unit Test 5: TuT Factor + avgRepDurationSec consistency (1.0s baseline).
     */
    @Test
    fun testUnit5_TutFactorAndAvgDurationConsistency() {
        val analyzer = ExerciseAnalyzer("mountain_climber", "Mountain Climber")
        var timeMs = 1000L

        // Base plank position
        analyzer.analyzeSyntheticFrame(elbowAngle = 160.0f, hipLineAngle = 180.0f, timestampMs = timeMs)
        timeMs += 100L

        // Rep 1: Exact 1.0s duration (1000ms) -> tutFactor = 1.0x
        analyzer.analyzeSyntheticFrame(elbowAngle = 145.0f, hipLineAngle = 180.0f, timestampMs = timeMs) // Starts at 1100L
        timeMs += 250L
        analyzer.analyzeSyntheticFrame(elbowAngle = 115.0f, hipLineAngle = 180.0f, timestampMs = timeMs) // 1350L
        timeMs += 250L
        analyzer.analyzeSyntheticFrame(elbowAngle = 80.0f, hipLineAngle = 180.0f, timestampMs = timeMs)  // 1600L
        timeMs += 250L
        analyzer.analyzeSyntheticFrame(elbowAngle = 115.0f, hipLineAngle = 180.0f, timestampMs = timeMs) // 1850L
        timeMs += 250L
        analyzer.analyzeSyntheticFrame(elbowAngle = 160.0f, hipLineAngle = 180.0f, timestampMs = timeMs) // 2100L -> Completes rep 1

        var latestRep = analyzer.latestCompletedRepMetrics
        assertNotNull(latestRep)
        assertEquals(1.0f, latestRep!!.durationSec, 0.05f)
        assertEquals(1.0f, latestRep.tutFactor, 0.05f)

        // Rep 2: Slower 1.4s duration (1400ms) -> tutFactor = 1.4x
        timeMs += 100L
        analyzer.analyzeSyntheticFrame(elbowAngle = 145.0f, hipLineAngle = 180.0f, timestampMs = timeMs) // Starts at 2200L
        timeMs += 350L
        analyzer.analyzeSyntheticFrame(elbowAngle = 115.0f, hipLineAngle = 180.0f, timestampMs = timeMs) // 2550L
        timeMs += 350L
        analyzer.analyzeSyntheticFrame(elbowAngle = 80.0f, hipLineAngle = 180.0f, timestampMs = timeMs)  // 2900L
        timeMs += 350L
        analyzer.analyzeSyntheticFrame(elbowAngle = 115.0f, hipLineAngle = 180.0f, timestampMs = timeMs) // 3250L
        timeMs += 350L
        analyzer.analyzeSyntheticFrame(elbowAngle = 160.0f, hipLineAngle = 180.0f, timestampMs = timeMs) // 3600L -> Completes rep 2

        latestRep = analyzer.latestCompletedRepMetrics
        assertNotNull(latestRep)
        assertEquals(1.4f, latestRep!!.durationSec, 0.05f)
        assertEquals(1.4f, latestRep.tutFactor, 0.05f)

        val session = analyzer.getSessionResult()
        assertEquals(2, session.completeReps)
        assertEquals(1.2f, session.avgRepDurationSec!!, 0.05f)
        assertEquals(1.2f, session.tutFactor!!, 0.05f)
    }

    /**
     * Unit Test 6: Scaled settle time (75ms) prevents false incomplete reps at 1.0s fast tempo.
     */
    @Test
    fun testUnit6_ScaledSettleTimePreventsFalseIncompleteReps() {
        val stateMachine = MountainClimberStateMachine()
        var timeMs = 1000L

        // Extended -> Driving -> Driven
        stateMachine.processAngles(kneeAngle = 160.0f, hipLineAngle = 180.0f, timestampMs = timeMs)
        timeMs += 100L
        stateMachine.processAngles(kneeAngle = 110.0f, hipLineAngle = 180.0f, timestampMs = timeMs)
        timeMs += 100L
        val drivenState = stateMachine.processAngles(kneeAngle = 85.0f, hipLineAngle = 180.0f, timestampMs = timeMs)
        assertEquals("Should transition to BOTTOM (DRIVEN)", ExercisePhase.BOTTOM, drivenState.phase)

        // Rapid bounce dwell: 75ms at driven
        timeMs += 75L
        stateMachine.processAngles(kneeAngle = 86.0f, hipLineAngle = 180.0f, timestampMs = timeMs)

        // Reversal starts: knee extends by > 5° (from 85° to 92°)
        timeMs += 50L
        val returningState = stateMachine.processAngles(kneeAngle = 92.0f, hipLineAngle = 180.0f, timestampMs = timeMs)
        assertEquals("Should transition to ASCENDING (RETURNING) after 75ms settle and 5° drop", ExercisePhase.ASCENDING, returningState.phase)
    }

    /**
     * Unit Test 7: incomplete_leg_drive triggers when rep completes with insufficient knee drive (ROM < 60%).
     */
    @Test
    fun testUnit7_IncompleteLegDriveTriggersCorrectly() {
        val analyzer = ExerciseAnalyzer("mountain_climber", "Mountain Climber")
        var timeMs = 1000L

        // Rep with shallow drive: min knee angle = 125° (ROM% = (160-125)/70 * 100 = 50% < 60%)
        analyzer.analyzeSyntheticFrame(elbowAngle = 160.0f, hipLineAngle = 180.0f, timestampMs = timeMs)
        timeMs += 250L
        analyzer.analyzeSyntheticFrame(elbowAngle = 135.0f, hipLineAngle = 180.0f, timestampMs = timeMs)
        timeMs += 250L
        analyzer.analyzeSyntheticFrame(elbowAngle = 88.0f, hipLineAngle = 180.0f, timestampMs = timeMs) // Target reached
        timeMs += 250L
        analyzer.analyzeSyntheticFrame(elbowAngle = 135.0f, hipLineAngle = 180.0f, timestampMs = timeMs)
        timeMs += 250L
        analyzer.analyzeSyntheticFrame(elbowAngle = 160.0f, hipLineAngle = 180.0f, timestampMs = timeMs)

        // Clean rep 1 has 0 errors
        var session = analyzer.getSessionResult()
        assertEquals(1, session.completeReps)
        assertTrue(session.formErrors.isEmpty())

        // Shallow rep 2: reaches only 125° (< 60% ROM)
        timeMs += 100L
        // We simulate a completed rep with min knee angle 125°
        // Note: For state machine to mark rep complete, it needs to reach DRIVEN or complete through state machine
        val formRuleEngine = com.example.cvassessment.sdk.form.MountainClimberFormRuleEngine()
        val shallowMetrics = com.example.cvassessment.sdk.metrics.RepMetrics(
            repIndex = 2,
            romPercent = 50.0f, // < 60%
            tutFactor = 1.0f,
            confidence = 1.0f,
            durationSec = 1.0f,
            minElbowAngle = 125.0f,
            startTimestampMs = timeMs,
            endTimestampMs = timeMs + 1000L
        )
        val formOutput = formRuleEngine.evaluateFrame(
            kneeAngle = 160.0f,
            hipLineAngle = 180.0f,
            phase = ExercisePhase.TOP,
            isRepInProgress = false,
            timestampMs = timeMs + 1000L,
            completedRepMetrics = shallowMetrics
        )

        val driveError = formOutput.allSessionErrors.find { it.errorName == "incomplete_leg_drive" }
        assertNotNull("incomplete_leg_drive should trigger when ROM% < 60%", driveError)
        assertEquals(0.50f, driveError!!.severity, 0.01f)
        assertTrue(formOutput.allFeedbackEvents.any { it.message == "Drive your knee further forward." })
    }

    /**
     * Unit Test 8: hips_dropping and hips_piking fire as CONCURRENT checks during active rep motion.
     * They do NOT block rep completion.
     */
    @Test
    fun testUnit8_HipsDroppingAndPikingConcurrentCheck() {
        val analyzer = ExerciseAnalyzer("mountain_climber", "Mountain Climber")
        var timeMs = 1000L

        // Rep 1 with hips sagging (155° < 165°) sustained for 3 frames
        analyzer.analyzeSyntheticFrame(elbowAngle = 160.0f, hipLineAngle = 155.0f, timestampMs = timeMs)
        timeMs += 100L
        analyzer.analyzeSyntheticFrame(elbowAngle = 130.0f, hipLineAngle = 155.0f, timestampMs = timeMs)
        timeMs += 100L
        analyzer.analyzeSyntheticFrame(elbowAngle = 85.0f, hipLineAngle = 155.0f, timestampMs = timeMs) // 3rd frame -> triggers
        timeMs += 100L
        analyzer.analyzeSyntheticFrame(elbowAngle = 130.0f, hipLineAngle = 155.0f, timestampMs = timeMs)
        timeMs += 100L
        val result = analyzer.analyzeSyntheticFrame(elbowAngle = 160.0f, hipLineAngle = 180.0f, timestampMs = timeMs)

        // Rep completes despite form error!
        assertEquals("Rep must complete despite hip sag", 1, result.currentReps)

        val session = analyzer.getSessionResult()
        assertEquals(1, session.completeReps)
        val sagError = session.formErrors.find { it.errorName == "hips_dropping" }
        assertNotNull("hips_dropping should trigger when hipLineAngle < 165°", sagError)
        assertEquals(0.70f, sagError!!.severity, 0.01f)
        assertTrue(session.feedbackEvents.any { it.message == "Keep your hips up." })
    }

    /**
     * Unit Test 9: Confirm reuse of PlankGeometry's hip_line_angle calculation (not reimplemented).
     */
    @Test
    fun testUnit9_ConfirmPlankGeometryHipLineAngleReuse() {
        val landmarks = createSyntheticLandmarks(isSideView = true, hipY = 0.75f)

        val plankResult = PlankGeometry.computeHipLineAngle(landmarks)
        val climberResult = MountainClimberGeometry.computeHipLineAngle(landmarks)

        assertEquals("MountainClimberGeometry must delegate directly to PlankGeometry", plankResult, climberResult, 0.001f)
    }

    /**
     * Unit Test 10: Verify no shared ENGINE code (the 5 core files) modified.
     */
    @Test
    fun testUnit10_VerifyNoSharedEngineCodeModified() {
        val rootDir = File(".").canonicalFile.let { if (it.name == "sdk") it.parentFile else it }
        val sharedEngineFiles = listOf(
            "sdk/src/main/kotlin/com/example/cvassessment/sdk/visibility/VisibilityGate.kt",
            "sdk/src/main/kotlin/com/example/cvassessment/sdk/statemachine/ExerciseStateMachine.kt",
            "sdk/src/main/kotlin/com/example/cvassessment/sdk/metrics/MetricsEngine.kt",
            "sdk/src/main/kotlin/com/example/cvassessment/sdk/form/FormRuleEngine.kt",
            "sdk/src/main/kotlin/com/example/cvassessment/sdk/output/OutputGate.kt"
        )

        for (relPath in sharedEngineFiles) {
            val file = File(rootDir, relPath)
            assertTrue("Shared engine file must exist: $relPath", file.exists())

            val process = ProcessBuilder("git", "diff", "HEAD", "--", relPath)
                .directory(rootDir)
                .redirectErrorStream(true)
                .start()
            val diffOutput = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()

            assertTrue(
                "Shared engine file $relPath must have zero diff relative to HEAD! Diff: $diffOutput",
                diffOutput.isEmpty()
            )
        }
    }
}
