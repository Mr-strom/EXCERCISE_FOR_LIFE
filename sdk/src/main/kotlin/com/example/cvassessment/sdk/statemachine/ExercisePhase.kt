package com.example.cvassessment.sdk.statemachine

/**
 * Movement phases tracked by the Exercise State Machine (Module 4) per EXERCISE_SPEC.md.
 */
enum class ExercisePhase {
    /**
     * Top resting/lockout phase (elbow_angle >= 160° for Push-Up).
     */
    TOP,

    /**
     * Downward motion towards inflection (elbow_angle decreasing).
     */
    DESCENDING,

    /**
     * Inflection zone at target depth (elbow_angle < 90°, tolerance ±10°).
     */
    BOTTOM,

    /**
     * Upward motion returning to lockout (elbow_angle increasing towards 160°).
     */
    ASCENDING
}
