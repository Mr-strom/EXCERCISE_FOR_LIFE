package com.example.cvassessment.sdk.visibility

import com.example.cvassessment.sdk.pose.PoseEstimationResult
import com.example.cvassessment.sdk.pose.PoseLandmark
import com.example.cvassessment.sdk.pose.PoseLandmarkType
import com.example.cvassessment.sdk.statemachine.CalfRaiseGeometry

/**
 * Visibility Gate for Calf Raise per VISIBILITY_POLICY.md, EXERCISE_SPEC.md, and DECISIONS.md D7.
 *
 * Primary Landmarks:
 * - Knees (25, 26)
 * - Ankles (27, 28)
 * - Heels (29, 30)
 * - Foot Indices (31, 32)
 *
 * Critical Constraint:
 * HARD SIDE-VIEW REQUIREMENT:
 * Heel elevation is fundamentally undetectable from front/45° view (EXERCISE_SPEC.md).
 * If side view is not detected, this gate returns INSUFFICIENT_VISIBILITY rather than attempting
 * graceful degradation.
 */
internal class CalfRaiseVisibilityGate(
    val exerciseId: String = "calf_raise",
    val landmarkVisibilityThreshold: Float = 0.4f,
    val maxMissingFrames: Int = 7,
    val sessionFailureThreshold: Float = 0.50f,
    val boundaryMargin: Float = 0.02f,
    val feetCutoffY: Float = 0.95f,
    val maxFootMissingFrames: Int = 4
) {
    companion object {
        val CALF_RAISE_REQUIRED_LANDMARKS = listOf(
            PoseLandmarkType.LEFT_KNEE,        // 25
            PoseLandmarkType.RIGHT_KNEE,       // 26
            PoseLandmarkType.LEFT_ANKLE,       // 27
            PoseLandmarkType.RIGHT_ANKLE,      // 28
            PoseLandmarkType.LEFT_HEEL,        // 29
            PoseLandmarkType.RIGHT_HEEL,       // 30
            PoseLandmarkType.LEFT_FOOT_INDEX,  // 31
            PoseLandmarkType.RIGHT_FOOT_INDEX  // 32
        )
    }

    private val consecutiveMissingCounts = mutableMapOf<Int, Int>()

    var totalFramesAnalyzed: Long = 0L
        private set
    var failedVisibilityFrames: Long = 0L
        private set

    fun getRequiredLandmarks(): List<Int> = CALF_RAISE_REQUIRED_LANDMARKS

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

        // 2. HARD SIDE-VIEW REQUIREMENT: Side view is strictly required for Calf Raise.
        // If front/45° view is detected, return INSUFFICIENT_VISIBILITY immediately.
        val isSide = CalfRaiseGeometry.isSideView(poseResult.landmarks)
        if (!isSide) {
            reasons.add(VisibilityFailureReason.LOW_CONFIDENCE)
        }

        val required = getRequiredLandmarks()
        val landmarkMap: Map<Int, PoseLandmark> = poseResult.landmarks.associateBy { it.index }

        // 3. Out-of-frame boundary detection
        var outOfFrameDetected = false
        for (reqIdx in required) {
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

        // Feet cutoff check at bottom boundary
        val leftHeel = landmarkMap[PoseLandmarkType.LEFT_HEEL]
        val rightHeel = landmarkMap[PoseLandmarkType.RIGHT_HEEL]
        val leftAnkle = landmarkMap[PoseLandmarkType.LEFT_ANKLE]
        val rightAnkle = landmarkMap[PoseLandmarkType.RIGHT_ANKLE]

        val feetCutoff = (leftHeel != null && leftHeel.y >= feetCutoffY) ||
                (rightHeel != null && rightHeel.y >= feetCutoffY) ||
                (leftAnkle != null && leftAnkle.y >= feetCutoffY) ||
                (rightAnkle != null && rightAnkle.y >= feetCutoffY)

        if (outOfFrameDetected || feetCutoff) {
            reasons.add(VisibilityFailureReason.BODY_OUT_OF_FRAME)
        }

        // 4. Consecutive missing tracking
        for (reqIdx in required) {
            val lm = landmarkMap[reqIdx]
            val isVisible = lm != null && lm.visibility >= landmarkVisibilityThreshold

            if (isVisible) {
                consecutiveMissingCounts[reqIdx] = 0
            } else {
                missingIndices.add(reqIdx)
                val currentMissing = (consecutiveMissingCounts[reqIdx] ?: 0) + 1
                consecutiveMissingCounts[reqIdx] = currentMissing
            }
        }

        // Check feet visibility: at least one ankle or heel must be visible
        val leftFootMissing = minOf(
            consecutiveMissingCounts[PoseLandmarkType.LEFT_HEEL] ?: 0,
            consecutiveMissingCounts[PoseLandmarkType.LEFT_ANKLE] ?: 0
        )
        val rightFootMissing = minOf(
            consecutiveMissingCounts[PoseLandmarkType.RIGHT_HEEL] ?: 0,
            consecutiveMissingCounts[PoseLandmarkType.RIGHT_ANKLE] ?: 0
        )

        // If both feet's ankle/heel have been missing beyond tolerance
        if (leftFootMissing > maxFootMissingFrames && rightFootMissing > maxFootMissingFrames) {
            reasons.add(VisibilityFailureReason.LOW_CONFIDENCE)
        }

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
