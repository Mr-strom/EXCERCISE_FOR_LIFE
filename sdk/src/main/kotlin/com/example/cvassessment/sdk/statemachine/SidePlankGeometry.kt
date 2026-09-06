package com.example.cvassessment.sdk.statemachine

import com.example.cvassessment.sdk.pose.PoseLandmark
import com.example.cvassessment.sdk.pose.PoseLandmarkType
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Support side for Side Plank (the side of the body contacting the ground).
 */
enum class SidePlankSupportSide {
    UNKNOWN,
    LEFT,
    RIGHT
}

/**
 * Geometric calculations for Side Plank exercise analysis per EXERCISE_SPEC.md #8,
 * METRICS_SPEC.md §2, §4, §5, and FORM_RULES.md.
 *
 * Implements:
 * 1. Support side detection: determines whether the athlete is planking on their left or right elbow
 *    based on ground proximity (higher y-coordinate in normalized image space).
 * 2. Lateral body line angle computation (shoulder -> hip -> ankle):
 *    - Uses support-side landmarks (or visible side in front-facing camera setup).
 *    - Differentiates sagging (< 180°, hips_dropping) vs piking (> 180°, hips_piking)
 *      relative to the shoulder-ankle line.
 * 3. Postural deviation ROM percentage per METRICS_SPEC.md §4:
 *    romPercent = clamp((180 - abs(180 - actual_body_line_angle)) / 180 * 100, 0, 100)
 * 4. TuT consistency factor per METRICS_SPEC.md §5:
 *    tutFactor = clamp(1 - (stddev(body_line_angle over hold) / max_allowed_stddev), 0, 1.5)
 * 5. Front-facing view detection (camera facing the lateral surface of the planked body).
 */
object SidePlankGeometry {

    const val DEFAULT_MAX_ALLOWED_STDDEV = 15.0f

    /**
     * Determines which side of the body is the support side (contacting the floor).
     * In normalized image coordinates, y=0 is top and y=1 is bottom (floor).
     * The support side (elbow, shoulder, hip) is closer to the floor (has larger y).
     */
    fun detectSupportSide(landmarks: List<PoseLandmark>): SidePlankSupportSide {
        val leftElbow = landmarks.getOrNull(PoseLandmarkType.LEFT_ELBOW)
        val rightElbow = landmarks.getOrNull(PoseLandmarkType.RIGHT_ELBOW)
        val leftShoulder = landmarks.getOrNull(PoseLandmarkType.LEFT_SHOULDER)
        val rightShoulder = landmarks.getOrNull(PoseLandmarkType.RIGHT_SHOULDER)
        val leftHip = landmarks.getOrNull(PoseLandmarkType.LEFT_HIP)
        val rightHip = landmarks.getOrNull(PoseLandmarkType.RIGHT_HIP)

        var leftPoints = 0
        var rightPoints = 0

        // 1. Elbow height comparison (support elbow is lowest down / highest y)
        if (leftElbow != null && rightElbow != null) {
            if (leftElbow.y > rightElbow.y + 0.04f) leftPoints += 2
            else if (rightElbow.y > leftElbow.y + 0.04f) rightPoints += 2
        } else if (leftElbow != null && leftElbow.visibility >= 0.4f) {
            leftPoints += 1
        } else if (rightElbow != null && rightElbow.visibility >= 0.4f) {
            rightPoints += 1
        }

        // 2. Shoulder height comparison (support shoulder is lower / higher y)
        if (leftShoulder != null && rightShoulder != null) {
            if (leftShoulder.y > rightShoulder.y + 0.02f) leftPoints += 1
            else if (rightShoulder.y > leftShoulder.y + 0.02f) rightPoints += 1
        }

        // 3. Hip height comparison (support hip is lower / higher y)
        if (leftHip != null && rightHip != null) {
            if (leftHip.y > rightHip.y + 0.02f) leftPoints += 1
            else if (rightHip.y > leftHip.y + 0.02f) rightPoints += 1
        }

        return when {
            leftPoints > rightPoints -> SidePlankSupportSide.LEFT
            rightPoints > leftPoints -> SidePlankSupportSide.RIGHT
            else -> SidePlankSupportSide.UNKNOWN
        }
    }

    /**
     * Checks if the camera is facing the front of the side planking athlete.
     * In front view, the body line (shoulder to ankle) spans horizontally across the frame.
     */
    fun isFrontView(landmarks: List<PoseLandmark>): Boolean {
        val leftShoulder = landmarks.getOrNull(PoseLandmarkType.LEFT_SHOULDER)
        val rightShoulder = landmarks.getOrNull(PoseLandmarkType.RIGHT_SHOULDER)
        val leftAnkle = landmarks.getOrNull(PoseLandmarkType.LEFT_ANKLE)
        val rightAnkle = landmarks.getOrNull(PoseLandmarkType.RIGHT_ANKLE)

        val shoulder = leftShoulder ?: rightShoulder
        val ankle = leftAnkle ?: rightAnkle

        if (shoulder != null && ankle != null) {
            val horizontalSpan = abs(shoulder.x - ankle.x)
            if (horizontalSpan < 0.20f) return false
        }

        return true
    }

    /**
     * Computes the 2D interior angle formed by shoulder -> hip -> ankle in the lateral plane,
     * with signed orientation to detect sagging (< 180°) vs piking (> 180°).
     */
    fun computeBodyLineAngle(
        landmarks: List<PoseLandmark>,
        supportSide: SidePlankSupportSide = SidePlankSupportSide.UNKNOWN
    ): Float {
        val effectiveSide = if (supportSide != SidePlankSupportSide.UNKNOWN) {
            supportSide
        } else {
            detectSupportSide(landmarks)
        }

        val leftShoulder = landmarks.getOrNull(PoseLandmarkType.LEFT_SHOULDER)
        val leftHip = landmarks.getOrNull(PoseLandmarkType.LEFT_HIP)
        val leftAnkle = landmarks.getOrNull(PoseLandmarkType.LEFT_ANKLE)

        val rightShoulder = landmarks.getOrNull(PoseLandmarkType.RIGHT_SHOULDER)
        val rightHip = landmarks.getOrNull(PoseLandmarkType.RIGHT_HIP)
        val rightAnkle = landmarks.getOrNull(PoseLandmarkType.RIGHT_ANKLE)

        return when (effectiveSide) {
            SidePlankSupportSide.LEFT -> {
                if (leftShoulder != null && leftHip != null && leftAnkle != null) {
                    calculateSignedAngle(leftShoulder, leftHip, leftAnkle)
                } else if (rightShoulder != null && rightHip != null && rightAnkle != null) {
                    calculateSignedAngle(rightShoulder, rightHip, rightAnkle)
                } else 180.0f
            }
            SidePlankSupportSide.RIGHT -> {
                if (rightShoulder != null && rightHip != null && rightAnkle != null) {
                    calculateSignedAngle(rightShoulder, rightHip, rightAnkle)
                } else if (leftShoulder != null && leftHip != null && leftAnkle != null) {
                    calculateSignedAngle(leftShoulder, leftHip, leftAnkle)
                } else 180.0f
            }
            SidePlankSupportSide.UNKNOWN -> {
                val leftVis = if (leftShoulder != null && leftHip != null && leftAnkle != null) {
                    (leftShoulder.visibility + leftHip.visibility + leftAnkle.visibility) / 3.0f
                } else 0.0f
                val rightVis = if (rightShoulder != null && rightHip != null && rightAnkle != null) {
                    (rightShoulder.visibility + rightHip.visibility + rightAnkle.visibility) / 3.0f
                } else 0.0f

                when {
                    leftVis > rightVis + 0.1f && leftShoulder != null && leftHip != null && leftAnkle != null -> {
                        calculateSignedAngle(leftShoulder, leftHip, leftAnkle)
                    }
                    rightVis > leftVis + 0.1f && rightShoulder != null && rightHip != null && rightAnkle != null -> {
                        calculateSignedAngle(rightShoulder, rightHip, rightAnkle)
                    }
                    leftShoulder != null && leftHip != null && leftAnkle != null && rightShoulder != null && rightHip != null && rightAnkle != null -> {
                        val leftAngle = calculateSignedAngle(leftShoulder, leftHip, leftAnkle)
                        val rightAngle = calculateSignedAngle(rightShoulder, rightHip, rightAnkle)
                        (leftAngle + rightAngle) / 2.0f
                    }
                    leftShoulder != null && leftHip != null && leftAnkle != null -> {
                        calculateSignedAngle(leftShoulder, leftHip, leftAnkle)
                    }
                    rightShoulder != null && rightHip != null && rightAnkle != null -> {
                        calculateSignedAngle(rightShoulder, rightHip, rightAnkle)
                    }
                    else -> 180.0f
                }
            }
        }
    }

    /**
     * Calculates the signed 2D body line angle where:
     * - Perfectly straight lateral line = 180.0°
     * - Hips sagging toward ground (lower y in screen coords / higher value) = < 180.0°
     * - Hips piking toward ceiling (higher y in screen coords / lower value) = > 180.0°
     */
    fun calculateSignedAngle(shoulder: PoseLandmark, hip: PoseLandmark, ankle: PoseLandmark): Float {
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
        val denom = ankle.x - shoulder.x
        if (abs(denom) < 1e-5f) {
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
     * romPercent = clamp((180 - abs(180 - actual_body_line_angle)) / 180 * 100, 0, 100)
     */
    fun calculatePosturalRom(bodyLineAngle: Float): Float {
        val deviation = abs(180.0f - bodyLineAngle)
        val rom = (180.0f - deviation) / 180.0f * 100.0f
        return rom.coerceIn(0.0f, 100.0f)
    }

    /**
     * Calculates Time under Tension (TuT) consistency factor per METRICS_SPEC.md §5:
     * tutFactor = clamp(1 - (stddev(body_line_angle over hold) / max_allowed_stddev), 0, 1.5)
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
