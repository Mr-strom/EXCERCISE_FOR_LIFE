package com.example.cvassessment.sdk.spec

/**
 * Exercise category classification per EXERCISE_SPEC.md.
 */
enum class ExerciseCategory(val identifier: String) {
    DYNAMIC_REP("dynamic_rep"),
    STATIC_HOLD("static_hold");

    override fun toString(): String = identifier

    companion object {
        fun fromIdentifier(id: String): ExerciseCategory {
            return entries.firstOrNull { it.identifier.equals(id, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown exercise category: '$id'")
        }
    }
}
