package com.example.cvassessment.sdk.visibility

import com.example.cvassessment.sdk.pose.PoseEstimationResult
import com.example.cvassessment.sdk.pose.PoseLandmark
import com.example.cvassessment.sdk.pose.PoseLandmarkType
import com.example.cvassessment.sdk.statemachine.MountainClimberGeometry

/**
 * Visibility gate for Mountain Climber exercise analysis per EXERCISE_SPEC.md #10
 * and R7 refusal rules.
 *
 * Implements:
 * 1. Landmark presence & confidence check for plank base:
 *    - Shoulders (11, 12), Wrists (15, 16), Hips (23, 24), Knees (25, 26), Ankles (27, 28).
 *    - At least one full side (shoulder, wrist, hip, knee, ankle) must have confidence >= 0.4.
 * 2. Side-view (profile) verification:
 *    - Profile view is required to assess knee drive excursion and sagittal plank alignment.
 * 3. Boundary check with 0.02 margin:
 *    - Landmarks must not be clipped near frame edges.
 * 4. Session-level tracking: fails session if >50% of frames fail visibility checks (R7 refusal).
 */
internal class MountainClimberVisibilityGate(
    val exerciseId: String = "mountain_climber",
    val landmarkVisibilityThreshold: Float = 0.4f,
    val maxMissingFrames: Int = 7,
    val sessionFailureThreshold: Float = 0.50f,
    val boundaryMargin: Float = 0.02f
) {
    companion object {
        val REQUIRED_LANDMARKS = listOf(
            PoseLandmarkType.LEFT_SHOULDER,  // 11
            PoseLandmarkType.RIGHT_SHOULDER, // 12
            PoseLandmarkType.LEFT_WRIST,     // 15
            PoseLandmarkType.RIGHT_WRIST,    // 16
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

    fun getRequiredLandmarks(): List<Int> = REQUIRED_LANDMARKS

    fun checkFrame(poseResult: PoseEstimationResult): FrameVisibilityResult {
        totalFramesAnalyzed++
        val reasons = mutableListOf<VisibilityFailureReason>()
        val missingIndices = mutableSetOf<Int>()

        if (!poseResult.hasPose || poseResult.landmarks.isEmpty()) {
            failedVisibilityFrames++
            return FrameVisibilityResult(
                status = VisibilityStatus.INSUFFICIENT_VISIBILITY,
                failureReasons = listOf(VisibilityFailureReason.NO_POSE_DETECTED, VisibilityFailureReason.BODY_OUT_OF_FRAME),
                missingLandmarkIndices = REQUIRED_LANDMARKS.toSet()
            )
        }

        val landmarks = poseResult.landmarks

        // 1. Check side-view orientation
        val isSideView = MountainClimberGeometry.isSideView(landmarks)
        if (!isSideView) {
            reasons.add(VisibilityFailureReason.LOW_CONFIDENCE)
            missingIndices.addAll(REQUIRED_LANDMARKS)
        }

        // 2. Check at least one complete side has sufficient confidence
        val leftShoulder = landmarks.getOrNull(PoseLandmarkType.LEFT_SHOULDER)
        val leftWrist = landmarks.getOrNull(PoseLandmarkType.LEFT_WRIST)
        val leftHip = landmarks.getOrNull(PoseLandmarkType.LEFT_HIP)
        val leftKnee = landmarks.getOrNull(PoseLandmarkType.LEFT_KNEE)
        val leftAnkle = landmarks.getOrNull(PoseLandmarkType.LEFT_ANKLE)

        val rightShoulder = landmarks.getOrNull(PoseLandmarkType.RIGHT_SHOULDER)
        val rightWrist = landmarks.getOrNull(PoseLandmarkType.RIGHT_WRIST)
        val rightHip = landmarks.getOrNull(PoseLandmarkType.RIGHT_HIP)
        val rightKnee = landmarks.getOrNull(PoseLandmarkType.RIGHT_KNEE)
        val rightAnkle = landmarks.getOrNull(PoseLandmarkType.RIGHT_ANKLE)

        val leftSideValid = leftShoulder != null && leftShoulder.visibility >= landmarkVisibilityThreshold &&
                leftWrist != null && leftWrist.visibility >= landmarkVisibilityThreshold &&
                leftHip != null && leftHip.visibility >= landmarkVisibilityThreshold &&
                leftKnee != null && leftKnee.visibility >= landmarkVisibilityThreshold &&
                leftAnkle != null && leftAnkle.visibility >= landmarkVisibilityThreshold

        val rightSideValid = rightShoulder != null && rightShoulder.visibility >= landmarkVisibilityThreshold &&
                rightWrist != null && rightWrist.visibility >= landmarkVisibilityThreshold &&
                rightHip != null && rightHip.visibility >= landmarkVisibilityThreshold &&
                rightKnee != null && rightKnee.visibility >= landmarkVisibilityThreshold &&
                rightAnkle != null && rightAnkle.visibility >= landmarkVisibilityThreshold

        if (!leftSideValid && !rightSideValid) {
            reasons.add(VisibilityFailureReason.LOW_CONFIDENCE)
            for (idx in REQUIRED_LANDMARKS) {
                val lm = landmarks.getOrNull(idx)
                if (lm == null || lm.visibility < landmarkVisibilityThreshold) {
                    missingIndices.add(idx)
                }
            }
        }

        // 3. Boundary check on the valid side
        val keyLandmarks = if (leftSideValid) {
            listOfNotNull(leftShoulder, leftWrist, leftHip, leftKnee, leftAnkle)
        } else {
            listOfNotNull(rightShoulder, rightWrist, rightHip, rightKnee, rightAnkle)
        }

        for (lm in keyLandmarks) {
            if (isOutOfBounds(lm)) {
                reasons.add(VisibilityFailureReason.BODY_OUT_OF_FRAME)
                missingIndices.add(lm.index)
            }
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

    private fun isOutOfBounds(landmark: PoseLandmark): Boolean {
        return landmark.x < boundaryMargin || landmark.x > (1.0f - boundaryMargin) ||
                landmark.y < boundaryMargin || landmark.y > (1.0f - boundaryMargin)
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
