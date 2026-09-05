package com.example.cvassessment.sdk.statemachine

/**
 * Represents a successfully completed repetition with exact timestamps and extremum metrics.
 *
 * @param repIndex 1-based index of this completed rep
 * @param startTimestampMs Wall-clock timestamp when user exited top resting position
 * @param bottomTimestampMs Wall-clock timestamp when user reached bottom inflection depth
 * @param endTimestampMs Wall-clock timestamp when user returned to top lockout position
 * @param durationMs Total repetition duration in milliseconds
 * @param minElbowAngle Deepest angle achieved during the repetition
 * @param isComplete True for valid repetitions that satisfied all phase thresholds
 */
data class RepBoundary(
    val repIndex: Int,
    val startTimestampMs: Long,
    val bottomTimestampMs: Long,
    val endTimestampMs: Long,
    val durationMs: Long = endTimestampMs - startTimestampMs,
    val minElbowAngle: Float,
    val isComplete: Boolean = true
)

/**
 * Represents an incomplete repetition attempt where the user started movement
 * but reversed direction before reaching bottom tolerance (< 100°).
 *
 * @param attemptIndex 1-based index of this incomplete attempt
 * @param startTimestampMs Timestamp when descent commenced
 * @param reversalTimestampMs Timestamp when reversal was confirmed
 * @param minElbowAngleAchieved Deepest angle reached before premature ascent
 * @param reason Descriptive diagnostic explanation
 */
data class IncompleteRep(
    val attemptIndex: Int,
    val startTimestampMs: Long,
    val reversalTimestampMs: Long,
    val minElbowAngleAchieved: Float,
    val reason: String = "Reversed before reaching bottom target (< 100°)"
)
