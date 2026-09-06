package com.example.cvassessment.sdk.statemachine

import com.example.cvassessment.sdk.pose.PoseLandmark
import com.example.cvassessment.sdk.pose.PoseLandmarkType
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Geometric calculations for Lunge exercise analysis per EXERCISE_SPEC.md and FORM_RULES.md.
 *
 * Implements:
 * 1. Independent left and right knee angle computation (hip -> knee -> ankle).
 * 2. Front-leg vs Back-leg identification:
 *    - In side view, compares horizontal (x-axis) ankle positions relative to hip/facing direction.
 *    - The leg whose ankle is positioned further forward along the facing direction is the front leg.
 *    - Re-evaluated at the beginning of each repetition to seamlessly handle alternating lunges.
 * 3. Side-view detection and graceful non-side-view degradation (scaling confidence, warning logged).
 * 4. Torso vertical angle computation (shoulder-hip line relative to vertical).
 * 5. Lunge ROM percentage computation based on front knee flexion.
 */
object LungeGeometry {

    enum class LegSide {
        LEFT,
        RIGHT
    }

    enum class FacingDirection {
        RIGHT, // Forward direction is +x
        LEFT   // Forward direction is -x
    }

    data class LungeAnglesResult(
        val leftKneeAngle: Float,
        val rightKneeAngle: Float,
        val frontLeg: LegSide,
        val frontKneeAngle: Float,
        val backKneeAngle: Float,
        val torsoVerticalAngle: Float,
        val isSideView: Boolean,
        val confidenceScale: Float = if (isSideView) 1.0f else 0.75f,
        val warningMessage: String? = if (!isSideView) "Non-side view detected for Lunge. Tracking accuracy is reduced." else null
    )

    /**
     * Determines whether the subject is facing right (+x) or left (-x).
     */
    fun detectFacingDirection(landmarks: List<PoseLandmark>): FacingDirection {
        val nose = landmarks.getOrNull(PoseLandmarkType.NOSE)
        val leftHip = landmarks.getOrNull(PoseLandmarkType.LEFT_HIP)
        val rightHip = landmarks.getOrNull(PoseLandmarkType.RIGHT_HIP)

        if (nose != null && (leftHip != null || rightHip != null)) {
            val hipX = if (leftHip != null && rightHip != null) (leftHip.x + rightHip.x) / 2.0f else (leftHip?.x ?: rightHip!!.x)
            if (nose.x - hipX > 0.03f) return FacingDirection.RIGHT
            if (hipX - nose.x > 0.03f) return FacingDirection.LEFT
        }

        val leftShoulder = landmarks.getOrNull(PoseLandmarkType.LEFT_SHOULDER)
        val rightShoulder = landmarks.getOrNull(PoseLandmarkType.RIGHT_SHOULDER)
        if (nose != null && (leftShoulder != null || rightShoulder != null)) {
            val shoulderX = if (leftShoulder != null && rightShoulder != null) (leftShoulder.x + rightShoulder.x) / 2.0f else (leftShoulder?.x ?: rightShoulder!!.x)
            if (nose.x - shoulderX > 0.03f) return FacingDirection.RIGHT
            if (shoulderX - nose.x > 0.03f) return FacingDirection.LEFT
        }

        val leftFoot = landmarks.getOrNull(PoseLandmarkType.LEFT_FOOT_INDEX)
        val rightFoot = landmarks.getOrNull(PoseLandmarkType.RIGHT_FOOT_INDEX)
        val leftAnkle = landmarks.getOrNull(PoseLandmarkType.LEFT_ANKLE)
        val rightAnkle = landmarks.getOrNull(PoseLandmarkType.RIGHT_ANKLE)

        if (leftFoot != null && leftAnkle != null && abs(leftFoot.x - leftAnkle.x) > 0.02f) {
            return if (leftFoot.x > leftAnkle.x) FacingDirection.RIGHT else FacingDirection.LEFT
        }
        if (rightFoot != null && rightAnkle != null && abs(rightFoot.x - rightAnkle.x) > 0.02f) {
            return if (rightFoot.x > rightAnkle.x) FacingDirection.RIGHT else FacingDirection.LEFT
        }

        // Default: forward is +x
        return FacingDirection.RIGHT
    }

    /**
     * Identifies which leg is the front leg based on horizontal ankle displacement relative to facing direction.
     *
     * In side view:
     * - Facing right: larger x means further forward -> front leg.
     * - Facing left: smaller x means further forward -> front leg.
     *
     * In non-side view (front view):
     * - Whichever ankle is further forward in depth (smaller z) or lower in y, or knee bending deeper.
     */
    fun identifyFrontLeg(
        landmarks: List<PoseLandmark>,
        facingOverride: FacingDirection? = null
    ): LegSide {
        val leftAnkle = landmarks.getOrNull(PoseLandmarkType.LEFT_ANKLE)
        val rightAnkle = landmarks.getOrNull(PoseLandmarkType.RIGHT_ANKLE)

        if (leftAnkle == null && rightAnkle != null) return LegSide.RIGHT
        if (rightAnkle == null && leftAnkle != null) return LegSide.LEFT
        if (leftAnkle == null || rightAnkle == null) return LegSide.LEFT

        val isSide = isSideView(landmarks)
        if (!isSide) {
            // Front-view fallback: compare depth z or knee flexion
            val zDiff = leftAnkle.z - rightAnkle.z
            if (abs(zDiff) > 0.08f) {
                // Smaller/more negative z is closer to camera / stepped forward
                return if (leftAnkle.z < rightAnkle.z) LegSide.LEFT else LegSide.RIGHT
            }
            val leftKnee = landmarks.getOrNull(PoseLandmarkType.LEFT_KNEE)
            val rightKnee = landmarks.getOrNull(PoseLandmarkType.RIGHT_KNEE)
            val leftHip = landmarks.getOrNull(PoseLandmarkType.LEFT_HIP)
            val rightHip = landmarks.getOrNull(PoseLandmarkType.RIGHT_HIP)
            if (leftHip != null && leftKnee != null && rightHip != null && rightKnee != null) {
                val leftAngle = PoseGeometry.calculateAngle3D(leftHip, leftKnee, leftAnkle)
                val rightAngle = PoseGeometry.calculateAngle3D(rightHip, rightKnee, rightAnkle)
                if (abs(leftAngle - rightAngle) > 10.0f) {
                    return if (leftAngle < rightAngle) LegSide.LEFT else LegSide.RIGHT
                }
            }
        }

        val facing = facingOverride ?: detectFacingDirection(landmarks)
        return when (facing) {
            FacingDirection.RIGHT -> if (leftAnkle.x >= rightAnkle.x) LegSide.LEFT else LegSide.RIGHT
            FacingDirection.LEFT -> if (leftAnkle.x <= rightAnkle.x) LegSide.LEFT else LegSide.RIGHT
        }
    }

    /**
     * Detects whether the camera is in side (profile) view.
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

        // Check depth difference: in profile, one side is deeper than the other
        val hipDepthDiff = abs(leftHip.z - rightHip.z)
        if (hipDepthDiff > 0.25f) {
            return true
        }

        return false
    }

    /**
     * Computes all tracked angles and identifies front/back leg for the frame.
     */
    fun computeLungeAngles(
        landmarks: List<PoseLandmark>,
        currentFrontLeg: LegSide? = null
    ): LungeAnglesResult {
        val leftHip = landmarks.getOrNull(PoseLandmarkType.LEFT_HIP)
        val leftKnee = landmarks.getOrNull(PoseLandmarkType.LEFT_KNEE)
        val leftAnkle = landmarks.getOrNull(PoseLandmarkType.LEFT_ANKLE)

        val rightHip = landmarks.getOrNull(PoseLandmarkType.RIGHT_HIP)
        val rightKnee = landmarks.getOrNull(PoseLandmarkType.RIGHT_KNEE)
        val rightAnkle = landmarks.getOrNull(PoseLandmarkType.RIGHT_ANKLE)

        val leftKneeAngle = if (leftHip != null && leftKnee != null && leftAnkle != null) {
            PoseGeometry.calculateAngle3D(leftHip, leftKnee, leftAnkle)
        } else 180.0f

        val rightKneeAngle = if (rightHip != null && rightKnee != null && rightAnkle != null) {
            PoseGeometry.calculateAngle3D(rightHip, rightKnee, rightAnkle)
        } else 180.0f

        val isSide = isSideView(landmarks)
        val frontLeg = currentFrontLeg ?: identifyFrontLeg(landmarks)

        val frontKneeAngle = if (frontLeg == LegSide.LEFT) leftKneeAngle else rightKneeAngle
        val backKneeAngle = if (frontLeg == LegSide.LEFT) rightKneeAngle else leftKneeAngle
        val torsoVerticalAngle = computeTorsoVerticalAngle(landmarks)

        return LungeAnglesResult(
            leftKneeAngle = leftKneeAngle,
            rightKneeAngle = rightKneeAngle,
            frontLeg = frontLeg,
            frontKneeAngle = frontKneeAngle,
            backKneeAngle = backKneeAngle,
            torsoVerticalAngle = torsoVerticalAngle,
            isSideView = isSide
        )
    }

    /**
     * Computes torso vertical deviation angle in degrees.
     * 0° indicates perfectly upright torso.
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
        val dy = hipY - shoulderY

        val angleRad = atan2(abs(dx), abs(dy))
        return Math.toDegrees(angleRad.toDouble()).toFloat()
    }

    /**
     * Computes ROM percentage based on front knee angle:
     * - startingAngle = 160.0° (standing extended)
     * - fullExpectedAngle = 90.0° (target 90° depth)
     * - 100% = reaching <= 90.0°
     */
    fun computeRomPercent(minFrontKneeAngle: Float): Float {
        val starting = 160.0f
        val target = 90.0f
        val rom = ((starting - minFrontKneeAngle) / (starting - target)) * 100.0f
        return rom.coerceIn(0.0f, 100.0f)
    }
}
