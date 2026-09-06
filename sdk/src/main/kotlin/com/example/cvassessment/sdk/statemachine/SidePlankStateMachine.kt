package com.example.cvassessment.sdk.statemachine

import com.example.cvassessment.sdk.metrics.FrameMetrics
import com.example.cvassessment.sdk.pose.PoseEstimationResult
import com.example.cvassessment.sdk.spec.ExerciseConfig
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Movement phases for static hold exercise (Side Plank) per EXERCISE_SPEC.md #8 and METRICS_SPEC.md §2.
 */
enum class SidePlankPhase {
    NOT_STARTED,
    HOLD_START,
    HOLDING,
    HOLD_END
}

/**
 * Exercise State Machine for Side Plank per EXERCISE_SPEC.md #8, METRICS_SPEC.md §2, §4, §5, and FORM_RULES.md.
 *
 * Implements:
 * 1. Support side determination:
 *    - Detects whether athlete is on left or right elbow during setup.
 *    - Locks support side at HOLD_START for the duration of the hold.
 * 2. Hold timer management (METRICS_SPEC.md §2):
 *    - Starts accumulating when body_line_angle enters tolerance (180° ± 15°, i.e. [165°, 195°]).
 *    - Pauses (does NOT reset to zero) during visibility gaps.
 *    - Resumes cleanly when visibility returns.
 * 3. Grace period / Settle protection:
 *    - Brief wobbles outside [165°, 195°] lasting shorter than gracePeriodMs (1000ms / 6 frames)
 *      trigger a postural_break event but do NOT terminate the hold.
 *    - Sustained deviations exceeding gracePeriodMs trigger HOLD_END, finalizing the hold timer.
 * 4. Postural deviation Range of Motion (ROM%) tracking:
 *    - Time-averaged across the valid hold per METRICS_SPEC.md §4.
 * 5. Time under Tension (TuT) Factor:
 *    - Computed as consistency of body_line_angle over the hold (METRICS_SPEC.md §5).
 */
internal class SidePlankStateMachine(
    val config: ExerciseConfig = ExerciseConfig.SIDE_PLANK,
    val toleranceDeg: Float = 15.0f,
    val gracePeriodMs: Long = 1000L,
    val minDeviationFramesToFail: Int = 6
) {
    var sidePlankPhase: SidePlankPhase = SidePlankPhase.NOT_STARTED
        private set

    var supportSide: SidePlankSupportSide = SidePlankSupportSide.UNKNOWN
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
        get() = SidePlankGeometry.calculateTutFactor(angleHistory, maxAllowedStddev = toleranceDeg)

    var isDebugLoggingEnabled: Boolean = false
    var debugLogger: ((String) -> Unit)? = null

    private fun logDebug(msg: String) {
        if (!isDebugLoggingEnabled) return
        debugLogger?.invoke(msg)
        try {
            android.util.Log.d("SidePlankStateMachine", msg)
        } catch (_: Throwable) {
        }
    }

    /**
     * Resets state machine to initial unstarted state.
     */
    fun reset() {
        sidePlankPhase = SidePlankPhase.NOT_STARTED
        supportSide = SidePlankSupportSide.UNKNOWN
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
     * Explicitly sets or locks the support side (useful for testing).
     */
    fun setSupportSideExplicit(side: SidePlankSupportSide) {
        supportSide = side
    }

    /**
     * Processes a single camera frame with pose estimation landmarks.
     */
    fun processFrame(poseResult: PoseEstimationResult, isVisibilitySufficient: Boolean): ExerciseState {
        if (sidePlankPhase == SidePlankPhase.NOT_STARTED && poseResult.hasPose && poseResult.landmarks.isNotEmpty()) {
            val detectedSide = SidePlankGeometry.detectSupportSide(poseResult.landmarks)
            if (detectedSide != SidePlankSupportSide.UNKNOWN) {
                supportSide = detectedSide
            }
        }

        val bodyLineAngle = if (poseResult.hasPose && poseResult.landmarks.isNotEmpty()) {
            SidePlankGeometry.computeBodyLineAngle(poseResult.landmarks, supportSide)
        } else {
            currentState.currentHipLineAngle
        }

        return processAngle(bodyLineAngle, poseResult.timestampMs, isVisibilitySufficient)
    }

    /**
     * Processes a synthetic or pre-computed angle with timestamp.
     */
    fun processAngle(
        bodyLineAngle: Float,
        timestampMs: Long,
        isVisibilitySufficient: Boolean,
        detectedSide: SidePlankSupportSide = SidePlankSupportSide.UNKNOWN
    ): ExerciseState {
        lastWobbleDetected = false

        if (sidePlankPhase == SidePlankPhase.NOT_STARTED && detectedSide != SidePlankSupportSide.UNKNOWN) {
            supportSide = detectedSide
        }

        // 1. Check visibility gap handling: PAUSE during tracking loss, do NOT reset to zero
        if (!isVisibilitySufficient) {
            if (sidePlankPhase == SidePlankPhase.HOLDING || sidePlankPhase == SidePlankPhase.HOLD_START) {
                isPaused = true
                logDebug("Frame $timestampMs: Visibility dropped during hold. Pausing hold timer (accumulated: ${holdDurationSec}s)")
            }
            // While visibility is insufficient, freeze timer
            lastActiveTimestampMs = 0L
            return currentState.copy(
                currentHipLineAngle = bodyLineAngle
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
                currentHipLineAngle = bodyLineAngle
            )
        }

        val isInTolerance = abs(180.0f - bodyLineAngle) <= toleranceDeg

        when (sidePlankPhase) {
            SidePlankPhase.NOT_STARTED -> {
                if (isInTolerance) {
                    sidePlankPhase = SidePlankPhase.HOLD_START
                    lastActiveTimestampMs = timestampMs
                    angleHistory.add(bodyLineAngle)
                    romHistory.add(SidePlankGeometry.calculatePosturalRom(bodyLineAngle))
                    logDebug("Frame $timestampMs: Hold start detected at angle $bodyLineAngle° (supportSide: $supportSide)")
                }
            }

            SidePlankPhase.HOLD_START -> {
                sidePlankPhase = SidePlankPhase.HOLDING
                if (lastActiveTimestampMs > 0L && timestampMs > lastActiveTimestampMs) {
                    accumulatedHoldMs += (timestampMs - lastActiveTimestampMs)
                }
                lastActiveTimestampMs = timestampMs
                angleHistory.add(bodyLineAngle)
                romHistory.add(SidePlankGeometry.calculatePosturalRom(bodyLineAngle))
            }

            SidePlankPhase.HOLDING -> {
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
                    angleHistory.add(bodyLineAngle)
                    romHistory.add(SidePlankGeometry.calculatePosturalRom(bodyLineAngle))
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
                        sidePlankPhase = SidePlankPhase.HOLD_END
                        isHoldEnded = true
                        logDebug("Frame $timestampMs: Hold ended! Deviation ($bodyLineAngle°) sustained for ${deviationDuration}ms ($deviationFrameCount frames). Final hold: ${holdDurationSec}s")
                    } else {
                        // Within grace period wobble: hold is still active, accumulate time
                        if (lastActiveTimestampMs > 0L && timestampMs > lastActiveTimestampMs) {
                            accumulatedHoldMs += (timestampMs - lastActiveTimestampMs)
                        }
                        lastActiveTimestampMs = timestampMs
                        angleHistory.add(bodyLineAngle)
                        romHistory.add(SidePlankGeometry.calculatePosturalRom(bodyLineAngle))
                    }
                }
            }

            SidePlankPhase.HOLD_END -> {
                isHoldEnded = true
            }
        }

        val mappedPhase = when (sidePlankPhase) {
            SidePlankPhase.NOT_STARTED -> ExercisePhase.TOP
            SidePlankPhase.HOLD_START -> ExercisePhase.DESCENDING
            SidePlankPhase.HOLDING -> ExercisePhase.BOTTOM
            SidePlankPhase.HOLD_END -> ExercisePhase.TOP
        }

        currentState = ExerciseState(
            phase = mappedPhase,
            currentElbowAngle = 180.0f,
            currentHipLineAngle = bodyLineAngle,
            completeReps = emptyList(),
            incompleteReps = emptyList(),
            isRepInProgress = (sidePlankPhase == SidePlankPhase.HOLDING || sidePlankPhase == SidePlankPhase.HOLD_START)
        )

        return currentState
    }

    /**
     * Builds real-time [FrameMetrics] for Side Plank.
     */
    fun getFrameMetrics(state: ExerciseState, isVisible: Boolean): FrameMetrics {
        return FrameMetrics(
            romPercent = averageRomPercent,
            tutFactor = tutFactor,
            confidence = if (isVisible) 1.0f else 0.0f,
            instantRomPercent = SidePlankGeometry.calculatePosturalRom(state.currentHipLineAngle),
            isVisibilitySufficient = isVisible
        )
    }
}
