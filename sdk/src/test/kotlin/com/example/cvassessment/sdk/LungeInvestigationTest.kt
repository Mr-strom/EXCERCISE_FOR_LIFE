package com.example.cvassessment.sdk

import com.example.cvassessment.sdk.pose.PoseEstimationResult
import com.example.cvassessment.sdk.pose.PoseLandmark
import com.example.cvassessment.sdk.pose.PoseLandmarkType
import com.example.cvassessment.sdk.statemachine.ExercisePhase
import com.example.cvassessment.sdk.statemachine.LungeGeometry
import com.example.cvassessment.sdk.statemachine.LungeStateMachine
import com.example.cvassessment.sdk.visibility.LungeVisibilityGate
import com.example.cvassessment.sdk.visibility.VisibilityStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Diagnostic simulation test investigating the real-world Lunge session anomaly:
 * 17 complete reps, 29 incomplete reps (46 total attempts) with repeated
 * "Move back — feet cut off" visibility warnings.
 */
class LungeInvestigationTest {

    @Test
    fun testDiagnoseVisibilityGapLeakingIntoIncompleteCount() {
        val stateMachine = LungeStateMachine()
        val visGate = LungeVisibilityGate()

        val logs = mutableListOf<String>()
        stateMachine.isDebugLoggingEnabled = true
        stateMachine.debugLogger = { logs.add(it) }
        visGate.isDebugLoggingEnabled = true
        visGate.debugLogger = { logs.add(it) }

        var t = 1000L

        // SCENARIO A: Single-leg ankle cutoff during backward step
        // User steps back into lunge. Front leg (left) is at y=0.80, rear leg (right ankle) is cut off at y=0.99.
        // Position guidance says "Move back — feet cut off".
        // What does LungeVisibilityGate do?
        println("=== SCENARIO A: Backward ankle cutoff at frame boundary ===")
        val cutOffLandmarks = listOf(
            PoseLandmark(PoseLandmarkType.LEFT_SHOULDER, "LEFT_SHOULDER", 0.45f, 0.25f, 0.0f, 0.95f),
            PoseLandmark(PoseLandmarkType.RIGHT_SHOULDER, "RIGHT_SHOULDER", 0.55f, 0.25f, 0.0f, 0.95f),
            PoseLandmark(PoseLandmarkType.LEFT_HIP, "LEFT_HIP", 0.45f, 0.50f, 0.0f, 0.90f),
            PoseLandmark(PoseLandmarkType.RIGHT_HIP, "RIGHT_HIP", 0.55f, 0.50f, 0.0f, 0.90f),
            PoseLandmark(PoseLandmarkType.LEFT_KNEE, "LEFT_KNEE", 0.42f, 0.70f, 0.0f, 0.85f),
            PoseLandmark(PoseLandmarkType.RIGHT_KNEE, "RIGHT_KNEE", 0.58f, 0.75f, 0.0f, 0.85f),
            PoseLandmark(PoseLandmarkType.LEFT_ANKLE, "LEFT_ANKLE", 0.40f, 0.85f, 0.0f, 0.85f),
            // Right ankle is cut off at bottom of frame (y=0.99 > boundaryMargin 0.02, so out-of-frame detected)
            PoseLandmark(PoseLandmarkType.RIGHT_ANKLE, "RIGHT_ANKLE", 0.60f, 0.99f, 0.0f, 0.30f)
        )
        val visResultA = visGate.checkFrame(PoseEstimationResult(cutOffLandmarks, hasPose = true, timestampMs = t))
        println("VisGate Result with right ankle at y=0.99 (conf 0.30): status=${visResultA.status}, reasons=${visResultA.failureReasons}")

        // SCENARIO B: Mid-rep visibility drop followed by recovery mid-movement
        // User descends into lunge (starts at 165° -> reaches 110°).
        // Visibility drops mid-rep (isVisibilitySufficient = false) -> attempt discarded (Good).
        // BUT 300ms later, visibility returns while athlete is still at knee angle 110° and standing up!
        println("\n=== SCENARIO B: Visibility restoration mid-ascent creating phantom incomplete rep ===")
        stateMachine.reset()

        // 1. Stand at top
        t += 300L; stateMachine.processAngle(165.0f, 0.0f, t, isVisibilitySufficient = true)

        // 2. Descend to 110°
        t += 300L; stateMachine.processAngle(140.0f, 0.0f, t, isVisibilitySufficient = true)
        t += 300L; stateMachine.processAngle(110.0f, 0.0f, t, isVisibilitySufficient = true)
        assertTrue("Rep is in progress", stateMachine.currentState.isRepInProgress)

        // 3. Feet leave frame: visibility drops mid-rep
        t += 300L; stateMachine.processAngle(100.0f, 0.0f, t, isVisibilitySufficient = false)
        assertEquals("In-progress rep discarded on visibility drop", 0, stateMachine.completeReps.size)
        assertEquals("Discarded rep NOT counted as incomplete", 0, stateMachine.incompleteReps.size)
        assertEquals("Phase reset to TOP", ExercisePhase.TOP, stateMachine.currentState.phase)

        // 4. Visibility restored while user is recovering back to standing (angle is 115°!)
        t += 300L; stateMachine.processAngle(115.0f, 0.0f, t, isVisibilitySufficient = true)
        // D13 FIX: AwaitingTopExtension latch prevents starting a new attempt at 115°
        assertEquals("D13 Guard: Remains in TOP phase during recovery", ExercisePhase.TOP, stateMachine.currentState.phase)
        org.junit.Assert.assertFalse("D13 Guard: Rep must NOT be in progress", stateMachine.currentState.isRepInProgress)

        // 5. User continues standing up (115° -> 135° -> 165°)
        t += 300L; stateMachine.processAngle(135.0f, 0.0f, t, isVisibilitySufficient = true)
        t += 300L; stateMachine.processAngle(165.0f, 0.0f, t, isVisibilitySufficient = true)

        // The state machine must record ZERO phantom incomplete reps!
        println("After recovery to top: complete=${stateMachine.completeReps.size}, incomplete=${stateMachine.incompleteReps.size}")
        assertEquals("D13 Guard: Zero phantom incomplete reps recorded from recovery ascent", 0, stateMachine.incompleteReps.size)
        org.junit.Assert.assertFalse("awaitingTopExtension cleared", stateMachine.awaitingTopExtension)
    }
}
