package com.example.cvassessment.sdk.metrics

/**
 * Metric evaluations for a single completed repetition per METRICS_SPEC.md.
 *
 * @param repIndex 1-based rep counter
 * @param romPercent Range of Motion percentage [0.0..100.0]
 * @param tutFactor Time Under Tension factor (actual_duration_sec / baseline_duration_sec)
 * @param confidence Overall evaluation confidence [0.0..1.0]
 * @param durationSec Repetition duration in seconds
 * @param minElbowAngle Minimum elbow angle reached during the bottom phase
 * @param startTimestampMs Rep start wall-clock timestamp
 * @param endTimestampMs Rep end wall-clock timestamp
 * @param landmarkVisibilityConfidence Landmark visibility confidence component [0.0..1.0] (40% weight)
 * @param phasePatternMatchConfidence Phase pattern match confidence component [0.0..1.0] (40% weight)
 * @param trajectorySmoothnessConfidence Trajectory smoothness confidence component [0.0..1.0] (20% weight)
 */
data class RepMetrics(
    val repIndex: Int,
    val romPercent: Float,
    val tutFactor: Float,
    val confidence: Float,
    val durationSec: Float,
    val minElbowAngle: Float,
    val startTimestampMs: Long,
    val endTimestampMs: Long,
    val landmarkVisibilityConfidence: Float = 1.0f,
    val phasePatternMatchConfidence: Float = 1.0f,
    val trajectorySmoothnessConfidence: Float = 1.0f
)
