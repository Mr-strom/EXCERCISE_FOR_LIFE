package com.example.cvassessment.sdk.visibility

import com.example.cvassessment.sdk.pose.PoseEstimationResult
import com.example.cvassessment.sdk.pose.PoseLandmark
import com.example.cvassessment.sdk.pose.PoseLandmarkType
import com.example.cvassessment.sdk.statemachine.JumpingJackGeometry

/**
 * Visibility Gate for Jumping Jack per VISIBILITY_POLICY.md and EXERCISE_SPEC.md #9.
 *
 * Primary Landmarks (Bilateral required):
 * - Shoulders (11, 12)
 * - Elbows (13, 14)
 * - Wrists (15, 16)
 * - Hips (23, 24)
 * - Ankles (27, 28)
 *
 * Requirements:
 * 1. HARD FRONT VIEW REQUIREMENT:
 *    Side view (profile) collapses the coronal plane, making leg spread and bilateral arm symmetry untrackable.
 *    Non-front view immediately fails visibility.
 * 2. BILATERAL FULL-BODY VISIBILITY:
 *    Both upper limbs and both lower limbs must be visible simultaneously.
 * 3. Out-of-frame boundary detection on image edges (boundary margin 0.02).
 * 4. Session-level tracking: fails session if >50% of frames fail visibility checks (R7 refusal).
 */
internal class JumpingJackVisibilityGate(
    val exerciseId: String = "jumping_jack",
    val landmarkVisibilityThreshold: Float = 0.4f,
    val maxMissingFrames: Int = 7,
    val sessionFailureThreshold: Float = 0.50f,
    val boundaryMargin: Float = 0.02f
) {
    companion object {
        val REQUIRED_LANDMARKS = listOf(
            PoseLandmarkType.LEFT_SHOULDER,  // 11
            PoseLandmarkType.RIGHT_SHOULDER, // 12
            PoseLandmarkType.LEFT_ELBOW,     // 13
            PoseLandmarkType.RIGHT_ELBOW,    // 14
            PoseLandmarkType.LEFT_WRIST,     // 15
            PoseLandmarkType.RIGHT_WRIST,    // 16
            PoseLandmarkType.LEFT_HIP,       // 23
            PoseLandmarkType.RIGHT_HIP,      // 24
            PoseLandmarkType.LEFT_ANKLE,     // 27
            PoseLandmarkType.RIGHT_ANKLE     // 28
        )
    }

    private val consecutiveMissingCounts = mutableMapOf<Int, Int>()

    var totalFramesAnalyzed: Long = 0L
        private set
    var failedVisibilityFrames: Long = 0L
        private set

    fun getRequiredLandmarks(): List<Int> = REQUIRED_LANDMARKS

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

        // 2. Camera View Check: Front View is strictly required
        if (!JumpingJackGeometry.isFrontView(poseResult.landmarks)) {
            reasons.add(VisibilityFailureReason.LOW_CONFIDENCE)
            missingIndices.addAll(REQUIRED_LANDMARKS)
        }

        val landmarkMap: Map<Int, PoseLandmark> = poseResult.landmarks.associateBy { it.index }

        // 3. Out-of-frame boundary detection
        var outOfFrameDetected = false
        for (reqIdx in REQUIRED_LANDMARKS) {
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

        // 4. Bilateral landmark confidence check
        for (reqIdx in REQUIRED_LANDMARKS) {
            val lm = landmarkMap[reqIdx]
            if (lm == null || lm.visibility < landmarkVisibilityThreshold) {
                missingIndices.add(reqIdx)
            }
        }

        if (missingIndices.isNotEmpty() && !reasons.contains(VisibilityFailureReason.LOW_CONFIDENCE)) {
            reasons.add(VisibilityFailureReason.LOW_CONFIDENCE)
        }

        // 5. Consecutive missing frame tracking
        var exceededMissing = false
        for (reqIdx in REQUIRED_LANDMARKS) {
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
