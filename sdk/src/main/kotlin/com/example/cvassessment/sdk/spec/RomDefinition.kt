package com.example.cvassessment.sdk.spec

/**
 * Range of Motion (ROM) definition per EXERCISE_SPEC.md and METRICS_SPEC.md.
 *
 * @param fullExpectedAngle Angle threshold representing 100% full ROM (e.g. 90.0° for push-up)
 * @param minimumAcceptablePercent Minimum ROM percentage to qualify as acceptable execution (e.g. 60.0%)
 * @param trackedAngleName Name of tracked angle evaluated for ROM (default "elbow_angle")
 * @param startingAngle Baseline angle representing starting lockout (e.g. 160.0°)
 * @param description Semantic explanation from specification
 */
data class RomDefinition(
    val fullExpectedAngle: Float,
    val minimumAcceptablePercent: Float,
    val trackedAngleName: String = "elbow_angle",
    val startingAngle: Float = 160.0f,
    val description: String = ""
)
