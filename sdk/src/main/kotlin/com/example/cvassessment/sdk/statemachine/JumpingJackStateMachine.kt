package com.example.cvassessment.sdk.statemachine

import com.example.cvassessment.sdk.metrics.FrameMetrics
import com.example.cvassessment.sdk.metrics.RepMetrics
import com.example.cvassessment.sdk.pose.PoseEstimationResult
import com.example.cvassessment.sdk.spec.ExerciseConfig
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Exercise State Machine for Jumping Jack per EXERCISE_SPEC.md #9, METRICS_SPEC.md,
 * DECISIONS.md D12 (settle time / guarded reversal), and D13 (latch recovery).
 *
 * Rep cycle:
 * TOP (Closed: arms down, feet together) ->
 * DESCENDING (Opening: arms abducting overhead, legs spreading) ->
 * BOTTOM (Open: inflection zone with arms overhead >= 145° and legs spread >= 40°) ->
 * ASCENDING (Closing: arms returning down, legs coming together) ->
 * TOP (Closed: rep completed).
 *
 * Fast cadence calibration:
 * - Baseline: 1.2s/rep (0.6s open + 0.6s close)
 * - Settle time: 90ms (scaled down from 200ms) to accommodate rapid bounce reversals
 * - Hysteresis: 5.0° (scaled down from 8.0°) for rapid directional change responsiveness
 * - ROM definition: Combined MINIMUM of arm ROM% and leg ROM%
 * - Asymmetry tracking: divergence between arm and leg open transition timestamps (> 180ms)
 * - D13 recovery latch: awaitingClosedReset prevents recovery phantom reps after visibility drop
 */
internal class JumpingJackStateMachine(
    val config: ExerciseConfig = ExerciseConfig.JUMPING_JACK
) {
    var currentPhase: ExercisePhase = ExercisePhase.TOP
        private set

    var currentState: ExerciseState = ExerciseState(
        phase = ExercisePhase.TOP,
        currentElbowAngle = JumpingJackGeometry.ARM_CLOSED_BASELINE,
        currentHipLineAngle = JumpingJackGeometry.LEG_CLOSED_BASELINE
    )
        private set

    private val _completeReps = mutableListOf<RepBoundary>()
    val completeReps: List<RepBoundary> get() = _completeReps.toList()

    private val _incompleteReps = mutableListOf<IncompleteRep>()
    val incompleteReps: List<IncompleteRep> get() = _incompleteReps.toList()

    private val _allRepMetrics = mutableListOf<RepMetrics>()
    val allRepMetrics: List<RepMetrics> get() = _allRepMetrics.toList()

    val latestCompletedRepMetrics: RepMetrics?
        get() = _allRepMetrics.lastOrNull()

    // Tracking flags
    var isRepInProgress: Boolean = false
        private set
    private var reachedOpen: Boolean = false
    private var maxArmAngleThisRep: Float = JumpingJackGeometry.ARM_CLOSED_BASELINE
    private var maxLegAngleThisRep: Float = JumpingJackGeometry.LEG_CLOSED_BASELINE
    private var minArmAngleInClosing: Float = 180.0f
    private var minLegAngleInClosing: Float = 180.0f

    private var repStartTimestampMs: Long = 0L
    private var openTimestampMs: Long = 0L
    private var armOpenTimestampMs: Long = 0L
    private var legOpenTimestampMs: Long = 0L
    private var reversalCandidateTimestampMs: Long = 0L

    // Asymmetry signal for form rule engine
    var lastRepAsymmetryDivergenceMs: Long? = null
        private set

    // D13: Latch requiring return to closed position after visibility interruption
    var awaitingClosedReset: Boolean = false
        private set

    // Thresholds
    private val armClosedThreshold: Float = 45.0f // Baseline 30° + 15° buffer
    private val legClosedThreshold: Float = 20.0f // Baseline 12° + 8° buffer
    private val armOpenThreshold: Float = 145.0f  // Target 150° with 5° tolerance
    private val legOpenThreshold: Float = 40.0f   // Target 45° with 5° tolerance

    private val reversalHysteresisDeg: Float = 5.0f
    private val minReversalSettleMs: Long = 90L

    // Debug logging
    var isDebugLoggingEnabled: Boolean = false
    var debugLogger: ((String) -> Unit)? = null

    private fun logDebug(msg: String) {
        if (!isDebugLoggingEnabled) return
        debugLogger?.invoke(msg)
        try {
            android.util.Log.d("JumpingJackSM", msg)
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
        val armAngle = JumpingJackGeometry.computeArmAbductionAngle(poseResult.landmarks)
        val legAngle = JumpingJackGeometry.computeLegSpreadAngle(poseResult.landmarks)
        return processAngles(armAngle, legAngle, poseResult.timestampMs, isVisibilitySufficient)
    }

    /**
     * Process pre-computed arm abduction and leg spread angles.
     */
    fun processAngles(
        armAngle: Float,
        legAngle: Float,
        timestampMs: Long,
        isVisibilitySufficient: Boolean = true
    ): ExerciseState {
        // Enforce D13 & Acceptance Criterion #9: Visibility gap mid-rep discards active rep
        if (!isVisibilitySufficient) {
            if (isRepInProgress) {
                logDebug("[VISIBILITY_DROP] Discarding in-progress rep at t=${timestampMs}ms")
                isRepInProgress = false
                reachedOpen = false
                maxArmAngleThisRep = JumpingJackGeometry.ARM_CLOSED_BASELINE
                maxLegAngleThisRep = JumpingJackGeometry.LEG_CLOSED_BASELINE
                repStartTimestampMs = 0L
                openTimestampMs = 0L
                armOpenTimestampMs = 0L
                legOpenTimestampMs = 0L
                reversalCandidateTimestampMs = 0L
                awaitingClosedReset = true
            }
            currentPhase = ExercisePhase.TOP
            val state = ExerciseState(
                phase = currentPhase,
                currentElbowAngle = armAngle,
                currentHipLineAngle = legAngle,
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

        // D13 Latch check: must return to closed position before re-arming
        val isClosed = armAngle <= armClosedThreshold && legAngle <= legClosedThreshold
        if (awaitingClosedReset) {
            if (isClosed) {
                awaitingClosedReset = false
                logDebug("[D13_LATCH] Athlete returned to CLOSED position at t=${timestampMs}ms; state machine re-armed.")
            } else {
                val state = ExerciseState(
                    phase = ExercisePhase.TOP,
                    currentElbowAngle = armAngle,
                    currentHipLineAngle = legAngle,
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
            ExercisePhase.TOP -> { // CLOSED position
                isRepInProgress = false
                maxArmAngleThisRep = armAngle
                maxLegAngleThisRep = legAngle
                reversalCandidateTimestampMs = 0L

                // Initiation check: athlete starts opening either arms or legs
                if (armAngle > armClosedThreshold || legAngle > legClosedThreshold) {
                    isRepInProgress = true
                    repStartTimestampMs = timestampMs
                    maxArmAngleThisRep = armAngle
                    maxLegAngleThisRep = legAngle
                    armOpenTimestampMs = if (armAngle >= armOpenThreshold) timestampMs else 0L
                    legOpenTimestampMs = if (legAngle >= legOpenThreshold) timestampMs else 0L
                    reversalCandidateTimestampMs = 0L

                    if (armAngle >= armOpenThreshold && legAngle >= legOpenThreshold) {
                        currentPhase = ExercisePhase.BOTTOM // OPEN
                        reachedOpen = true
                        openTimestampMs = timestampMs
                        logDebug("[TRANSITION] CLOSED (TOP) -> OPEN (BOTTOM) at t=${timestampMs}ms (arm=${armAngle}°, leg=${legAngle}°)")
                    } else {
                        currentPhase = ExercisePhase.DESCENDING // OPENING
                        reachedOpen = false
                        logDebug("[TRANSITION] CLOSED (TOP) -> OPENING (DESCENDING) at t=${timestampMs}ms (arm=${armAngle}°, leg=${legAngle}°)")
                    }
                }
            }

            ExercisePhase.DESCENDING -> { // OPENING movement
                maxArmAngleThisRep = max(maxArmAngleThisRep, armAngle)
                maxLegAngleThisRep = max(maxLegAngleThisRep, legAngle)

                // Track timing when arms reach open threshold
                if (armAngle >= armOpenThreshold && armOpenTimestampMs == 0L) {
                    armOpenTimestampMs = timestampMs
                }
                // Track timing when legs reach open threshold
                if (legAngle >= legOpenThreshold && legOpenTimestampMs == 0L) {
                    legOpenTimestampMs = timestampMs
                }

                // Check if target OPEN position achieved: both arms >= 145° and legs >= 40°
                if (armAngle >= armOpenThreshold && legAngle >= legOpenThreshold) {
                    currentPhase = ExercisePhase.BOTTOM // OPEN
                    reachedOpen = true
                    openTimestampMs = timestampMs
                    reversalCandidateTimestampMs = 0L
                    logDebug("[TRANSITION] OPENING -> OPEN (BOTTOM) at t=${timestampMs}ms (arm=${armAngle}°, leg=${legAngle}°)")
                } else if (armAngle < (maxArmAngleThisRep - reversalHysteresisDeg) ||
                    legAngle < (maxLegAngleThisRep - reversalHysteresisDeg)
                ) {
                    // Upward inflection reversal check: athlete reversed before opening fully
                    if (armAngle <= armClosedThreshold && legAngle <= legClosedThreshold) {
                        // Premature return to closed: incomplete rep
                        val combinedRom = JumpingJackGeometry.calculateCombinedRom(maxArmAngleThisRep, maxLegAngleThisRep)
                        val incomplete = IncompleteRep(
                            attemptIndex = _incompleteReps.size + 1,
                            startTimestampMs = repStartTimestampMs,
                            reversalTimestampMs = timestampMs,
                            minElbowAngleAchieved = combinedRom,
                            reason = "Reversed before reaching open target (armAchieved=${maxArmAngleThisRep}°, legAchieved=${maxLegAngleThisRep}°)"
                        )
                        _incompleteReps.add(incomplete)
                        newlyIncomplete = incomplete
                        isRepInProgress = false
                        reachedOpen = false
                        reversalCandidateTimestampMs = 0L
                        currentPhase = ExercisePhase.TOP
                        logDebug("[INCOMPLETE_REP] Attempt #${incomplete.attemptIndex} at t=${timestampMs}ms | achievedRom=${combinedRom}% | reason='${incomplete.reason}'")
                    }
                }
            }

            ExercisePhase.BOTTOM -> { // OPEN position
                maxArmAngleThisRep = max(maxArmAngleThisRep, armAngle)
                maxLegAngleThisRep = max(maxLegAngleThisRep, legAngle)

                if (armOpenTimestampMs == 0L && armAngle >= armOpenThreshold) {
                    armOpenTimestampMs = timestampMs
                }
                if (legOpenTimestampMs == 0L && legAngle >= legOpenThreshold) {
                    legOpenTimestampMs = timestampMs
                }

                if (armAngle <= armClosedThreshold && legAngle <= legClosedThreshold) {
                    // Returned all the way to closed directly
                    if (reachedOpen) {
                        newlyCompleted = finalizeCompleteRep(timestampMs)
                    } else {
                        newlyIncomplete = finalizeIncompleteRep(timestampMs, "Closed without achieving full open position")
                    }
                } else {
                    // Transition to CLOSING (ASCENDING) when arms and legs start coming back down
                    val armsClosing = armAngle < (maxArmAngleThisRep - reversalHysteresisDeg)
                    val legsClosing = legAngle < (maxLegAngleThisRep - reversalHysteresisDeg)
                    val openDwellDuration = timestampMs - openTimestampMs

                    if (armsClosing || legsClosing) {
                        if (reversalCandidateTimestampMs == 0L) {
                            reversalCandidateTimestampMs = timestampMs
                        }
                        val dwellDuration = timestampMs - reversalCandidateTimestampMs
                        val isDecisiveClosing = armAngle <= (armOpenThreshold - 10.0f) || legAngle <= (legOpenThreshold - 8.0f)

                        if (isDecisiveClosing || dwellDuration >= minReversalSettleMs || openDwellDuration >= minReversalSettleMs) {
                            currentPhase = ExercisePhase.ASCENDING // CLOSING
                            minArmAngleInClosing = armAngle
                            minLegAngleInClosing = legAngle
                            reversalCandidateTimestampMs = 0L
                            logDebug("[TRANSITION] OPEN -> CLOSING (ASCENDING) at t=${timestampMs}ms (dwell=${openDwellDuration}ms, arm=${armAngle}°, leg=${legAngle}°)")
                        }
                    } else {
                        reversalCandidateTimestampMs = 0L
                    }
                }
            }

            ExercisePhase.ASCENDING -> { // CLOSING movement
                minArmAngleInClosing = min(minArmAngleInClosing, armAngle)
                minLegAngleInClosing = min(minLegAngleInClosing, legAngle)

                // Rep completion: athlete returns to closed position (arms <= 45°, legs <= 20°)
                if (armAngle <= armClosedThreshold && legAngle <= legClosedThreshold) {
                    if (reachedOpen) {
                        newlyCompleted = finalizeCompleteRep(timestampMs)
                    } else {
                        newlyIncomplete = finalizeIncompleteRep(timestampMs, "Closed without achieving full open position")
                    }
                }
            }
        }

        val state = ExerciseState(
            phase = currentPhase,
            currentElbowAngle = armAngle,
            currentHipLineAngle = legAngle,
            completeReps = _completeReps.toList(),
            incompleteReps = _incompleteReps.toList(),
            newlyCompletedRep = newlyCompleted,
            newlyDetectedIncompleteRep = newlyIncomplete,
            isRepInProgress = isRepInProgress,
            currentRepMinAngle = if (isRepInProgress) JumpingJackGeometry.calculateCombinedRom(maxArmAngleThisRep, maxLegAngleThisRep) else null
        )
        currentState = state
        return state
    }

    /**
     * Assembles FrameMetrics for Jumping Jack honoring R7 refusal rules.
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

        val instantRom = JumpingJackGeometry.calculateCombinedRom(
            exerciseState.currentElbowAngle,
            exerciseState.currentHipLineAngle
        )
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
        val combinedRom = JumpingJackGeometry.calculateCombinedRom(maxArmAngleThisRep, maxLegAngleThisRep)

        val rep = RepBoundary(
            repIndex = _completeReps.size + 1,
            startTimestampMs = repStartTimestampMs,
            bottomTimestampMs = openTimestampMs,
            endTimestampMs = timestampMs,
            durationMs = durationMs,
            minElbowAngle = combinedRom,
            isComplete = true
        )
        _completeReps.add(rep)

        // Asymmetry divergence calculation
        val divergence = if (armOpenTimestampMs > 0L && legOpenTimestampMs > 0L) {
            abs(armOpenTimestampMs - legOpenTimestampMs)
        } else {
            null
        }
        lastRepAsymmetryDivergenceMs = divergence

        // Record RepMetrics
        val durationSec = durationMs / 1000.0f
        val tutFactor = durationSec / config.tutBaseline // 1.2s baseline
        val repMetrics = RepMetrics(
            repIndex = rep.repIndex,
            romPercent = combinedRom,
            tutFactor = tutFactor,
            confidence = 1.0f,
            durationSec = durationSec,
            minElbowAngle = combinedRom,
            startTimestampMs = rep.startTimestampMs,
            endTimestampMs = rep.endTimestampMs,
            landmarkVisibilityConfidence = 1.0f,
            phasePatternMatchConfidence = 1.0f,
            trajectorySmoothnessConfidence = 1.0f
        )
        _allRepMetrics.add(repMetrics)

        logDebug("[REP_COMPLETE] Rep #${rep.repIndex} at t=${timestampMs}ms | duration=${durationMs}ms | ROM=${combinedRom}% | divergence=${divergence}ms")

        isRepInProgress = false
        reachedOpen = false
        currentPhase = ExercisePhase.TOP
        return rep
    }

    private fun finalizeIncompleteRep(timestampMs: Long, reason: String): IncompleteRep {
        val combinedRom = JumpingJackGeometry.calculateCombinedRom(maxArmAngleThisRep, maxLegAngleThisRep)
        val incomplete = IncompleteRep(
            attemptIndex = _incompleteReps.size + 1,
            startTimestampMs = repStartTimestampMs,
            reversalTimestampMs = timestampMs,
            minElbowAngleAchieved = combinedRom,
            reason = reason
        )
        _incompleteReps.add(incomplete)

        isRepInProgress = false
        reachedOpen = false
        currentPhase = ExercisePhase.TOP
        return incomplete
    }

    fun reset() {
        currentPhase = ExercisePhase.TOP
        currentState = ExerciseState(
            phase = ExercisePhase.TOP,
            currentElbowAngle = JumpingJackGeometry.ARM_CLOSED_BASELINE,
            currentHipLineAngle = JumpingJackGeometry.LEG_CLOSED_BASELINE
        )
        isRepInProgress = false
        reachedOpen = false
        maxArmAngleThisRep = JumpingJackGeometry.ARM_CLOSED_BASELINE
        maxLegAngleThisRep = JumpingJackGeometry.LEG_CLOSED_BASELINE
        minArmAngleInClosing = 180.0f
        minLegAngleInClosing = 180.0f
        repStartTimestampMs = 0L
        openTimestampMs = 0L
        armOpenTimestampMs = 0L
        legOpenTimestampMs = 0L
        reversalCandidateTimestampMs = 0L
        lastRepAsymmetryDivergenceMs = null
        awaitingClosedReset = false
        _completeReps.clear()
        _incompleteReps.clear()
        _allRepMetrics.clear()
    }
}
