package com.example.cvassessment.sdk.statemachine

/**
 * Output state emitted per-frame by the Exercise State Machine (Module 4).
 *
 * @param phase Current exercise movement phase (TOP, DESCENDING, BOTTOM, ASCENDING)
 * @param currentElbowAngle Computed primary elbow angle for the current frame
 * @param currentHipLineAngle Computed hip line angle (shoulder-hip-ankle) for form monitoring
 * @param completeReps Cumulative list of all complete rep boundaries detected in the session
 * @param incompleteReps Cumulative list of all incomplete rep attempts detected in the session
 * @param newlyCompletedRep Present only on the exact frame a complete rep is finalized
 * @param newlyDetectedIncompleteRep Present only on the exact frame an incomplete rep is finalized
 * @param isRepInProgress True if user has initiated a rep and has not yet completed or aborted
 * @param currentRepMinAngle Deepest angle reached during the currently active rep attempt
 */
data class ExerciseState(
    val phase: ExercisePhase,
    val currentElbowAngle: Float,
    val currentHipLineAngle: Float,
    val completeReps: List<RepBoundary> = emptyList(),
    val incompleteReps: List<IncompleteRep> = emptyList(),
    val newlyCompletedRep: RepBoundary? = null,
    val newlyDetectedIncompleteRep: IncompleteRep? = null,
    val isRepInProgress: Boolean = false,
    val currentRepMinAngle: Float? = null
) {
    val completeRepCount: Int get() = completeReps.size
    val incompleteRepCount: Int get() = incompleteReps.size
}
