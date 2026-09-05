package com.example.cvassessment.sdk.pose

/**
 * Standard 33 BlazePose landmark indices and topological connections.
 */
object PoseLandmarkType {
    const val NOSE = 0
    const val LEFT_EYE_INNER = 1
    const val LEFT_EYE = 2
    const val LEFT_EYE_OUTER = 3
    const val RIGHT_EYE_INNER = 4
    const val RIGHT_EYE = 5
    const val RIGHT_EYE_OUTER = 6
    const val LEFT_EAR = 7
    const val RIGHT_EAR = 8
    const val MOUTH_LEFT = 9
    const val MOUTH_RIGHT = 10
    const val LEFT_SHOULDER = 11
    const val RIGHT_SHOULDER = 12
    const val LEFT_ELBOW = 13
    const val RIGHT_ELBOW = 14
    const val LEFT_WRIST = 15
    const val RIGHT_WRIST = 16
    const val LEFT_PINKY = 17
    const val RIGHT_PINKY = 18
    const val LEFT_INDEX = 19
    const val RIGHT_INDEX = 20
    const val LEFT_THUMB = 21
    const val RIGHT_THUMB = 22
    const val LEFT_HIP = 23
    const val RIGHT_HIP = 24
    const val LEFT_KNEE = 25
    const val RIGHT_KNEE = 26
    const val LEFT_ANKLE = 27
    const val RIGHT_ANKLE = 28
    const val LEFT_HEEL = 29
    const val RIGHT_HEEL = 30
    const val LEFT_FOOT_INDEX = 31
    const val RIGHT_FOOT_INDEX = 32

    private val NAMES = arrayOf(
        "NOSE",
        "LEFT_EYE_INNER", "LEFT_EYE", "LEFT_EYE_OUTER",
        "RIGHT_EYE_INNER", "RIGHT_EYE", "RIGHT_EYE_OUTER",
        "LEFT_EAR", "RIGHT_EAR",
        "MOUTH_LEFT", "MOUTH_RIGHT",
        "LEFT_SHOULDER", "RIGHT_SHOULDER",
        "LEFT_ELBOW", "RIGHT_ELBOW",
        "LEFT_WRIST", "RIGHT_WRIST",
        "LEFT_PINKY", "RIGHT_PINKY",
        "LEFT_INDEX", "RIGHT_INDEX",
        "LEFT_THUMB", "RIGHT_THUMB",
        "LEFT_HIP", "RIGHT_HIP",
        "LEFT_KNEE", "RIGHT_KNEE",
        "LEFT_ANKLE", "RIGHT_ANKLE",
        "LEFT_HEEL", "RIGHT_HEEL",
        "LEFT_FOOT_INDEX", "RIGHT_FOOT_INDEX"
    )

    fun getName(index: Int): String {
        return if (index in NAMES.indices) NAMES[index] else "LANDMARK_$index"
    }

    /**
     * Pairs of landmark indices defining the human skeleton lines.
     */
    val SKELETON_CONNECTIONS = listOf(
        // Face
        0 to 1, 1 to 2, 2 to 3, 3 to 7,
        0 to 4, 4 to 5, 5 to 6, 6 to 8,
        9 to 10,
        // Upper body / arms
        11 to 12, // shoulders
        11 to 13, 13 to 15, // left arm
        15 to 17, 15 to 19, 15 to 21, 17 to 19, // left hand
        12 to 14, 14 to 16, // right arm
        16 to 18, 16 to 20, 16 to 22, 18 to 20, // right hand
        // Torso
        11 to 23, 12 to 24, 23 to 24, // shoulders to hips, hip bridge
        // Lower body / legs
        23 to 25, 25 to 27, 27 to 29, 29 to 31, 27 to 31, // left leg & foot
        24 to 26, 26 to 28, 28 to 30, 30 to 32, 28 to 32  // right leg & foot
    )
}
