package com.example.cvassessment.sdk.statemachine

import com.example.cvassessment.sdk.metrics.FrameMetrics
import com.example.cvassessment.sdk.metrics.RepMetrics
import com.example.cvassessment.sdk.pose.PoseEstimationResult
import com.example.cvassessment.sdk.spec.ExerciseConfig
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Movement phases for static hold exercise (Plank) per EXERCISE_SPEC.md #7 and METRICS_SPEC.md §2.
 */
enum class PlankPhase {
    NOT_STARTED,
    HOLD_START,
    HOLDING,
    HOLD_END
}

/**
 * Exercise State Machine for Plank per EXERCISE_SPEC.md #7, METRICS_SPEC.md §2, §4, §5, and FORM_RULES.md.
 *
 * Implements:
 * 1. Hold timer management (METRICS_SPEC.md §2):
 *    - Starts accumulating when hip_line_angle enters tolerance (180° ± 15°, i.e. [165°, 195°]).
 *    - Pauses (does NOT reset to zero) during visibility gaps.
 *    - Resumes cleanly when visibility returns.
 * 2. Grace period / Settle protection:
 *    - Brief wobbles outside [165°, 195°] lasting shorter than gracePeriodMs (1000ms / 5 frames)
 *      trigger a postural_break event but do NOT terminate the hold.
 *    - Sustained deviations exceeding gracePeriodMs trigger HOLD_END, finalizing the hold timer.
 * 3. Postural deviation Range of Motion (ROM%) tracking:
 *    - Time-averaged across the valid hold per METRICS_SPEC.md §4.
 * 4. Time under Tension (TuT) Factor:
 *    - Computed as consistency of hip_line_angle over the hold (METRICS_SPEC.md §5).
 */
internal class PlankStateMachine(
    val config: ExerciseConfig = ExerciseConfig.PLANK,
    val toleranceDeg: Float = 15.0f,
    val gracePeriodMs: Long = 1000L,
    val minDeviationFramesToFail: Int = 6
) {
    var plankPhase: PlankPhase = PlankPhase.NOT_STARTED
        private set

    var currentState: ExerciseState = ExerciseState(
        phase = ExercisePhase.TOP,
        currentElbowAngle = 180.0f,
        currentHipLineAngle = 180.0f
    )
        private set

    // Cumulative hold duration in milliseconds (excluding paused gaps)
    var accumulatedHoldMs: Long = 0L
        private set

    val holdDurationSec: Float
        get() = (accumulatedHoldMs / 100.0f).roundToInt() / 10.0f // rounded to 1 decimal

    var isPaused: Boolean = false
        private set

    var isHoldEnded: Boolean = false
        private set

    // Timestamp tracking
    private var lastActiveTimestampMs: Long = 0L
    private var deviationStartTimestampMs: Long = 0L
    private var deviationFrameCount: Int = 0

    // History for TuT factor and running ROM%
    private val angleHistory = mutableListOf<Float>()
    private val romHistory = mutableListOf<Float>()

    // Postural break wobble events (timestamp and duration)
    var lastWobbleDetected: Boolean = false
        private set

    val averageRomPercent: Float
        get() = if (romHistory.isNotEmpty()) romHistory.average().toFloat() else 100.0f

    val tutFactor: Float
        get() = PlankGeometry.calculateTutFactor(angleHistory, maxAllowedStddev = toleranceDeg)

    var isDebugLoggingEnabled: Boolean = false
    var debugLogger: ((String) -> Unit)? = null

    private fun logDebug(msg: String) {
        if (!isDebugLoggingEnabled) return
        debugLogger?.invoke(msg)
        try {
            android.util.Log.d("PlankStateMachine", msg)
        } catch (_: Throwable) {
        }
    }

    /**
     * Resets state machine to initial unstarted state.
     */
    fun reset() {
        plankPhase = PlankPhase.NOT_STARTED
        accumulatedHoldMs = 0L
        isPaused = false
        isHoldEnded = false
        lastActiveTimestampMs = 0L
        deviationStartTimestampMs = 0L
        deviationFrameCount = 0
        angleHistory.clear()
        romHistory.clear()
        lastWobbleDetected = false
        currentState = ExerciseState(
            phase = ExercisePhase.TOP,
            currentElbowAngle = 180.0f,
            currentHipLineAngle = 180.0f
        )
    }

    /**
     * Processes a single camera frame with pose estimation landmarks.
     */
    fun processFrame(poseResult: PoseEstimationResult, isVisibilitySufficient: Boolean): ExerciseState {
        val hipLineAngle = if (poseResult.hasPose && poseResult.landmarks.isNotEmpty()) {
            PlankGeometry.computeHipLineAngle(poseResult.landmarks)
        } else {
            currentState.currentHipLineAngle
        }
        return processAngle(hipLineAngle, poseResult.timestampMs, isVisibilitySufficient)
    }

    /**
     * Processes a synthetic or pre-computed angle with timestamp.
     */
    fun processAngle(hipLineAngle: Float, timestampMs: Long, isVisibilitySufficient: Boolean): ExerciseState {
        lastWobbleDetected = false

        // 1. Check visibility gap handling: PAUSE during tracking loss, do NOT reset to zero
        if (!isVisibilitySufficient) {
            if (plankPhase == PlankPhase.HOLDING || plankPhase == PlankPhase.HOLD_START) {
                isPaused = true
                logDebug("Frame $timestampMs: Visibility dropped during hold. Pausing hold timer (accumulated: ${holdDurationSec}s)")
            }
            // While visibility is insufficient, freeze timer
            lastActiveTimestampMs = 0L
            return currentState.copy(
                currentHipLineAngle = hipLineAngle
            )
        }

        // Visibility is sufficient here. If previously paused, resume timer cleanly
        if (isPaused) {
            isPaused = false
            lastActiveTimestampMs = timestampMs
            logDebug("Frame $timestampMs: Visibility restored. Resuming hold timer from ${holdDurationSec}s")
        }

        // If hold has already finalized (HOLD_END), do not accumulate further time
        if (isHoldEnded) {
            return currentState.copy(
                phase = ExercisePhase.TOP,
                currentHipLineAngle = hipLineAngle
            )
        }

        val isInTolerance = abs(180.0f - hipLineAngle) <= toleranceDeg

        when (plankPhase) {
            PlankPhase.NOT_STARTED -> {
                if (isInTolerance) {
                    plankPhase = PlankPhase.HOLD_START
                    lastActiveTimestampMs = timestampMs
                    angleHistory.add(hipLineAngle)
                    romHistory.add(PlankGeometry.calculatePosturalRom(hipLineAngle))
                    logDebug("Frame $timestampMs: Hold start detected at angle $hipLineAngle°")
                }
            }

            PlankPhase.HOLD_START -> {
                plankPhase = PlankPhase.HOLDING
                if (lastActiveTimestampMs > 0L && timestampMs > lastActiveTimestampMs) {
                    accumulatedHoldMs += (timestampMs - lastActiveTimestampMs)
                }
                lastActiveTimestampMs = timestampMs
                angleHistory.add(hipLineAngle)
                romHistory.add(PlankGeometry.calculatePosturalRom(hipLineAngle))
            }

            PlankPhase.HOLDING -> {
                if (isInTolerance) {
                    // Position maintained within tolerance
                    if (deviationFrameCount > 0) {
                        // User wobbled out of tolerance but recovered within grace period!
                        // This constitutes a postural_break
                        val deviationDuration = timestampMs - deviationStartTimestampMs
                        logDebug("Frame $timestampMs: Postural wobble recovered after ${deviationDuration}ms (${deviationFrameCount} frames). Hold continues!")
                        lastWobbleDetected = true
                        deviationStartTimestampMs = 0L
                        deviationFrameCount = 0
                    }

                    if (lastActiveTimestampMs > 0L && timestampMs > lastActiveTimestampMs) {
                        accumulatedHoldMs += (timestampMs - lastActiveTimestampMs)
                    }
                    lastActiveTimestampMs = timestampMs
                    angleHistory.add(hipLineAngle)
                    romHistory.add(PlankGeometry.calculatePosturalRom(hipLineAngle))
                } else {
                    // Angle deviated outside tolerance: start/continue grace period countdown
                    if (deviationStartTimestampMs == 0L) {
                        deviationStartTimestampMs = timestampMs
                        deviationFrameCount = 1
                    } else {
                        deviationFrameCount++
                    }

                    val deviationDuration = timestampMs - deviationStartTimestampMs

                    // If deviation exceeds gracePeriodMs OR exceeds max frames, trigger HOLD_END
                    if (deviationDuration >= gracePeriodMs || deviationFrameCount >= minDeviationFramesToFail) {
                        plankPhase = PlankPhase.HOLD_END
                        isHoldEnded = true
                        logDebug("Frame $timestampMs: Hold ended! Deviation ($hipLineAngle°) sustained for ${deviationDuration}ms ($deviationFrameCount frames). Final hold: ${holdDurationSec}s")
                    } else {
                        // Within grace period wobble: hold is still active, accumulate time
                        if (lastActiveTimestampMs > 0L && timestampMs > lastActiveTimestampMs) {
                            accumulatedHoldMs += (timestampMs - lastActiveTimestampMs)
                        }
                        lastActiveTimestampMs = timestampMs
                        angleHistory.add(hipLineAngle)
                        romHistory.add(PlankGeometry.calculatePosturalRom(hipLineAngle))
                    }
                }
            }

            PlankPhase.HOLD_END -> {
                // Hold is finalized
                isHoldEnded = true
            }
        }

        val mappedPhase = when (plankPhase) {
            PlankPhase.NOT_STARTED -> ExercisePhase.TOP
            PlankPhase.HOLD_START -> ExercisePhase.DESCENDING
            PlankPhase.HOLDING -> ExercisePhase.BOTTOM
            PlankPhase.HOLD_END -> ExercisePhase.TOP
        }

        currentState = ExerciseState(
            phase = mappedPhase,
            currentElbowAngle = 180.0f,
            currentHipLineAngle = hipLineAngle,
            completeReps = emptyList(),
            incompleteReps = emptyList(),
            isRepInProgress = (plankPhase == PlankPhase.HOLDING || plankPhase == PlankPhase.HOLD_START)
        )

        return currentState
    }

    /**
     * Builds real-time [FrameMetrics] for Plank.
     */
    fun getFrameMetrics(state: ExerciseState, isVisible: Boolean): FrameMetrics {
        return FrameMetrics(
            romPercent = averageRomPercent,
            tutFactor = tutFactor,
            confidence = if (isVisible) 1.0f else 0.0f,
            instantRomPercent = PlankGeometry.calculatePosturalRom(state.currentHipLineAngle),
            isVisibilitySufficient = isVisible
        )
    }
}
