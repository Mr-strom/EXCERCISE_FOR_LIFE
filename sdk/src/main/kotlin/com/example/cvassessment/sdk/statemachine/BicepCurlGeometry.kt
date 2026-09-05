package com.example.cvassessment.sdk.statemachine

import com.example.cvassessment.sdk.pose.PoseLandmark
import com.example.cvassessment.sdk.pose.PoseLandmarkType
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Geometric calculations for Bicep Curl exercise analysis per EXERCISE_SPEC.md and FORM_RULES.md.
 *
 * Implements:
 * 1. Dual-arm independent elbow angles (shoulder -> elbow -> wrist for left and right arms).
 * 2. View detection: identifies side-view (profile) vs front/45° view based on shoulder width and depth.
 * 3. Shoulder stability: computes shoulder-hip vertical deviation/displacement to detect body swinging/momentum.
 * 4. Back arching: computes hip-shoulder angle deviation from vertical.
 * 5. Arm asymmetry: calculates angle discrepancy between left and right arms during synchronized phases.
 */
object BicepCurlGeometry {

    data class ArmAnglesResult(
        val leftElbowAngle: Float,
        val rightElbowAngle: Float,
        val leftVisibility: Float,
        val rightVisibility: Float,
        val isLeftArmVisible: Boolean,
        val isRightArmVisible: Boolean,
        val activeElbowAngle: Float // Representative angle: single arm, or active/moving arm, or average
    )

    /**
     * Computes independent elbow angles for both arms:
     * Left arm: LEFT_SHOULDER (11) -> LEFT_ELBOW (13) -> LEFT_WRIST (15)
     * Right arm: RIGHT_SHOULDER (12) -> RIGHT_ELBOW (14) -> RIGHT_WRIST (16)
     */
    fun computeElbowAngles(landmarks: List<PoseLandmark>): ArmAnglesResult {
        val leftShoulder = landmarks.getOrNull(PoseLandmarkType.LEFT_SHOULDER)
        val leftElbow = landmarks.getOrNull(PoseLandmarkType.LEFT_ELBOW)
        val leftWrist = landmarks.getOrNull(PoseLandmarkType.LEFT_WRIST)

        val rightShoulder = landmarks.getOrNull(PoseLandmarkType.RIGHT_SHOULDER)
        val rightElbow = landmarks.getOrNull(PoseLandmarkType.RIGHT_ELBOW)
        val rightWrist = landmarks.getOrNull(PoseLandmarkType.RIGHT_WRIST)

        val leftVis = if (leftShoulder != null && leftElbow != null && leftWrist != null) {
            (leftShoulder.visibility + leftElbow.visibility + leftWrist.visibility) / 3.0f
        } else 0.0f

        val rightVis = if (rightShoulder != null && rightElbow != null && rightWrist != null) {
            (rightShoulder.visibility + rightElbow.visibility + rightWrist.visibility) / 3.0f
        } else 0.0f

        val isLeftVisible = leftVis >= 0.4f && leftShoulder != null && leftElbow != null && leftWrist != null
        val isRightVisible = rightVis >= 0.4f && rightShoulder != null && rightElbow != null && rightWrist != null

        val leftAngle = if (isLeftVisible) {
            PoseGeometry.calculateAngle3D(leftShoulder!!, leftElbow!!, leftWrist!!)
        } else 180.0f

        val rightAngle = if (isRightVisible) {
            PoseGeometry.calculateAngle3D(rightShoulder!!, rightElbow!!, rightWrist!!)
        } else 180.0f

        val activeAngle = when {
            isLeftVisible && isRightVisible -> {
                // If both visible: if one is actively curling (angle < 150) and other is resting (> 150), pick curling arm
                if (leftAngle < 150.0f && rightAngle >= 150.0f) leftAngle
                else if (rightAngle < 150.0f && leftAngle >= 150.0f) rightAngle
                else (leftAngle + rightAngle) / 2.0f
            }
            isLeftVisible -> leftAngle
            isRightVisible -> rightAngle
            else -> 180.0f
        }

        return ArmAnglesResult(
            leftElbowAngle = leftAngle,
            rightElbowAngle = rightAngle,
            leftVisibility = leftVis,
            rightVisibility = rightVis,
            isLeftArmVisible = isLeftVisible,
            isRightArmVisible = isRightVisible,
            activeElbowAngle = activeAngle
        )
    }

    /**
     * Detects whether the user is positioned in side/profile view.
     * In side view, shoulder width is compressed (|x_left - x_right| < 0.08) or
     * only one arm has sufficient visibility confidence.
     */
    fun isSideView(landmarks: List<PoseLandmark>): Boolean {
        val leftShoulder = landmarks.getOrNull(PoseLandmarkType.LEFT_SHOULDER)
        val rightShoulder = landmarks.getOrNull(PoseLandmarkType.RIGHT_SHOULDER)
        if (leftShoulder == null || rightShoulder == null) return true

        val shoulderWidth = abs(leftShoulder.x - rightShoulder.x)
        if (shoulderWidth < 0.08f) return true

        val shoulderDepth = abs(leftShoulder.z - rightShoulder.z)
        if (shoulderDepth > 0.25f) return true

        // If one arm is clearly occluded / low confidence while the other is visible
        val leftWrist = landmarks.getOrNull(PoseLandmarkType.LEFT_WRIST)
        val rightWrist = landmarks.getOrNull(PoseLandmarkType.RIGHT_WRIST)
        val leftElbow = landmarks.getOrNull(PoseLandmarkType.LEFT_ELBOW)
        val rightElbow = landmarks.getOrNull(PoseLandmarkType.RIGHT_ELBOW)

        val leftArmVis = if (leftElbow != null && leftWrist != null) (leftElbow.visibility + leftWrist.visibility) / 2f else 0f
        val rightArmVis = if (rightElbow != null && rightWrist != null) (rightElbow.visibility + rightWrist.visibility) / 2f else 0f

        if ((leftArmVis < 0.35f && rightArmVis >= 0.5f) || (rightArmVis < 0.35f && leftArmVis >= 0.5f)) {
            return true
        }

        return false
    }

    /**
     * Computes torso / spine line angle relative to true vertical (in degrees, 0° = perfectly vertical).
     * Used for shoulder stability (excessive_momentum) and lower-back arching (back_arching).
     */
    fun computeTorsoVerticalAngle(landmarks: List<PoseLandmark>): Float {
        val leftShoulder = landmarks.getOrNull(PoseLandmarkType.LEFT_SHOULDER)
        val rightShoulder = landmarks.getOrNull(PoseLandmarkType.RIGHT_SHOULDER)
        val leftHip = landmarks.getOrNull(PoseLandmarkType.LEFT_HIP)
        val rightHip = landmarks.getOrNull(PoseLandmarkType.RIGHT_HIP)

        val shoulderX = when {
            leftShoulder != null && rightShoulder != null -> (leftShoulder.x + rightShoulder.x) / 2.0f
            leftShoulder != null -> leftShoulder.x
            rightShoulder != null -> rightShoulder.x
            else -> return 0.0f
        }
        val shoulderY = when {
            leftShoulder != null && rightShoulder != null -> (leftShoulder.y + rightShoulder.y) / 2.0f
            leftShoulder != null -> leftShoulder.y
            rightShoulder != null -> rightShoulder.y
            else -> return 0.0f
        }

        val hipX = when {
            leftHip != null && rightHip != null -> (leftHip.x + rightHip.x) / 2.0f
            leftHip != null -> leftHip.x
            rightHip != null -> rightHip.x
            else -> return 0.0f
        }
        val hipY = when {
            leftHip != null && rightHip != null -> (leftHip.y + rightHip.y) / 2.0f
            leftHip != null -> leftHip.y
            rightHip != null -> rightHip.y
            else -> return 0.0f
        }

        val dx = shoulderX - hipX
        val dy = hipY - shoulderY // positive upwards

        val angleRad = atan2(abs(dx), abs(dy))
        return Math.toDegrees(angleRad.toDouble()).toFloat()
    }

    /**
     * Computes ROM percentage for a completed bicep curl rep:
     * - startingAngle = 160.0° (arms extended down at bottom)
     * - fullExpectedAngle = 45.0° (curled up to top)
     * - 100% = reaching <= 45.0°
     */
    fun computeRomPercent(minElbowAngle: Float): Float {
        val starting = 160.0f
        val target = 45.0f
        val rom = ((starting - minElbowAngle) / (starting - target)) * 100.0f
        return rom.coerceIn(0.0f, 100.0f)
    }
}
