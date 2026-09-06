package com.example.cvassessment.sdk.statemachine

import com.example.cvassessment.sdk.pose.PoseLandmark
import com.example.cvassessment.sdk.pose.PoseLandmarkType
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Geometric calculations for Shoulder Press exercise analysis per EXERCISE_SPEC.md and FORM_RULES.md.
 *
 * Implements:
 * 1. Dual-arm independent elbow angles (shoulder -> elbow -> wrist for left and right arms).
 * 2. Shoulder elevation angle (elbow -> shoulder -> hip) for overhead range tracking.
 * 3. Overhead verification (wrists reaching above nose/head level).
 * 4. View detection: identifies side-view (profile) vs front/45° view based on shoulder width and depth.
 * 5. Torso stability & back arching: computes shoulder-hip angle deviation from vertical.
 * 6. Arm asymmetry: calculates angle discrepancy between left and right arms during pressing.
 */
object ShoulderPressGeometry {

    data class ArmAnglesResult(
        val leftElbowAngle: Float,
        val rightElbowAngle: Float,
        val leftElevationAngle: Float,
        val rightElevationAngle: Float,
        val leftVisibility: Float,
        val rightVisibility: Float,
        val isLeftArmVisible: Boolean,
        val isRightArmVisible: Boolean,
        val activeElbowAngle: Float,
        val activeElevationAngle: Float,
        val areWristsAboveHead: Boolean
    )

    /**
     * Computes independent elbow and elevation angles for both arms:
     * Elbow angles:
     * - Left: LEFT_SHOULDER (11) -> LEFT_ELBOW (13) -> LEFT_WRIST (15)
     * - Right: RIGHT_SHOULDER (12) -> RIGHT_ELBOW (14) -> RIGHT_WRIST (16)
     * Elevation angles:
     * - Left: LEFT_ELBOW (13) -> LEFT_SHOULDER (11) -> LEFT_HIP (23)
     * - Right: RIGHT_ELBOW (14) -> RIGHT_SHOULDER (12) -> RIGHT_HIP (24)
     */
    fun computeArmAngles(landmarks: List<PoseLandmark>): ArmAnglesResult {
        val leftShoulder = landmarks.getOrNull(PoseLandmarkType.LEFT_SHOULDER)
        val leftElbow = landmarks.getOrNull(PoseLandmarkType.LEFT_ELBOW)
        val leftWrist = landmarks.getOrNull(PoseLandmarkType.LEFT_WRIST)
        val leftHip = landmarks.getOrNull(PoseLandmarkType.LEFT_HIP)

        val rightShoulder = landmarks.getOrNull(PoseLandmarkType.RIGHT_SHOULDER)
        val rightElbow = landmarks.getOrNull(PoseLandmarkType.RIGHT_ELBOW)
        val rightWrist = landmarks.getOrNull(PoseLandmarkType.RIGHT_WRIST)
        val rightHip = landmarks.getOrNull(PoseLandmarkType.RIGHT_HIP)

        val nose = landmarks.getOrNull(PoseLandmarkType.NOSE)

        val leftVis = if (leftShoulder != null && leftElbow != null && leftWrist != null) {
            (leftShoulder.visibility + leftElbow.visibility + leftWrist.visibility) / 3.0f
        } else 0.0f

        val rightVis = if (rightShoulder != null && rightElbow != null && rightWrist != null) {
            (rightShoulder.visibility + rightElbow.visibility + rightWrist.visibility) / 3.0f
        } else 0.0f

        val isLeftVisible = leftVis >= 0.4f && leftShoulder != null && leftElbow != null && leftWrist != null
        val isRightVisible = rightVis >= 0.4f && rightShoulder != null && rightElbow != null && rightWrist != null

        val leftElbowAngle = if (isLeftVisible) {
            PoseGeometry.calculateAngle3D(leftShoulder!!, leftElbow!!, leftWrist!!)
        } else 90.0f

        val rightElbowAngle = if (isRightVisible) {
            PoseGeometry.calculateAngle3D(rightShoulder!!, rightElbow!!, rightWrist!!)
        } else 90.0f

        val leftElevationAngle = if (isLeftVisible && leftHip != null) {
            PoseGeometry.calculateAngle3D(leftElbow!!, leftShoulder!!, leftHip)
        } else 90.0f

        val rightElevationAngle = if (isRightVisible && rightHip != null) {
            PoseGeometry.calculateAngle3D(rightElbow!!, rightShoulder!!, rightHip)
        } else 90.0f

        val activeElbow = when {
            isLeftVisible && isRightVisible -> {
                // If one arm is pressing higher (greater extension angle) while other is resting, pick pressing arm
                if (leftElbowAngle > 110.0f && rightElbowAngle <= 100.0f) leftElbowAngle
                else if (rightElbowAngle > 110.0f && leftElbowAngle <= 100.0f) rightElbowAngle
                else (leftElbowAngle + rightElbowAngle) / 2.0f
            }
            isLeftVisible -> leftElbowAngle
            isRightVisible -> rightElbowAngle
            else -> 90.0f
        }

        val activeElevation = when {
            isLeftVisible && isRightVisible -> (leftElevationAngle + rightElevationAngle) / 2.0f
            isLeftVisible -> leftElevationAngle
            isRightVisible -> rightElevationAngle
            else -> 90.0f
        }

        val wristsAboveHead = if (nose != null) {
            val leftAbove = leftWrist != null && (leftWrist.y < nose.y)
            val rightAbove = rightWrist != null && (rightWrist.y < nose.y)
            leftAbove || rightAbove
        } else {
            activeElevation >= 150.0f
        }

        return ArmAnglesResult(
            leftElbowAngle = leftElbowAngle,
            rightElbowAngle = rightElbowAngle,
            leftElevationAngle = leftElevationAngle,
            rightElevationAngle = rightElevationAngle,
            leftVisibility = leftVis,
            rightVisibility = rightVis,
            isLeftArmVisible = isLeftVisible,
            isRightArmVisible = isRightVisible,
            activeElbowAngle = activeElbow,
            activeElevationAngle = activeElevation,
            areWristsAboveHead = wristsAboveHead
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

        val leftElbow = landmarks.getOrNull(PoseLandmarkType.LEFT_ELBOW)
        val rightElbow = landmarks.getOrNull(PoseLandmarkType.RIGHT_ELBOW)
        val leftVis = leftElbow?.visibility ?: 0.0f
        val rightVis = rightElbow?.visibility ?: 0.0f

        return (leftVis < 0.35f && rightVis >= 0.5f) || (rightVis < 0.35f && leftVis >= 0.5f)
    }

    /**
     * Computes torso vertical deviation angle (hip-to-shoulder vertical line) in degrees.
     * 0° indicates perfectly vertical torso.
     * Used for detecting body swinging (excessive_momentum) and hyperextension (back_arching).
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
     * Computes ROM percentage for a completed shoulder press rep:
     * - startingAngle = 90.0° (rack position at shoulders)
     * - fullExpectedAngle = 155.0° (extended overhead lockout per D12 calibration)
     * - 100% = reaching >= 155.0°
     */
    fun computeRomPercent(maxElbowAngle: Float): Float {
        val starting = 90.0f
        val target = 155.0f
        val rom = ((maxElbowAngle - starting) / (target - starting)) * 100.0f
        return rom.coerceIn(0.0f, 100.0f)
    }

    /**
     * Computes ROM percentage based on shoulder elevation angle:
     * - startingAngle = 80.0°
     * - fullExpectedAngle = 170.0°
     */
    fun computeElevationRomPercent(maxElevationAngle: Float): Float {
        val starting = 80.0f
        val target = 170.0f
        val rom = ((maxElevationAngle - starting) / (target - starting)) * 100.0f
        return rom.coerceIn(0.0f, 100.0f)
    }
}
