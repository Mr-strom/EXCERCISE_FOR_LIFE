package com.example.cvassessment.sdk.visibility

import com.example.cvassessment.sdk.pose.PoseEstimationResult
import com.example.cvassessment.sdk.pose.PoseLandmark
import com.example.cvassessment.sdk.pose.PoseLandmarkType

/**
 * Visibility Gate for Squat per VISIBILITY_POLICY.md and EXERCISE_SPEC.md.
 *
 * Required landmarks for Squat:
 * - Shoulders (11, 12)
 * - Hips (23, 24)
 * - Knees (25, 26)
 * - Ankles (27, 28)
 *
 * Enforces R7 refusal rules:
 * - Minimum landmark confidence: 0.4
 * - Max missing frames tolerance: 7 consecutive frames
 * - Session failure threshold: 50% of analyzed frames
 * - Min required landmarks present: 60%
 */
internal class SquatVisibilityGate(
    val exerciseId: String = "squat",
    val landmarkVisibilityThreshold: Float = 0.4f,
    val maxMissingFrames: Int = 7,
    val sessionFailureThreshold: Float = 0.50f,
    val minRequiredLandmarksPresent: Float = 0.60f,
    val boundaryMargin: Float = 0.02f
) {

    companion object {
        val SQUAT_REQUIRED_LANDMARKS = listOf(
            PoseLandmarkType.LEFT_SHOULDER,  // 11
            PoseLandmarkType.RIGHT_SHOULDER, // 12
            PoseLandmarkType.LEFT_HIP,       // 23
            PoseLandmarkType.RIGHT_HIP,      // 24
            PoseLandmarkType.LEFT_KNEE,      // 25
            PoseLandmarkType.RIGHT_KNEE,     // 26
            PoseLandmarkType.LEFT_ANKLE,     // 27
            PoseLandmarkType.RIGHT_ANKLE     // 28
        )
    }

    private val consecutiveMissingCounts = mutableMapOf<Int, Int>()

    var totalFramesAnalyzed: Long = 0L
        private set
    var failedVisibilityFrames: Long = 0L
        private set

    fun getRequiredLandmarks(): List<Int> = SQUAT_REQUIRED_LANDMARKS

    fun checkFrame(poseResult: PoseEstimationResult): FrameVisibilityResult {
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

        val required = getRequiredLandmarks()
        val landmarkMap: Map<Int, PoseLandmark> = poseResult.landmarks.associateBy { it.index }

        // 2. Check for missing required landmarks & Out-Of-Frame boundary detection
        var presentCount = 0
        var outOfFrameDetected = false

        for (reqIdx in required) {
            val lm = landmarkMap[reqIdx]
            if (lm == null) {
                outOfFrameDetected = true
                missingIndices.add(reqIdx)
            } else {
                val isAtBoundary = (lm.x <= boundaryMargin || lm.x >= (1.0f - boundaryMargin) ||
                        lm.y <= boundaryMargin || lm.y >= (1.0f - boundaryMargin))
                if (isAtBoundary) {
                    outOfFrameDetected = true
                    missingIndices.add(reqIdx)
                } else {
                    presentCount++
                }
            }
        }

        val presentRatio = presentCount.toFloat() / required.size.toFloat()
        if (presentRatio < minRequiredLandmarksPresent || outOfFrameDetected) {
            reasons.add(VisibilityFailureReason.BODY_OUT_OF_FRAME)
        }

        // 3. Landmark visibility confidence & consecutive missing tracking
        var hasExceededConsecutiveThreshold = false

        for (reqIdx in required) {
            val lm = landmarkMap[reqIdx]
            val isVisible = lm != null && lm.visibility >= landmarkVisibilityThreshold

            if (isVisible) {
                consecutiveMissingCounts[reqIdx] = 0
            } else {
                missingIndices.add(reqIdx)
                val currentMissing = (consecutiveMissingCounts[reqIdx] ?: 0) + 1
                consecutiveMissingCounts[reqIdx] = currentMissing

                if (currentMissing > maxMissingFrames) {
                    hasExceededConsecutiveThreshold = true
                }
            }
        }

        if (hasExceededConsecutiveThreshold) {
            reasons.add(VisibilityFailureReason.LOW_CONFIDENCE)
        }

        // 4. Assemble result
        val isInsufficient = reasons.isNotEmpty()
        if (isInsufficient) {
            failedVisibilityFrames++
        }

        return FrameVisibilityResult(
            status = if (isInsufficient) VisibilityStatus.INSUFFICIENT_VISIBILITY else VisibilityStatus.SUFFICIENT_VISIBILITY,
            failureReasons = reasons.distinct(),
            missingLandmarkIndices = missingIndices,
            consecutiveMissingCounts = consecutiveMissingCounts.toMap()
        )
    }

    fun getSessionVisibilityStatus(): VisibilityStatus {
        if (totalFramesAnalyzed == 0L) return VisibilityStatus.INSUFFICIENT_VISIBILITY
        val failureRate = failedVisibilityFrames.toFloat() / totalFramesAnalyzed.toFloat()
        return if (failureRate > sessionFailureThreshold) {
            VisibilityStatus.INSUFFICIENT_VISIBILITY
        } else {
            VisibilityStatus.SUFFICIENT_VISIBILITY
        }
    }

    fun getSessionFailureRate(): Float {
        if (totalFramesAnalyzed == 0L) return 0.0f
        return failedVisibilityFrames.toFloat() / totalFramesAnalyzed.toFloat()
    }

    fun reset() {
        consecutiveMissingCounts.clear()
        totalFramesAnalyzed = 0L
        failedVisibilityFrames = 0L
    }
}
