package com.example.cvassessment.sdk.metrics

/**
 * Output data model emitted per frame by the Metrics Engine (Module 5).
 *
 * Per architecture rules and VISIBILITY_POLICY.md:
 * When visibility is insufficient, [romPercent] and [tutFactor] MUST be null.
 *
 * @param romPercent Range of Motion as % (0..100) from the latest completed rep (or null if no valid rep/insufficient visibility)
 * @param tutFactor Time Under Tension factor (actual_duration / baseline_duration)
 * @param confidence Overall confidence score [0.0..1.0] (40% visibility + 40% pattern match + 20% trajectory smoothness)
 * @param instantRomPercent Real-time instant ROM% based on current elbow angle
 * @param latestCompletedRepMetrics Detailed metrics of the most recently completed rep (if any)
 * @param allRepMetrics Cumulative history of metrics for all completed reps
 * @param isVisibilitySufficient Whether visibility passed the Visibility Gate
 */
data class FrameMetrics(
    val romPercent: Float?,
    val tutFactor: Float?,
    val confidence: Float,
    val instantRomPercent: Float? = null,
    val latestCompletedRepMetrics: RepMetrics? = null,
    val allRepMetrics: List<RepMetrics> = emptyList(),
    val isVisibilitySufficient: Boolean = true
)
