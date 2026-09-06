package com.example.cvassessment.sdk

import com.example.cvassessment.sdk.statemachine.ExercisePhase
import com.example.cvassessment.sdk.statemachine.SquatStateMachine
import org.junit.Test

class SquatInvestigationTest {

    @Test
    fun runLiveReplaySessionInvestigation() {
        val stateMachine = SquatStateMachine()
        val logs = mutableListOf<String>()
        stateMachine.isDebugLoggingEnabled = true
        stateMachine.debugLogger = { msg ->
            logs.add(msg)
            println(msg)
        }

        var t = 1000L

        // Initial setup at TOP (165°)
        stateMachine.processAngle(165.0f, 180.0f, t)

        println("=== REPLAYING 9 SQUAT ATTEMPTS FROM LIVE SESSION ===")

        // ATTEMPT 1: Clean deep squat (reaches 92° <= 110°) -> COMPLETE
        println("\n--- ATTEMPT 1 (Deep Squat, max flexion 92°) ---")
        t += 300L; stateMachine.processAngle(150.0f, 180.0f, t)
        t += 300L; stateMachine.processAngle(125.0f, 180.0f, t)
        t += 300L; stateMachine.processAngle(92.0f, 180.0f, t) // Reaches bottom
        t += 300L; stateMachine.processAngle(125.0f, 180.0f, t)
        t += 300L; stateMachine.processAngle(165.0f, 180.0f, t) // Rep 1 complete!

        // ATTEMPT 2: Parallel squat (reaches 112.5° > 110°) -> FALSE INCOMPLETE (Depth threshold)
        println("\n--- ATTEMPT 2 (Parallel Squat, max flexion 112.5°) ---")
        t += 500L; stateMachine.processAngle(165.0f, 180.0f, t)
        t += 300L; stateMachine.processAngle(148.0f, 180.0f, t)
        t += 300L; stateMachine.processAngle(128.0f, 180.0f, t)
        t += 300L; stateMachine.processAngle(112.5f, 180.0f, t) // Deepest point: 112.5° (visually parallel, but > 110°)
        t += 300L; stateMachine.processAngle(125.0f, 180.0f, t)
        t += 300L; stateMachine.processAngle(145.0f, 180.0f, t)
        t += 300L; stateMachine.processAngle(165.0f, 180.0f, t) // Top lockout: flagged INCOMPLETE!

        // ATTEMPT 3: Deep squat (reaches 95° <= 110°) -> COMPLETE
        println("\n--- ATTEMPT 3 (Deep Squat, max flexion 95°) ---")
        t += 500L; stateMachine.processAngle(165.0f, 180.0f, t)
        t += 300L; stateMachine.processAngle(145.0f, 180.0f, t)
        t += 300L; stateMachine.processAngle(120.0f, 180.0f, t)
        t += 300L; stateMachine.processAngle(95.0f, 180.0f, t) // Reaches bottom
        t += 300L; stateMachine.processAngle(130.0f, 180.0f, t)
        t += 300L; stateMachine.processAngle(165.0f, 180.0f, t) // Rep 2 complete!

        // ATTEMPT 4: Squat with 0.8s bottom-hold pause at 113.0° + jitter -> FALSE INCOMPLETE (Pause Reversal)
        println("\n--- ATTEMPT 4 (Squat with 0.8s Bottom Pause at 113.0° + tracking jitter) ---")
        t += 500L; stateMachine.processAngle(165.0f, 180.0f, t)
        t += 300L; stateMachine.processAngle(145.0f, 180.0f, t)
        t += 300L; stateMachine.processAngle(125.0f, 180.0f, t)
        t += 300L; stateMachine.processAngle(113.0f, 180.0f, t) // Bottom pause begins (min = 113.0°)
        // Natural 0.8s pause at bottom with ±4-9° BlazePose jitter
        t += 200L; stateMachine.processAngle(114.5f, 180.0f, t)
        t += 200L; stateMachine.processAngle(121.8f, 180.0f, t) // Jitter: 121.8° > 113.0° + 8.0° -> triggers ASCENDING!
        t += 200L; stateMachine.processAngle(107.0f, 180.0f, t) // Sinks slightly / jitter settles: < 113.0° - 5.0° (108.0°) -> triggers RE-DESCENT RESET!
        t += 200L; stateMachine.processAngle(112.0f, 180.0f, t) // Pause ends
        // Ascends back up
        t += 300L; stateMachine.processAngle(135.0f, 180.0f, t)
        t += 300L; stateMachine.processAngle(165.0f, 180.0f, t)

        // ATTEMPT 5: Parallel squat (reaches 114.0°) -> FALSE INCOMPLETE
        println("\n--- ATTEMPT 5 (Parallel Squat, max flexion 114.0°) ---")
        t += 500L; stateMachine.processAngle(165.0f, 180.0f, t)
        t += 300L; stateMachine.processAngle(145.0f, 180.0f, t)
        t += 300L; stateMachine.processAngle(125.0f, 180.0f, t)
        t += 300L; stateMachine.processAngle(114.0f, 180.0f, t) // Deepest: 114.0°
        t += 300L; stateMachine.processAngle(130.0f, 180.0f, t)
        t += 300L; stateMachine.processAngle(165.0f, 180.0f, t) // Flagged INCOMPLETE!

        // ATTEMPT 6: Parallel squat (reaches 111.8°) -> FALSE INCOMPLETE
        println("\n--- ATTEMPT 6 (Parallel Squat, max flexion 111.8°) ---")
        t += 500L; stateMachine.processAngle(165.0f, 180.0f, t)
        t += 300L; stateMachine.processAngle(145.0f, 180.0f, t)
        t += 300L; stateMachine.processAngle(125.0f, 180.0f, t)
        t += 300L; stateMachine.processAngle(111.8f, 180.0f, t) // Deepest: 111.8°
        t += 300L; stateMachine.processAngle(130.0f, 180.0f, t)
        t += 300L; stateMachine.processAngle(165.0f, 180.0f, t) // Flagged INCOMPLETE!

        // ATTEMPT 7: Squat with 0.6s bottom pause at 113.5° -> FALSE INCOMPLETE
        println("\n--- ATTEMPT 7 (Parallel Squat, max flexion 113.5° with pause) ---")
        t += 500L; stateMachine.processAngle(165.0f, 180.0f, t)
        t += 300L; stateMachine.processAngle(145.0f, 180.0f, t)
        t += 300L; stateMachine.processAngle(125.0f, 180.0f, t)
        t += 300L; stateMachine.processAngle(113.5f, 180.0f, t) // Pause
        t += 300L; stateMachine.processAngle(114.0f, 180.0f, t)
        t += 300L; stateMachine.processAngle(135.0f, 180.0f, t)
        t += 300L; stateMachine.processAngle(165.0f, 180.0f, t) // Flagged INCOMPLETE!

        // ATTEMPT 8: Parallel squat (reaches 112.0°) -> FALSE INCOMPLETE
        println("\n--- ATTEMPT 8 (Parallel Squat, max flexion 112.0°) ---")
        t += 500L; stateMachine.processAngle(165.0f, 180.0f, t)
        t += 300L; stateMachine.processAngle(145.0f, 180.0f, t)
        t += 300L; stateMachine.processAngle(125.0f, 180.0f, t)
        t += 300L; stateMachine.processAngle(112.0f, 180.0f, t)
        t += 300L; stateMachine.processAngle(130.0f, 180.0f, t)
        t += 300L; stateMachine.processAngle(165.0f, 180.0f, t) // Flagged INCOMPLETE!

        // ATTEMPT 9: Parallel squat (reaches 113.0°) -> FALSE INCOMPLETE
        println("\n--- ATTEMPT 9 (Parallel Squat, max flexion 113.0°) ---")
        t += 500L; stateMachine.processAngle(165.0f, 180.0f, t)
        t += 300L; stateMachine.processAngle(145.0f, 180.0f, t)
        t += 300L; stateMachine.processAngle(125.0f, 180.0f, t)
        t += 300L; stateMachine.processAngle(113.0f, 180.0f, t)
        t += 300L; stateMachine.processAngle(130.0f, 180.0f, t)
        t += 300L; stateMachine.processAngle(165.0f, 180.0f, t) // Flagged INCOMPLETE!

        println("\n=== SESSION SUMMARY ===")
        println("Complete Reps: ${stateMachine.completeReps.size}")
        println("Incomplete Reps: ${stateMachine.incompleteReps.size}")
        stateMachine.incompleteReps.forEach { inc ->
            println("  Incomplete #${inc.attemptIndex}: Achieved ${inc.minElbowAngleAchieved}°, Reason: ${inc.reason}")
        }

        org.junit.Assert.assertEquals("All 9 real squat attempts must complete", 9, stateMachine.completeReps.size)
        org.junit.Assert.assertEquals("Zero false incomplete reps should be flagged", 0, stateMachine.incompleteReps.size)
    }
}
