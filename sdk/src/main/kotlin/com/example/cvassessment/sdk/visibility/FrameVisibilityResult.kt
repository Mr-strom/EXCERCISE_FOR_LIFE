package com.example.cvassessment.sdk.visibility

/**
 * Result of evaluating an individual frame through the Visibility Gate.
 *
 * @param status SUFFICIENT_VISIBILITY if all visibility checks pass, INSUFFICIENT_VISIBILITY otherwise.
 * @param failureReasons List of specific failure causes if status is INSUFFICIENT_VISIBILITY.
 * @param missingLandmarkIndices Set of required landmark indices that are currently missing or low-confidence.
 * @param consecutiveMissingCounts Map of landmark index to consecutive frame count below visibility threshold.
 */
data class FrameVisibilityResult(
    val status: VisibilityStatus,
    val failureReasons: List<VisibilityFailureReason>,
    val missingLandmarkIndices: Set<Int> = emptySet(),
    val consecutiveMissingCounts: Map<Int, Int> = emptyMap()
) {
    val isSufficient: Boolean
        get() = status == VisibilityStatus.SUFFICIENT_VISIBILITY
}
