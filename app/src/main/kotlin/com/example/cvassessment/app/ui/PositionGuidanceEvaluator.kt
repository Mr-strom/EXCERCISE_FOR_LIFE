package com.example.cvassessment.app.ui

import com.example.cvassessment.sdk.pose.PoseLandmark
import com.example.cvassessment.sdk.pose.PoseLandmarkType

/**
 * Evaluates real-time landmark coordinates and confidences to provide
 * proactive position guidance messages and diagnostic explanation for Screen 3.
 */
object PositionGuidanceEvaluator {

    data class GuidanceResult(
        val guidanceMessage: String,
        val isWarning: Boolean,
        val insufficientWhyMessage: String,
        val lowConfidenceIndices: Set<Int>
    )

    fun formatFriendlyName(index: Int): String {
        return when (index) {
            PoseLandmarkType.LEFT_SHOULDER -> "Left shoulder"
            PoseLandmarkType.RIGHT_SHOULDER -> "Right shoulder"
            PoseLandmarkType.LEFT_ELBOW -> "Left elbow"
            PoseLandmarkType.RIGHT_ELBOW -> "Right elbow"
            PoseLandmarkType.LEFT_WRIST -> "Left wrist"
            PoseLandmarkType.RIGHT_WRIST -> "Right wrist"
            PoseLandmarkType.LEFT_HIP -> "Left hip"
            PoseLandmarkType.RIGHT_HIP -> "Right hip"
            PoseLandmarkType.LEFT_ANKLE -> "Left ankle"
            PoseLandmarkType.RIGHT_ANKLE -> "Right ankle"
            else -> PoseLandmarkType.getName(index).replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
        }
    }

    /**
     * Evaluates live frame landmarks for Screen 3.
     */
    fun evaluate(
        landmarks: List<PoseLandmark>,
        hasPose: Boolean,
        requiredIndices: List<Int> = listOf(11, 12, 13, 14, 15, 16, 23, 24, 27, 28)
    ): GuidanceResult {
        if (!hasPose || landmarks.isEmpty()) {
            return GuidanceResult(
                guidanceMessage = "Step into frame — no person detected",
                isWarning = true,
                insufficientWhyMessage = "Why: No person detected in camera frame",
                lowConfidenceIndices = requiredIndices.toSet()
            )
        }

        val landmarkMap = landmarks.associateBy { it.index }
        val requiredLandmarks = requiredIndices.mapNotNull { landmarkMap[it] }

        val lowConfidenceIndices = requiredIndices.filter { idx ->
            val lm = landmarkMap[idx]
            lm == null || lm.visibility < 0.40f
        }.toSet()

        // Identify the landmark with the lowest visibility for diagnosis
        val worstLandmarkInfo = requiredIndices.map { idx ->
            val lm = landmarkMap[idx]
            val vis = lm?.visibility ?: 0.0f
            val isOutOfFrame = lm != null && (lm.x < 0.05f || lm.x > 0.95f || lm.y < 0.05f || lm.y > 0.95f)
            Triple(idx, vis, isOutOfFrame)
        }.minByOrNull { it.second }

        val whyMessage = if (worstLandmarkInfo != null) {
            val name = formatFriendlyName(worstLandmarkInfo.first)
            if (worstLandmarkInfo.third) {
                "Why: $name moved out of frame"
            } else {
                "Why: $name confidence dropped"
            }
        } else {
            "Why: Required joints occluded"
        }

        // Proactive edge & distance checks (triggering 2-3s before complete loss)
        val leftHip = landmarkMap[PoseLandmarkType.LEFT_HIP]
        val rightHip = landmarkMap[PoseLandmarkType.RIGHT_HIP]
        val leftShoulder = landmarkMap[PoseLandmarkType.LEFT_SHOULDER]
        val rightShoulder = landmarkMap[PoseLandmarkType.RIGHT_SHOULDER]
        val leftAnkle = landmarkMap[PoseLandmarkType.LEFT_ANKLE]
        val rightAnkle = landmarkMap[PoseLandmarkType.RIGHT_ANKLE]

        val maxHipX = maxOf(leftHip?.x ?: 0f, rightHip?.x ?: 0f)
        val minHipX = minOf(leftHip?.x ?: 1f, rightHip?.x ?: 1f)

        val maxShoulderX = maxOf(leftShoulder?.x ?: 0f, rightShoulder?.x ?: 0f)
        val minShoulderX = minOf(leftShoulder?.x ?: 1f, rightShoulder?.x ?: 1f)

        val maxAnkleY = maxOf(leftAnkle?.y ?: 0f, rightAnkle?.y ?: 0f)
        val minShoulderY = minOf(leftShoulder?.y ?: 1f, rightShoulder?.y ?: 1f)

        // Height span of torso/legs
        val avgShoulderY = listOfNotNull(leftShoulder?.y, rightShoulder?.y).average()
        val avgAnkleY = listOfNotNull(leftAnkle?.y, rightAnkle?.y).average()
        val bodySpan = if (!avgShoulderY.isNaN() && !avgAnkleY.isNaN()) (avgAnkleY - avgShoulderY) else 0.5

        val guidanceMessage: String
        val isWarning: Boolean

        when {
            // 1. Proactive horizontal boundary warnings
            maxHipX > 0.85f -> {
                guidanceMessage = "Move left — hip out of frame"
                isWarning = true
            }
            minHipX < 0.15f -> {
                guidanceMessage = "Move right — hip out of frame"
                isWarning = true
            }
            maxShoulderX > 0.88f -> {
                guidanceMessage = "Move left — shoulder out of frame"
                isWarning = true
            }
            minShoulderX < 0.12f -> {
                guidanceMessage = "Move right — shoulder out of frame"
                isWarning = true
            }

            // 2. Proactive vertical boundary warnings
            maxAnkleY > 0.88f -> {
                guidanceMessage = "Move back — feet cut off"
                isWarning = true
            }
            minShoulderY < 0.08f -> {
                guidanceMessage = "Move back — head cut off"
                isWarning = true
            }

            // 3. Proactive distance scale check
            bodySpan > 0.0 && bodySpan < 0.28 -> {
                guidanceMessage = "Move closer — arms too far"
                isWarning = true
            }

            // 4. Proactive low confidence warning on specific joint
            lowConfidenceIndices.isNotEmpty() -> {
                val worstIndex = lowConfidenceIndices.first()
                val name = formatFriendlyName(worstIndex)
                guidanceMessage = "Adjust position — $name confidence dropping"
                isWarning = true
            }

            // 5. Normal full body tracking
            else -> {
                guidanceMessage = "Full body visible"
                isWarning = false
            }
        }

        return GuidanceResult(
            guidanceMessage = guidanceMessage,
            isWarning = isWarning,
            insufficientWhyMessage = whyMessage,
            lowConfidenceIndices = lowConfidenceIndices
        )
    }
}
