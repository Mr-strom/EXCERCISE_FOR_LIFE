package com.example.cvassessment.app

import com.example.cvassessment.sdk.SessionResult

/**
 * In-memory repository holder for passing session data between activities safely.
 */
object SessionDataHolder {
    var latestResult: SessionResult? = null
    var selectedExerciseId: String = "push_up"
    var selectedExerciseName: String = "Push-Up"

    fun clear() {
        latestResult = null
    }
}
