package com.example.cvassessment.sdk.statemachine

import com.example.cvassessment.sdk.pose.PoseLandmark
import com.example.cvassessment.sdk.pose.PoseLandmarkType
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Geometric calculations for Mountain Climber exercise analysis per EXERCISE_SPEC.md #10,
 * FORM_RULES.md, and DECISIONS.md D14/D15.
 *
 * Implements:
 * 1. Knee drive angle calculation (hip-knee-ankle):
 *    - Computes both left and right knee angles.
 *    - Active knee angle is the minimum (the leg driving forward toward chest).
 * 2. Hip line angle calculation:
 *    - Directly delegates to [PlankGeometry.computeHipLineAngle] for sagittal plank stability.
 * 3. ROM percentage calculation:
 *    - 160.0° (extended) -> 0%
 *    - 90.0° (driven) -> 100%
 * 4. Profile/side view verification.
 */
object MountainClimberGeometry {

    const val KNEE_EXTENDED_BASELINE = 160.0f
    const val KNEE_DRIVEN_TARGET = 90.0f
    const val ROM_TARGET_SPAN = KNEE_EXTENDED_BASELINE - KNEE_DRIVEN_TARGET // 70.0f

    /**
     * Computes the 2D interior angle formed by left hip (23) -> left knee (25) -> left ankle (27).
     */
    fun computeLeftKneeAngle(landmarks: List<PoseLandmark>): Float? {
        val hip = landmarks.getOrNull(PoseLandmarkType.LEFT_HIP) ?: return null
        val knee = landmarks.getOrNull(PoseLandmarkType.LEFT_KNEE) ?: return null
        val ankle = landmarks.getOrNull(PoseLandmarkType.LEFT_ANKLE) ?: return null

        if (hip.visibility < 0.2f || knee.visibility < 0.2f || ankle.visibility < 0.2f) return null
        return calculateAngle(hip, knee, ankle)
    }

    /**
     * Computes the 2D interior angle formed by right hip (24) -> right knee (26) -> right ankle (28).
     */
    fun computeRightKneeAngle(landmarks: List<PoseLandmark>): Float? {
        val hip = landmarks.getOrNull(PoseLandmarkType.RIGHT_HIP) ?: return null
        val knee = landmarks.getOrNull(PoseLandmarkType.RIGHT_KNEE) ?: return null
        val ankle = landmarks.getOrNull(PoseLandmarkType.RIGHT_ANKLE) ?: return null

        if (hip.visibility < 0.2f || knee.visibility < 0.2f || ankle.visibility < 0.2f) return null
        return calculateAngle(hip, knee, ankle)
    }

    /**
     * Computes the active knee drive angle as the minimum of the available left and right knee angles.
     * The leg driving forward toward the chest has the smaller interior knee angle.
     */
    fun computeActiveKneeAngle(landmarks: List<PoseLandmark>): Float {
        val leftAngle = computeLeftKneeAngle(landmarks)
        val rightAngle = computeRightKneeAngle(landmarks)

        return when {
            leftAngle != null && rightAngle != null -> minOf(leftAngle, rightAngle)
            leftAngle != null -> leftAngle
            rightAngle != null -> rightAngle
            else -> KNEE_EXTENDED_BASELINE
        }
    }

    /**
     * Computes sagittal hip line angle (shoulder-hip-ankle) by directly reusing [PlankGeometry.computeHipLineAngle].
     * Guarantees identical plank stability and sag/piking detection without duplicating engine geometry logic.
     */
    fun computeHipLineAngle(landmarks: List<PoseLandmark>): Float {
        return PlankGeometry.computeHipLineAngle(landmarks)
    }

    /**
     * Calculates ROM percentage for Mountain Climber:
     * - 160.0° (extended plank position) = 0%
     * - 90.0° (knee driven to chest/hip) = 100%
     * - Clamped between 0.0% and 100.0%.
     */
    fun calculateRomPercent(minKneeAngle: Float): Float {
        val rom = (KNEE_EXTENDED_BASELINE - minKneeAngle) / ROM_TARGET_SPAN * 100.0f
        return rom.coerceIn(0.0f, 100.0f)
    }

    /**
     * Checks if the user is in profile/side view.
     */
    fun isSideView(landmarks: List<PoseLandmark>): Boolean {
        return PlankGeometry.isSideView(landmarks)
    }

    /**
     * Calculates 2D interior angle at vertex b: a -> b -> c in degrees [0°, 180°].
     */
    private fun calculateAngle(a: PoseLandmark, b: PoseLandmark, c: PoseLandmark): Float {
        val v1x = a.x - b.x
        val v1y = a.y - b.y
        val v2x = c.x - b.x
        val v2y = c.y - b.y

        val dot = v1x * v2x + v1y * v2y
        val mag1 = sqrt(v1x * v1x + v1y * v1y)
        val mag2 = sqrt(v2x * v2x + v2y * v2y)

        if (mag1 < 1e-6f || mag2 < 1e-6f) return 180.0f
        val cosVal = (dot / (mag1 * mag2)).coerceIn(-1.0f, 1.0f)
        return (acos(cosVal) * 180.0 / Math.PI).toFloat()
    }
}
