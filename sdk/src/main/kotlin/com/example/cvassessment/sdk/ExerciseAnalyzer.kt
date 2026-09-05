package com.example.cvassessment.sdk

import com.example.cvassessment.sdk.pose.PoseEstimationResult
import com.example.cvassessment.sdk.visibility.FrameVisibilityResult
import com.example.cvassessment.sdk.visibility.VisibilityGate
import com.example.cvassessment.sdk.visibility.VisibilityStatus

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

    // Module 3: Visibility Gate enforcing R7 refusal rules
    val visibilityGate = VisibilityGate(exerciseId = exerciseId)

    init {
        val normalizedId = exerciseId.trim().lowercase()
        if (normalizedId !in supportedExercises) {
            throw UnknownExerciseException(
                "Unknown exercise ID: '$exerciseId'. Must be one of: $supportedExercises"
            )
        }
    }

    /**
     * Evaluate frame through the Visibility Gate (Module 3).
     */
    fun checkVisibility(poseResult: PoseEstimationResult): FrameVisibilityResult {
        return visibilityGate.checkFrame(poseResult)
    }

    /**
     * Process an individual camera frame with timestamp.
     */
    fun analyzeFrame(frame: CameraFrame, timestampMs: Long): FrameResult {
        return FrameResult(
            status = ValidationStatus.VALID,
            confidence = 1.0f
        )
    }

    /**
     * Compile and return the complete session result up to the current moment.
     * Enforces R7 refusal rule: if visibility failed for >50% of the session,
     * session status is INSUFFICIENT_VISIBILITY and ALL metric fields are strictly null.
     */
    fun getSessionResult(): SessionResult {
        if (visibilityGate.totalFramesAnalyzed > 0L &&
            visibilityGate.getSessionVisibilityStatus() == VisibilityStatus.INSUFFICIENT_VISIBILITY) {
            return SessionResult(
                status = ValidationStatus.INSUFFICIENT_VISIBILITY,
                confidence = 0.0f,
                completeReps = null,
                incompleteReps = null,
                holdDurationSec = null,
                avgRepDurationSec = null,
                romPercent = null,
                tutFactor = null,
                formFactor = null,
                formErrors = emptyList(),
                feedbackEvents = emptyList()
            )
        }

        return SessionResult(
            status = ValidationStatus.VALID,
            confidence = 1.0f
        )
    }
}
