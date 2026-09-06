package com.example.cvassessment.app.ui

import com.example.cvassessment.sdk.pose.PoseLandmark
import com.example.cvassessment.sdk.pose.PoseLandmarkType

enum class CameraViewRequirement {
    ANY,
    SIDE_REQUIRED,
    FRONT_REQUIRED,
    SIDE_PREFERRED,
    FRONT_PREFERRED
}

/**
 * Aggregates continuous frame samples over a 7-second setup window on Screen 2.
 *
 * Computes average confidence per landmark and determines a single, actionable
 * recommendation rather than raw fluctuating numbers or long error lists.
 */
class SetupAnalysisEvaluator(
    val exerciseId: String = "push_up",
    private val requiredIndices: List<Int> = listOf(
        PoseLandmarkType.NOSE,
        PoseLandmarkType.LEFT_SHOULDER,
        PoseLandmarkType.RIGHT_SHOULDER,
        PoseLandmarkType.LEFT_ELBOW,
        PoseLandmarkType.RIGHT_ELBOW,
        PoseLandmarkType.LEFT_WRIST,
        PoseLandmarkType.RIGHT_WRIST,
        PoseLandmarkType.LEFT_HIP,
        PoseLandmarkType.RIGHT_HIP,
        PoseLandmarkType.LEFT_KNEE,
        PoseLandmarkType.RIGHT_KNEE,
        PoseLandmarkType.LEFT_ANKLE,
        PoseLandmarkType.RIGHT_ANKLE
    )
) {

    fun getViewRequirement(): CameraViewRequirement {
        return when (exerciseId.trim().lowercase()) {
            "calf_raise", "plank", "mountain_climber" -> CameraViewRequirement.SIDE_REQUIRED
            "jumping_jack", "side_plank" -> CameraViewRequirement.FRONT_REQUIRED
            "push_up", "lunge", "squat" -> CameraViewRequirement.SIDE_PREFERRED
            "bicep_curl", "shoulder_press" -> CameraViewRequirement.FRONT_PREFERRED
            else -> CameraViewRequirement.ANY
        }
    }

    data class SetupEvaluationResult(
        val isGood: Boolean,
        val overallConfidence: Float,
        val headline: String,
        val actionableTip: String,
        val leftArmScore: Float,
        val rightArmScore: Float,
        val torsoScore: Float,
        val leftLegScore: Float,
        val rightLegScore: Float,
        val canStartAnyway: Boolean = true
    )

    private var totalSamples: Int = 0
    private var poseSamples: Int = 0
    private var lowLightSamples: Int = 0

    private val visibilitySamples = mutableMapOf<Int, MutableList<Float>>()
    private val xSamples = mutableMapOf<Int, MutableList<Float>>()
    private val ySamples = mutableMapOf<Int, MutableList<Float>>()

    /**
     * Resets internal sample buffers between analysis runs.
     */
    fun reset() {
        totalSamples = 0
        poseSamples = 0
        lowLightSamples = 0
        visibilitySamples.clear()
        xSamples.clear()
        ySamples.clear()
    }

    /**
     * Records a single frame sample during the 7-second analysis window.
     */
    fun recordSample(landmarks: List<PoseLandmark>, hasPose: Boolean, isLowLight: Boolean = false) {
        totalSamples++
        if (isLowLight) {
            lowLightSamples++
        }
        if (hasPose && landmarks.isNotEmpty()) {
            poseSamples++
            val landmarkMap = landmarks.associateBy { it.index }
            for (idx in requiredIndices) {
                val lm = landmarkMap[idx]
                val vis = lm?.visibility ?: 0.0f
                visibilitySamples.getOrPut(idx) { mutableListOf() }.add(vis)
                if (lm != null) {
                    xSamples.getOrPut(idx) { mutableListOf() }.add(lm.x)
                    ySamples.getOrPut(idx) { mutableListOf() }.add(lm.y)
                }
            }
        }
    }

    /**
     * Provides a live framing hint during the 7-second analysis countdown
     * covering camera height/floor angle, distance (<40% or >85%), lighting,
     * and exercise orientation.
     */
    fun getLiveFramingHint(landmarks: List<PoseLandmark>, isLowLight: Boolean = false): String? {
        if (landmarks.isEmpty()) return null
        if (isLowLight) {
            return "Low light detected — move to a brighter area"
        }

        val landmarkMap = landmarks.associateBy { it.index }
        val leftShoulder = landmarkMap[PoseLandmarkType.LEFT_SHOULDER]
        val rightShoulder = landmarkMap[PoseLandmarkType.RIGHT_SHOULDER]
        val leftHip = landmarkMap[PoseLandmarkType.LEFT_HIP]
        val rightHip = landmarkMap[PoseLandmarkType.RIGHT_HIP]
        val leftAnkle = landmarkMap[PoseLandmarkType.LEFT_ANKLE]
        val rightAnkle = landmarkMap[PoseLandmarkType.RIGHT_ANKLE]

        val viewReq = getViewRequirement()
        val shoulderWidth = if (leftShoulder != null && rightShoulder != null &&
            leftShoulder.visibility >= 0.35f && rightShoulder.visibility >= 0.35f) {
            kotlin.math.abs(leftShoulder.x - rightShoulder.x)
        } else null

        // 1. Strict view requirement violation
        if (shoulderWidth != null) {
            if (viewReq == CameraViewRequirement.SIDE_REQUIRED && shoulderWidth > 0.18f) {
                return "Turn sideways for this exercise"
            }
            if (viewReq == CameraViewRequirement.FRONT_REQUIRED && shoulderWidth < 0.10f) {
                return "Face the camera for this exercise"
            }
        }

        // 2. Camera height / floor angle heuristic:
        // When phone is placed on the floor tilted upward, the torso projection is severely foreshortened (ratio < 0.42)
        val validShouldersY = listOfNotNull(leftShoulder?.takeIf { it.visibility >= 0.35f }?.y, rightShoulder?.takeIf { it.visibility >= 0.35f }?.y)
        val validHipsY = listOfNotNull(leftHip?.takeIf { it.visibility >= 0.35f }?.y, rightHip?.takeIf { it.visibility >= 0.35f }?.y)
        val validAnklesY = listOfNotNull(leftAnkle?.takeIf { it.visibility >= 0.35f }?.y, rightAnkle?.takeIf { it.visibility >= 0.35f }?.y)

        if (validShouldersY.isNotEmpty() && validHipsY.isNotEmpty() && validAnklesY.isNotEmpty()) {
            val shoulderY = validShouldersY.average().toFloat()
            val hipY = validHipsY.average().toFloat()
            val ankleY = validAnklesY.average().toFloat()
            if (hipY > shoulderY && ankleY > hipY) {
                val torsoSpan = hipY - shoulderY
                val legSpan = ankleY - hipY
                if (legSpan > 0.10f && torsoSpan > 0.05f) {
                    if (torsoSpan / legSpan < 0.42f || torsoSpan / legSpan > 1.35f) {
                        return "Place your phone at waist-to-chest height, not on the floor"
                    }
                }
            }
        }

        // 3. Distance check using bounding box height
        val validY = landmarks.filter { it.visibility >= 0.30f }.map { it.y }
        if (validY.isNotEmpty()) {
            val minY = validY.minOrNull() ?: 0.5f
            val maxY = validY.maxOrNull() ?: 0.5f
            val bboxHeight = maxY - minY
            if (bboxHeight < 0.40f) {
                return "Move closer"
            }
            if (bboxHeight > 0.85f) {
                return "Move back — you're too close"
            }
        }

        // 4. Orientation hint for preferred exercises or fallback
        return getLiveOrientationHint(landmarks)
    }

    /**
     * Provides a live framing hint during the 7-second analysis countdown
     * if the user's orientation contradicts the exercise's view requirement.
     */
    fun getLiveOrientationHint(landmarks: List<PoseLandmark>): String? {
        if (landmarks.isEmpty()) return null
        val landmarkMap = landmarks.associateBy { it.index }
        val leftShoulder = landmarkMap[PoseLandmarkType.LEFT_SHOULDER] ?: return null
        val rightShoulder = landmarkMap[PoseLandmarkType.RIGHT_SHOULDER] ?: return null
        if (leftShoulder.visibility < 0.35f || rightShoulder.visibility < 0.35f) return null

        val shoulderWidth = kotlin.math.abs(leftShoulder.x - rightShoulder.x)
        val viewReq = getViewRequirement()

        return when {
            (viewReq == CameraViewRequirement.SIDE_REQUIRED || viewReq == CameraViewRequirement.SIDE_PREFERRED) && shoulderWidth > 0.18f -> {
                "Turn sideways for this exercise"
            }
            (viewReq == CameraViewRequirement.FRONT_REQUIRED || viewReq == CameraViewRequirement.FRONT_PREFERRED) && shoulderWidth < 0.10f -> {
                "Face the camera for this exercise"
            }
            else -> null
        }
    }

    /**
     * Evaluates accumulated samples to produce the final SetupEvaluationResult.
     */
    fun evaluate(): SetupEvaluationResult {
        // Case 1: No person detected or detected in very few frames (< 30%)
        if (poseSamples == 0 || (totalSamples > 0 && poseSamples.toFloat() / totalSamples.toFloat() < 0.30f)) {
            return SetupEvaluationResult(
                isGood = false,
                overallConfidence = 0.0f,
                headline = "Step into frame — no person detected",
                actionableTip = "Position yourself in front of the camera so we can see you.",
                leftArmScore = 0.0f,
                rightArmScore = 0.0f,
                torsoScore = 0.0f,
                leftLegScore = 0.0f,
                rightLegScore = 0.0f,
                canStartAnyway = true
            )
        }

        // Compute average visibility per landmark across pose frames
        val avgVis = requiredIndices.associateWith { idx ->
            val list = visibilitySamples[idx]
            if (list.isNullOrEmpty()) 0.0f else list.average().toFloat()
        }

        // Compute average coordinates per landmark
        val avgX = requiredIndices.associateWith { idx ->
            val list = xSamples[idx]
            if (list.isNullOrEmpty()) 0.5f else list.average().toFloat()
        }
        val avgY = requiredIndices.associateWith { idx ->
            val list = ySamples[idx]
            if (list.isNullOrEmpty()) 0.5f else list.average().toFloat()
        }

        // Compute limb group scores
        val leftArmScore = listOfNotNull(
            avgVis[PoseLandmarkType.LEFT_SHOULDER],
            avgVis[PoseLandmarkType.LEFT_ELBOW],
            avgVis[PoseLandmarkType.LEFT_WRIST]
        ).average().toFloat()

        val rightArmScore = listOfNotNull(
            avgVis[PoseLandmarkType.RIGHT_SHOULDER],
            avgVis[PoseLandmarkType.RIGHT_ELBOW],
            avgVis[PoseLandmarkType.RIGHT_WRIST]
        ).average().toFloat()

        val leftLegScore = listOfNotNull(
            avgVis[PoseLandmarkType.LEFT_HIP],
            avgVis[PoseLandmarkType.LEFT_KNEE],
            avgVis[PoseLandmarkType.LEFT_ANKLE]
        ).average().toFloat()

        val rightLegScore = listOfNotNull(
            avgVis[PoseLandmarkType.RIGHT_HIP],
            avgVis[PoseLandmarkType.RIGHT_KNEE],
            avgVis[PoseLandmarkType.RIGHT_ANKLE]
        ).average().toFloat()

        val torsoScore = listOfNotNull(
            avgVis[PoseLandmarkType.NOSE],
            avgVis[PoseLandmarkType.LEFT_SHOULDER],
            avgVis[PoseLandmarkType.RIGHT_SHOULDER],
            avgVis[PoseLandmarkType.LEFT_HIP],
            avgVis[PoseLandmarkType.RIGHT_HIP]
        ).average().toFloat()

        val overallConfidence = avgVis.values.average().toFloat()

        // Coordinate boundary and distance checks
        val maxAnkleY = maxOf(avgY[PoseLandmarkType.LEFT_ANKLE] ?: 0.5f, avgY[PoseLandmarkType.RIGHT_ANKLE] ?: 0.5f)
        val minShoulderY = minOf(avgY[PoseLandmarkType.LEFT_SHOULDER] ?: 0.5f, avgY[PoseLandmarkType.RIGHT_SHOULDER] ?: 0.5f)
        val maxHipX = maxOf(avgX[PoseLandmarkType.LEFT_HIP] ?: 0.5f, avgX[PoseLandmarkType.RIGHT_HIP] ?: 0.5f)
        val minHipX = minOf(avgX[PoseLandmarkType.LEFT_HIP] ?: 0.5f, avgX[PoseLandmarkType.RIGHT_HIP] ?: 0.5f)

        // Bounding box height relative to frame height
        val minY = avgY.values.minOrNull() ?: 0.5f
        val maxY = avgY.values.maxOrNull() ?: 0.5f
        val bboxHeight = maxY - minY
        val isTooFar = bboxHeight < 0.40f
        val isTooClose = bboxHeight > 0.85f

        val isCutOff = maxAnkleY > 0.90f || minShoulderY < 0.08f || maxHipX > 0.90f || minHipX < 0.10f

        // Camera height / floor angle check via vertical foreshortening of torso vs legs
        val avgLeftShoulderY = avgY[PoseLandmarkType.LEFT_SHOULDER] ?: 0.5f
        val avgRightShoulderY = avgY[PoseLandmarkType.RIGHT_SHOULDER] ?: 0.5f
        val avgShoulderY = (avgLeftShoulderY + avgRightShoulderY) / 2f

        val avgLeftHipY = avgY[PoseLandmarkType.LEFT_HIP] ?: 0.5f
        val avgRightHipY = avgY[PoseLandmarkType.RIGHT_HIP] ?: 0.5f
        val avgHipY = (avgLeftHipY + avgRightHipY) / 2f

        val avgLeftAnkleY = avgY[PoseLandmarkType.LEFT_ANKLE] ?: 0.5f
        val avgRightAnkleY = avgY[PoseLandmarkType.RIGHT_ANKLE] ?: 0.5f
        val avgAnkleY = (avgLeftAnkleY + avgRightAnkleY) / 2f

        val torsoSpan = avgHipY - avgShoulderY
        val legSpan = avgAnkleY - avgHipY
        val isCameraHeightProblem = (legSpan > 0.10f && torsoSpan > 0.05f) &&
            (torsoSpan / legSpan < 0.42f || torsoSpan / legSpan > 1.35f)

        val maxLimbScore = maxOf(leftArmScore, rightArmScore, leftLegScore, rightLegScore)
        val isUniformlyLow = overallConfidence < 0.45f && maxLimbScore < 0.48f
        val isLowLightDetected = totalSamples > 0 && (lowLightSamples.toFloat() / totalSamples.toFloat() > 0.50f)

        // View orientation check
        val avgLeftShoulderX = avgX[PoseLandmarkType.LEFT_SHOULDER] ?: 0.5f
        val avgRightShoulderX = avgX[PoseLandmarkType.RIGHT_SHOULDER] ?: 0.5f
        val avgShoulderWidth = kotlin.math.abs(avgLeftShoulderX - avgRightShoulderX)
        val viewReq = getViewRequirement()

        val isWrongViewForStrictExercise = when (viewReq) {
            CameraViewRequirement.SIDE_REQUIRED -> avgShoulderWidth > 0.18f
            CameraViewRequirement.FRONT_REQUIRED -> avgShoulderWidth < 0.10f
            else -> false
        }

        // Hierarchical single-message selection
        val headline: String
        val actionableTip: String
        val isGood: Boolean
        var canStartAnyway = true

        when {
            // 1. Strict view requirement violation
            isWrongViewForStrictExercise -> {
                isGood = false
                canStartAnyway = false
                if (viewReq == CameraViewRequirement.SIDE_REQUIRED) {
                    headline = "Turn sideways for this exercise"
                    actionableTip = "This exercise requires a side view to track your movement accurately."
                } else {
                    headline = "Turn to face the camera"
                    actionableTip = "This exercise requires facing the camera directly to track your movement."
                }
            }

            // 2. Camera height / floor angle issue (e.g. phone propped low on floor pointing up)
            isCameraHeightProblem -> {
                isGood = false
                headline = "Place your phone at waist-to-chest height, not on the floor"
                actionableTip = "Elevate your phone to waist or chest level for accurate angle tracking."
            }

            // 3. Distance: Person too far (< 40% frame height)
            isTooFar -> {
                isGood = false
                headline = "Move closer"
                actionableTip = "Step closer so your body fills more of the frame."
            }

            // 4. Distance: Person too close (> 85% frame height)
            isTooClose -> {
                isGood = false
                headline = "Move back — you're too close"
                actionableTip = "Step back until your head, arms, and feet fit comfortably inside the frame."
            }

            // 5. Boundary / full body cut off
            isCutOff -> {
                isGood = false
                headline = "Move back — we can't see your full body"
                actionableTip = "Step back until your head, arms, and feet fit comfortably inside the frame."
            }

            // 6. Low light detected via luminance or uniformly low landmark visibility
            isLowLightDetected -> {
                isGood = false
                headline = "Low light detected — move to a brighter area"
                actionableTip = "Increase the lighting in front of you so your silhouette is clear."
            }

            isUniformlyLow -> {
                isGood = false
                headline = "Move to better lighting"
                actionableTip = "Increase the lighting in front of you so your silhouette is clear."
            }

            // 7. Specific limb poorly tracked
            rightArmScore < 0.40f -> {
                isGood = false
                headline = "We can't see your right arm — try adjusting your angle"
                actionableTip = "Ensure your right arm and hand are not hidden or blocked."
            }

            leftArmScore < 0.40f -> {
                isGood = false
                headline = "We can't see your left arm — try adjusting your angle"
                actionableTip = "Ensure your left arm and hand are not hidden or blocked."
            }

            leftLegScore < 0.40f || rightLegScore < 0.40f -> {
                isGood = false
                headline = "We can't see your legs — check your camera tilt"
                actionableTip = "Tilt the camera down slightly so both feet and legs are visible."
            }

            // 5. Preferred (non-strict) view sub-optimal orientation
            viewReq == CameraViewRequirement.SIDE_PREFERRED && avgShoulderWidth > 0.20f && overallConfidence < 0.65f -> {
                isGood = false
                headline = "Turn sideways for this exercise"
                actionableTip = "Position the camera to your side for best accuracy, or start anyway."
            }

            viewReq == CameraViewRequirement.FRONT_PREFERRED && avgShoulderWidth < 0.10f && overallConfidence < 0.65f -> {
                isGood = false
                headline = "Turn to face the camera"
                actionableTip = "Face the camera directly for best accuracy, or start anyway."
            }

            // 6. Borderline overall confidence
            overallConfidence < 0.50f -> {
                isGood = false
                headline = "Stand still and face the camera directly"
                actionableTip = "Position yourself clearly in view to ensure accurate rep tracking."
            }

            // 7. Setup verified and optimal
            else -> {
                isGood = true
                headline = "Great! We can see you clearly."
                actionableTip = "Your setup looks optimal. Ready to begin your workout!"
            }
        }

        return SetupEvaluationResult(
            isGood = isGood,
            overallConfidence = overallConfidence,
            headline = headline,
            actionableTip = actionableTip,
            leftArmScore = leftArmScore,
            rightArmScore = rightArmScore,
            torsoScore = torsoScore,
            leftLegScore = leftLegScore,
            rightLegScore = rightLegScore,
            canStartAnyway = canStartAnyway
        )
    }
}
