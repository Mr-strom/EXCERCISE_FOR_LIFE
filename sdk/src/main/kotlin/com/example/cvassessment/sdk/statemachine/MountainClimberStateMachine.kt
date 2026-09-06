package com.example.cvassessment.sdk.statemachine

import com.example.cvassessment.sdk.metrics.FrameMetrics
import com.example.cvassessment.sdk.metrics.RepMetrics
import com.example.cvassessment.sdk.pose.PoseEstimationResult
import com.example.cvassessment.sdk.spec.ExerciseConfig
import kotlin.math.max
import kotlin.math.min

/**
 * State machine for Mountain Climber exercise analysis per EXERCISE_SPEC.md #10,
 * FORM_RULES.md, and DECISIONS.md D14/D15.
 *
 * Implements:
 * 1. 4-phase rep cycle:
 *    - TOP (EXTENDED / PLANK BASE): knee_drive_angle >= 150.0°
 *    - DESCENDING (DRIVING forward toward chest): knee_drive_angle decreasing
 *    - BOTTOM (DRIVEN): knee_drive_angle <= 90.0° (target depth reached)
 *    - ASCENDING (RETURNING back to plank): leg extending back
 *    - TOP (REP COMPLETE): knee_drive_angle >= 150.0°
 * 2. Proportional fast-cadence settle time scaling (D14/D15):
 *    - Scaled dwell: 75ms (for 1.0s baseline tempo) with 5.0° hysteresis.
 *    - Direct transition from TOP -> BOTTOM if peak drive achieved within single frame.
 * 3. D13 recovery latch: mid-rep visibility gaps discard in-progress attempt and require
 *    returning to full extension (>= 150.0°) before re-arming.
 * 4. Dual-leg drive tracking: active knee angle is minimum of left and right knees.
 */
class MountainClimberStateMachine(
    private val config: ExerciseConfig = ExerciseConfig.MOUNTAIN_CLIMBER,
    private val minReversalSettleMs: Long = 75L,
    private val reversalHysteresisDeg: Float = 5.0f
) {
    private val kneeExtendedThreshold = 150.0f // 160° - 10° tolerance
    private val kneeDrivenThreshold = 90.0f    // Target drive depth

    private var currentPhase: ExercisePhase = ExercisePhase.TOP
    var currentState: ExerciseState = ExerciseState(
        phase = ExercisePhase.TOP,
        currentElbowAngle = MountainClimberGeometry.KNEE_EXTENDED_BASELINE,
        currentHipLineAngle = 180.0f
    )
        private set

    var isRepInProgress: Boolean = false
        private set

    private var reachedDriven: Boolean = false
    private var repStartTimestampMs: Long = 0L
    private var drivenTimestampMs: Long = 0L
    private var minKneeAngleThisRep: Float = MountainClimberGeometry.KNEE_EXTENDED_BASELINE
    private var reversalCandidateTimestampMs: Long = 0L

    // D13 Recovery Latch
    private var awaitingExtendedReset: Boolean = false

    private val _completeReps = mutableListOf<RepBoundary>()
    val completeReps: List<RepBoundary> get() = _completeReps.toList()

    private val _incompleteReps = mutableListOf<IncompleteRep>()
    val incompleteReps: List<IncompleteRep> get() = _incompleteReps.toList()

    private val _allRepMetrics = mutableListOf<RepMetrics>()
    val allRepMetrics: List<RepMetrics> get() = _allRepMetrics.toList()

    val latestCompletedRepMetrics: RepMetrics? get() = _allRepMetrics.lastOrNull()

    var isDebugLoggingEnabled: Boolean = false
    var debugLogger: ((String) -> Unit)? = null

    private fun logDebug(msg: String) {
        if (!isDebugLoggingEnabled) return
        debugLogger?.invoke(msg)
        try {
            android.util.Log.d("MountainClimberSM", msg)
        } catch (_: Throwable) {
            // JVM unit test fallback
        }
    }

    /**
     * Process a raw [PoseEstimationResult].
     */
    fun processFrame(
        poseResult: PoseEstimationResult,
        isVisibilitySufficient: Boolean = true
    ): ExerciseState {
        val kneeAngle = MountainClimberGeometry.computeActiveKneeAngle(poseResult.landmarks)
        val hipLineAngle = MountainClimberGeometry.computeHipLineAngle(poseResult.landmarks)
        return processAngles(kneeAngle, hipLineAngle, poseResult.timestampMs, isVisibilitySufficient)
    }

    /**
     * Process pre-computed knee drive and hip line angles.
     */
    fun processAngles(
        kneeAngle: Float,
        hipLineAngle: Float,
        timestampMs: Long,
        isVisibilitySufficient: Boolean = true
    ): ExerciseState {
        // Enforce D13: Visibility drop mid-rep discards active rep
        if (!isVisibilitySufficient) {
            if (isRepInProgress) {
                logDebug("[VISIBILITY_DROP] Discarding in-progress rep at t=${timestampMs}ms")
                isRepInProgress = false
                reachedDriven = false
                minKneeAngleThisRep = MountainClimberGeometry.KNEE_EXTENDED_BASELINE
                repStartTimestampMs = 0L
                drivenTimestampMs = 0L
                reversalCandidateTimestampMs = 0L
                awaitingExtendedReset = true
            }
            currentPhase = ExercisePhase.TOP
            val state = ExerciseState(
                phase = currentPhase,
                currentElbowAngle = kneeAngle,
                currentHipLineAngle = hipLineAngle,
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

        // D13 Latch check: must return to extended position before re-arming
        val isExtended = kneeAngle >= kneeExtendedThreshold
        if (awaitingExtendedReset) {
            if (isExtended) {
                awaitingExtendedReset = false
                logDebug("[D13_LATCH] Athlete returned to EXTENDED position at t=${timestampMs}ms; state machine re-armed.")
            } else {
                val state = ExerciseState(
                    phase = ExercisePhase.TOP,
                    currentElbowAngle = kneeAngle,
                    currentHipLineAngle = hipLineAngle,
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
        }

        var newlyCompleted: RepBoundary? = null
        var newlyIncomplete: IncompleteRep? = null

        when (currentPhase) {
            ExercisePhase.TOP -> { // EXTENDED (Plank base)
                isRepInProgress = false
                minKneeAngleThisRep = kneeAngle
                reversalCandidateTimestampMs = 0L

                // Initiation check: driving leg starts pulling forward toward chest
                if (kneeAngle < kneeExtendedThreshold) {
                    isRepInProgress = true
                    repStartTimestampMs = timestampMs
                    minKneeAngleThisRep = kneeAngle
                    reversalCandidateTimestampMs = 0L

                    // Fast single-frame drive check
                    if (kneeAngle <= kneeDrivenThreshold) {
                        currentPhase = ExercisePhase.BOTTOM // DRIVEN
                        reachedDriven = true
                        drivenTimestampMs = timestampMs
                        logDebug("[TRANSITION] EXTENDED (TOP) -> DRIVEN (BOTTOM) at t=${timestampMs}ms (knee=${kneeAngle}°)")
                    } else {
                        currentPhase = ExercisePhase.DESCENDING // DRIVING
                        reachedDriven = false
                        logDebug("[TRANSITION] EXTENDED (TOP) -> DRIVING (DESCENDING) at t=${timestampMs}ms (knee=${kneeAngle}°)")
                    }
                }
            }

            ExercisePhase.DESCENDING -> { // DRIVING forward
                minKneeAngleThisRep = min(minKneeAngleThisRep, kneeAngle)

                // Check if target DRIVEN depth achieved (knee <= 90°)
                if (kneeAngle <= kneeDrivenThreshold) {
                    currentPhase = ExercisePhase.BOTTOM // DRIVEN
                    reachedDriven = true
                    drivenTimestampMs = timestampMs
                    reversalCandidateTimestampMs = 0L
                    logDebug("[TRANSITION] DRIVING -> DRIVEN (BOTTOM) at t=${timestampMs}ms (knee=${kneeAngle}°)")
                } else if (kneeAngle > (minKneeAngleThisRep + reversalHysteresisDeg)) {
                    // Premature upward reversal check before reaching drive target
                    if (kneeAngle >= kneeExtendedThreshold) {
                        val rom = MountainClimberGeometry.calculateRomPercent(minKneeAngleThisRep)
                        val incomplete = IncompleteRep(
                            attemptIndex = _incompleteReps.size + 1,
                            startTimestampMs = repStartTimestampMs,
                            reversalTimestampMs = timestampMs,
                            minElbowAngleAchieved = minKneeAngleThisRep,
                            reason = "Reversed before reaching target knee drive depth (achieved=${minKneeAngleThisRep}°)"
                        )
                        _incompleteReps.add(incomplete)
                        newlyIncomplete = incomplete
                        isRepInProgress = false
                        reachedDriven = false
                        reversalCandidateTimestampMs = 0L
                        currentPhase = ExercisePhase.TOP
                        logDebug("[INCOMPLETE_REP] Attempt #${incomplete.attemptIndex} at t=${timestampMs}ms | minKnee=${minKneeAngleThisRep}° | ROM=${rom}%")
                    }
                }
            }

            ExercisePhase.BOTTOM -> { // DRIVEN (Knee pulled to chest)
                minKneeAngleThisRep = min(minKneeAngleThisRep, kneeAngle)

                if (kneeAngle >= kneeExtendedThreshold) {
                    // Returned all the way to extended directly
                    if (reachedDriven) {
                        newlyCompleted = finalizeCompleteRep(timestampMs)
                    } else {
                        newlyIncomplete = finalizeIncompleteRep(timestampMs, "Extended without achieving full knee drive depth")
                    }
                } else {
                    // Check if leg starts extending back
                    val legReturning = kneeAngle > (minKneeAngleThisRep + reversalHysteresisDeg)
                    val dwellDuration = timestampMs - drivenTimestampMs

                    if (legReturning) {
                        if (reversalCandidateTimestampMs == 0L) {
                            reversalCandidateTimestampMs = timestampMs
                        }
                        val candidateDwell = timestampMs - reversalCandidateTimestampMs
                        val isDecisiveReturning = kneeAngle >= (kneeDrivenThreshold + 15.0f)

                        if (isDecisiveReturning || candidateDwell >= minReversalSettleMs || dwellDuration >= minReversalSettleMs) {
                            currentPhase = ExercisePhase.ASCENDING // RETURNING
                            reversalCandidateTimestampMs = 0L
                            logDebug("[TRANSITION] DRIVEN -> RETURNING (ASCENDING) at t=${timestampMs}ms (dwell=${dwellDuration}ms, knee=${kneeAngle}°)")
                        }
                    } else {
                        reversalCandidateTimestampMs = 0L
                    }
                }
            }

            ExercisePhase.ASCENDING -> { // RETURNING back to plank base
                // Rep completion: leg returns to extended position (knee >= 150°)
                if (kneeAngle >= kneeExtendedThreshold) {
                    if (reachedDriven) {
                        newlyCompleted = finalizeCompleteRep(timestampMs)
                    } else {
                        newlyIncomplete = finalizeIncompleteRep(timestampMs, "Extended without achieving full knee drive depth")
                    }
                }
            }
        }

        val state = ExerciseState(
            phase = currentPhase,
            currentElbowAngle = kneeAngle,
            currentHipLineAngle = hipLineAngle,
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
     * Assembles FrameMetrics for Mountain Climber.
     */
    fun getFrameMetrics(
        exerciseState: ExerciseState,
        isVisibilitySufficient: Boolean = true
    ): FrameMetrics {
        if (!isVisibilitySufficient) {
            return FrameMetrics(
                romPercent = null,
                tutFactor = null,
                confidence = 0.0f,
                instantRomPercent = null,
                latestCompletedRepMetrics = null,
                allRepMetrics = _allRepMetrics.toList(),
                isVisibilitySufficient = false
            )
        }

        val instantRom = MountainClimberGeometry.calculateRomPercent(exerciseState.currentElbowAngle)
        val latest = latestCompletedRepMetrics

        return FrameMetrics(
            romPercent = latest?.romPercent,
            tutFactor = latest?.tutFactor,
            confidence = latest?.confidence ?: 1.0f,
            instantRomPercent = instantRom,
            latestCompletedRepMetrics = latest,
            allRepMetrics = _allRepMetrics.toList(),
            isVisibilitySufficient = true
        )
    }

    private fun finalizeCompleteRep(timestampMs: Long): RepBoundary {
        val durationMs = timestampMs - repStartTimestampMs
        val rom = MountainClimberGeometry.calculateRomPercent(minKneeAngleThisRep)

        val rep = RepBoundary(
            repIndex = _completeReps.size + 1,
            startTimestampMs = repStartTimestampMs,
            bottomTimestampMs = drivenTimestampMs,
            endTimestampMs = timestampMs,
            durationMs = durationMs,
            minElbowAngle = minKneeAngleThisRep,
            isComplete = true
        )
        _completeReps.add(rep)

        val durationSec = durationMs / 1000.0f
        val tutFactor = durationSec / config.tutBaseline // 1.0s baseline
        val repMetrics = RepMetrics(
            repIndex = rep.repIndex,
            romPercent = rom,
            tutFactor = tutFactor,
            confidence = 1.0f,
            durationSec = durationSec,
            minElbowAngle = minKneeAngleThisRep,
            startTimestampMs = rep.startTimestampMs,
            endTimestampMs = rep.endTimestampMs,
            landmarkVisibilityConfidence = 1.0f,
            phasePatternMatchConfidence = 1.0f,
            trajectorySmoothnessConfidence = 1.0f
        )
        _allRepMetrics.add(repMetrics)

        logDebug("[REP_COMPLETE] Rep #${rep.repIndex} at t=${timestampMs}ms | duration=${durationMs}ms | minKnee=${minKneeAngleThisRep}° | ROM=${rom}%")

        isRepInProgress = false
        reachedDriven = false
        currentPhase = ExercisePhase.TOP
        return rep
    }

    private fun finalizeIncompleteRep(timestampMs: Long, reason: String): IncompleteRep {
        val rom = MountainClimberGeometry.calculateRomPercent(minKneeAngleThisRep)
        val incomplete = IncompleteRep(
            attemptIndex = _incompleteReps.size + 1,
            startTimestampMs = repStartTimestampMs,
            reversalTimestampMs = timestampMs,
            minElbowAngleAchieved = minKneeAngleThisRep,
            reason = reason
        )
        _incompleteReps.add(incomplete)

        isRepInProgress = false
        reachedDriven = false
        currentPhase = ExercisePhase.TOP
        return incomplete
    }

    fun reset() {
        currentPhase = ExercisePhase.TOP
        currentState = ExerciseState(
            phase = ExercisePhase.TOP,
            currentElbowAngle = MountainClimberGeometry.KNEE_EXTENDED_BASELINE,
            currentHipLineAngle = 180.0f
        )
        isRepInProgress = false
        reachedDriven = false
        minKneeAngleThisRep = MountainClimberGeometry.KNEE_EXTENDED_BASELINE
        repStartTimestampMs = 0L
        drivenTimestampMs = 0L
        reversalCandidateTimestampMs = 0L
        awaitingExtendedReset = false
        _completeReps.clear()
        _incompleteReps.clear()
        _allRepMetrics.clear()
    }
}
