package com.example.cvassessment.sdk.visibility

import com.example.cvassessment.sdk.pose.PoseEstimationResult
import com.example.cvassessment.sdk.pose.PoseLandmark
import com.example.cvassessment.sdk.pose.PoseLandmarkType

/**
 * Module 3: Visibility Gate.
 * Enforces R7 (Refusal-to-Output Rules) per VISIBILITY_POLICY.md.
 *
 * CRITICAL RULE: Downstream modules (State Machine, Metrics, Form Rules) must REFUSE
 * to compute or emit any metrics whenever this gate outputs INSUFFICIENT_VISIBILITY.
 */
internal class VisibilityGate(
    val exerciseId: String = "push_up",
    val landmarkVisibilityThreshold: Float = 0.4f,
    val maxMissingFrames: Int = 7,
    val sessionFailureThreshold: Float = 0.50f,
    val minRequiredLandmarksPresent: Float = 0.60f,
    val boundaryMargin: Float = 0.02f
) {

    companion object {
        // Push-Up required landmarks per EXERCISE_SPEC.md: shoulders, elbows, wrists, hips, ankles
        val PUSH_UP_REQUIRED_LANDMARKS = listOf(
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

    // Map of required landmark index to consecutive frame count below visibility threshold
    private val consecutiveMissingCounts = mutableMapOf<Int, Int>()

    // Session-level tracking
    var totalFramesAnalyzed: Long = 0L
        private set
    var failedVisibilityFrames: Long = 0L
        private set

    /**
     * Get the required landmarks list for the configured exercise.
     */
    fun getRequiredLandmarks(): List<Int> {
        return when (exerciseId.trim().lowercase()) {
            "push_up" -> PUSH_UP_REQUIRED_LANDMARKS
            else -> PUSH_UP_REQUIRED_LANDMARKS
        }
    }

    /**
     * Evaluates a single frame from the PoseEstimator.
     *
     * @param poseResult PoseEstimationResult containing 33 landmarks and metadata
     * @return FrameVisibilityResult indicating SUFFICIENT_VISIBILITY or INSUFFICIENT_VISIBILITY with reasons
     */
    fun checkFrame(poseResult: PoseEstimationResult): FrameVisibilityResult {
        totalFramesAnalyzed++
        val reasons = mutableListOf<VisibilityFailureReason>()
        val missingIndices = mutableSetOf<Int>()

        // 1. No person detected in frame
        if (!poseResult.hasPose || poseResult.landmarks.isEmpty()) {
            failedVisibilityFrames++
            return FrameVisibilityResult(
                status = VisibilityStatus.INSUFFICIENT_VISIBILITY,
                failureReasons = listOf(VisibilityFailureReason.NO_POSE_DETECTED, VisibilityFailureReason.BODY_OUT_OF_FRAME)
            )
        }

        val required = getRequiredLandmarks()
        val landmarkMap: Map<Int, PoseLandmark> = poseResult.landmarks.associateBy { it.index }

        // 2. Check for missing required landmarks & Out-Of-Frame detection
        var presentCount = 0
        var outOfFrameDetected = false

        for (reqIdx in required) {
            val lm = landmarkMap[reqIdx]
            if (lm == null) {
                outOfFrameDetected = true
                missingIndices.add(reqIdx)
            } else {
                // Check if landmark coordinates are at or outside the camera frame boundary
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

        // Rule: If fewer than 60% of required landmarks are present at all -> immediate failure
        val presentRatio = presentCount.toFloat() / required.size.toFloat()
        if (presentRatio < minRequiredLandmarksPresent || outOfFrameDetected) {
            reasons.add(VisibilityFailureReason.BODY_OUT_OF_FRAME)
        }

        // 3. Landmark visibility confidence & consecutive missing frame tracking
        var hasExceededConsecutiveThreshold = false

        for (reqIdx in required) {
            val lm = landmarkMap[reqIdx]
            val isVisible = lm != null && lm.visibility >= landmarkVisibilityThreshold

            if (isVisible) {
                // Reset consecutive missing counter when visibility is restored
                consecutiveMissingCounts[reqIdx] = 0
            } else {
                missingIndices.add(reqIdx)
                val currentMissing = (consecutiveMissingCounts[reqIdx] ?: 0) + 1
                consecutiveMissingCounts[reqIdx] = currentMissing

                // If any required landmark falls below threshold for more than MAX_MISSING_FRAMES consecutive frames
                if (currentMissing > maxMissingFrames) {
                    hasExceededConsecutiveThreshold = true
                }
            }
        }

        if (hasExceededConsecutiveThreshold) {
            reasons.add(VisibilityFailureReason.LOW_CONFIDENCE)
        }

        // 4. Assemble final frame visibility result
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

    /**
     * Computes the aggregated session-level visibility status.
     * Per VISIBILITY_POLICY.md: if visibility fails for >50% of the session,
     * the overall session status is INSUFFICIENT_VISIBILITY.
     */
    fun getSessionVisibilityStatus(): VisibilityStatus {
        if (totalFramesAnalyzed == 0L) return VisibilityStatus.INSUFFICIENT_VISIBILITY
        val failureRate = failedVisibilityFrames.toFloat() / totalFramesAnalyzed.toFloat()
        return if (failureRate > sessionFailureThreshold) {
            VisibilityStatus.INSUFFICIENT_VISIBILITY
        } else {
            VisibilityStatus.SUFFICIENT_VISIBILITY
        }
    }

    /**
     * Current percentage of frames that failed visibility (0.0..1.0).
     */
    fun getSessionFailureRate(): Float {
        if (totalFramesAnalyzed == 0L) return 0.0f
        return failedVisibilityFrames.toFloat() / totalFramesAnalyzed.toFloat()
    }

    /**
     * Reset state for a new exercise session.
     */
    fun reset() {
        consecutiveMissingCounts.clear()
        totalFramesAnalyzed = 0L
        failedVisibilityFrames = 0L
    }
}
