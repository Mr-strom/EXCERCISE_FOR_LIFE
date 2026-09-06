package com.example.cvassessment.sdk.visibility

import com.example.cvassessment.sdk.pose.PoseEstimationResult
import com.example.cvassessment.sdk.pose.PoseLandmark
import com.example.cvassessment.sdk.pose.PoseLandmarkType
import com.example.cvassessment.sdk.statemachine.SidePlankSupportSide

/**
 * Visibility Gate for Side Plank per VISIBILITY_POLICY.md and EXERCISE_SPEC.md #8.
 *
 * Primary Landmarks:
 * - Shoulders (11, 12)
 * - Support-side Elbow (13 if Left, 14 if Right)
 * - Hips (23, 24)
 * - Ankles (27, 28)
 *
 * Requirements:
 * - Requires the support side (shoulder, elbow, hip, ankle) to be visible above threshold.
 * - Out-of-frame boundary detection on image edges.
 * - Session-level tracking: fails session if >50% of frames fail visibility checks (R7).
 */
internal class SidePlankVisibilityGate(
    val exerciseId: String = "side_plank",
    val landmarkVisibilityThreshold: Float = 0.4f,
    val maxMissingFrames: Int = 7,
    val sessionFailureThreshold: Float = 0.50f,
    val boundaryMargin: Float = 0.02f
) {
    companion object {
        val ALL_SIDE_PLANK_LANDMARKS = listOf(
            PoseLandmarkType.LEFT_SHOULDER,  // 11
            PoseLandmarkType.RIGHT_SHOULDER, // 12
            PoseLandmarkType.LEFT_ELBOW,     // 13
            PoseLandmarkType.RIGHT_ELBOW,    // 14
            PoseLandmarkType.LEFT_HIP,       // 23
            PoseLandmarkType.RIGHT_HIP,      // 24
            PoseLandmarkType.LEFT_ANKLE,     // 27
            PoseLandmarkType.RIGHT_ANKLE     // 28
        )

        val LEFT_SIDE_LANDMARKS = listOf(
            PoseLandmarkType.LEFT_SHOULDER, // 11
            PoseLandmarkType.LEFT_ELBOW,    // 13
            PoseLandmarkType.LEFT_HIP,      // 23
            PoseLandmarkType.LEFT_ANKLE     // 27
        )

        val RIGHT_SIDE_LANDMARKS = listOf(
            PoseLandmarkType.RIGHT_SHOULDER, // 12
            PoseLandmarkType.RIGHT_ELBOW,    // 14
            PoseLandmarkType.RIGHT_HIP,      // 24
            PoseLandmarkType.RIGHT_ANKLE     // 28
        )
    }

    private val consecutiveMissingCounts = mutableMapOf<Int, Int>()

    var totalFramesAnalyzed: Long = 0L
        private set
    var failedVisibilityFrames: Long = 0L
        private set

    fun getRequiredLandmarks(): List<Int> = ALL_SIDE_PLANK_LANDMARKS

    fun checkFrame(
        poseResult: PoseEstimationResult,
        supportSide: SidePlankSupportSide = SidePlankSupportSide.UNKNOWN
    ): FrameVisibilityResult {
        totalFramesAnalyzed++
        val reasons = mutableListOf<VisibilityFailureReason>()
        val missingIndices = mutableSetOf<Int>()

        // 1. No person detected
        if (!poseResult.hasPose || poseResult.landmarks.isEmpty()) {
            failedVisibilityFrames++
            return FrameVisibilityResult(
                status = VisibilityStatus.INSUFFICIENT_VISIBILITY,
                failureReasons = listOf(VisibilityFailureReason.NO_POSE_DETECTED, VisibilityFailureReason.BODY_OUT_OF_FRAME)
            )
        }

        val landmarkMap: Map<Int, PoseLandmark> = poseResult.landmarks.associateBy { it.index }

        // 2. Out-of-frame boundary detection
        var outOfFrameDetected = false
        val checkLandmarks = when (supportSide) {
            SidePlankSupportSide.LEFT -> LEFT_SIDE_LANDMARKS
            SidePlankSupportSide.RIGHT -> RIGHT_SIDE_LANDMARKS
            SidePlankSupportSide.UNKNOWN -> ALL_SIDE_PLANK_LANDMARKS
        }

        for (reqIdx in checkLandmarks) {
            val lm = landmarkMap[reqIdx]
            if (lm == null) {
                missingIndices.add(reqIdx)
            } else {
                val isAtBoundary = (lm.x <= boundaryMargin || lm.x >= (1.0f - boundaryMargin) ||
                        lm.y <= boundaryMargin || lm.y >= (1.0f - boundaryMargin))
                if (isAtBoundary) {
                    outOfFrameDetected = true
                    missingIndices.add(reqIdx)
                }
            }
        }
        if (outOfFrameDetected) {
            reasons.add(VisibilityFailureReason.BODY_OUT_OF_FRAME)
        }

        // 3. Support side landmark check: support side must be visible
        val leftVisible = LEFT_SIDE_LANDMARKS.all { idx ->
            val lm = landmarkMap[idx]
            lm != null && lm.visibility >= landmarkVisibilityThreshold
        }
        val rightVisible = RIGHT_SIDE_LANDMARKS.all { idx ->
            val lm = landmarkMap[idx]
            lm != null && lm.visibility >= landmarkVisibilityThreshold
        }

        val hasRequiredSideVisible = when (supportSide) {
            SidePlankSupportSide.LEFT -> leftVisible
            SidePlankSupportSide.RIGHT -> rightVisible
            SidePlankSupportSide.UNKNOWN -> leftVisible || rightVisible
        }

        if (!hasRequiredSideVisible) {
            reasons.add(VisibilityFailureReason.LOW_CONFIDENCE)
            missingIndices.addAll(checkLandmarks)
        }

        // 4. Consecutive missing frame tracking
        var exceededMissing = false
        for (reqIdx in checkLandmarks) {
            if (missingIndices.contains(reqIdx)) {
                val count = (consecutiveMissingCounts[reqIdx] ?: 0) + 1
                consecutiveMissingCounts[reqIdx] = count
                if (count > maxMissingFrames) {
                    exceededMissing = true
                }
            } else {
                consecutiveMissingCounts[reqIdx] = 0
            }
        }
        if (exceededMissing && !reasons.contains(VisibilityFailureReason.LOW_CONFIDENCE)) {
            reasons.add(VisibilityFailureReason.LOW_CONFIDENCE)
        }

        val status = if (reasons.isEmpty()) {
            VisibilityStatus.SUFFICIENT_VISIBILITY
        } else {
            failedVisibilityFrames++
            VisibilityStatus.INSUFFICIENT_VISIBILITY
        }

        return FrameVisibilityResult(
            status = status,
            failureReasons = reasons,
            missingLandmarkIndices = missingIndices
        )
    }

    /**
     * Session-level R7 refusal: returns INSUFFICIENT_VISIBILITY if failed frames > 50%.
     */
    fun getSessionVisibilityStatus(): VisibilityStatus {
        if (totalFramesAnalyzed == 0L) return VisibilityStatus.SUFFICIENT_VISIBILITY
        val failureRatio = failedVisibilityFrames.toFloat() / totalFramesAnalyzed.toFloat()
        return if (failureRatio > sessionFailureThreshold) {
            VisibilityStatus.INSUFFICIENT_VISIBILITY
        } else {
            VisibilityStatus.SUFFICIENT_VISIBILITY
        }
    }

    fun reset() {
        consecutiveMissingCounts.clear()
        totalFramesAnalyzed = 0L
        failedVisibilityFrames = 0L
    }
}
