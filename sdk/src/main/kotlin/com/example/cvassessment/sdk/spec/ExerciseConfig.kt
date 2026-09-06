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
                    thresholdAngle = 105.0f,
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
         * Canonical Shoulder Press specification from EXERCISE_SPEC.md.
         *
         * - category: dynamic_rep
         * - primaryLandmarks: [shoulders, elbows, wrists, hips]
         * - trackedAngles: [elbow_angle, shoulder_elevation_angle]
         * - phases: bottom (elbow_angle ~90°, wrists near shoulder height) -> top (arms extended overhead, elbow_angle > 155° ± 10°) -> bottom
         * - romDefinition: shoulder_elevation_angle range; 100% = wrists reaching above head landmark
         * - tutBaseline: 1.5s up + 1.5s down = 3.0s/rep
         * - cameraNotes: "front or side both viable; elevated camera angle improves overhead ROM tracking"
         */
        val SHOULDER_PRESS = ExerciseConfig(
            exerciseId = "shoulder_press",
            name = "Shoulder Press",
            category = ExerciseCategory.DYNAMIC_REP,
            primaryLandmarks = listOf("shoulders", "elbows", "wrists", "hips"),
            trackedAngles = listOf("elbow_angle", "shoulder_elevation_angle"),
            phases = mapOf(
                "bottom" to PhaseCondition(
                    phaseName = "bottom",
                    trackedAngleName = "elbow_angle",
                    comparison = AngleComparison.LESS_THAN,
                    thresholdAngle = 90.0f,
                    toleranceDeg = 15.0f
                ),
                "top" to PhaseCondition(
                    phaseName = "top",
                    trackedAngleName = "elbow_angle",
                    comparison = AngleComparison.GREATER_THAN,
                    thresholdAngle = 155.0f,
                    toleranceDeg = 10.0f
                )
            ),
            romDefinition = RomDefinition(
                trackedAngleName = "shoulder_elevation_angle",
                fullExpectedAngle = 170.0f,
                minimumAcceptablePercent = 60.0f,
                startingAngle = 80.0f,
                description = "shoulder_elevation_angle range; 100% = wrists reaching above head landmark"
            ),
            tutBaseline = 3.0f,
            cameraNotes = "front or side both viable; elevated camera angle improves overhead ROM tracking",
            angleDefinitions = listOf(
                JointAngleDefinition(
                    angleName = "elbow_angle",
                    firstJoint = PoseLandmarkType.LEFT_SHOULDER,
                    vertexJoint = PoseLandmarkType.LEFT_ELBOW,
                    lastJoint = PoseLandmarkType.LEFT_WRIST,
                    description = "Elbow angle (shoulder-elbow-wrist) for overhead extension tracking"
                ),
                JointAngleDefinition(
                    angleName = "shoulder_elevation_angle",
                    firstJoint = PoseLandmarkType.LEFT_ELBOW,
                    vertexJoint = PoseLandmarkType.LEFT_SHOULDER,
                    lastJoint = PoseLandmarkType.LEFT_HIP,
                    description = "Shoulder elevation angle (elbow-shoulder-hip) for overhead press tracking"
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
         * Canonical Lunge specification from EXERCISE_SPEC.md.
         *
         * - category: dynamic_rep
         * - primaryLandmarks: [hips, knees, ankles] (both legs)
         * - trackedAngles: [front_knee_angle, back_knee_angle, torso_vertical_angle]
         * - phases: top (both knee_angles > 160°) -> bottom (front_knee_angle ~90°) -> top
         * - romDefinition: front_knee_angle range; 100% = reaching <= 90°
         * - tutBaseline: 2.0s down + 2.0s up = 4.0s/rep
         * - cameraNotes: "side view strongly preferred — front view struggles to separate front/back leg angles"
         */
        val LUNGE = ExerciseConfig(
            exerciseId = "lunge",
            name = "Lunge",
            category = ExerciseCategory.DYNAMIC_REP,
            primaryLandmarks = listOf("hips", "knees", "ankles"),
            trackedAngles = listOf("front_knee_angle", "back_knee_angle", "torso_vertical_angle"),
            phases = mapOf(
                "top" to PhaseCondition(
                    phaseName = "top",
                    trackedAngleName = "front_knee_angle",
                    comparison = AngleComparison.GREATER_THAN,
                    thresholdAngle = 160.0f,
                    toleranceDeg = 10.0f
                ),
                "bottom" to PhaseCondition(
                    phaseName = "bottom",
                    trackedAngleName = "front_knee_angle",
                    comparison = AngleComparison.LESS_THAN,
                    thresholdAngle = 90.0f,
                    toleranceDeg = 15.0f
                )
            ),
            romDefinition = RomDefinition(
                trackedAngleName = "front_knee_angle",
                fullExpectedAngle = 90.0f,
                minimumAcceptablePercent = 60.0f,
                startingAngle = 160.0f,
                description = "front_knee_angle range; 100% = front knee reaching ~90 deg, back knee lowering toward floor"
            ),
            tutBaseline = 4.0f,
            cameraNotes = "side view strongly preferred — front view struggles to separate front/back leg angles",
            angleDefinitions = listOf(
                JointAngleDefinition(
                    angleName = "front_knee_angle",
                    firstJoint = PoseLandmarkType.LEFT_HIP,
                    vertexJoint = PoseLandmarkType.LEFT_KNEE,
                    lastJoint = PoseLandmarkType.LEFT_ANKLE,
                    description = "Front knee angle (hip-knee-ankle) for lunge depth tracking"
                ),
                JointAngleDefinition(
                    angleName = "back_knee_angle",
                    firstJoint = PoseLandmarkType.RIGHT_HIP,
                    vertexJoint = PoseLandmarkType.RIGHT_KNEE,
                    lastJoint = PoseLandmarkType.RIGHT_ANKLE,
                    description = "Back knee angle (hip-knee-ankle) for back leg extension/drop"
                ),
                JointAngleDefinition(
                    angleName = "torso_vertical_angle",
                    firstJoint = PoseLandmarkType.LEFT_SHOULDER,
                    vertexJoint = PoseLandmarkType.LEFT_HIP,
                    lastJoint = PoseLandmarkType.LEFT_KNEE,
                    description = "Torso vertical angle for upper-body posture stability"
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
                PoseLandmarkType.RIGHT_ANKLE      // 28
            )
        )

        /**
         * Calf Raise specification per EXERCISE_SPEC.md #6 and DECISIONS.md D7:
         * - category: dynamic_rep
         * - primaryLandmarks: [ankles, knees, heels, foot_indices]
         * - trackedAngles: [ankle_vertical_displacement] (heel height relative to calibrated standing baseline)
         * - phases: bottom (heel at ground baseline) -> top (max heel elevation) -> bottom
         * - romDefinition: heel vertical displacement relative to calibrated standing baseline; 100% = max individual elevation achieved in first rep
         * - tutBaseline: 1.0s up + 1.0s down = 2.0s/rep
         * - cameraNotes: "side view required; front/45° cannot reliably detect heel elevation"
         */
        val CALF_RAISE = ExerciseConfig(
            exerciseId = "calf_raise",
            name = "Calf Raise",
            category = ExerciseCategory.DYNAMIC_REP,
            primaryLandmarks = listOf("ankles", "knees", "heels", "foot_indices"),
            trackedAngles = listOf("ankle_vertical_displacement"),
            phases = mapOf(
                "bottom" to PhaseCondition(
                    phaseName = "bottom",
                    trackedAngleName = "ankle_vertical_displacement",
                    comparison = AngleComparison.LESS_THAN,
                    thresholdAngle = 0.02f,
                    toleranceDeg = 0.01f
                ),
                "top" to PhaseCondition(
                    phaseName = "top",
                    trackedAngleName = "ankle_vertical_displacement",
                    comparison = AngleComparison.GREATER_THAN,
                    thresholdAngle = 0.05f,
                    toleranceDeg = 0.02f
                )
            ),
            romDefinition = RomDefinition(
                trackedAngleName = "ankle_vertical_displacement",
                fullExpectedAngle = 100.0f,
                minimumAcceptablePercent = 50.0f,
                startingAngle = 0.0f,
                description = "heel vertical displacement relative to calibrated standing baseline; 100% = max individual elevation in first rep"
            ),
            tutBaseline = 2.0f,
            cameraNotes = "side view required; front/45° cannot reliably detect heel elevation",
            angleDefinitions = listOf(
                JointAngleDefinition(
                    angleName = "ankle_vertical_displacement",
                    firstJoint = PoseLandmarkType.LEFT_KNEE,
                    vertexJoint = PoseLandmarkType.LEFT_ANKLE,
                    lastJoint = PoseLandmarkType.LEFT_HEEL,
                    description = "Vertical elevation displacement of heel relative to calibrated baseline"
                )
            ),
            requiredLandmarkIndices = listOf(
                PoseLandmarkType.LEFT_KNEE,        // 25
                PoseLandmarkType.RIGHT_KNEE,       // 26
                PoseLandmarkType.LEFT_ANKLE,       // 27
                PoseLandmarkType.RIGHT_ANKLE,      // 28
                PoseLandmarkType.LEFT_HEEL,        // 29
                PoseLandmarkType.RIGHT_HEEL,       // 30
                PoseLandmarkType.LEFT_FOOT_INDEX,  // 31
                PoseLandmarkType.RIGHT_FOOT_INDEX  // 32
            )
        )

        /**
         * Convenience factory delegating to [ExerciseRegistry.getConfig].
         * Throws [com.example.cvassessment.sdk.UnknownExerciseException] if exerciseId is not registered.
         */
        fun load(exerciseId: String): ExerciseConfig = ExerciseRegistry.getConfig(exerciseId)
    }
}
