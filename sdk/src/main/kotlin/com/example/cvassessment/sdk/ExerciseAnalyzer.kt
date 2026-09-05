package com.example.cvassessment.sdk

/**
 * Public facade and main entry point for the Exercise Assessment SDK.
 * Host applications (like the demo app) interact exclusively with this class.
 */
class ExerciseAnalyzer(
    val exerciseId: String,
    val exerciseName: String
) {
    private val supportedExercises = setOf(
        "push_up", "bicep_curl", "shoulder_press",
        "squat", "lunge", "calf_raise",
        "plank", "side_plank",
        "jumping_jack", "mountain_climber"
    )

    init {
        val normalizedId = exerciseId.trim().lowercase()
        if (normalizedId !in supportedExercises) {
            throw UnknownExerciseException(
                "Unknown exercise ID: '$exerciseId'. Must be one of: $supportedExercises"
            )
        }
    }

    /**
     * Process an individual camera frame with timestamp.
     */
    fun analyzeFrame(frame: CameraFrame, timestampMs: Long): FrameResult {
        // Scaffolding stub for Task 1; pipeline wired in subsequent tasks
        return FrameResult(
            status = ValidationStatus.VALID,
            confidence = 1.0f
        )
    }

    /**
     * Compile and return the complete session result up to the current moment.
     */
    fun getSessionResult(): SessionResult {
        // Scaffolding stub for Task 1
        return SessionResult(
            status = ValidationStatus.VALID,
            confidence = 1.0f
        )
    }
}
