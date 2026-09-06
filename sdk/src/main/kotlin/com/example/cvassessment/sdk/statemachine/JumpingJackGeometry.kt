package com.example.cvassessment.sdk.statemachine

import com.example.cvassessment.sdk.pose.PoseLandmark
import com.example.cvassessment.sdk.pose.PoseLandmarkType
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Geometric calculations for Jumping Jack exercise analysis per EXERCISE_SPEC.md #9,
 * METRICS_SPEC.md, and FORM_RULES.md.
 *
 * Implements:
 * 1. Arm abduction angle: bilateral average of elbow-shoulder-hip angles (flexion/abduction overhead).
 * 2. Leg spread angle: interior angle between left and right ankle vectors from hip center.
 * 3. Combined two-limb-group ROM%: MINIMUM of arm ROM% and leg ROM%, ensuring full range requires
 *    simultaneous overhead arm reach AND wide leg spread.
 * 4. Front-view detection: verifies coronal plane alignment (rejects side profile).
 */
object JumpingJackGeometry {

    const val ARM_CLOSED_BASELINE = 30.0f
    const val ARM_OPEN_TARGET = 150.0f

    const val LEG_CLOSED_BASELINE = 12.0f
    const val LEG_OPEN_TARGET = 45.0f

    /**
     * Checks if the athlete is facing the camera in front view.
     * In side view (profile), shoulder width and hip width collapse horizontally.
     */
    fun isFrontView(landmarks: List<PoseLandmark>): Boolean {
        val leftShoulder = landmarks.getOrNull(PoseLandmarkType.LEFT_SHOULDER)
        val rightShoulder = landmarks.getOrNull(PoseLandmarkType.RIGHT_SHOULDER)
        val leftHip = landmarks.getOrNull(PoseLandmarkType.LEFT_HIP)
        val rightHip = landmarks.getOrNull(PoseLandmarkType.RIGHT_HIP)

        if (leftShoulder != null && rightShoulder != null) {
            val shoulderWidth = abs(leftShoulder.x - rightShoulder.x)
            if (shoulderWidth < 0.11f) return false
        }

        if (leftHip != null && rightHip != null) {
            val hipWidth = abs(leftHip.x - rightHip.x)
            if (hipWidth < 0.08f) return false
        }

        return true
    }

    /**
     * Computes the arm abduction angle (elbow-shoulder-hip).
     * Returns bilateral average of left and right arms, or single arm if visibility differs.
     */
    fun computeArmAbductionAngle(landmarks: List<PoseLandmark>): Float {
        val leftAngle = computeSingleArmAbduction(
            shoulder = landmarks.getOrNull(PoseLandmarkType.LEFT_SHOULDER),
            elbow = landmarks.getOrNull(PoseLandmarkType.LEFT_ELBOW),
            hip = landmarks.getOrNull(PoseLandmarkType.LEFT_HIP)
        )

        val rightAngle = computeSingleArmAbduction(
            shoulder = landmarks.getOrNull(PoseLandmarkType.RIGHT_SHOULDER),
            elbow = landmarks.getOrNull(PoseLandmarkType.RIGHT_ELBOW),
            hip = landmarks.getOrNull(PoseLandmarkType.RIGHT_HIP)
        )

        return when {
            leftAngle != null && rightAngle != null -> (leftAngle + rightAngle) / 2.0f
            leftAngle != null -> leftAngle
            rightAngle != null -> rightAngle
            else -> ARM_CLOSED_BASELINE
        }
    }

    /**
     * Computes angle formed by elbow-shoulder-hip with vertex at shoulder.
     */
    fun computeSingleArmAbduction(
        shoulder: PoseLandmark?,
        elbow: PoseLandmark?,
        hip: PoseLandmark?
    ): Float? {
        if (shoulder == null || elbow == null || hip == null) return null

        val v1x = elbow.x - shoulder.x
        val v1y = elbow.y - shoulder.y

        val v2x = hip.x - shoulder.x
        val v2y = hip.y - shoulder.y

        val dot = (v1x * v2x) + (v1y * v2y)
        val mag1 = sqrt((v1x * v1x) + (v1y * v1y))
        val mag2 = sqrt((v2x * v2x) + (v2y * v2y))

        if (mag1 < 1e-6f || mag2 < 1e-6f) return null

        val cosTheta = (dot / (mag1 * mag2)).coerceIn(-1.0f, 1.0f)
        return Math.toDegrees(acos(cosTheta.toDouble())).toFloat()
    }

    /**
     * Computes leg spread angle (ankle-hipCenter-ankle) with vertex at hip center.
     */
    fun computeLegSpreadAngle(landmarks: List<PoseLandmark>): Float {
        val leftHip = landmarks.getOrNull(PoseLandmarkType.LEFT_HIP)
        val rightHip = landmarks.getOrNull(PoseLandmarkType.RIGHT_HIP)
        val leftAnkle = landmarks.getOrNull(PoseLandmarkType.LEFT_ANKLE)
        val rightAnkle = landmarks.getOrNull(PoseLandmarkType.RIGHT_ANKLE)

        if (leftAnkle == null || rightAnkle == null) return LEG_CLOSED_BASELINE

        val hipCenterX = if (leftHip != null && rightHip != null) {
            (leftHip.x + rightHip.x) / 2.0f
        } else {
            leftHip?.x ?: rightHip?.x ?: 0.5f
        }

        val hipCenterY = if (leftHip != null && rightHip != null) {
            (leftHip.y + rightHip.y) / 2.0f
        } else {
            leftHip?.y ?: rightHip?.y ?: 0.5f
        }

        val v1x = leftAnkle.x - hipCenterX
        val v1y = leftAnkle.y - hipCenterY

        val v2x = rightAnkle.x - hipCenterX
        val v2y = rightAnkle.y - hipCenterY

        val dot = (v1x * v2x) + (v1y * v2y)
        val mag1 = sqrt((v1x * v1x) + (v1y * v1y))
        val mag2 = sqrt((v2x * v2x) + (v2y * v2y))

        if (mag1 < 1e-6f || mag2 < 1e-6f) return LEG_CLOSED_BASELINE

        val cosTheta = (dot / (mag1 * mag2)).coerceIn(-1.0f, 1.0f)
        return Math.toDegrees(acos(cosTheta.toDouble())).toFloat()
    }

    /**
     * Calculates arm abduction ROM% against target of 150°.
     */
    fun calculateArmRom(armAngle: Float): Float {
        val rom = (armAngle - ARM_CLOSED_BASELINE) / (ARM_OPEN_TARGET - ARM_CLOSED_BASELINE) * 100.0f
        return rom.coerceIn(0.0f, 100.0f)
    }

    /**
     * Calculates leg spread ROM% against target of 45°.
     */
    fun calculateLegRom(legAngle: Float): Float {
        val rom = (legAngle - LEG_CLOSED_BASELINE) / (LEG_OPEN_TARGET - LEG_CLOSED_BASELINE) * 100.0f
        return rom.coerceIn(0.0f, 100.0f)
    }

    /**
     * Calculates combined ROM% using the MINIMUM of arm ROM and leg ROM:
     * romPercent = min(armRom, legRom)
     * Full 100% requires both arms overhead AND legs spread apart.
     */
    fun calculateCombinedRom(armAngle: Float, legAngle: Float): Float {
        val armRom = calculateArmRom(armAngle)
        val legRom = calculateLegRom(legAngle)
        return min(armRom, legRom)
    }
}
