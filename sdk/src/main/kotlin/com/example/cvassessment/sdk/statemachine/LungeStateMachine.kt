package com.example.cvassessment.sdk.statemachine

import com.example.cvassessment.sdk.pose.PoseEstimationResult
import com.example.cvassessment.sdk.spec.ExerciseConfig

/**
 * Exercise State Machine for Lunge per EXERCISE_SPEC.md and D12 lessons.
 *
 * Tracks front/back knee angles frame-by-frame, detects movement phase transitions
 * (TOP, DESCENDING, BOTTOM, ASCENDING), manages alternating leg re-identification,
 * and applies D12 anti-jitter protections (200ms settle time, 8° hysteresis).
 *
 * Thresholds per EXERCISE_SPEC.md & D12 calibration:
 * - Top phase: front_knee_angle > 160° (tolerance ±10°, i.e. >= 150°)
 * - Bottom phase: front_knee_angle ~90° (tolerance ±15°, i.e. <= 105°)
 * - Reversal hysteresis: 8.0° uniform
 * - Bottom-hold settle time: 200ms
 */
internal class LungeStateMachine(
    val config: ExerciseConfig = ExerciseConfig.LUNGE
) {
    var currentPhase: ExercisePhase = ExercisePhase.TOP
        private set

    var currentState: ExerciseState = ExerciseState(
        phase = ExercisePhase.TOP,
        currentElbowAngle = 180.0f, // Primary tracked angle: front_knee_angle
        currentHipLineAngle = 0.0f  // Secondary tracked angle: torso_vertical_angle
    )
        private set

    private val _completeReps = mutableListOf<RepBoundary>()
    val completeReps: List<RepBoundary> get() = _completeReps.toList()

    private val _incompleteReps = mutableListOf<IncompleteRep>()
    val incompleteReps: List<IncompleteRep> get() = _incompleteReps.toList()

    // Map of repIndex to the front leg identified for that rep
    private val _repFrontLegs = mutableMapOf<Int, LungeGeometry.LegSide>()
    val repFrontLegs: Map<Int, LungeGeometry.LegSide> get() = _repFrontLegs.toMap()

    var activeFrontLeg: LungeGeometry.LegSide = LungeGeometry.LegSide.LEFT
        private set

    private var isRepInProgress = false
    private var reachedBottom = false
    private var minKneeAngleThisRep = 180.0f
    private var maxAngleInAscending = 0.0f
    private var repStartTimestampMs = 0L
    private var bottomTimestampMs = 0L
    private var reversalCandidateTimestampMs = 0L

    private val minReversalSettleMs = 200L
    private val topThreshold: Float = config.phases["top"]?.thresholdAngle ?: 160.0f
    private val topTolerance: Float = config.phases["top"]?.toleranceDeg ?: 10.0f
    private val bottomThreshold: Float = config.phases["bottom"]?.thresholdAngle ?: 90.0f
    private val bottomTolerance: Float = config.phases["bottom"]?.toleranceDeg ?: 15.0f
    private val reversalHysteresisDeg: Float = 8.0f

    var isDebugLoggingEnabled: Boolean = false
    var debugLogger: ((String) -> Unit)? = null

    private fun logDebug(msg: String) {
        if (!isDebugLoggingEnabled) return
        debugLogger?.invoke(msg)
        try {
            android.util.Log.d("LungeStateMachine", msg)
        } catch (_: Throwable) {
            // JVM unit test fallback
        }
    }

    /**
     * Process a raw [PoseEstimationResult] through Lunge geometry and state machine.
     */
    fun processFrame(
        poseResult: PoseEstimationResult,
        isVisibilitySufficient: Boolean = true
    ): ExerciseState {
        // In TOP phase, re-evaluate front leg identification so alternating lunges are dynamically recognized
        if (currentPhase == ExercisePhase.TOP && !isRepInProgress) {
            activeFrontLeg = LungeGeometry.identifyFrontLeg(poseResult.landmarks)
        }

        val angles = LungeGeometry.computeLungeAngles(poseResult.landmarks, activeFrontLeg)
        return processAngles(
            frontKneeAngle = angles.frontKneeAngle,
            torsoAngle = angles.torsoVerticalAngle,
            timestampMs = poseResult.timestampMs,
            isVisibilitySufficient = isVisibilitySufficient,
            frontLeg = activeFrontLeg
        )
    }

    /**
     * Process directly with primary front knee angle and torso vertical angle.
     * Ideal for synthetic testing.
     */
    fun processAngle(
        frontKneeAngle: Float,
        torsoAngle: Float = 0.0f,
        timestampMs: Long,
        isVisibilitySufficient: Boolean = true,
        frontLeg: LungeGeometry.LegSide = activeFrontLeg
    ): ExerciseState {
        return processAngles(
            frontKneeAngle = frontKneeAngle,
            torsoAngle = torsoAngle,
            timestampMs = timestampMs,
            isVisibilitySufficient = isVisibilitySufficient,
            frontLeg = frontLeg
        )
    }

    /**
     * Process angles with explicit front leg specification.
     */
    fun processAngles(
        frontKneeAngle: Float,
        torsoAngle: Float = 0.0f,
        timestampMs: Long,
        isVisibilitySufficient: Boolean = true,
        frontLeg: LungeGeometry.LegSide = activeFrontLeg
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
            val state = ExerciseState(
                phase = currentPhase,
                currentElbowAngle = frontKneeAngle,
                currentHipLineAngle = torsoAngle,
                completeReps = _completeReps.toList(),
                incompleteReps = _incompleteReps.toList(),
                newlyCompletedRep = null,
                newlyDetectedIncompleteRep = null,
                isRepInProgress = false,
                currentRepMinAngle = null
            )
            currentState = state
            return state
        }

        var newlyCompleted: RepBoundary? = null
        var newlyIncomplete: IncompleteRep? = null

        val topCutoff = topThreshold - topTolerance // 160° - 10° = 150°
        val bottomCutoff = bottomThreshold + bottomTolerance // 90° + 15° = 105°

        when (currentPhase) {
            ExercisePhase.TOP -> {
                isRepInProgress = false
                minKneeAngleThisRep = frontKneeAngle
                maxAngleInAscending = 0.0f
                reversalCandidateTimestampMs = 0L

                // User starts descending below top threshold with 5° buffer (< 155°)
                if (frontKneeAngle < (topThreshold - 5.0f)) {
                    currentPhase = ExercisePhase.DESCENDING
                    isRepInProgress = true
                    reachedBottom = false
                    activeFrontLeg = frontLeg
                    repStartTimestampMs = timestampMs
                    minKneeAngleThisRep = frontKneeAngle
                    reversalCandidateTimestampMs = 0L
                    logDebug("[TRANSITION] TOP -> DESCENDING at t=${timestampMs}ms (leg=$activeFrontLeg, angle=${frontKneeAngle}°)")
                }
            }

            ExercisePhase.DESCENDING -> {
                if (frontKneeAngle < minKneeAngleThisRep) {
                    minKneeAngleThisRep = frontKneeAngle
                    reversalCandidateTimestampMs = 0L
                }

                // Check if user reached bottom depth target (<= 105°)
                if (frontKneeAngle <= bottomCutoff) {
                    currentPhase = ExercisePhase.BOTTOM
                    reachedBottom = true
                    bottomTimestampMs = timestampMs
                    reversalCandidateTimestampMs = 0L
                    logDebug("[TRANSITION] DESCENDING -> BOTTOM at t=${timestampMs}ms (leg=$activeFrontLeg, angle=${frontKneeAngle}°)")
                } else if (frontKneeAngle >= (topThreshold - 5.0f)) {
                    // Returned all the way to top without reaching bottom: incomplete rep
                    val inc = IncompleteRep(
                        attemptIndex = _incompleteReps.size + 1,
                        startTimestampMs = repStartTimestampMs,
                        reversalTimestampMs = timestampMs,
                        minElbowAngleAchieved = minKneeAngleThisRep,
                        reason = "Reversed before reaching bottom target (achieved ${minKneeAngleThisRep}° vs <= ${bottomCutoff}°)"
                    )
                    _incompleteReps.add(inc)
                    newlyIncomplete = inc
                    isRepInProgress = false
                    reachedBottom = false
                    reversalCandidateTimestampMs = 0L
                    currentPhase = ExercisePhase.TOP
                    logDebug("[INCOMPLETE] Attempt #${inc.attemptIndex} at t=${timestampMs}ms (achieved=${inc.minElbowAngleAchieved}°)")
                } else if (frontKneeAngle > (minKneeAngleThisRep + reversalHysteresisDeg)) {
                    // Upward reversal detected before reaching bottom
                    if (reversalCandidateTimestampMs == 0L) {
                        reversalCandidateTimestampMs = timestampMs
                    }
                    val settleDuration = timestampMs - reversalCandidateTimestampMs
                    val isDecisiveReversal = frontKneeAngle >= (minKneeAngleThisRep + 12.0f) || frontKneeAngle >= (topThreshold - 10.0f)

                    if (isDecisiveReversal || settleDuration >= minReversalSettleMs) {
                        currentPhase = ExercisePhase.ASCENDING
                        maxAngleInAscending = frontKneeAngle
                        reversalCandidateTimestampMs = 0L
                        logDebug("[TRANSITION] DESCENDING -> ASCENDING (premature reversal confirmed after ${settleDuration}ms)")
                    }
                } else {
                    reversalCandidateTimestampMs = 0L
                }
            }

            ExercisePhase.BOTTOM -> {
                if (frontKneeAngle < minKneeAngleThisRep) {
                    minKneeAngleThisRep = frontKneeAngle
                    bottomTimestampMs = timestampMs
                }

                // D12 Lesson: Bottom-hold settle time protection
                // User pausing at bottom with ±5° tracking jitter remains in BOTTOM
                val hasExtended = frontKneeAngle > (minKneeAngleThisRep + reversalHysteresisDeg)
                val hasExitedBottom = frontKneeAngle > bottomCutoff

                if (hasExtended && hasExitedBottom) {
                    currentPhase = ExercisePhase.ASCENDING
                    maxAngleInAscending = frontKneeAngle
                    logDebug("[TRANSITION] BOTTOM -> ASCENDING at t=${timestampMs}ms (angle=${frontKneeAngle}°)")

                    // If user leaped back to top in single jump
                    if (frontKneeAngle >= topCutoff) {
                        val repIndex = _completeReps.size + 1
                        val rep = RepBoundary(
                            repIndex = repIndex,
                            startTimestampMs = repStartTimestampMs,
                            bottomTimestampMs = bottomTimestampMs,
                            endTimestampMs = timestampMs,
                            durationMs = timestampMs - repStartTimestampMs,
                            minElbowAngle = minKneeAngleThisRep,
                            isComplete = true
                        )
                        _completeReps.add(rep)
                        _repFrontLegs[repIndex] = activeFrontLeg
                        newlyCompleted = rep
                        isRepInProgress = false
                        reachedBottom = false
                        currentPhase = ExercisePhase.TOP
                        logDebug("[COMPLETE] Rep #$repIndex (leg=$activeFrontLeg, duration=${rep.durationMs}ms)")
                    }
                }
            }

            ExercisePhase.ASCENDING -> {
                if (frontKneeAngle > maxAngleInAscending) {
                    maxAngleInAscending = frontKneeAngle
                }

                // Check if user reached top extension (>= 150°)
                if (frontKneeAngle >= topCutoff) {
                    if (reachedBottom) {
                        val repIndex = _completeReps.size + 1
                        val rep = RepBoundary(
                            repIndex = repIndex,
                            startTimestampMs = repStartTimestampMs,
                            bottomTimestampMs = bottomTimestampMs,
                            endTimestampMs = timestampMs,
                            durationMs = timestampMs - repStartTimestampMs,
                            minElbowAngle = minKneeAngleThisRep,
                            isComplete = true
                        )
                        _completeReps.add(rep)
                        _repFrontLegs[repIndex] = activeFrontLeg
                        newlyCompleted = rep
                        logDebug("[COMPLETE] Rep #$repIndex (leg=$activeFrontLeg, duration=${rep.durationMs}ms)")
                    } else {
                        val inc = IncompleteRep(
                            attemptIndex = _incompleteReps.size + 1,
                            startTimestampMs = repStartTimestampMs,
                            reversalTimestampMs = timestampMs,
                            minElbowAngleAchieved = minKneeAngleThisRep,
                            reason = "Reversed before reaching bottom target (achieved ${minKneeAngleThisRep}° vs <= ${bottomCutoff}°)"
                        )
                        _incompleteReps.add(inc)
                        newlyIncomplete = inc
                        logDebug("[INCOMPLETE] Attempt #${inc.attemptIndex} at t=${timestampMs}ms")
                    }

                    isRepInProgress = false
                    reachedBottom = false
                    currentPhase = ExercisePhase.TOP
                } else if (frontKneeAngle < (maxAngleInAscending - reversalHysteresisDeg)) {
                    // Re-descent during ascending phase
                    if (!reachedBottom && maxAngleInAscending >= (minKneeAngleThisRep + 15.0f)) {
                        val inc = IncompleteRep(
                            attemptIndex = _incompleteReps.size + 1,
                            startTimestampMs = repStartTimestampMs,
                            reversalTimestampMs = timestampMs,
                            minElbowAngleAchieved = minKneeAngleThisRep,
                            reason = "Re-descended before completing rep"
                        )
                        _incompleteReps.add(inc)
                        newlyIncomplete = inc
                        repStartTimestampMs = timestampMs
                        minKneeAngleThisRep = frontKneeAngle
                        currentPhase = ExercisePhase.DESCENDING
                    } else {
                        currentPhase = ExercisePhase.DESCENDING
                    }
                }
            }
        }

        val state = ExerciseState(
            phase = currentPhase,
            currentElbowAngle = frontKneeAngle,
            currentHipLineAngle = torsoAngle,
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

    fun getFrontLegForRep(repIndex: Int): LungeGeometry.LegSide? = _repFrontLegs[repIndex]

    fun reset() {
        currentPhase = ExercisePhase.TOP
        currentState = ExerciseState(
            phase = ExercisePhase.TOP,
            currentElbowAngle = 180.0f,
            currentHipLineAngle = 0.0f
        )
        _completeReps.clear()
        _incompleteReps.clear()
        _repFrontLegs.clear()
        activeFrontLeg = LungeGeometry.LegSide.LEFT
        isRepInProgress = false
        reachedBottom = false
        minKneeAngleThisRep = 180.0f
        maxAngleInAscending = 0.0f
        repStartTimestampMs = 0L
        bottomTimestampMs = 0L
        reversalCandidateTimestampMs = 0L
    }
}
