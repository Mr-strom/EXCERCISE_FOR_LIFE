package com.example.cvassessment.sdk.spec

import com.example.cvassessment.sdk.pose.PoseLandmarkType

/**
 * Structured exercise configuration data model per EXERCISE_SPEC.md.
 * Encodes exercise topology, phases, ROM expectations, and cadence baseline as pure data.
 *
 * @param exerciseId Unique string identifier (e.g. "push_up")
 * @param name Human-readable exercise display name (e.g. "Push-Up")
 * @param category Classification: dynamic_rep or static_hold
 * @param primaryLandmarks Required anatomical landmarks (e.g. ["shoulders", "elbows", "wrists", "hips", "ankles"])
 * @param trackedAngles List of joint angle identifiers computed per frame (e.g. ["elbow_angle", "hip_line_angle"])
 * @param phases Map of phase names to threshold conditions (e.g. "top" and "bottom")
 * @param romDefinition Definition of ROM criteria (fullExpectedAngle and minimumAcceptablePercent)
 * @param tutBaseline Expected seconds for a controlled repetition or hold baseline (e.g. 4.0s)
 * @param cameraNotes Recommended camera placement and positioning notes
 * @param angleDefinitions Detailed 3-point joint definitions for angles
 * @param requiredLandmarkIndices Underlying BlazePose landmark indices corresponding to primaryLandmarks
 */
data class ExerciseConfig(
    val exerciseId: String,
    val name: String,
    val category: ExerciseCategory,
    val primaryLandmarks: List<String>,
    val trackedAngles: List<String>,
    val phases: Map<String, PhaseCondition>,
    val romDefinition: RomDefinition,
    val tutBaseline: Float,
    val cameraNotes: String,
    val angleDefinitions: List<JointAngleDefinition> = emptyList(),
    val requiredLandmarkIndices: List<Int> = emptyList()
) {
    companion object {
        /**
         * Canonical Push-Up specification from EXERCISE_SPEC.md.
         *
         * - category: dynamic_rep
         * - primaryLandmarks: [shoulders, elbows, wrists, hips, ankles]
         * - trackedAngles: [elbow_angle, hip_line_angle]
         * - phases: top (elbow_angle > 160°) -> bottom (elbow_angle < 90°) -> top
         * - romDefinition: elbow_angle range from top to bottom; 100% = reaching <= 90°
         * - tutBaseline: 2.0s down + 2.0s up = 4.0s/rep
         * - cameraNotes: "side view most accurate for elbow angle; front/45° usable with reduced ROM precision"
         */
        val PUSH_UP = ExerciseConfig(
            exerciseId = "push_up",
            name = "Push-Up",
            category = ExerciseCategory.DYNAMIC_REP,
            primaryLandmarks = listOf("shoulders", "elbows", "wrists", "hips", "ankles"),
            trackedAngles = listOf("elbow_angle", "hip_line_angle"),
            phases = mapOf(
                "top" to PhaseCondition(
                    phaseName = "top",
                    trackedAngleName = "elbow_angle",
                    comparison = AngleComparison.GREATER_THAN,
                    thresholdAngle = 160.0f,
                    toleranceDeg = 10.0f
                ),
                "bottom" to PhaseCondition(
                    phaseName = "bottom",
                    trackedAngleName = "elbow_angle",
                    comparison = AngleComparison.LESS_THAN,
                    thresholdAngle = 90.0f,
                    toleranceDeg = 10.0f
                )
            ),
            romDefinition = RomDefinition(
                trackedAngleName = "elbow_angle",
                fullExpectedAngle = 90.0f,
                minimumAcceptablePercent = 60.0f,
                startingAngle = 160.0f,
                description = "elbow_angle range from top to bottom; 100% = reaching <= 90 deg"
            ),
            tutBaseline = 4.0f,
            cameraNotes = "side view most accurate for elbow angle; front/45° usable with reduced ROM precision",
            angleDefinitions = listOf(
                JointAngleDefinition(
                    angleName = "elbow_angle",
                    firstJoint = PoseLandmarkType.LEFT_SHOULDER,
                    vertexJoint = PoseLandmarkType.LEFT_ELBOW,
                    lastJoint = PoseLandmarkType.LEFT_WRIST,
                    description = "Elbow angle (shoulder-elbow-wrist) for flexion/extension tracking"
                ),
                JointAngleDefinition(
                    angleName = "hip_line_angle",
                    firstJoint = PoseLandmarkType.LEFT_SHOULDER,
                    vertexJoint = PoseLandmarkType.LEFT_HIP,
                    lastJoint = PoseLandmarkType.LEFT_ANKLE,
                    description = "Hip line angle (shoulder-hip-ankle) for sag/piking detection"
                )
            ),
            requiredLandmarkIndices = listOf(
                PoseLandmarkType.LEFT_SHOULDER,  // 11
                PoseLandmarkType.RIGHT_SHOULDER, // 12
                PoseLandmarkType.LEFT_ELBOW,     // 13
                PoseLandmarkType.RIGHT_ELBOW,    // 14
                PoseLandmarkType.LEFT_WRIST,     // 15
                PoseLandmarkType.RIGHT_WRIST,    // 16
                PoseLandmarkType.LEFT_HIP,       // 23
                PoseLandmarkType.RIGHT_HIP,      // 24
                PoseLandmarkType.LEFT_ANKLE,     // 27
                PoseLandmarkType.RIGHT_ANKLE     // 28
            )
        )

        /**
         * Convenience factory delegating to [ExerciseRegistry.getConfig].
         * Throws [com.example.cvassessment.sdk.UnknownExerciseException] if exerciseId is not registered.
         */
        fun load(exerciseId: String): ExerciseConfig = ExerciseRegistry.getConfig(exerciseId)
    }
}
