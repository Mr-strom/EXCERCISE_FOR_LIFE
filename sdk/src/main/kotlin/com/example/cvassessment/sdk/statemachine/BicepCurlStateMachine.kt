package com.example.cvassessment.sdk.statemachine

import com.example.cvassessment.sdk.pose.PoseEstimationResult
import com.example.cvassessment.sdk.spec.ExerciseConfig
import kotlin.math.abs

/**
 * Exercise State Machine for Bicep Curl per EXERCISE_SPEC.md.
 *
 * Implements:
 * 1. Bottom -> Ascending -> Top -> Descending -> Bottom rep cycle.
 *    - Bottom phase: elbow_angle > 160° (tolerance ±10°, i.e. >= 150°)
 *    - Top phase: elbow_angle < 45° (tolerance ±10°, i.e. <= 55°)
 *    - 8.0° reversal hysteresis to suppress BlazePose coordinate jitter.
 * 2. Dual-arm tracking:
 *    - Tracks left and right arms independently.
 *    - Supports simultaneous (synchronized) curls (counts as 1 rep when both complete).
 *    - Supports alternating curls (counts each arm's completed curl as a rep).
 *    - Supports single-arm curl (side-view or one-arm execution).
 * 3. Mid-rep visibility drop discarding.
 */
internal class BicepCurlStateMachine(
    val config: ExerciseConfig = ExerciseConfig.BICEP_CURL
) {
    var currentPhase: ExercisePhase = ExercisePhase.BOTTOM
        private set

    var currentState: ExerciseState = ExerciseState(
        phase = ExercisePhase.BOTTOM,
        currentElbowAngle = 180.0f,
        currentHipLineAngle = 0.0f
    )
        private set

    private val _completeReps = mutableListOf<RepBoundary>()
    val completeReps: List<RepBoundary> get() = _completeReps.toList()

    private val _incompleteReps = mutableListOf<IncompleteRep>()
    val incompleteReps: List<IncompleteRep> get() = _incompleteReps.toList()

    // Thresholds per EXERCISE_SPEC.md
    private val bottomThreshold: Float = config.phases["bottom"]?.thresholdAngle ?: 160.0f
    private val bottomTolerance: Float = config.phases["bottom"]?.toleranceDeg ?: 10.0f
    private val topThreshold: Float = config.phases["top"]?.thresholdAngle ?: 45.0f
    private val topTolerance: Float = config.phases["top"]?.toleranceDeg ?: 10.0f

    // 8.0° reversal hysteresis to avoid false reversals from jitter
    private val reversalHysteresisDeg: Float = 8.0f

    // Synchronization window (ms) to detect simultaneous dual-arm curling
    private val syncWindowMs: Long = 600L
    private var lastSynchronizedRepTimeMs: Long = -10000L

    internal class ArmTracker(val name: String) {
        var phase: ExercisePhase = ExercisePhase.BOTTOM
        var isRepInProgress: Boolean = false
        var reachedTop: Boolean = false
        var minAngleThisRep: Float = 180.0f
        var repStartTimestampMs: Long = 0L
        var topTimestampMs: Long = 0L
        var lastCompletedRepTimestampMs: Long = -10000L

        fun reset() {
            phase = ExercisePhase.BOTTOM
            isRepInProgress = false
            reachedTop = false
            minAngleThisRep = 180.0f
            repStartTimestampMs = 0L
            topTimestampMs = 0L
            lastCompletedRepTimestampMs = -10000L
        }

        fun abort() {
            phase = ExercisePhase.BOTTOM
            isRepInProgress = false
            reachedTop = false
            minAngleThisRep = 180.0f
            repStartTimestampMs = 0L
            topTimestampMs = 0L
        }
    }

    private val leftTracker = ArmTracker("LEFT")
    private val rightTracker = ArmTracker("RIGHT")

    /**
     * Process raw [PoseEstimationResult] through Bicep Curl geometry and state machine.
     */
    fun processFrame(
        poseResult: PoseEstimationResult,
        isVisibilitySufficient: Boolean = true
    ): ExerciseState {
        val armAngles = BicepCurlGeometry.computeElbowAngles(poseResult.landmarks)
        val stabilityAngle = BicepCurlGeometry.computeTorsoVerticalAngle(poseResult.landmarks)

        return when {
            armAngles.isLeftArmVisible && armAngles.isRightArmVisible -> {
                processAngles(
                    leftAngle = armAngles.leftElbowAngle,
                    rightAngle = armAngles.rightElbowAngle,
                    shoulderStabilityAngle = stabilityAngle,
                    timestampMs = poseResult.timestampMs,
                    isVisibilitySufficient = isVisibilitySufficient
                )
            }
            armAngles.isLeftArmVisible -> {
                processAngle(
                    elbowAngle = armAngles.leftElbowAngle,
                    hipLineAngle = stabilityAngle,
                    timestampMs = poseResult.timestampMs,
                    isVisibilitySufficient = isVisibilitySufficient
                )
            }
            armAngles.isRightArmVisible -> {
                processAngle(
                    elbowAngle = armAngles.rightElbowAngle,
                    hipLineAngle = stabilityAngle,
                    timestampMs = poseResult.timestampMs,
                    isVisibilitySufficient = isVisibilitySufficient
                )
            }
            else -> {
                processAngle(
                    elbowAngle = 180.0f,
                    hipLineAngle = stabilityAngle,
                    timestampMs = poseResult.timestampMs,
                    isVisibilitySufficient = isVisibilitySufficient
                )
            }
        }
    }

    /**
     * Process with a single elbow angle (used for single-arm curling or synchronized synthetic feeds).
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
     * Process both arm angles independently.
     */
    fun processAngles(
        leftAngle: Float,
        rightAngle: Float,
        shoulderStabilityAngle: Float,
        timestampMs: Long,
        isVisibilitySufficient: Boolean = true,
        isSingleOrSynchronized: Boolean = false
    ): ExerciseState {
        // Enforce Acceptance Criterion #3: Visibility gap mid-rep discards in-progress rep
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
            // Both arms tracking the exact same angle (or single active arm)
            val result = updateArm(leftTracker, leftAngle, timestampMs)
            rightTracker.phase = leftTracker.phase
            rightTracker.isRepInProgress = leftTracker.isRepInProgress
            rightTracker.reachedTop = leftTracker.reachedTop
            rightTracker.minAngleThisRep = leftTracker.minAngleThisRep

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
            // Dual-arm independent processing
            val leftResult = updateArm(leftTracker, leftAngle, timestampMs)
            val rightResult = updateArm(rightTracker, rightAngle, timestampMs)

            // Evaluate completions
            val leftDone = leftResult.completedRep
            val rightDone = rightResult.completedRep

            if (leftDone != null && rightDone != null) {
                // Both completed simultaneously in the exact same frame
                _completeReps.add(leftDone)
                newlyCompleted = leftDone
                lastSynchronizedRepTimeMs = timestampMs
                leftTracker.lastCompletedRepTimestampMs = timestampMs
                rightTracker.lastCompletedRepTimestampMs = timestampMs
            } else if (leftDone != null) {
                // Left completed: check if right recently completed (within sync window)
                if (abs(timestampMs - rightTracker.lastCompletedRepTimestampMs) <= syncWindowMs) {
                    // Right already counted this synchronized rep; do not double count
                    lastSynchronizedRepTimeMs = timestampMs
                } else if (rightTracker.reachedTop || rightTracker.phase == ExercisePhase.DESCENDING) {
                    // Right is also finishing a synchronized rep
                    _completeReps.add(leftDone)
                    newlyCompleted = leftDone
                    lastSynchronizedRepTimeMs = timestampMs
                } else {
                    // Alternating / independent curl
                    _completeReps.add(leftDone)
                    newlyCompleted = leftDone
                }
                leftTracker.lastCompletedRepTimestampMs = timestampMs
            } else if (rightDone != null) {
                // Right completed: check if left recently completed (within sync window)
                if (abs(timestampMs - leftTracker.lastCompletedRepTimestampMs) <= syncWindowMs) {
                    // Left already counted this synchronized rep; do not double count
                    lastSynchronizedRepTimeMs = timestampMs
                } else if (leftTracker.reachedTop || leftTracker.phase == ExercisePhase.DESCENDING) {
                    // Left is also finishing a synchronized rep
                    _completeReps.add(rightDone)
                    newlyCompleted = rightDone
                    lastSynchronizedRepTimeMs = timestampMs
                } else {
                    // Alternating / independent curl
                    _completeReps.add(rightDone)
                    newlyCompleted = rightDone
                }
                rightTracker.lastCompletedRepTimestampMs = timestampMs
            }

            // Incomplete attempts
            if (leftResult.incompleteRep != null) {
                _incompleteReps.add(leftResult.incompleteRep)
                newlyIncomplete = leftResult.incompleteRep
            } else if (rightResult.incompleteRep != null) {
                _incompleteReps.add(rightResult.incompleteRep)
                newlyIncomplete = rightResult.incompleteRep
            }
        }

        // Overall phase: prioritize moving phase over resting BOTTOM
        currentPhase = when {
            leftTracker.phase == ExercisePhase.TOP || rightTracker.phase == ExercisePhase.TOP -> ExercisePhase.TOP
            leftTracker.phase == ExercisePhase.ASCENDING || rightTracker.phase == ExercisePhase.ASCENDING -> ExercisePhase.ASCENDING
            leftTracker.phase == ExercisePhase.DESCENDING || rightTracker.phase == ExercisePhase.DESCENDING -> ExercisePhase.DESCENDING
            else -> ExercisePhase.BOTTOM
        }

        val inProgress = leftTracker.isRepInProgress || rightTracker.isRepInProgress
        val minAngle = when {
            leftTracker.isRepInProgress && rightTracker.isRepInProgress -> minOf(leftTracker.minAngleThisRep, rightTracker.minAngleThisRep)
            leftTracker.isRepInProgress -> leftTracker.minAngleThisRep
            rightTracker.isRepInProgress -> rightTracker.minAngleThisRep
            else -> null
        }

        val state = ExerciseState(
            phase = currentPhase,
            currentElbowAngle = (leftAngle + rightAngle) / 2.0f,
            currentHipLineAngle = shoulderStabilityAngle,
            completeReps = _completeReps.toList(),
            incompleteReps = _incompleteReps.toList(),
            newlyCompletedRep = newlyCompleted,
            newlyDetectedIncompleteRep = newlyIncomplete,
            isRepInProgress = inProgress,
            currentRepMinAngle = minAngle
        )
        currentState = state
        return state
    }

    private data class ArmUpdateResult(
        val completedRep: RepBoundary? = null,
        val incompleteRep: IncompleteRep? = null
    )

    private fun updateArm(arm: ArmTracker, angle: Float, timestampMs: Long): ArmUpdateResult {
        var completed: RepBoundary? = null
        var incomplete: IncompleteRep? = null

        val bottomThresholdDeg = bottomThreshold - bottomTolerance // 160° - 10° = 150°
        val topThresholdDeg = topThreshold + topTolerance // 45° + 10° = 55°

        when (arm.phase) {
            ExercisePhase.BOTTOM -> {
                arm.isRepInProgress = false
                arm.minAngleThisRep = angle

                // Arms begin flexing upwards: angle drops below bottom threshold buffer
                if (angle < (bottomThresholdDeg - 5.0f)) {
                    arm.phase = ExercisePhase.ASCENDING
                    arm.isRepInProgress = true
                    arm.reachedTop = false
                    arm.repStartTimestampMs = timestampMs
                    arm.minAngleThisRep = angle
                }
            }

            ExercisePhase.ASCENDING -> {
                if (angle < arm.minAngleThisRep) {
                    arm.minAngleThisRep = angle
                }

                // Check if reached top inflection target (<= 55°)
                if (angle <= topThresholdDeg) {
                    arm.phase = ExercisePhase.TOP
                    arm.reachedTop = true
                    arm.topTimestampMs = timestampMs
                } else if (angle >= bottomThresholdDeg) {
                    // Dropped all the way back to bottom without reaching top target: incomplete
                    val inc = IncompleteRep(
                        attemptIndex = _incompleteReps.size + 1,
                        startTimestampMs = arm.repStartTimestampMs,
                        reversalTimestampMs = timestampMs,
                        minElbowAngleAchieved = arm.minAngleThisRep,
                        reason = "Reversed before reaching top target (achieved ${arm.minAngleThisRep}° vs <= ${topThresholdDeg}°)"
                    )
                    incomplete = inc
                    arm.abort()
                } else if (angle > (arm.minAngleThisRep + reversalHysteresisDeg)) {
                    // Reversal detected during ascending phase before reaching top: incomplete attempt
                    val inc = IncompleteRep(
                        attemptIndex = _incompleteReps.size + 1,
                        startTimestampMs = arm.repStartTimestampMs,
                        reversalTimestampMs = timestampMs,
                        minElbowAngleAchieved = arm.minAngleThisRep,
                        reason = "Reversed by > ${reversalHysteresisDeg}° before reaching top target (achieved ${arm.minAngleThisRep}° vs <= ${topThresholdDeg}°)"
                    )
                    incomplete = inc
                    arm.abort()
                }
            }

            ExercisePhase.TOP -> {
                if (angle < arm.minAngleThisRep) {
                    arm.minAngleThisRep = angle
                    arm.topTimestampMs = timestampMs
                }

                // Check if arm directly returned to full extension at bottom (>= 150°)
                if (angle >= bottomThresholdDeg) {
                    if (arm.reachedTop) {
                        val rep = RepBoundary(
                            repIndex = _completeReps.size + 1,
                            startTimestampMs = arm.repStartTimestampMs,
                            bottomTimestampMs = arm.topTimestampMs,
                            endTimestampMs = timestampMs,
                            durationMs = timestampMs - arm.repStartTimestampMs,
                            minElbowAngle = arm.minAngleThisRep,
                            isComplete = true
                        )
                        completed = rep
                    }
                    arm.abort()
                } else if (angle > (arm.minAngleThisRep + 5.0f) || angle > topThresholdDeg) {
                    arm.phase = ExercisePhase.DESCENDING
                }
            }

            ExercisePhase.DESCENDING -> {
                // Check if user returned to full extension at bottom (>= 150°)
                if (angle >= bottomThresholdDeg) {
                    if (arm.reachedTop) {
                        // Clean rep complete!
                        val rep = RepBoundary(
                            repIndex = _completeReps.size + 1,
                            startTimestampMs = arm.repStartTimestampMs,
                            bottomTimestampMs = arm.topTimestampMs,
                            endTimestampMs = timestampMs,
                            durationMs = timestampMs - arm.repStartTimestampMs,
                            minElbowAngle = arm.minAngleThisRep,
                            isComplete = true
                        )
                        completed = rep
                    } else {
                        // Returned to bottom without having reached top
                        val inc = IncompleteRep(
                            attemptIndex = _incompleteReps.size + 1,
                            startTimestampMs = arm.repStartTimestampMs,
                            reversalTimestampMs = timestampMs,
                            minElbowAngleAchieved = arm.minAngleThisRep,
                            reason = "Returned to bottom without reaching top target"
                        )
                        incomplete = inc
                    }
                    arm.abort()
                } else if (angle < (arm.minAngleThisRep - 5.0f)) {
                    // Re-curling upward before finishing descent
                    arm.phase = ExercisePhase.ASCENDING
                }
            }
        }

        return ArmUpdateResult(completedRep = completed, incompleteRep = incomplete)
    }

    /**
     * Resets all internal state, reps, and arm trackers.
     */
    fun reset() {
        currentPhase = ExercisePhase.BOTTOM
        currentState = ExerciseState(
            phase = ExercisePhase.BOTTOM,
            currentElbowAngle = 180.0f,
            currentHipLineAngle = 0.0f
        )
        _completeReps.clear()
        _incompleteReps.clear()
        leftTracker.reset()
        rightTracker.reset()
        lastSynchronizedRepTimeMs = -10000L
    }
}
