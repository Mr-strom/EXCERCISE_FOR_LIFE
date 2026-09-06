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
    private var maxAngleInAscending = 0.0f
    private var repStartTimestampMs = 0L
    private var bottomTimestampMs = 0L
    private var reversalCandidateTimestampMs = 0L

    // Minimum settle time (ms) before an upward reversal in DESCENDING is confirmed
    private val minReversalSettleMs = 200L

    // Squat thresholds configured from EXERCISE_SPEC.md via ExerciseConfig
    private val topThreshold: Float = config.phases["top"]?.thresholdAngle ?: 160.0f
    private val topTolerance: Float = config.phases["top"]?.toleranceDeg ?: 10.0f
    private val bottomThreshold: Float = config.phases["bottom"]?.thresholdAngle ?: 105.0f
    private val bottomTolerance: Float = config.phases["bottom"]?.toleranceDeg ?: 10.0f

    // Reversal threshold to avoid false-positive reversals from frame-to-frame tracking jitter
    private val reversalHysteresisDeg: Float = 8.0f

    // Temporary debug logging for live session and test investigation
    var isDebugLoggingEnabled: Boolean = false
    var debugLogger: ((String) -> Unit)? = null

    private fun logDebug(msg: String) {
        if (!isDebugLoggingEnabled) return
        debugLogger?.invoke(msg)
        try {
            android.util.Log.d("SquatStateMachine", msg)
        } catch (_: Throwable) {
            // JVM unit tests fallback
        }
    }

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
                maxAngleInAscending = 0.0f
                repStartTimestampMs = 0L
                bottomTimestampMs = 0L
                reversalCandidateTimestampMs = 0L
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
        val previousPhase = currentPhase

        when (currentPhase) {
            ExercisePhase.TOP -> {
                isRepInProgress = false
                minKneeAngleThisRep = kneeAngle
                maxAngleInAscending = 0.0f
                reversalCandidateTimestampMs = 0L

                // User starts descending below top threshold with 5° buffer
                if (kneeAngle < (topThreshold - 5.0f)) {
                    currentPhase = ExercisePhase.DESCENDING
                    isRepInProgress = true
                    reachedBottom = false
                    repStartTimestampMs = timestampMs
                    minKneeAngleThisRep = kneeAngle
                    reversalCandidateTimestampMs = 0L
                    logDebug("[TRANSITION] TOP -> DESCENDING at t=${timestampMs}ms (knee=${kneeAngle}°, minKnee=${minKneeAngleThisRep}°)")
                }
            }

            ExercisePhase.DESCENDING -> {
                if (kneeAngle < minKneeAngleThisRep) {
                    minKneeAngleThisRep = kneeAngle
                    reversalCandidateTimestampMs = 0L
                }

                // Check if user reached bottom tolerance (< 105° ± 10°, i.e. <= 115°)
                if (kneeAngle <= (bottomThreshold + bottomTolerance)) {
                    currentPhase = ExercisePhase.BOTTOM
                    reachedBottom = true
                    bottomTimestampMs = timestampMs
                    reversalCandidateTimestampMs = 0L
                    logDebug("[TRANSITION] DESCENDING -> BOTTOM at t=${timestampMs}ms (knee=${kneeAngle}°, minKnee=${minKneeAngleThisRep}°, target<=${bottomThreshold + bottomTolerance}°)")
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
                    reversalCandidateTimestampMs = 0L
                    currentPhase = ExercisePhase.TOP
                    logDebug("[INCOMPLETE_REP] Attempt #${incomplete.attemptIndex} at t=${timestampMs}ms | minAchieved=${incomplete.minElbowAngleAchieved}° | reason='${incomplete.reason}'")
                    logDebug("[TRANSITION] DESCENDING -> TOP (reversal to top) at t=${timestampMs}ms")
                } else if (kneeAngle > (minKneeAngleThisRep + reversalHysteresisDeg)) {
                    // Upward reversal detected: immediate if decisive (>= 12° extension or near top),
                    // otherwise require settle time (200ms) to filter tracking jitter during pauses
                    if (reversalCandidateTimestampMs == 0L) {
                        reversalCandidateTimestampMs = timestampMs
                    }
                    val settleDuration = timestampMs - reversalCandidateTimestampMs
                    val isDecisiveReversal = kneeAngle >= (minKneeAngleThisRep + 12.0f) || kneeAngle >= (topThreshold - 10.0f)

                    if (isDecisiveReversal || settleDuration >= minReversalSettleMs) {
                        currentPhase = ExercisePhase.ASCENDING
                        maxAngleInAscending = kneeAngle
                        reversalCandidateTimestampMs = 0L
                        logDebug("[TRANSITION] DESCENDING -> ASCENDING (reversal confirmed after ${settleDuration}ms, knee=${kneeAngle}°, minKnee=${minKneeAngleThisRep}°)")
                    }
                } else {
                    reversalCandidateTimestampMs = 0L
                }
            }

            ExercisePhase.BOTTOM -> {
                if (kneeAngle < minKneeAngleThisRep) {
                    minKneeAngleThisRep = kneeAngle
                    bottomTimestampMs = timestampMs
                }

                // Transition to ascending when leg extension begins:
                // Require kneeAngle to exceed min + reversalHysteresisDeg (8.0°) AND exit the bottom target zone
                val hasExtended = kneeAngle > (minKneeAngleThisRep + reversalHysteresisDeg)
                val hasExitedBottom = kneeAngle > (bottomThreshold + bottomTolerance)

                if (hasExtended && hasExitedBottom) {
                    currentPhase = ExercisePhase.ASCENDING
                    maxAngleInAscending = kneeAngle
                    logDebug("[TRANSITION] BOTTOM -> ASCENDING at t=${timestampMs}ms (knee=${kneeAngle}°, minKnee=${minKneeAngleThisRep}°)")
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
                        logDebug("[COMPLETE_REP] Rep #${rep.repIndex} at t=${timestampMs}ms | duration=${rep.durationMs}ms | minKnee=${rep.minElbowAngle}°")
                        logDebug("[TRANSITION] ASCENDING -> TOP at t=${timestampMs}ms")
                    }
                }
            }

            ExercisePhase.ASCENDING -> {
                if (kneeAngle > maxAngleInAscending) {
                    maxAngleInAscending = kneeAngle
                }

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
                        logDebug("[COMPLETE_REP] Rep #${rep.repIndex} at t=${timestampMs}ms | duration=${rep.durationMs}ms | minKnee=${rep.minElbowAngle}°")
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
                        logDebug("[INCOMPLETE_REP] Attempt #${incomplete.attemptIndex} at t=${timestampMs}ms | minAchieved=${incomplete.minElbowAngleAchieved}° | reason='${incomplete.reason}'")
                    }

                    isRepInProgress = false
                    reachedBottom = false
                    maxAngleInAscending = 0.0f
                    currentPhase = ExercisePhase.TOP
                    logDebug("[TRANSITION] ASCENDING -> TOP at t=${timestampMs}ms")
                } else if (!reachedBottom && kneeAngle < (minKneeAngleThisRep - 5.0f)) {
                    // Knee angle dropped below the previously recorded minimum
                    val hasGenuinelyAscended = (maxAngleInAscending >= (minKneeAngleThisRep + 15.0f)) || (maxAngleInAscending >= 135.0f)
                    if (hasGenuinelyAscended) {
                        // User genuinely ascended halfway, then aborted and re-descended without reaching top
                        val incomplete = IncompleteRep(
                            attemptIndex = _incompleteReps.size + 1,
                            startTimestampMs = repStartTimestampMs,
                            reversalTimestampMs = timestampMs,
                            minElbowAngleAchieved = minKneeAngleThisRep,
                            reason = "Reversed before reaching bottom target and re-descended"
                        )
                        _incompleteReps.add(incomplete)
                        newlyIncomplete = incomplete
                        repStartTimestampMs = timestampMs
                        minKneeAngleThisRep = kneeAngle
                        maxAngleInAscending = 0.0f
                        currentPhase = ExercisePhase.DESCENDING
                        logDebug("[INCOMPLETE_REP] Attempt #${incomplete.attemptIndex} at t=${timestampMs}ms | minAchieved=${incomplete.minElbowAngleAchieved}° | reason='${incomplete.reason}'")
                        logDebug("[TRANSITION] ASCENDING -> DESCENDING (re-descent reset) at t=${timestampMs}ms (new start=${repStartTimestampMs}ms)")
                    } else {
                        // User was merely settling deeper during a bottom-hold pause without a true upward ascent attempt
                        minKneeAngleThisRep = minOf(minKneeAngleThisRep, kneeAngle)
                        currentPhase = ExercisePhase.DESCENDING
                        logDebug("[SETTLE_PAUSE] Bottom pause settle adjustment: resuming DESCENDING at t=${timestampMs}ms (minKnee=${minKneeAngleThisRep}°)")
                    }
                }
            }
        }

        logDebug("[FRAME] t=${timestampMs}ms | knee=${"%.1f".format(kneeAngle)}° | minKnee=${"%.1f".format(minKneeAngleThisRep)}° | phase=$currentPhase | reachedBottom=$reachedBottom | completeReps=${_completeReps.size} | incompleteReps=${_incompleteReps.size}")

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
        maxAngleInAscending = 0.0f
        repStartTimestampMs = 0L
        bottomTimestampMs = 0L
        reversalCandidateTimestampMs = 0L
    }
}
