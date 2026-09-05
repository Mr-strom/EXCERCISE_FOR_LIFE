package com.example.cvassessment.sdk.spec

/**
 * Type of comparison applied to the tracked angle.
 */
enum class AngleComparison {
    GREATER_THAN,
    LESS_THAN,
    BETWEEN
}

/**
 * Condition defining when an exercise enters a specific movement phase.
 *
 * @param phaseName Name of movement phase (e.g. "top", "bottom")
 * @param trackedAngleName Key referencing the JointAngleDefinition
 * @param comparison Comparison operator
 * @param thresholdAngle Primary angle threshold in degrees
 * @param secondaryThresholdAngle Optional secondary angle threshold for range checks
 * @param toleranceDeg Acceptable deviation in degrees (default 10 deg per METRICS_SPEC.md)
 */
data class PhaseCondition(
    val phaseName: String,
    val trackedAngleName: String,
    val comparison: AngleComparison,
    val thresholdAngle: Float,
    val secondaryThresholdAngle: Float? = null,
    val toleranceDeg: Float = 10.0f
) {
    /**
     * Evaluates if the supplied angle satisfies this phase condition.
     */
    fun matches(angle: Float): Boolean {
        return when (comparison) {
            AngleComparison.GREATER_THAN -> angle >= (thresholdAngle - toleranceDeg)
            AngleComparison.LESS_THAN -> angle <= (thresholdAngle + toleranceDeg)
            AngleComparison.BETWEEN -> {
                val min = minOf(thresholdAngle, secondaryThresholdAngle ?: thresholdAngle) - toleranceDeg
                val max = maxOf(thresholdAngle, secondaryThresholdAngle ?: thresholdAngle) + toleranceDeg
                angle in min..max
            }
        }
    }
}
