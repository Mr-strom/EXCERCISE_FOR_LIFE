package com.example.cvassessment.sdk.statemachine

import com.example.cvassessment.sdk.pose.PoseEstimationResult
import com.example.cvassessment.sdk.spec.ExerciseConfig

/**
 * Exercise State Machine for Squat per EXERCISE_SPEC.md.
 *
 * Tracks knee angle frame-by-frame, detects movement phase transitions
 * (TOP, DESCENDING, BOTTOM, ASCENDING), and identifies rep boundaries
 * (complete repetitions and incomplete repetition attempts).
 *
 * Thresholds per EXERCISE_SPEC.md:
 * - Top phase: knee_angle > 160° (tolerance ±10°)
 * - Bottom phase: knee_angle < 100° (tolerance ±10°, i.e. <= 110°)
 * - Reversal hysteresis: 8.0° to prevent BlazePose jitter from causing false reversals
 */
internal class SquatStateMachine(
    val config: ExerciseConfig = ExerciseConfig.SQUAT
) {
    var currentPhase: ExercisePhase = ExercisePhase.TOP
        private set

    var currentState: ExerciseState = ExerciseState(
        phase = ExercisePhase.TOP,
        currentElbowAngle = 180.0f, // Primary tracked angle (knee_angle for squat)
        currentHipLineAngle = 180.0f // Secondary tracked angle (hip_angle for squat)
    )
        private set

    private val _completeReps = mutableListOf<RepBoundary>()
    val completeReps: List<RepBoundary> get() = _completeReps.toList()

    private val _incompleteReps = mutableListOf<IncompleteRep>()
    val incompleteReps: List<IncompleteRep> get() = _incompleteReps.toList()

    private var isRepInProgress = false
    private var reachedBottom = false
    private var minKneeAngleThisRep = 180.0f
    private var repStartTimestampMs = 0L
    private var bottomTimestampMs = 0L

    // Squat thresholds configured from EXERCISE_SPEC.md via ExerciseConfig
    private val topThreshold: Float = config.phases["top"]?.thresholdAngle ?: 160.0f
    private val topTolerance: Float = config.phases["top"]?.toleranceDeg ?: 10.0f
    private val bottomThreshold: Float = config.phases["bottom"]?.thresholdAngle ?: 100.0f
    private val bottomTolerance: Float = config.phases["bottom"]?.toleranceDeg ?: 10.0f

    // Reversal threshold to avoid false-positive reversals from frame-to-frame tracking jitter
    private val reversalHysteresisDeg: Float = 8.0f

    /**
     * Process a raw [PoseEstimationResult] through Squat geometry and state machine transitions.
     */
    fun processFrame(
        poseResult: PoseEstimationResult,
        isVisibilitySufficient: Boolean = true
    ): ExerciseState {
        val kneeAngle = SquatGeometry.computeKneeAngle(poseResult.landmarks)
        val hipAngle = SquatGeometry.computeHipAngle(poseResult.landmarks)
        return processAngle(kneeAngle, hipAngle, poseResult.timestampMs, isVisibilitySufficient)
    }

    /**
     * Process directly with knee angle, hip angle, and frame timestamp.
     * Essential for feeding synthetic angle sequences during unit testing.
     *
     * @param kneeAngle Primary tracked angle (hip-knee-ankle)
     * @param hipAngle Secondary tracked angle (shoulder-hip-knee)
     * @param timestampMs Frame timestamp in milliseconds
     * @param isVisibilitySufficient Visibility Gate signal
     */
    fun processAngle(
        kneeAngle: Float,
        hipAngle: Float,
        timestampMs: Long,
        isVisibilitySufficient: Boolean = true
    ): ExerciseState {
        // Enforce Acceptance Criterion #3: Visibility gap mid-rep discards in-progress rep
        if (!isVisibilitySufficient) {
            if (isRepInProgress) {
                isRepInProgress = false
                reachedBottom = false
                minKneeAngleThisRep = 180.0f
                repStartTimestampMs = 0L
                bottomTimestampMs = 0L
            }
            currentPhase = ExercisePhase.TOP
            return ExerciseState(
                phase = currentPhase,
                currentElbowAngle = kneeAngle,
                currentHipLineAngle = hipAngle,
                completeReps = _completeReps.toList(),
                incompleteReps = _incompleteReps.toList(),
                newlyCompletedRep = null,
                newlyDetectedIncompleteRep = null,
                isRepInProgress = false,
                currentRepMinAngle = null
            )
        }

        var newlyCompleted: RepBoundary? = null
        var newlyIncomplete: IncompleteRep? = null

        when (currentPhase) {
            ExercisePhase.TOP -> {
                isRepInProgress = false
                minKneeAngleThisRep = kneeAngle

                // User starts descending below top threshold with 5° buffer
                if (kneeAngle < (topThreshold - 5.0f)) {
                    currentPhase = ExercisePhase.DESCENDING
                    isRepInProgress = true
                    reachedBottom = false
                    repStartTimestampMs = timestampMs
                    minKneeAngleThisRep = kneeAngle
                }
            }

            ExercisePhase.DESCENDING -> {
                if (kneeAngle < minKneeAngleThisRep) {
                    minKneeAngleThisRep = kneeAngle
                }

                // Check if user reached bottom tolerance (< 100° ± 10°, i.e. <= 110°)
                if (kneeAngle <= (bottomThreshold + bottomTolerance)) {
                    currentPhase = ExercisePhase.BOTTOM
                    reachedBottom = true
                    bottomTimestampMs = timestampMs
                } else if (kneeAngle >= (topThreshold - 5.0f)) {
                    // Returned directly to top without reaching bottom: incomplete rep
                    val incomplete = IncompleteRep(
                        attemptIndex = _incompleteReps.size + 1,
                        startTimestampMs = repStartTimestampMs,
                        reversalTimestampMs = timestampMs,
                        minElbowAngleAchieved = minKneeAngleThisRep,
                        reason = "Reversed before reaching bottom target (achieved ${minKneeAngleThisRep}° vs <= ${bottomThreshold + bottomTolerance}°)"
                    )
                    _incompleteReps.add(incomplete)
                    newlyIncomplete = incomplete
                    isRepInProgress = false
                    reachedBottom = false
                    currentPhase = ExercisePhase.TOP
                } else if (kneeAngle > (minKneeAngleThisRep + reversalHysteresisDeg)) {
                    // Upward reversal detected before reaching bottom
                    currentPhase = ExercisePhase.ASCENDING
                }
            }

            ExercisePhase.BOTTOM -> {
                if (kneeAngle < minKneeAngleThisRep) {
                    minKneeAngleThisRep = kneeAngle
                    bottomTimestampMs = timestampMs
                }

                // Transition to ascending when leg extension begins
                if (kneeAngle > (minKneeAngleThisRep + 5.0f) || kneeAngle > (bottomThreshold + bottomTolerance)) {
                    currentPhase = ExercisePhase.ASCENDING
                    if (kneeAngle >= (topThreshold - 5.0f)) {
                        val rep = RepBoundary(
                            repIndex = _completeReps.size + 1,
                            startTimestampMs = repStartTimestampMs,
                            bottomTimestampMs = bottomTimestampMs,
                            endTimestampMs = timestampMs,
                            durationMs = timestampMs - repStartTimestampMs,
                            minElbowAngle = minKneeAngleThisRep,
                            isComplete = true
                        )
                        _completeReps.add(rep)
                        newlyCompleted = rep
                        isRepInProgress = false
                        reachedBottom = false
                        currentPhase = ExercisePhase.TOP
                    }
                }
            }

            ExercisePhase.ASCENDING -> {
                // Check if user returned to top lockout position (>= 160° - 5.0°)
                if (kneeAngle >= (topThreshold - 5.0f)) {
                    if (reachedBottom) {
                        // Clean rep completion
                        val rep = RepBoundary(
                            repIndex = _completeReps.size + 1,
                            startTimestampMs = repStartTimestampMs,
                            bottomTimestampMs = bottomTimestampMs,
                            endTimestampMs = timestampMs,
                            durationMs = timestampMs - repStartTimestampMs,
                            minElbowAngle = minKneeAngleThisRep,
                            isComplete = true
                        )
                        _completeReps.add(rep)
                        newlyCompleted = rep
                    } else {
                        // Movement reversed before reaching bottom
                        val incomplete = IncompleteRep(
                            attemptIndex = _incompleteReps.size + 1,
                            startTimestampMs = repStartTimestampMs,
                            reversalTimestampMs = timestampMs,
                            minElbowAngleAchieved = minKneeAngleThisRep,
                            reason = "Reversed before reaching bottom target (achieved ${minKneeAngleThisRep}° vs <= ${bottomThreshold + bottomTolerance}°)"
                        )
                        _incompleteReps.add(incomplete)
                        newlyIncomplete = incomplete
                    }

                    isRepInProgress = false
                    reachedBottom = false
                    currentPhase = ExercisePhase.TOP
                } else if (!reachedBottom && kneeAngle < (minKneeAngleThisRep - 5.0f)) {
                    // User re-descended after an incomplete attempt without touching top
                    currentPhase = ExercisePhase.DESCENDING
                }
            }
        }

        val state = ExerciseState(
            phase = currentPhase,
            currentElbowAngle = kneeAngle,
            currentHipLineAngle = hipAngle,
            completeReps = _completeReps.toList(),
            incompleteReps = _incompleteReps.toList(),
            newlyCompletedRep = newlyCompleted,
            newlyDetectedIncompleteRep = newlyIncomplete,
            isRepInProgress = isRepInProgress,
            currentRepMinAngle = if (isRepInProgress) minKneeAngleThisRep else null
        )
        currentState = state
        return state
    }

    /**
     * Resets internal state, reps, and history.
     */
    fun reset() {
        currentPhase = ExercisePhase.TOP
        currentState = ExerciseState(
            phase = ExercisePhase.TOP,
            currentElbowAngle = 180.0f,
            currentHipLineAngle = 180.0f
        )
        _completeReps.clear()
        _incompleteReps.clear()
        isRepInProgress = false
        reachedBottom = false
        minKneeAngleThisRep = 180.0f
        repStartTimestampMs = 0L
        bottomTimestampMs = 0L
    }
}
