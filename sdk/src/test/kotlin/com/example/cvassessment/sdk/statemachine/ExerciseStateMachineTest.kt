package com.example.cvassessment.sdk.statemachine

import com.example.cvassessment.sdk.pose.PoseLandmark
import com.example.cvassessment.sdk.pose.PoseLandmarkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Acceptance criteria tests for Module 4 (Exercise State Machine) for Push-Up.
 */
class ExerciseStateMachineTest {

    private lateinit var stateMachine: ExerciseStateMachine

    @Before
    fun setUp() {
        stateMachine = ExerciseStateMachine()
    }

    /**
     * Unit Test 1: Feed synthetic angle sequence representing 5 clean reps ->
     * assert 5 complete reps detected.
     */
    @Test
    fun testFiveCleanRepsDetected() {
        // Initial setup at TOP lockout
        stateMachine.processAngle(elbowAngle = 165.0f, hipLineAngle = 180.0f, timestampMs = 0L)
        assertEquals(ExercisePhase.TOP, stateMachine.currentPhase)

        var currentTimeMs = 500L

        // Execute 5 clean reps
        for (rep in 1..5) {
            // Descent: 165° -> 145° -> 120° -> 95° -> 80° (bottom reached <= 100°)
            stateMachine.processAngle(150.0f, 180.0f, currentTimeMs)
            assertEquals("Should be descending in rep $rep", ExercisePhase.DESCENDING, stateMachine.currentPhase)
            currentTimeMs += 300L

            stateMachine.processAngle(125.0f, 180.0f, currentTimeMs)
            currentTimeMs += 300L

            stateMachine.processAngle(95.0f, 180.0f, currentTimeMs)
            currentTimeMs += 300L

            val bottomState = stateMachine.processAngle(80.0f, 180.0f, currentTimeMs)
            assertEquals("Should reach bottom in rep $rep", ExercisePhase.BOTTOM, bottomState.phase)
            currentTimeMs += 400L

            // Ascent: 80° -> 110° -> 135° -> 165° (lockout reached >= 155°)
            val ascendingState = stateMachine.processAngle(110.0f, 180.0f, currentTimeMs)
            assertEquals("Should be ascending in rep $rep", ExercisePhase.ASCENDING, ascendingState.phase)
            currentTimeMs += 400L

            stateMachine.processAngle(140.0f, 180.0f, currentTimeMs)
            currentTimeMs += 400L

            val finishState = stateMachine.processAngle(165.0f, 180.0f, currentTimeMs)
            assertEquals("Should return to top in rep $rep", ExercisePhase.TOP, finishState.phase)
            assertNotNull("Newly completed rep should be emitted in rep $rep", finishState.newlyCompletedRep)
            assertEquals(rep, finishState.completeRepCount)
            assertEquals(0, finishState.incompleteRepCount)

            // Pause between reps
            currentTimeMs += 500L
            stateMachine.processAngle(165.0f, 180.0f, currentTimeMs)
            currentTimeMs += 200L
        }

        assertEquals(5, stateMachine.completeReps.size)
        assertEquals(0, stateMachine.incompleteReps.size)

        // Verify each rep has proper metadata
        for (i in 0 until 5) {
            val rep = stateMachine.completeReps[i]
            assertEquals(i + 1, rep.repIndex)
            assertTrue("Min elbow angle should be <= 90°", rep.minElbowAngle <= 90.0f)
            assertTrue(rep.isComplete)
        }
    }

    /**
     * Unit Test 2: Feed sequence that reverses direction before reaching bottom
     * tolerance -> assert 1 incomplete rep, 0 complete reps.
     */
    @Test
    fun testReversalBeforeBottomYieldsIncompleteRep() {
        var timeMs = 1000L

        // Start at top
        stateMachine.processAngle(elbowAngle = 165.0f, hipLineAngle = 180.0f, timestampMs = timeMs)
        assertEquals(ExercisePhase.TOP, stateMachine.currentPhase)
        timeMs += 300L

        // User descends partially, but only reaches 125° (target bottom tolerance is <= 100°)
        stateMachine.processAngle(150.0f, 180.0f, timeMs)
        assertEquals(ExercisePhase.DESCENDING, stateMachine.currentPhase)
        timeMs += 300L

        stateMachine.processAngle(135.0f, 180.0f, timeMs)
        timeMs += 300L

        stateMachine.processAngle(125.0f, 180.0f, timeMs) // Inflection point (shallow attempt)
        timeMs += 300L

        // User reverses direction upwards before reaching bottom target
        val revState1 = stateMachine.processAngle(138.0f, 180.0f, timeMs) // > 125° + 8° hysteresis
        assertEquals(ExercisePhase.ASCENDING, revState1.phase)
        timeMs += 400L

        stateMachine.processAngle(150.0f, 180.0f, timeMs)
        timeMs += 400L

        // User completes return to top lockout
        val finalState = stateMachine.processAngle(165.0f, 180.0f, timeMs)

        // Assert 1 incomplete rep, 0 complete reps
        assertEquals("Complete reps must be 0", 0, finalState.completeRepCount)
        assertEquals("Incomplete reps must be 1", 1, finalState.incompleteRepCount)
        assertEquals(ExercisePhase.TOP, finalState.phase)

        val incompleteRep = finalState.incompleteReps.first()
        assertEquals(1, incompleteRep.attemptIndex)
        assertEquals(125.0f, incompleteRep.minElbowAngleAchieved, 0.01f)
        assertTrue(incompleteRep.reason.contains("Reversed before reaching bottom target"))
    }

    /**
     * Unit Test 3: Feed sequence with visibility gap mid-rep (simulating what the
     * Visibility Gate will signal) -> assert that rep is neither falsely completed
     * nor counted, it's discarded from rep list.
     */
    @Test
    fun testVisibilityGapMidRepDiscardsAttempt() {
        var timeMs = 1000L

        // Rep 1: Clean complete rep
        stateMachine.processAngle(165.0f, 180.0f, timeMs, isVisibilitySufficient = true)
        timeMs += 300L
        stateMachine.processAngle(140.0f, 180.0f, timeMs, isVisibilitySufficient = true)
        timeMs += 300L
        stateMachine.processAngle(80.0f, 180.0f, timeMs, isVisibilitySufficient = true)
        timeMs += 300L
        stateMachine.processAngle(120.0f, 180.0f, timeMs, isVisibilitySufficient = true)
        timeMs += 300L
        val rep1State = stateMachine.processAngle(165.0f, 180.0f, timeMs, isVisibilitySufficient = true)
        assertEquals(1, rep1State.completeRepCount)
        assertEquals(0, rep1State.incompleteRepCount)
        timeMs += 500L

        // Rep 2 (Aborted mid-rep due to visibility gap):
        // User starts descent
        stateMachine.processAngle(150.0f, 180.0f, timeMs, isVisibilitySufficient = true)
        timeMs += 300L
        stateMachine.processAngle(110.0f, 180.0f, timeMs, isVisibilitySufficient = true)
        timeMs += 300L

        // Visibility Gate signals INSUFFICIENT_VISIBILITY mid-rep!
        val gapState1 = stateMachine.processAngle(90.0f, 180.0f, timeMs, isVisibilitySufficient = false)
        assertFalse("Rep should not be in progress after visibility drop", gapState1.isRepInProgress)
        assertNull(gapState1.newlyCompletedRep)
        assertNull(gapState1.newlyDetectedIncompleteRep)
        timeMs += 300L

        // Continued visibility gap
        val gapState2 = stateMachine.processAngle(85.0f, 180.0f, timeMs, isVisibilitySufficient = false)
        assertFalse(gapState2.isRepInProgress)
        timeMs += 300L

        // Visibility recovers when user is back near top
        val recoverState = stateMachine.processAngle(165.0f, 180.0f, timeMs, isVisibilitySufficient = true)
        timeMs += 500L

        // Assert that the mid-rep gap attempt was DISCARDED:
        // Still exactly 1 complete rep, and 0 incomplete reps!
        assertEquals("Discarded attempt must not be counted as complete", 1, recoverState.completeRepCount)
        assertEquals("Discarded attempt must not be counted as incomplete", 0, recoverState.incompleteRepCount)

        // Rep 3: User now does another clean rep after visibility restored
        stateMachine.processAngle(140.0f, 180.0f, timeMs, isVisibilitySufficient = true)
        timeMs += 300L
        stateMachine.processAngle(80.0f, 180.0f, timeMs, isVisibilitySufficient = true)
        timeMs += 300L
        stateMachine.processAngle(120.0f, 180.0f, timeMs, isVisibilitySufficient = true)
        timeMs += 300L
        val rep3State = stateMachine.processAngle(165.0f, 180.0f, timeMs, isVisibilitySufficient = true)

        // Now exactly 2 complete reps!
        assertEquals(2, rep3State.completeRepCount)
        assertEquals(0, rep3State.incompleteRepCount)
    }

    /**
     * Unit Test 4: Verify rep boundary timestamps are monotonically increasing and
     * sensible (each rep takes ~2-4 seconds per EXERCISE_SPEC.md tutBaseline).
     */
    @Test
    fun testRepBoundaryTimestampsMonotonicAndSensible() {
        var timeMs = 1000L

        // Execute 3 reps with realistic cadence (each rep ~3.0 seconds, tutBaseline is 4.0s)
        for (rep in 1..3) {
            // Rep start: exit top (1000ms, 4800ms, 8600ms)
            stateMachine.processAngle(165.0f, 180.0f, timeMs)
            timeMs += 200L

            stateMachine.processAngle(145.0f, 180.0f, timeMs)
            timeMs += 500L

            stateMachine.processAngle(110.0f, 180.0f, timeMs)
            timeMs += 600L

            // Bottom reached (takes ~1.3s down)
            stateMachine.processAngle(80.0f, 180.0f, timeMs)
            timeMs += 500L

            // Ascending (takes ~1.3s up)
            stateMachine.processAngle(115.0f, 180.0f, timeMs)
            timeMs += 500L

            stateMachine.processAngle(140.0f, 180.0f, timeMs)
            timeMs += 500L

            // Rep end: lockout reached
            stateMachine.processAngle(165.0f, 180.0f, timeMs)

            // Rest at top: 600ms
            timeMs += 600L
            stateMachine.processAngle(165.0f, 180.0f, timeMs)
            timeMs += 200L
        }

        assertEquals(3, stateMachine.completeReps.size)

        val reps = stateMachine.completeReps
        for (i in reps.indices) {
            val rep = reps[i]

            // 1. Monotonic within the rep: start < bottom < end
            assertTrue(
                "rep $i: start (${rep.startTimestampMs}) must be < bottom (${rep.bottomTimestampMs})",
                rep.startTimestampMs < rep.bottomTimestampMs
            )
            assertTrue(
                "rep $i: bottom (${rep.bottomTimestampMs}) must be < end (${rep.endTimestampMs})",
                rep.bottomTimestampMs < rep.endTimestampMs
            )

            // 2. Sensible duration: between 2.0s and 4.0s (tutBaseline is 4.0s)
            val durationSec = rep.durationMs / 1000.0f
            assertTrue(
                "rep $i duration ($durationSec s) must be >= 2.0s",
                durationSec >= 2.0f
            )
            assertTrue(
                "rep $i duration ($durationSec s) must be <= 4.0s",
                durationSec <= 4.0f
            )

            // 3. Monotonic between successive reps: rep[i].end <= rep[i+1].start
            if (i > 0) {
                val prevRep = reps[i - 1]
                assertTrue(
                    "rep $i start (${rep.startTimestampMs}) must be >= prevRep end (${prevRep.endTimestampMs})",
                    rep.startTimestampMs >= prevRep.endTimestampMs
                )
            }
        }
    }

    /**
     * Tests PoseGeometry angle calculation on 3D coordinates.
     */
    @Test
    fun testPoseGeometryAngleCalculation() {
        // Straight line (180 deg)
        val a = PoseLandmark(PoseLandmarkType.LEFT_SHOULDER, "LEFT_SHOULDER", 0.5f, 0.2f, 0.0f, 1.0f)
        val b = PoseLandmark(PoseLandmarkType.LEFT_ELBOW, "LEFT_ELBOW", 0.5f, 0.5f, 0.0f, 1.0f)
        val c = PoseLandmark(PoseLandmarkType.LEFT_WRIST, "LEFT_WRIST", 0.5f, 0.8f, 0.0f, 1.0f)

        val straightAngle = PoseGeometry.calculateAngle3D(a, b, c)
        assertEquals(180.0f, straightAngle, 0.5f)

        // Right angle (90 deg)
        val c90 = PoseLandmark(PoseLandmarkType.LEFT_WRIST, "LEFT_WRIST", 0.8f, 0.5f, 0.0f, 1.0f)
        val rightAngle = PoseGeometry.calculateAngle3D(a, b, c90)
        assertEquals(90.0f, rightAngle, 0.5f)

        // 2D angle check
        val angle2D = PoseGeometry.calculateAngle2D(0.5f, 0.2f, 0.5f, 0.5f, 0.8f, 0.5f)
        assertEquals(90.0f, angle2D, 0.5f)
    }
}
