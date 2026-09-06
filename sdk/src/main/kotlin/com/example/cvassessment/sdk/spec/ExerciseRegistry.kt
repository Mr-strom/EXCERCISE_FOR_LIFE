package com.example.cvassessment.sdk.spec

import com.example.cvassessment.sdk.UnknownExerciseException

/**
 * Registry and loader for exercise configurations defined in EXERCISE_SPEC.md.
 * Allows retrieving configurations dynamically by exerciseId.
 *
 * Per architecture rules (R11.2), exercises are defined as data, not hardcoded engine logic.
 */
object ExerciseRegistry {

    private val configs: Map<String, ExerciseConfig> = mapOf(
        ExerciseConfig.PUSH_UP.exerciseId to ExerciseConfig.PUSH_UP,
        ExerciseConfig.SQUAT.exerciseId to ExerciseConfig.SQUAT,
        ExerciseConfig.BICEP_CURL.exerciseId to ExerciseConfig.BICEP_CURL,
        ExerciseConfig.SHOULDER_PRESS.exerciseId to ExerciseConfig.SHOULDER_PRESS
    )

    /**
     * Loads the ExerciseConfig for the given exerciseId.
     *
     * @param exerciseId The unique identifier of the exercise (e.g. "push_up")
     * @return The corresponding ExerciseConfig
     * @throws UnknownExerciseException if the exerciseId is not registered
     */
    fun getConfig(exerciseId: String): ExerciseConfig {
        val normalizedId = exerciseId.trim().lowercase()
        return configs[normalizedId]
            ?: throw UnknownExerciseException(
                "Unknown exercise ID: '$exerciseId'. Supported exercises: ${configs.keys}"
            )
    }

    /**
     * Checks whether an exerciseId is recognized and supported.
     */
    fun isSupported(exerciseId: String): Boolean {
        return configs.containsKey(exerciseId.trim().lowercase())
    }

    /**
     * Returns all registered exercise configurations.
     */
    fun getAllConfigs(): List<ExerciseConfig> = configs.values.toList()

    /**
     * Returns all registered exercise IDs.
     */
    fun getSupportedExerciseIds(): Set<String> = configs.keys
}
