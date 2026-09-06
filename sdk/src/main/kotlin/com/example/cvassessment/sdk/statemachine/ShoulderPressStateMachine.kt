package com.example.cvassessment.sdk.statemachine

import com.example.cvassessment.sdk.pose.PoseEstimationResult
import com.example.cvassessment.sdk.spec.ExerciseConfig
import kotlin.math.abs

/**
 * Exercise State Machine for Shoulder Press per EXERCISE_SPEC.md and D12 lessons.
 *
 * Tracks overhead arm extension frame-by-frame, detects movement phase transitions
 * (BOTTOM -> ASCENDING -> TOP -> DESCENDING -> BOTTOM), and manages rep boundaries
 * with dual-arm synchronization and D12 proactive anti-jitter protections.
 *
 * Thresholds per EXERCISE_SPEC.md & D12 calibration:
 * - Bottom phase: elbow_angle ~90° (tolerance ±15°, i.e. <= 105°)
 * - Top phase: elbow_angle ~155° (tolerance ±10°, i.e. >= 145°)
 * - Reversal hysteresis: 8.0° uniform
 * - Top-hold settle time: 200ms
 */
internal class ShoulderPressStateMachine(
    val config: ExerciseConfig = ExerciseConfig.SHOULDER_PRESS,
    val syncWindowMs: Long = 600L
) {
    var currentPhase: ExercisePhase = ExercisePhase.BOTTOM
        private set

    var currentState: ExerciseState = ExerciseState(
        phase = ExercisePhase.BOTTOM,
        currentElbowAngle = 90.0f,
        currentHipLineAngle = 0.0f
    )
        private set

    private val _completeReps = mutableListOf<RepBoundary>()
    val completeReps: List<RepBoundary> get() = _completeReps.toList()

    private val _incompleteReps = mutableListOf<IncompleteRep>()
    val incompleteReps: List<IncompleteRep> get() = _incompleteReps.toList()

    private val topThreshold: Float = config.phases["top"]?.thresholdAngle ?: 155.0f
    private val topTolerance: Float = config.phases["top"]?.toleranceDeg ?: 10.0f
    private val bottomThreshold: Float = config.phases["bottom"]?.thresholdAngle ?: 90.0f
    private val bottomTolerance: Float = config.phases["bottom"]?.toleranceDeg ?: 15.0f

    private val reversalHysteresisDeg: Float = 8.0f
    private val minTopHoldSettleMs: Long = 200L

    var isDebugLoggingEnabled: Boolean = false
    var debugLogger: ((String) -> Unit)? = null

    private fun logDebug(msg: String) {
        if (!isDebugLoggingEnabled) return
        debugLogger?.invoke(msg)
        try {
            android.util.Log.d("ShoulderPressSM", msg)
        } catch (_: Throwable) {
            // JVM unit test fallback
        }
    }

    private class ArmTracker(val armName: String) {
        var phase: ExercisePhase = ExercisePhase.BOTTOM
        var isRepInProgress: Boolean = false
        var reachedTop: Boolean = false
        var maxAngleThisRep: Float = 90.0f
        var minAngleThisRep: Float = 180.0f
        var repStartTimestampMs: Long = 0L
        var topTimestampMs: Long = 0L
        var reversalCandidateTimestampMs: Long = 0L
        var lastCompletedRepTimestampMs: Long = 0L

        fun reset() {
            phase = ExercisePhase.BOTTOM
            isRepInProgress = false
            reachedTop = false
            maxAngleThisRep = 90.0f
            minAngleThisRep = 180.0f
            repStartTimestampMs = 0L
            topTimestampMs = 0L
            reversalCandidateTimestampMs = 0L
            lastCompletedRepTimestampMs = 0L
        }

        fun abort() {
            phase = ExercisePhase.BOTTOM
            isRepInProgress = false
            reachedTop = false
            maxAngleThisRep = 90.0f
            minAngleThisRep = 180.0f
            repStartTimestampMs = 0L
            topTimestampMs = 0L
            reversalCandidateTimestampMs = 0L
        }
    }

    private val leftTracker = ArmTracker("LEFT_ARM")
    private val rightTracker = ArmTracker("RIGHT_ARM")
    private var lastSynchronizedRepTimeMs: Long = 0L

    /**
     * Process a raw [PoseEstimationResult] through Shoulder Press geometry.
     */
    fun processFrame(
        poseResult: PoseEstimationResult,
        isVisibilitySufficient: Boolean = true
    ): ExerciseState {
        val armAngles = ShoulderPressGeometry.computeArmAngles(poseResult.landmarks)
        val stabilityAngle = ShoulderPressGeometry.computeTorsoVerticalAngle(poseResult.landmarks)
        val isSide = ShoulderPressGeometry.isSideView(poseResult.landmarks)

        return when {
            isSide && armAngles.isLeftArmVisible -> {
                processAngle(
                    elbowAngle = armAngles.leftElbowAngle,
                    hipLineAngle = stabilityAngle,
                    timestampMs = poseResult.timestampMs,
                    isVisibilitySufficient = isVisibilitySufficient
                )
            }
            isSide && armAngles.isRightArmVisible -> {
                processAngle(
                    elbowAngle = armAngles.rightElbowAngle,
                    hipLineAngle = stabilityAngle,
                    timestampMs = poseResult.timestampMs,
                    isVisibilitySufficient = isVisibilitySufficient
                )
            }
            !isSide && (armAngles.isLeftArmVisible || armAngles.isRightArmVisible) -> {
                processAngles(
                    leftAngle = if (armAngles.isLeftArmVisible) armAngles.leftElbowAngle else armAngles.rightElbowAngle,
                    rightAngle = if (armAngles.isRightArmVisible) armAngles.rightElbowAngle else armAngles.leftElbowAngle,
                    shoulderStabilityAngle = stabilityAngle,
                    timestampMs = poseResult.timestampMs,
                    isVisibilitySufficient = isVisibilitySufficient,
                    isSingleOrSynchronized = false
                )
            }
            else -> {
                processAngle(
                    elbowAngle = 90.0f,
                    hipLineAngle = stabilityAngle,
                    timestampMs = poseResult.timestampMs,
                    isVisibilitySufficient = isVisibilitySufficient
                )
            }
        }
    }

    /**
     * Process with a single elbow angle (used for single-arm pressing or synthetic testing).
     */
    fun processAngle(
        elbowAngle: Float,
        hipLineAngle: Float,
        timestampMs: Long,
        isVisibilitySufficient: Boolean = true
    ): ExerciseState {
        return processAngles(
            leftAngle = elbowAngle,
            rightAngle = elbowAngle,
            shoulderStabilityAngle = hipLineAngle,
            timestampMs = timestampMs,
            isVisibilitySufficient = isVisibilitySufficient,
            isSingleOrSynchronized = true
        )
    }

    /**
     * Process both arm angles independently with dual-arm synchronization.
     */
    fun processAngles(
        leftAngle: Float,
        rightAngle: Float,
        shoulderStabilityAngle: Float,
        timestampMs: Long,
        isVisibilitySufficient: Boolean = true,
        isSingleOrSynchronized: Boolean = false
    ): ExerciseState {
        // Acceptance Criterion #3: Visibility gap mid-rep discards in-progress rep
        if (!isVisibilitySufficient) {
            leftTracker.abort()
            rightTracker.abort()
            currentPhase = ExercisePhase.BOTTOM
            val state = ExerciseState(
                phase = currentPhase,
                currentElbowAngle = (leftAngle + rightAngle) / 2.0f,
                currentHipLineAngle = shoulderStabilityAngle,
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

        if (isSingleOrSynchronized) {
            // Single arm or synchronized feed
            val result = updateArm(leftTracker, leftAngle, timestampMs)
            rightTracker.phase = leftTracker.phase
            rightTracker.isRepInProgress = leftTracker.isRepInProgress
            rightTracker.reachedTop = leftTracker.reachedTop
            rightTracker.maxAngleThisRep = leftTracker.maxAngleThisRep

            if (result.completedRep != null) {
                _completeReps.add(result.completedRep)
                newlyCompleted = result.completedRep
                lastSynchronizedRepTimeMs = timestampMs
            }
            if (result.incompleteRep != null) {
                _incompleteReps.add(result.incompleteRep)
                newlyIncomplete = result.incompleteRep
            }
        } else {
            // Independent dual-arm processing
            val leftResult = updateArm(leftTracker, leftAngle, timestampMs)
            val rightResult = updateArm(rightTracker, rightAngle, timestampMs)

            val leftDone = leftResult.completedRep
            val rightDone = rightResult.completedRep

            if (leftDone != null && rightDone != null) {
                _completeReps.add(leftDone)
                newlyCompleted = leftDone
                lastSynchronizedRepTimeMs = timestampMs
                leftTracker.lastCompletedRepTimestampMs = timestampMs
                rightTracker.lastCompletedRepTimestampMs = timestampMs
            } else if (leftDone != null) {
                if (abs(timestampMs - rightTracker.lastCompletedRepTimestampMs) <= syncWindowMs) {
                    lastSynchronizedRepTimeMs = timestampMs
                } else if (rightTracker.reachedTop || rightTracker.phase == ExercisePhase.DESCENDING) {
                    _completeReps.add(leftDone)
                    newlyCompleted = leftDone
                    lastSynchronizedRepTimeMs = timestampMs
                } else {
                    _completeReps.add(leftDone)
                    newlyCompleted = leftDone
                }
                leftTracker.lastCompletedRepTimestampMs = timestampMs
            } else if (rightDone != null) {
                if (abs(timestampMs - leftTracker.lastCompletedRepTimestampMs) <= syncWindowMs) {
                    lastSynchronizedRepTimeMs = timestampMs
                } else if (leftTracker.reachedTop || leftTracker.phase == ExercisePhase.DESCENDING) {
                    _completeReps.add(rightDone)
                    newlyCompleted = rightDone
                    lastSynchronizedRepTimeMs = timestampMs
                } else {
                    _completeReps.add(rightDone)
                    newlyCompleted = rightDone
                }
                rightTracker.lastCompletedRepTimestampMs = timestampMs
            }

            if (leftResult.incompleteRep != null) {
                _incompleteReps.add(leftResult.incompleteRep)
                newlyIncomplete = leftResult.incompleteRep
            } else if (rightResult.incompleteRep != null) {
                _incompleteReps.add(rightResult.incompleteRep)
                newlyIncomplete = rightResult.incompleteRep
            }
        }

        currentPhase = when {
            leftTracker.phase == ExercisePhase.TOP || rightTracker.phase == ExercisePhase.TOP -> ExercisePhase.TOP
            leftTracker.phase == ExercisePhase.ASCENDING || rightTracker.phase == ExercisePhase.ASCENDING -> ExercisePhase.ASCENDING
            leftTracker.phase == ExercisePhase.DESCENDING || rightTracker.phase == ExercisePhase.DESCENDING -> ExercisePhase.DESCENDING
            else -> ExercisePhase.BOTTOM
        }

        val inProgress = leftTracker.isRepInProgress || rightTracker.isRepInProgress
        val maxAngle = when {
            leftTracker.isRepInProgress && rightTracker.isRepInProgress -> maxOf(leftTracker.maxAngleThisRep, rightTracker.maxAngleThisRep)
            leftTracker.isRepInProgress -> leftTracker.maxAngleThisRep
            rightTracker.isRepInProgress -> rightTracker.maxAngleThisRep
            else -> null
        }

        logDebug("[FRAME] t=${timestampMs}ms | left=${"%.1f".format(leftAngle)}° | right=${"%.1f".format(rightAngle)}° | phase=$currentPhase | complete=${_completeReps.size} | incomplete=${_incompleteReps.size}")

        val state = ExerciseState(
            phase = currentPhase,
            currentElbowAngle = (leftAngle + rightAngle) / 2.0f,
            currentHipLineAngle = shoulderStabilityAngle,
            completeReps = _completeReps.toList(),
            incompleteReps = _incompleteReps.toList(),
            newlyCompletedRep = newlyCompleted,
            newlyDetectedIncompleteRep = newlyIncomplete,
            isRepInProgress = inProgress,
            currentRepMinAngle = maxAngle // Records peak extension achieved
        )
        currentState = state
        return state
    }

    private data class ArmUpdateResult(
        val completedRep: RepBoundary? = null,
        val incompleteRep: IncompleteRep? = null
    )

    /**
     * State machine logic for an individual arm in Shoulder Press.
     *
     * In Shoulder Press:
     * - BOTTOM: elbow angle <= 105° (flexed in rack position)
     * - ASCENDING: elbow angle increases upwards toward lockout
     * - TOP: elbow angle >= 145° (overhead lockout per D12 calibration)
     * - DESCENDING: elbow angle decreases back down to rack position
     */
    private fun updateArm(arm: ArmTracker, angle: Float, timestampMs: Long): ArmUpdateResult {
        var completed: RepBoundary? = null
        var incomplete: IncompleteRep? = null

        val bottomThresholdDeg = bottomThreshold + bottomTolerance // 90° + 15° = 105°
        val topThresholdDeg = topThreshold - topTolerance // 155° - 10° = 145° (calibrated per D12)

        when (arm.phase) {
            ExercisePhase.BOTTOM -> {
                arm.isRepInProgress = false
                arm.maxAngleThisRep = angle
                arm.reversalCandidateTimestampMs = 0L

                // Arm begins extending upward past bottom threshold buffer (+5°)
                if (angle > (bottomThreshold + 5.0f)) {
                    arm.phase = ExercisePhase.ASCENDING
                    arm.isRepInProgress = true
                    arm.reachedTop = false
                    arm.repStartTimestampMs = timestampMs
                    arm.maxAngleThisRep = angle
                    arm.reversalCandidateTimestampMs = 0L
                    logDebug("[TRANSITION-${arm.armName}] BOTTOM -> ASCENDING at t=${timestampMs}ms (angle=${angle}°)")
                }
            }

            ExercisePhase.ASCENDING -> {
                if (angle > arm.maxAngleThisRep) {
                    arm.maxAngleThisRep = angle
                    arm.reversalCandidateTimestampMs = 0L
                }

                // Check if reached overhead lockout (>= 145°)
                if (angle >= topThresholdDeg) {
                    arm.phase = ExercisePhase.TOP
                    arm.reachedTop = true
                    arm.topTimestampMs = timestampMs
                    arm.reversalCandidateTimestampMs = 0L
                    logDebug("[TRANSITION-${arm.armName}] ASCENDING -> TOP at t=${timestampMs}ms (angle=${angle}°)")
                } else if (angle <= (bottomThreshold + 5.0f)) {
                    // Returned all the way to bottom rack without reaching top lockout: incomplete rep
                    val inc = IncompleteRep(
                        attemptIndex = _incompleteReps.size + 1,
                        startTimestampMs = arm.repStartTimestampMs,
                        reversalTimestampMs = timestampMs,
                        minElbowAngleAchieved = arm.maxAngleThisRep,
                        reason = "Reversed before reaching top lockout (achieved ${arm.maxAngleThisRep}° vs >= ${topThresholdDeg}°)"
                    )
                    incomplete = inc
                    logDebug("[INCOMPLETE-${arm.armName}] Attempt #${inc.attemptIndex} at t=${timestampMs}ms | maxAchieved=${inc.minElbowAngleAchieved}° | reason='${inc.reason}'")
                    arm.abort()
                } else if (angle < (arm.maxAngleThisRep - reversalHysteresisDeg)) {
                    // Downward reversal detected before reaching top lockout
                    if (arm.reversalCandidateTimestampMs == 0L) {
                        arm.reversalCandidateTimestampMs = timestampMs
                    }
                    val settleDuration = timestampMs - arm.reversalCandidateTimestampMs
                    val isDecisiveReversal = angle <= (arm.maxAngleThisRep - 12.0f) || angle <= (bottomThreshold + 10.0f)

                    if (isDecisiveReversal || settleDuration >= 200L) {
                        val inc = IncompleteRep(
                            attemptIndex = _incompleteReps.size + 1,
                            startTimestampMs = arm.repStartTimestampMs,
                            reversalTimestampMs = timestampMs,
                            minElbowAngleAchieved = arm.maxAngleThisRep,
                            reason = "Reversed by > ${reversalHysteresisDeg}° before reaching top lockout (achieved ${arm.maxAngleThisRep}° vs >= ${topThresholdDeg}°)"
                        )
                        incomplete = inc
                        logDebug("[INCOMPLETE-${arm.armName}] Attempt #${inc.attemptIndex} at t=${timestampMs}ms | maxAchieved=${inc.minElbowAngleAchieved}° | reason='${inc.reason}'")
                        arm.abort()
                    }
                } else {
                    arm.reversalCandidateTimestampMs = 0L
                }
            }

            ExercisePhase.TOP -> {
                if (angle > arm.maxAngleThisRep) {
                    arm.maxAngleThisRep = angle
                    arm.topTimestampMs = timestampMs
                }

                // D12 Proactive Lesson: Top-hold settle time protection
                // Jitter of ±5° during lockout pause (e.g. 155° to 148°) must stay in TOP.
                val hasLoweredPastHysteresis = angle < (arm.maxAngleThisRep - reversalHysteresisDeg)
                val hasExitedTop = angle < topThresholdDeg

                if (hasLoweredPastHysteresis && hasExitedTop) {
                    // Downward descent begun
                    if (arm.reversalCandidateTimestampMs == 0L) {
                        arm.reversalCandidateTimestampMs = timestampMs
                    }
                    val settleDuration = timestampMs - arm.reversalCandidateTimestampMs
                    val isDecisiveDescent = angle <= (arm.maxAngleThisRep - 12.0f) || angle <= 135.0f

                    if (isDecisiveDescent || settleDuration >= minTopHoldSettleMs) {
                        arm.phase = ExercisePhase.DESCENDING
                        arm.minAngleThisRep = angle
                        arm.reversalCandidateTimestampMs = 0L
                        logDebug("[TRANSITION-${arm.armName}] TOP -> DESCENDING at t=${timestampMs}ms (angle=${angle}°)")

                        // If user dropped all the way to bottom rack in a single leap
                        if (angle <= bottomThresholdDeg) {
                            if (arm.reachedTop) {
                                val rep = RepBoundary(
                                    repIndex = _completeReps.size + 1,
                                    startTimestampMs = arm.repStartTimestampMs,
                                    bottomTimestampMs = arm.topTimestampMs,
                                    endTimestampMs = timestampMs,
                                    durationMs = timestampMs - arm.repStartTimestampMs,
                                    minElbowAngle = arm.maxAngleThisRep,
                                    isComplete = true
                                )
                                completed = rep
                                logDebug("[COMPLETE-${arm.armName}] Rep #${rep.repIndex} at t=${timestampMs}ms | duration=${rep.durationMs}ms")
                            }
                            arm.abort()
                        }
                    }
                } else {
                    // Stable in overhead lockout hold: absorb tracking noise
                    arm.reversalCandidateTimestampMs = 0L
                }
            }

            ExercisePhase.DESCENDING -> {
                if (angle < arm.minAngleThisRep) {
                    arm.minAngleThisRep = angle
                }

                // Check if arm returned to rack position at bottom (<= 105°)
                if (angle <= bottomThresholdDeg) {
                    if (arm.reachedTop) {
                        val rep = RepBoundary(
                            repIndex = _completeReps.size + 1,
                            startTimestampMs = arm.repStartTimestampMs,
                            bottomTimestampMs = arm.topTimestampMs,
                            endTimestampMs = timestampMs,
                            durationMs = timestampMs - arm.repStartTimestampMs,
                            minElbowAngle = arm.maxAngleThisRep,
                            isComplete = true
                        )
                        completed = rep
                        logDebug("[COMPLETE-${arm.armName}] Rep #${rep.repIndex} at t=${timestampMs}ms | duration=${rep.durationMs}ms")
                    } else {
                        val inc = IncompleteRep(
                            attemptIndex = _incompleteReps.size + 1,
                            startTimestampMs = arm.repStartTimestampMs,
                            reversalTimestampMs = timestampMs,
                            minElbowAngleAchieved = arm.maxAngleThisRep,
                            reason = "Descent finished but lockout was incomplete"
                        )
                        incomplete = inc
                    }
                    arm.abort()
                } else if (angle > (arm.minAngleThisRep + reversalHysteresisDeg)) {
                    // D12 Guarded re-ascent: user started pressing up again before reaching bottom
                    if (arm.reachedTop) {
                        // User completed overhead lockout, lowered partially, then started pressing up for next rep
                        val rep = RepBoundary(
                            repIndex = _completeReps.size + 1,
                            startTimestampMs = arm.repStartTimestampMs,
                            bottomTimestampMs = arm.topTimestampMs,
                            endTimestampMs = timestampMs,
                            durationMs = timestampMs - arm.repStartTimestampMs,
                            minElbowAngle = arm.maxAngleThisRep,
                            isComplete = true
                        )
                        completed = rep
                        // Start next rep seamlessly
                        arm.phase = ExercisePhase.ASCENDING
                        arm.isRepInProgress = true
                        arm.reachedTop = false
                        arm.repStartTimestampMs = timestampMs
                        arm.maxAngleThisRep = angle
                    } else {
                        arm.phase = ExercisePhase.ASCENDING
                    }
                }
            }
        }

        return ArmUpdateResult(completedRep = completed, incompleteRep = incomplete)
    }

    fun reset() {
        currentPhase = ExercisePhase.BOTTOM
        currentState = ExerciseState(
            phase = ExercisePhase.BOTTOM,
            currentElbowAngle = 90.0f,
            currentHipLineAngle = 0.0f
        )
        _completeReps.clear()
        _incompleteReps.clear()
        leftTracker.reset()
        rightTracker.reset()
        lastSynchronizedRepTimeMs = 0L
    }
}
