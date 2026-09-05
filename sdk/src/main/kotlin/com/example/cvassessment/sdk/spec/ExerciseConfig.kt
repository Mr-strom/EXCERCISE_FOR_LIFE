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
         * Canonical Squat specification from EXERCISE_SPEC.md.
         *
         * - category: dynamic_rep
         * - primaryLandmarks: [hips, knees, ankles, shoulders]
         * - trackedAngles: [knee_angle, hip_angle, back_angle]
         * - phases: top (knee_angle > 160°) -> bottom (knee_angle < 100°, target depth, tolerance ±10°) -> top
         * - romDefinition: knee_angle range; 100% = reaching <= 100° depth
         * - tutBaseline: 2.0s down + 2.0s up = 4.0s/rep
         * - cameraNotes: "side view most accurate for depth; front view good for knee valgus detection"
         */
        val SQUAT = ExerciseConfig(
            exerciseId = "squat",
            name = "Squat",
            category = ExerciseCategory.DYNAMIC_REP,
            primaryLandmarks = listOf("hips", "knees", "ankles", "shoulders"),
            trackedAngles = listOf("knee_angle", "hip_angle", "back_angle"),
            phases = mapOf(
                "top" to PhaseCondition(
                    phaseName = "top",
                    trackedAngleName = "knee_angle",
                    comparison = AngleComparison.GREATER_THAN,
                    thresholdAngle = 160.0f,
                    toleranceDeg = 10.0f
                ),
                "bottom" to PhaseCondition(
                    phaseName = "bottom",
                    trackedAngleName = "knee_angle",
                    comparison = AngleComparison.LESS_THAN,
                    thresholdAngle = 100.0f,
                    toleranceDeg = 10.0f
                )
            ),
            romDefinition = RomDefinition(
                trackedAngleName = "knee_angle",
                fullExpectedAngle = 100.0f,
                minimumAcceptablePercent = 60.0f,
                startingAngle = 160.0f,
                description = "knee_angle range; 100% = reaching <= 100 deg depth"
            ),
            tutBaseline = 4.0f,
            cameraNotes = "side view most accurate for depth; front view good for knee valgus detection",
            angleDefinitions = listOf(
                JointAngleDefinition(
                    angleName = "knee_angle",
                    firstJoint = PoseLandmarkType.LEFT_HIP,
                    vertexJoint = PoseLandmarkType.LEFT_KNEE,
                    lastJoint = PoseLandmarkType.LEFT_ANKLE,
                    description = "Knee angle (hip-knee-ankle) for flexion/depth tracking"
                ),
                JointAngleDefinition(
                    angleName = "hip_angle",
                    firstJoint = PoseLandmarkType.LEFT_SHOULDER,
                    vertexJoint = PoseLandmarkType.LEFT_HIP,
                    lastJoint = PoseLandmarkType.LEFT_KNEE,
                    description = "Hip angle (shoulder-hip-knee) for hip hinge / forward lean"
                )
            ),
            requiredLandmarkIndices = listOf(
                PoseLandmarkType.LEFT_SHOULDER,  // 11
                PoseLandmarkType.RIGHT_SHOULDER, // 12
                PoseLandmarkType.LEFT_HIP,       // 23
                PoseLandmarkType.RIGHT_HIP,      // 24
                PoseLandmarkType.LEFT_KNEE,      // 25
                PoseLandmarkType.RIGHT_KNEE,     // 26
                PoseLandmarkType.LEFT_ANKLE,     // 27
                PoseLandmarkType.RIGHT_ANKLE     // 28
            )
        )

        /**
         * Canonical Bicep Curl specification from EXERCISE_SPEC.md.
         *
         * - category: dynamic_rep
         * - primaryLandmarks: [shoulders, elbows, wrists]
         * - trackedAngles: [elbow_angle, shoulder_stability]
         * - phases: bottom (elbow_angle > 160°) -> top (elbow_angle < 45°) -> bottom
         * - romDefinition: elbow_angle range top-to-bottom; 100% = reaching <= 45° at top, >= 160° at bottom
         * - tutBaseline: 1.5s up + 1.5s down = 3.0s/rep
         * - cameraNotes: "front or 45° preferred to see both arms; side view only tracks near-side arm"
         */
        val BICEP_CURL = ExerciseConfig(
            exerciseId = "bicep_curl",
            name = "Bicep Curl",
            category = ExerciseCategory.DYNAMIC_REP,
            primaryLandmarks = listOf("shoulders", "elbows", "wrists"),
            trackedAngles = listOf("elbow_angle", "shoulder_stability"),
            phases = mapOf(
                "bottom" to PhaseCondition(
                    phaseName = "bottom",
                    trackedAngleName = "elbow_angle",
                    comparison = AngleComparison.GREATER_THAN,
                    thresholdAngle = 160.0f,
                    toleranceDeg = 10.0f
                ),
                "top" to PhaseCondition(
                    phaseName = "top",
                    trackedAngleName = "elbow_angle",
                    comparison = AngleComparison.LESS_THAN,
                    thresholdAngle = 45.0f,
                    toleranceDeg = 10.0f
                )
            ),
            romDefinition = RomDefinition(
                trackedAngleName = "elbow_angle",
                fullExpectedAngle = 45.0f,
                minimumAcceptablePercent = 60.0f,
                startingAngle = 160.0f,
                description = "elbow_angle range top-to-bottom; 100% = reaching <= 45 deg at top, >= 160 deg at bottom"
            ),
            tutBaseline = 3.0f,
            cameraNotes = "front or 45° preferred to see both arms; side view only tracks near-side arm",
            angleDefinitions = listOf(
                JointAngleDefinition(
                    angleName = "elbow_angle",
                    firstJoint = PoseLandmarkType.LEFT_SHOULDER,
                    vertexJoint = PoseLandmarkType.LEFT_ELBOW,
                    lastJoint = PoseLandmarkType.LEFT_WRIST,
                    description = "Elbow angle (shoulder-elbow-wrist) for curl flexion/extension"
                ),
                JointAngleDefinition(
                    angleName = "shoulder_stability",
                    firstJoint = PoseLandmarkType.LEFT_SHOULDER,
                    vertexJoint = PoseLandmarkType.LEFT_HIP,
                    lastJoint = PoseLandmarkType.LEFT_ANKLE,
                    description = "Shoulder stability (shoulder-hip vertical) detects swinging/momentum"
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
                PoseLandmarkType.RIGHT_HIP       // 24
            )
        )

        /**
         * Convenience factory delegating to [ExerciseRegistry.getConfig].
         * Throws [com.example.cvassessment.sdk.UnknownExerciseException] if exerciseId is not registered.
         */
        fun load(exerciseId: String): ExerciseConfig = ExerciseRegistry.getConfig(exerciseId)
    }
}
