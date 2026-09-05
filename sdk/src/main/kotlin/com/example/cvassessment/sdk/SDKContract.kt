package com.example.cvassessment.sdk

/**
 * Validation status of the exercise being analyzed.
 */
enum class ValidationStatus {
    VALID,
    INVALID,
    INSUFFICIENT_VISIBILITY
}

/**
 * Form error detected during exercise execution.
 */
data class FormError(
    val errorName: String,
    val confidence: Float,
    val repIndex: Int?,
    val severity: Float = 0.5f
)

/**
 * Audio or visual feedback event triggered by the SDK.
 */
data class FeedbackEvent(
    val message: String,
    val timestampMs: Long,
    val relatedError: String? = null
)

/**
 * Final structured session result representing the complete evaluation of an exercise session.
 */
data class SessionResult(
    val status: ValidationStatus,
    val confidence: Float,
    val completeReps: Int? = null,
    val incompleteReps: Int? = null,
    val holdDurationSec: Float? = null,
    val avgRepDurationSec: Float? = null,
    val romPercent: Float? = null,
    val tutFactor: Float? = null,
    val formFactor: Float? = null,
    val formErrors: List<FormError> = emptyList(),
    val feedbackEvents: List<FeedbackEvent> = emptyList()
)

/**
 * Abstraction for camera frame input.
 */
interface CameraFrame {
    val width: Int
    val height: Int
    val rotationDegrees: Int
    val timestampMs: Long
}

/**
 * Frame callback interface that the SDK consumes from the camera capture layer.
 */
fun interface FrameCallback {
    fun onFrame(frame: CameraFrame, timestampMs: Long)
}

/**
 * Real-time per-frame analysis output.
 */
data class FrameResult(
    val status: ValidationStatus,
    val confidence: Float,
    val currentReps: Int? = null,
    val currentHoldSec: Float? = null,
    val instantRomPercent: Float? = null,
    val activeFeedback: FeedbackEvent? = null
)

/**
 * Thrown when an unknown exerciseId is provided at initialization.
 */
class UnknownExerciseException(message: String) : IllegalArgumentException(message)
