package com.example.cvassessment.sdk.statemachine

import com.example.cvassessment.sdk.pose.PoseLandmark
import com.example.cvassessment.sdk.pose.PoseLandmarkType
import kotlin.math.abs

/**
 * Geometric calculations for Squat exercise analysis.
 *
 * Implements:
 * 1. Dual-leg angle selection: knee_angle computed from the more visible leg (or averaged when both visible).
 * 2. Hip angle (shoulder-hip-knee) for torso lean and hip hinge tracking.
 * 3. View detection: identifies side-view (profile) vs front-view based on landmark span and depth.
 * 4. Knee valgus detection: calculates medial knee displacement relative to hip-ankle vertical line.
 */
object SquatGeometry {

    data class ValgusCheckResult(
        val isValgus: Boolean,
        val deviationRatio: Float,
        val isSideViewSkipped: Boolean = false,
        val skipReason: String? = null
    )

    /**
     * Computes the knee angle (hip -> knee -> ankle) using dual-leg visibility selection.
     * Selects whichever leg (left or right) has higher combined visibility across hip-knee-ankle,
     * or averages both when both are clearly visible with similar visibility (difference <= 0.15).
     */
    fun computeKneeAngle(landmarks: List<PoseLandmark>): Float {
        if (landmarks.size <= PoseLandmarkType.RIGHT_ANKLE) return 180.0f

        val leftHip = landmarks.getOrNull(PoseLandmarkType.LEFT_HIP) ?: return 180.0f
        val leftKnee = landmarks.getOrNull(PoseLandmarkType.LEFT_KNEE) ?: return 180.0f
        val leftAnkle = landmarks.getOrNull(PoseLandmarkType.LEFT_ANKLE) ?: return 180.0f

        val rightHip = landmarks.getOrNull(PoseLandmarkType.RIGHT_HIP) ?: return 180.0f
        val rightKnee = landmarks.getOrNull(PoseLandmarkType.RIGHT_KNEE) ?: return 180.0f
        val rightAnkle = landmarks.getOrNull(PoseLandmarkType.RIGHT_ANKLE) ?: return 180.0f

        val leftVis = (leftHip.visibility + leftKnee.visibility + leftAnkle.visibility) / 3.0f
        val rightVis = (rightHip.visibility + rightKnee.visibility + rightAnkle.visibility) / 3.0f

        val leftAngle = PoseGeometry.calculateAngle3D(leftHip, leftKnee, leftAnkle)
        val rightAngle = PoseGeometry.calculateAngle3D(rightHip, rightKnee, rightAnkle)

        return when {
            abs(leftVis - rightVis) <= 0.15f && leftVis >= 0.4f && rightVis >= 0.4f -> {
                (leftAngle + rightAngle) / 2.0f
            }
            leftVis > rightVis -> leftAngle
            else -> rightAngle
        }
    }

    /**
     * Computes hip angle (shoulder -> hip -> knee) to track torso lean and hip hinge during squats.
     */
    fun computeHipAngle(landmarks: List<PoseLandmark>): Float {
        if (landmarks.size <= PoseLandmarkType.RIGHT_KNEE) return 180.0f

        val leftShoulder = landmarks.getOrNull(PoseLandmarkType.LEFT_SHOULDER) ?: return 180.0f
        val leftHip = landmarks.getOrNull(PoseLandmarkType.LEFT_HIP) ?: return 180.0f
        val leftKnee = landmarks.getOrNull(PoseLandmarkType.LEFT_KNEE) ?: return 180.0f

        val rightShoulder = landmarks.getOrNull(PoseLandmarkType.RIGHT_SHOULDER) ?: return 180.0f
        val rightHip = landmarks.getOrNull(PoseLandmarkType.RIGHT_HIP) ?: return 180.0f
        val rightKnee = landmarks.getOrNull(PoseLandmarkType.RIGHT_KNEE) ?: return 180.0f

        val leftVis = (leftShoulder.visibility + leftHip.visibility + leftKnee.visibility) / 3.0f
        val rightVis = (rightShoulder.visibility + rightHip.visibility + rightKnee.visibility) / 3.0f

        val leftAngle = PoseGeometry.calculateAngle3D(leftShoulder, leftHip, leftKnee)
        val rightAngle = PoseGeometry.calculateAngle3D(rightShoulder, rightHip, rightKnee)

        return when {
            abs(leftVis - rightVis) <= 0.15f && leftVis >= 0.4f && rightVis >= 0.4f -> {
                (leftAngle + rightAngle) / 2.0f
            }
            leftVis > rightVis -> leftAngle
            else -> rightAngle
        }
    }

    /**
     * Detects whether the camera is viewing the subject from the side (profile view).
     *
     * In profile view, the horizontal distance between left and right hips (and shoulders)
     * is heavily compressed in the camera projection (hipWidth < 0.08), or depth separation
     * between left and right is significant.
     */
    fun isSideView(landmarks: List<PoseLandmark>): Boolean {
        if (landmarks.size <= PoseLandmarkType.RIGHT_ANKLE) return true

        val leftHip = landmarks.getOrNull(PoseLandmarkType.LEFT_HIP) ?: return true
        val rightHip = landmarks.getOrNull(PoseLandmarkType.RIGHT_HIP) ?: return true
        val leftShoulder = landmarks.getOrNull(PoseLandmarkType.LEFT_SHOULDER)
        val rightShoulder = landmarks.getOrNull(PoseLandmarkType.RIGHT_SHOULDER)

        val hipWidth = abs(leftHip.x - rightHip.x)
        val shoulderWidth = if (leftShoulder != null && rightShoulder != null) {
            abs(leftShoulder.x - rightShoulder.x)
        } else {
            hipWidth
        }

        // Profile view: horizontal distance in X is compressed
        if (hipWidth < 0.08f || shoulderWidth < 0.08f) {
            return true
        }

        // Check depth difference: in profile, one side is much closer than the other
        val hipDepthDiff = abs(leftHip.z - rightHip.z)
        if (hipDepthDiff > 0.25f) {
            return true
        }

        return false
    }

    /**
     * Evaluates knee valgus (knees caving inward) during DESCENDING or BOTTOM phase.
     *
     * @param landmarks 33 BlazePose landmarks
     * @param phase Current exercise movement phase
     * @param thresholdRatio Minimum medial displacement ratio relative to hip width (default: 0.12 = 12%)
     */
    fun detectKneeValgus(
        landmarks: List<PoseLandmark>,
        phase: ExercisePhase,
        thresholdRatio: Float = 0.12f
    ): ValgusCheckResult {
        // Valgus check is only active during DESCENDING or BOTTOM phases
        if (phase != ExercisePhase.DESCENDING && phase != ExercisePhase.BOTTOM) {
            return ValgusCheckResult(
                isValgus = false,
                deviationRatio = 0.0f,
                isSideViewSkipped = false,
                skipReason = "Not in descending or bottom phase"
            )
        }

        // Profile view check: if side-view is detected, skip to avoid false positives
        if (isSideView(landmarks)) {
            return ValgusCheckResult(
                isValgus = false,
                deviationRatio = 0.0f,
                isSideViewSkipped = true,
                skipReason = "Skipping knee_valgus detection: Side-view detected (unreliable for frontal knee tracking)"
            )
        }

        val leftHip = landmarks.getOrNull(PoseLandmarkType.LEFT_HIP) ?: return ValgusCheckResult(false, 0.0f)
        val rightHip = landmarks.getOrNull(PoseLandmarkType.RIGHT_HIP) ?: return ValgusCheckResult(false, 0.0f)
        val leftKnee = landmarks.getOrNull(PoseLandmarkType.LEFT_KNEE) ?: return ValgusCheckResult(false, 0.0f)
        val rightKnee = landmarks.getOrNull(PoseLandmarkType.RIGHT_KNEE) ?: return ValgusCheckResult(false, 0.0f)
        val leftAnkle = landmarks.getOrNull(PoseLandmarkType.LEFT_ANKLE) ?: return ValgusCheckResult(false, 0.0f)
        val rightAnkle = landmarks.getOrNull(PoseLandmarkType.RIGHT_ANKLE) ?: return ValgusCheckResult(false, 0.0f)

        val hipWidth = abs(leftHip.x - rightHip.x)
        if (hipWidth < 0.02f) {
            return ValgusCheckResult(false, 0.0f, true, "Hip width too narrow for frontal valgus detection")
        }

        val xMid = (leftHip.x + rightHip.x) / 2.0f

        // Check Left Leg medial displacement
        val leftT = if (abs(leftAnkle.y - leftHip.y) > 1e-4f) {
            (leftKnee.y - leftHip.y) / (leftAnkle.y - leftHip.y)
        } else {
            0.5f
        }
        val expectedLeftKneeX = leftHip.x + leftT * (leftAnkle.x - leftHip.x)
        val leftExpectedDistToMid = abs(expectedLeftKneeX - xMid)
        val leftActualDistToMid = abs(leftKnee.x - xMid)
        val leftMedialDeviation = leftExpectedDistToMid - leftActualDistToMid
        val leftRatio = leftMedialDeviation / hipWidth

        // Check Right Leg medial displacement
        val rightT = if (abs(rightAnkle.y - rightHip.y) > 1e-4f) {
            (rightKnee.y - rightHip.y) / (rightAnkle.y - rightHip.y)
        } else {
            0.5f
        }
        val expectedRightKneeX = rightHip.x + rightT * (rightAnkle.x - rightHip.x)
        val rightExpectedDistToMid = abs(expectedRightKneeX - xMid)
        val rightActualDistToMid = abs(rightKnee.x - xMid)
        val rightMedialDeviation = rightExpectedDistToMid - rightActualDistToMid
        val rightRatio = rightMedialDeviation / hipWidth

        val maxRatio = maxOf(leftRatio, rightRatio)
        val isValgus = maxRatio >= thresholdRatio

        return ValgusCheckResult(
            isValgus = isValgus,
            deviationRatio = maxRatio,
            isSideViewSkipped = false,
            skipReason = null
        )
    }
}
