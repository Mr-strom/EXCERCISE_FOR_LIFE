package com.example.cvassessment.sdk.statemachine

import com.example.cvassessment.sdk.pose.PoseEstimationResult
import com.example.cvassessment.sdk.spec.ExerciseConfig

/**
 * Module 4: Exercise State Machine for Push-Up.
 *
 * Tracks joint angles frame-by-frame, detects movement phase transitions
 * (TOP, DESCENDING, BOTTOM, ASCENDING), and identifies rep boundaries
 * (complete repetitions and incomplete repetition attempts).
 *
 * Config-driven per ARCHITECTURE.md (R11.2).
 */
class ExerciseStateMachine(
    val config: ExerciseConfig = ExerciseConfig.PUSH_UP
) {
    var currentPhase: ExercisePhase = ExercisePhase.TOP
        private set

    private val _completeReps = mutableListOf<RepBoundary>()
    val completeReps: List<RepBoundary> get() = _completeReps.toList()

    private val _incompleteReps = mutableListOf<IncompleteRep>()
    val incompleteReps: List<IncompleteRep> get() = _incompleteReps.toList()

    private var isRepInProgress = false
    private var reachedBottom = false
    private var minElbowAngleThisRep = 180.0f
    private var repStartTimestampMs = 0L
    private var bottomTimestampMs = 0L

    // Push-Up thresholds configured from EXERCISE_SPEC.md via ExerciseConfig
    private val topThreshold: Float = config.phases["top"]?.thresholdAngle ?: 160.0f
    private val topTolerance: Float = config.phases["top"]?.toleranceDeg ?: 10.0f
    private val bottomThreshold: Float = config.phases["bottom"]?.thresholdAngle ?: 90.0f
    private val bottomTolerance: Float = config.phases["bottom"]?.toleranceDeg ?: 10.0f

    // Reversal threshold to avoid false-positive reversals from frame-to-frame tracking jitter
    private val reversalHysteresisDeg: Float = 8.0f

    /**
     * Process a raw [PoseEstimationResult] through angle computation and state machine transitions.
     *
     * @param poseResult MediaPipe BlazePose estimation output
     * @param isVisibilitySufficient Signal from Module 3 (Visibility Gate). If false,
     *                               in-progress reps are discarded.
     * @return Current [ExerciseState]
     */
    fun processFrame(
        poseResult: PoseEstimationResult,
        isVisibilitySufficient: Boolean = true
    ): ExerciseState {
        val elbowAngle = PoseGeometry.computeElbowAngle(poseResult.landmarks)
        val hipLineAngle = PoseGeometry.computeHipLineAngle(poseResult.landmarks)
        return processAngle(elbowAngle, hipLineAngle, poseResult.timestampMs, isVisibilitySufficient)
    }

    /**
     * Process directly with joint angles and timestamp.
     * Essential for feeding synthetic angle sequences during testing.
     *
     * @param elbowAngle Primary tracked angle (shoulder-elbow-wrist)
     * @param hipLineAngle Secondary tracked angle (shoulder-hip-ankle)
     * @param timestampMs Wall-clock frame timestamp in milliseconds
     * @param isVisibilitySufficient Visibility Gate signal
     */
    fun processAngle(
        elbowAngle: Float,
        hipLineAngle: Float,
        timestampMs: Long,
        isVisibilitySufficient: Boolean = true
    ): ExerciseState {
        // Handle visibility gap mid-rep per Acceptance Criteria #3:
        // Any in-progress rep is discarded (neither complete nor incomplete).
        if (!isVisibilitySufficient) {
            if (isRepInProgress) {
                isRepInProgress = false
                reachedBottom = false
                minElbowAngleThisRep = 180.0f
                repStartTimestampMs = 0L
                bottomTimestampMs = 0L
            }
            currentPhase = ExercisePhase.TOP
            return ExerciseState(
                phase = currentPhase,
                currentElbowAngle = elbowAngle,
                currentHipLineAngle = hipLineAngle,
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
                minElbowAngleThisRep = elbowAngle

                // User starts descending below top threshold with buffer
                if (elbowAngle < (topThreshold - 5.0f)) {
                    currentPhase = ExercisePhase.DESCENDING
                    isRepInProgress = true
                    reachedBottom = false
                    repStartTimestampMs = timestampMs
                    minElbowAngleThisRep = elbowAngle
                }
            }

            ExercisePhase.DESCENDING -> {
                if (elbowAngle < minElbowAngleThisRep) {
                    minElbowAngleThisRep = elbowAngle
                }

                // Check if user reached bottom tolerance (< 90° ± 10°, i.e. <= 100°)
                if (elbowAngle <= (bottomThreshold + bottomTolerance)) {
                    currentPhase = ExercisePhase.BOTTOM
                    reachedBottom = true
                    bottomTimestampMs = timestampMs
                } else if (elbowAngle >= (topThreshold - 5.0f)) {
                    // Returned directly to top without reaching bottom: incomplete rep
                    val incomplete = IncompleteRep(
                        attemptIndex = _incompleteReps.size + 1,
                        startTimestampMs = repStartTimestampMs,
                        reversalTimestampMs = timestampMs,
                        minElbowAngleAchieved = minElbowAngleThisRep,
                        reason = "Reversed before reaching bottom target (achieved ${minElbowAngleThisRep}° vs <= ${bottomThreshold + bottomTolerance}°)"
                    )
                    _incompleteReps.add(incomplete)
                    newlyIncomplete = incomplete
                    isRepInProgress = false
                    reachedBottom = false
                    currentPhase = ExercisePhase.TOP
                } else if (elbowAngle > (minElbowAngleThisRep + reversalHysteresisDeg)) {
                    // Upward reversal detected before reaching bottom
                    currentPhase = ExercisePhase.ASCENDING
                }
            }

            ExercisePhase.BOTTOM -> {
                if (elbowAngle < minElbowAngleThisRep) {
                    minElbowAngleThisRep = elbowAngle
                    bottomTimestampMs = timestampMs
                }

                // Transition to ascending when arm extension begins
                if (elbowAngle > (minElbowAngleThisRep + 5.0f) || elbowAngle > (bottomThreshold + bottomTolerance)) {
                    currentPhase = ExercisePhase.ASCENDING
                }
            }

            ExercisePhase.ASCENDING -> {
                // Check if user returned to top lockout position (>= 160° - 5.0°)
                if (elbowAngle >= (topThreshold - 5.0f)) {
                    if (reachedBottom) {
                        // Clean rep completion
                        val rep = RepBoundary(
                            repIndex = _completeReps.size + 1,
                            startTimestampMs = repStartTimestampMs,
                            bottomTimestampMs = bottomTimestampMs,
                            endTimestampMs = timestampMs,
                            durationMs = timestampMs - repStartTimestampMs,
                            minElbowAngle = minElbowAngleThisRep,
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
                            minElbowAngleAchieved = minElbowAngleThisRep,
                            reason = "Reversed before reaching bottom target (achieved ${minElbowAngleThisRep}° vs <= ${bottomThreshold + bottomTolerance}°)"
                        )
                        _incompleteReps.add(incomplete)
                        newlyIncomplete = incomplete
                    }

                    isRepInProgress = false
                    reachedBottom = false
                    currentPhase = ExercisePhase.TOP
                } else if (!reachedBottom && elbowAngle < (minElbowAngleThisRep - 5.0f)) {
                    // User re-descended after an incomplete attempt without touching top
                    currentPhase = ExercisePhase.DESCENDING
                }
            }
        }

        return ExerciseState(
            phase = currentPhase,
            currentElbowAngle = elbowAngle,
            currentHipLineAngle = hipLineAngle,
            completeReps = _completeReps.toList(),
            incompleteReps = _incompleteReps.toList(),
            newlyCompletedRep = newlyCompleted,
            newlyDetectedIncompleteRep = newlyIncomplete,
            isRepInProgress = isRepInProgress,
            currentRepMinAngle = if (isRepInProgress) minElbowAngleThisRep else null
        )
    }

    /**
     * Resets all internal counters, state, and history.
     */
    fun reset() {
        currentPhase = ExercisePhase.TOP
        _completeReps.clear()
        _incompleteReps.clear()
        isRepInProgress = false
        reachedBottom = false
        minElbowAngleThisRep = 180.0f
        repStartTimestampMs = 0L
        bottomTimestampMs = 0L
    }
}
