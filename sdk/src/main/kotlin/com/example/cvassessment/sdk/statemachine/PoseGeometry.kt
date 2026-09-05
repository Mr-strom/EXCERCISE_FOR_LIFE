package com.example.cvassessment.sdk.statemachine

import com.example.cvassessment.sdk.pose.PoseLandmark
import com.example.cvassessment.sdk.pose.PoseLandmarkType
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Geometric calculations for landmark vectors and joint angles.
 */
object PoseGeometry {

    /**
     * Computes the 3D interior angle (in degrees, [0.0..180.0]) formed by three points (A -> B -> C),
     * where B is the vertex joint where the angle is measured.
     */
    fun calculateAngle3D(a: PoseLandmark, b: PoseLandmark, c: PoseLandmark): Float {
        // Vector BA = A - B
        val v1x = a.x - b.x
        val v1y = a.y - b.y
        val v1z = a.z - b.z

        // Vector BC = C - B
        val v2x = c.x - b.x
        val v2y = c.y - b.y
        val v2z = c.z - b.z

        val dot = (v1x * v2x) + (v1y * v2y) + (v1z * v2z)
        val mag1 = sqrt((v1x * v1x) + (v1y * v1y) + (v1z * v1z))
        val mag2 = sqrt((v2x * v2x) + (v2y * v2y) + (v2z * v2z))

        if (mag1 < 1e-6f || mag2 < 1e-6f) {
            return 180.0f
        }

        val cosTheta = (dot / (mag1 * mag2)).coerceIn(-1.0f, 1.0f)
        return Math.toDegrees(acos(cosTheta.toDouble())).toFloat()
    }

    /**
     * Computes 2D interior angle in degrees for coordinate triplets.
     */
    fun calculateAngle2D(
        ax: Float, ay: Float,
        bx: Float, by: Float,
        cx: Float, cy: Float
    ): Float {
        val v1x = ax - bx
        val v1y = ay - by
        val v2x = cx - bx
        val v2y = cy - by

        val dot = (v1x * v2x) + (v1y * v2y)
        val mag1 = sqrt((v1x * v1x) + (v1y * v1y))
        val mag2 = sqrt((v2x * v2x) + (v2y * v2y))

        if (mag1 < 1e-6f || mag2 < 1e-6f) {
            return 180.0f
        }

        val cosTheta = (dot / (mag1 * mag2)).coerceIn(-1.0f, 1.0f)
        return Math.toDegrees(acos(cosTheta.toDouble())).toFloat()
    }

    /**
     * Computes the elbow angle (shoulder -> elbow -> wrist) for both arms.
     * Selects the arm with higher visibility in side-view camera placement,
     * or averages both when both arms are equally visible.
     */
    fun computeElbowAngle(landmarks: List<PoseLandmark>): Float {
        if (landmarks.size <= PoseLandmarkType.RIGHT_WRIST) return 180.0f

        val leftShoulder = landmarks[PoseLandmarkType.LEFT_SHOULDER]
        val leftElbow = landmarks[PoseLandmarkType.LEFT_ELBOW]
        val leftWrist = landmarks[PoseLandmarkType.LEFT_WRIST]

        val rightShoulder = landmarks[PoseLandmarkType.RIGHT_SHOULDER]
        val rightElbow = landmarks[PoseLandmarkType.RIGHT_ELBOW]
        val rightWrist = landmarks[PoseLandmarkType.RIGHT_WRIST]

        val leftVis = (leftShoulder.visibility + leftElbow.visibility + leftWrist.visibility) / 3.0f
        val rightVis = (rightShoulder.visibility + rightElbow.visibility + rightWrist.visibility) / 3.0f

        val leftAngle = calculateAngle3D(leftShoulder, leftElbow, leftWrist)
        val rightAngle = calculateAngle3D(rightShoulder, rightElbow, rightWrist)

        return when {
            leftVis > rightVis + 0.1f -> leftAngle
            rightVis > leftVis + 0.1f -> rightAngle
            else -> (leftAngle + rightAngle) / 2.0f
        }
    }

    /**
     * Computes the hip line angle (shoulder -> hip -> ankle) for detecting trunk sag/piking.
     * Uses the higher-visibility side in side-view camera setups.
     */
    fun computeHipLineAngle(landmarks: List<PoseLandmark>): Float {
        if (landmarks.size <= PoseLandmarkType.RIGHT_ANKLE) return 180.0f

        val leftShoulder = landmarks[PoseLandmarkType.LEFT_SHOULDER]
        val leftHip = landmarks[PoseLandmarkType.LEFT_HIP]
        val leftAnkle = landmarks[PoseLandmarkType.LEFT_ANKLE]

        val rightShoulder = landmarks[PoseLandmarkType.RIGHT_SHOULDER]
        val rightHip = landmarks[PoseLandmarkType.RIGHT_HIP]
        val rightAnkle = landmarks[PoseLandmarkType.RIGHT_ANKLE]

        val leftVis = (leftShoulder.visibility + leftHip.visibility + leftAnkle.visibility) / 3.0f
        val rightVis = (rightShoulder.visibility + rightHip.visibility + rightAnkle.visibility) / 3.0f

        val leftAngle = calculateAngle3D(leftShoulder, leftHip, leftAnkle)
        val rightAngle = calculateAngle3D(rightShoulder, rightHip, rightAnkle)

        return when {
            leftVis > rightVis + 0.1f -> leftAngle
            rightVis > leftVis + 0.1f -> rightAngle
            else -> (leftAngle + rightAngle) / 2.0f
        }
    }
}
