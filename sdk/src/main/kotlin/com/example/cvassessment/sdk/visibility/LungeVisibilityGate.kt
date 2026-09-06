package com.example.cvassessment.sdk.visibility

import com.example.cvassessment.sdk.pose.PoseEstimationResult
import com.example.cvassessment.sdk.pose.PoseLandmark
import com.example.cvassessment.sdk.pose.PoseLandmarkType

/**
 * Visibility Gate for Lunge per VISIBILITY_POLICY.md, EXERCISE_SPEC.md, and D10.
 *
 * Primary Landmarks:
 * - Hips (23, 24)
 * - Knees (25, 26)
 * - Ankles (27, 28)
 * - Shoulders (11, 12)
 *
 * Enforces:
 * - Minimum landmark confidence: 0.4 (per D10)
 * - Max missing frames tolerance: 7 consecutive frames (per D10)
 * - Session failure threshold: 50% of analyzed frames
 * - Boundary margin detection: 0.02
 */
internal class LungeVisibilityGate(
    val exerciseId: String = "lunge",
    val landmarkVisibilityThreshold: Float = 0.4f,
    val maxMissingFrames: Int = 7,
    val sessionFailureThreshold: Float = 0.50f,
    val boundaryMargin: Float = 0.02f,
    val ankleCutoffY: Float = 0.92f,
    val maxAnkleMissingFrames: Int = 3
) {
    companion object {
        val LUNGE_REQUIRED_LANDMARKS = listOf(
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

    var isDebugLoggingEnabled: Boolean = false
    var debugLogger: ((String) -> Unit)? = null

    private fun logDebug(msg: String) {
        if (!isDebugLoggingEnabled) return
        debugLogger?.invoke(msg)
        try {
            android.util.Log.d("LungeVisibilityGate", msg)
        } catch (_: Throwable) {
        }
    }

    var totalFramesAnalyzed: Long = 0L
        private set
    var failedVisibilityFrames: Long = 0L
        private set

    fun getRequiredLandmarks(): List<Int> = LUNGE_REQUIRED_LANDMARKS

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

        // 2. Out-of-frame boundary detection
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

        // Check if at least one leg (hip + knee + ankle) is present
        val leftLegPresent = landmarkMap[PoseLandmarkType.LEFT_HIP] != null &&
                landmarkMap[PoseLandmarkType.LEFT_KNEE] != null &&
                landmarkMap[PoseLandmarkType.LEFT_ANKLE] != null
        val rightLegPresent = landmarkMap[PoseLandmarkType.RIGHT_HIP] != null &&
                landmarkMap[PoseLandmarkType.RIGHT_KNEE] != null &&
                landmarkMap[PoseLandmarkType.RIGHT_ANKLE] != null

        // D13: Ankle cutoff check at bottom boundary (e.g. feet cut off during backward step)
        val leftAnkle = landmarkMap[PoseLandmarkType.LEFT_ANKLE]
        val rightAnkle = landmarkMap[PoseLandmarkType.RIGHT_ANKLE]
        val ankleOutOfFrame = (leftAnkle != null && leftAnkle.y >= ankleCutoffY) ||
                (rightAnkle != null && rightAnkle.y >= ankleCutoffY)

        if ((!leftLegPresent && !rightLegPresent) || outOfFrameDetected || ankleOutOfFrame) {
            reasons.add(VisibilityFailureReason.BODY_OUT_OF_FRAME)
        }

        // 3. Consecutive missing tracking
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

        // In side view, far leg landmark occlusions are tolerated with 7-frame grace period
        val leftLegMissingCount = maxOf(
            consecutiveMissingCounts[PoseLandmarkType.LEFT_HIP] ?: 0,
            consecutiveMissingCounts[PoseLandmarkType.LEFT_KNEE] ?: 0,
            consecutiveMissingCounts[PoseLandmarkType.LEFT_ANKLE] ?: 0
        )
        val rightLegMissingCount = maxOf(
            consecutiveMissingCounts[PoseLandmarkType.RIGHT_HIP] ?: 0,
            consecutiveMissingCounts[PoseLandmarkType.RIGHT_KNEE] ?: 0,
            consecutiveMissingCounts[PoseLandmarkType.RIGHT_ANKLE] ?: 0
        )

        // Low confidence if BOTH legs have exceeded consecutive missing threshold
        if (leftLegMissingCount > maxMissingFrames && rightLegMissingCount > maxMissingFrames) {
            reasons.add(VisibilityFailureReason.LOW_CONFIDENCE)
        }

        // D13: Strict ankle missing check — if either ankle is missing/occluded > maxAnkleMissingFrames (3 frames)
        val leftAnkleMissing = consecutiveMissingCounts[PoseLandmarkType.LEFT_ANKLE] ?: 0
        val rightAnkleMissing = consecutiveMissingCounts[PoseLandmarkType.RIGHT_ANKLE] ?: 0
        if (leftAnkleMissing > maxAnkleMissingFrames || rightAnkleMissing > maxAnkleMissingFrames) {
            reasons.add(VisibilityFailureReason.LOW_CONFIDENCE)
        }

        val isInsufficient = reasons.isNotEmpty()
        if (isInsufficient) {
            failedVisibilityFrames++
        }

        val result = FrameVisibilityResult(
            status = if (isInsufficient) VisibilityStatus.INSUFFICIENT_VISIBILITY else VisibilityStatus.SUFFICIENT_VISIBILITY,
            failureReasons = reasons.distinct(),
            missingLandmarkIndices = missingIndices,
            consecutiveMissingCounts = consecutiveMissingCounts.toMap()
        )

        val lY = leftAnkle?.y?.let { "%.2f".format(it) } ?: "null"
        val lConf = leftAnkle?.visibility?.let { "%.2f".format(it) } ?: "null"
        val rY = rightAnkle?.y?.let { "%.2f".format(it) } ?: "null"
        val rConf = rightAnkle?.visibility?.let { "%.2f".format(it) } ?: "null"

        logDebug("LUNGE_VIS_GATE: t=${poseResult.timestampMs}ms | status=${result.status} | reasons=${result.failureReasons} | L_ankle(y=$lY, conf=$lConf) | R_ankle(y=$rY, conf=$rConf)")

        return result
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
