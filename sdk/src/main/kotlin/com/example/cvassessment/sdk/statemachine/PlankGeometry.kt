package com.example.cvassessment.sdk.statemachine

import com.example.cvassessment.sdk.pose.PoseLandmark
import com.example.cvassessment.sdk.pose.PoseLandmarkType
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Geometric calculations for Plank exercise analysis per EXERCISE_SPEC.md #7,
 * METRICS_SPEC.md §2, §4, §5, and FORM_RULES.md.
 *
 * Implements:
 * 1. Hip line angle computation (shoulder -> hip -> ankle):
 *    - Uses side with higher visibility in side-view camera setup.
 *    - Differentiates sagging (< 180°, hips_dropping) vs piking (> 180°, hips_piking)
 *      relative to the shoulder-ankle line.
 * 2. Postural deviation ROM percentage per METRICS_SPEC.md §4:
 *    romPercent = clamp((180 - abs(180 - actual_hip_line_angle)) / 180 * 100, 0, 100)
 * 3. TuT consistency factor per METRICS_SPEC.md §5:
 *    tutFactor = clamp(1 - (stddev(hip_line_angle over hold) / max_allowed_stddev), 0, 1.5)
 * 4. Profile/side view detection.
 */
object PlankGeometry {

    const val DEFAULT_MAX_ALLOWED_STDDEV = 15.0f

    /**
     * Checks if the user is positioned in side view (profile).
     */
    fun isSideView(landmarks: List<PoseLandmark>): Boolean {
        val leftShoulder = landmarks.getOrNull(PoseLandmarkType.LEFT_SHOULDER)
        val rightShoulder = landmarks.getOrNull(PoseLandmarkType.RIGHT_SHOULDER)
        val leftHip = landmarks.getOrNull(PoseLandmarkType.LEFT_HIP)
        val rightHip = landmarks.getOrNull(PoseLandmarkType.RIGHT_HIP)

        if (leftShoulder != null && rightShoulder != null) {
            val shoulderWidth = abs(leftShoulder.x - rightShoulder.x)
            if (shoulderWidth < 0.12f) return true
        }

        if (leftHip != null && rightHip != null) {
            val hipWidth = abs(leftHip.x - rightHip.x)
            if (hipWidth < 0.12f) return true
        }

        // Check visibility asymmetry (in side view, one side is typically significantly more visible)
        if (leftShoulder != null && rightShoulder != null) {
            val visDiff = abs(leftShoulder.visibility - rightShoulder.visibility)
            if (visDiff > 0.25f) return true
        }

        return false
    }

    /**
     * Computes the 2D interior angle formed by shoulder -> hip -> ankle with signed
     * orientation to detect sagging (< 180°) vs piking (> 180°).
     */
    fun computeHipLineAngle(landmarks: List<PoseLandmark>): Float {
        val leftShoulder = landmarks.getOrNull(PoseLandmarkType.LEFT_SHOULDER)
        val leftHip = landmarks.getOrNull(PoseLandmarkType.LEFT_HIP)
        val leftAnkle = landmarks.getOrNull(PoseLandmarkType.LEFT_ANKLE)

        val rightShoulder = landmarks.getOrNull(PoseLandmarkType.RIGHT_SHOULDER)
        val rightHip = landmarks.getOrNull(PoseLandmarkType.RIGHT_HIP)
        val rightAnkle = landmarks.getOrNull(PoseLandmarkType.RIGHT_ANKLE)

        val leftVis = if (leftShoulder != null && leftHip != null && leftAnkle != null) {
            (leftShoulder.visibility + leftHip.visibility + leftAnkle.visibility) / 3.0f
        } else 0.0f

        val rightVis = if (rightShoulder != null && rightHip != null && rightAnkle != null) {
            (rightShoulder.visibility + rightHip.visibility + rightAnkle.visibility) / 3.0f
        } else 0.0f

        return when {
            leftVis > rightVis + 0.1f && leftShoulder != null && leftHip != null && leftAnkle != null -> {
                calculateSignedHipAngle(leftShoulder, leftHip, leftAnkle)
            }
            rightVis > leftVis + 0.1f && rightShoulder != null && rightHip != null && rightAnkle != null -> {
                calculateSignedHipAngle(rightShoulder, rightHip, rightAnkle)
            }
            leftShoulder != null && leftHip != null && leftAnkle != null && rightShoulder != null && rightHip != null && rightAnkle != null -> {
                val leftAngle = calculateSignedHipAngle(leftShoulder, leftHip, leftAnkle)
                val rightAngle = calculateSignedHipAngle(rightShoulder, rightHip, rightAnkle)
                (leftAngle + rightAngle) / 2.0f
            }
            leftShoulder != null && leftHip != null && leftAnkle != null -> {
                calculateSignedHipAngle(leftShoulder, leftHip, leftAnkle)
            }
            rightShoulder != null && rightHip != null && rightAnkle != null -> {
                calculateSignedHipAngle(rightShoulder, rightHip, rightAnkle)
            }
            else -> 180.0f
        }
    }

    /**
     * Calculates the signed 2D hip line angle where:
     * - Perfectly straight line = 180.0°
     * - Hips sagging toward ground (lower y in screen coords / higher value) = < 180.0°
     * - Hips piking toward ceiling (higher y in screen coords / lower value) = > 180.0°
     */
    fun calculateSignedHipAngle(shoulder: PoseLandmark, hip: PoseLandmark, ankle: PoseLandmark): Float {
        // Vector HS = Shoulder - Hip
        val v1x = shoulder.x - hip.x
        val v1y = shoulder.y - hip.y

        // Vector HA = Ankle - Hip
        val v2x = ankle.x - hip.x
        val v2y = ankle.y - hip.y

        val dot = (v1x * v2x) + (v1y * v2y)
        val mag1 = sqrt((v1x * v1x) + (v1y * v1y))
        val mag2 = sqrt((v2x * v2x) + (v2y * v2y))

        if (mag1 < 1e-6f || mag2 < 1e-6f) {
            return 180.0f
        }

        val cosTheta = (dot / (mag1 * mag2)).coerceIn(-1.0f, 1.0f)
        val thetaDeg = Math.toDegrees(acos(cosTheta.toDouble())).toFloat()

        // Determine sagging vs piking relative to the straight line between shoulder and ankle
        // In image coordinates, y=0 is top (ceiling) and y=1 is bottom (floor).
        val denom = ankle.x - shoulder.x
        if (abs(denom) < 1e-5f) {
            // Degenerate vertical alignment fallback
            return thetaDeg
        }

        val t = (hip.x - shoulder.x) / denom
        val expectedLineY = shoulder.y + t * (ankle.y - shoulder.y)

        // If hip.y > expectedLineY, hip is lower down (sagging towards floor) -> angle < 180°
        // If hip.y < expectedLineY, hip is higher up (piking towards ceiling) -> angle > 180°
        return if (hip.y > expectedLineY + 0.005f) {
            thetaDeg
        } else if (hip.y < expectedLineY - 0.005f) {
            360.0f - thetaDeg
        } else {
            180.0f
        }
    }

    /**
     * Calculates postural deviation Range of Motion (ROM) % per METRICS_SPEC.md §4:
     * romPercent = clamp((180 - abs(180 - actual_hip_line_angle)) / 180 * 100, 0, 100)
     */
    fun calculatePosturalRom(hipLineAngle: Float): Float {
        val deviation = abs(180.0f - hipLineAngle)
        val rom = (180.0f - deviation) / 180.0f * 100.0f
        return rom.coerceIn(0.0f, 100.0f)
    }

    /**
     * Calculates Time under Tension (TuT) consistency factor per METRICS_SPEC.md §5:
     * tutFactor = clamp(1 - (stddev(hip_line_angle over hold) / max_allowed_stddev), 0, 1.5)
     */
    fun calculateTutFactor(
        angleSamples: List<Float>,
        maxAllowedStddev: Float = DEFAULT_MAX_ALLOWED_STDDEV
    ): Float {
        if (angleSamples.isEmpty()) return 1.0f
        if (angleSamples.size == 1) return 1.0f

        val mean = angleSamples.average().toFloat()
        val variance = angleSamples.map { (it - mean) * (it - mean) }.average().toFloat()
        val stddev = sqrt(variance.toDouble()).toFloat()

        val tut = 1.0f - (stddev / maxAllowedStddev)
        return tut.coerceIn(0.0f, 1.5f)
    }
}
