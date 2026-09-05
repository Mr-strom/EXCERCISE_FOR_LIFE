package com.example.cvassessment.sdk.pose

/**
 * Result emitted by Module 2 (Pose Estimation Layer).
 *
 * @param landmarks List of 33 normalized landmarks (empty if no person detected)
 * @param timestampMs Frame timestamp in milliseconds
 * @param hasPose True if at least one person pose was successfully detected
 */
data class PoseEstimationResult(
    val landmarks: List<PoseLandmark>,
    val timestampMs: Long,
    val hasPose: Boolean
) {
    companion object {
        fun empty(timestampMs: Long): PoseEstimationResult =
            PoseEstimationResult(emptyList(), timestampMs, false)
    }

    /**
     * Compute average visibility across key tracking landmarks.
     */
    fun getAverageVisibility(): Float {
        if (landmarks.isEmpty()) return 0.0f
        return landmarks.map { it.visibility }.average().toFloat()
    }
}
