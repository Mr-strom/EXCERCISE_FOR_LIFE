package com.example.cvassessment.sdk.statemachine

import com.example.cvassessment.sdk.metrics.FrameMetrics
import com.example.cvassessment.sdk.metrics.RepMetrics
import com.example.cvassessment.sdk.pose.PoseEstimationResult
import com.example.cvassessment.sdk.spec.ExerciseConfig

/**
 * Exercise State Machine for Calf Raise per EXERCISE_SPEC.md, DECISIONS.md D7, D12, and D13.
 *
 * Implements:
 * 1. Displacement-based ROM tracking (D7):
 *    - Resting heel Y-position captured during standing baseline calibration.
 *    - First rep peak elevation establishes session's `personalMaxReference` (ROM% = 100%).
 *    - Subsequent reps score proportional to `personalMaxReference`.
 *    - If a subsequent rep exceeds current reference, `personalMaxReference` updates upward
 *      (that rep gets 100% ROM), without retroactively changing past reps' recorded ROM%.
 * 2. Bottom -> Ascending -> Top -> Descending -> Bottom rep cycle based on rise threshold
 *    (e.g., 40% of personal_max_reference elevation) and return to baseline.
 * 3. Proactive D12 protections:
 *    - Top-hold dwell / settle protection (minimum 200ms) to accommodate hold pauses without jitter penalty.
 *    - Consistent hysteresis and guarded descent.
 * 4. Proactive D13 protection:
 *    - Mid-rep visibility drop discards active attempt without registering complete or incomplete rep.
 *    - `awaitingBaselineReturn` latch prevents recovery motions from generating phantom incomplete reps.
 */
internal class CalfRaiseStateMachine(
    val config: ExerciseConfig = ExerciseConfig.CALF_RAISE
) {
    var currentPhase: ExercisePhase = ExercisePhase.BOTTOM
        private set

    var currentState: ExerciseState = ExerciseState(
        phase = ExercisePhase.BOTTOM,
        currentElbowAngle = 0.0f, // Primary tracked metric: vertical displacement / elevation
        currentHipLineAngle = 0.0f
    )
        private set

    // Baseline calibration state
    var isCalibrated: Boolean = false
        private set
    var baselineHeelY: Float = 0.90f
        private set
    private val calibrationSamples = mutableListOf<Float>()
    private var calibrationStartTimestampMs: Long = 0L

    // Personal max reference elevation established during the session
    var personalMaxReference: Float? = null
        private set

    private val _completeReps = mutableListOf<RepBoundary>()
    val completeReps: List<RepBoundary> get() = _completeReps.toList()

    private val _incompleteReps = mutableListOf<IncompleteRep>()
    val incompleteReps: List<IncompleteRep> get() = _incompleteReps.toList()

    private val _allRepMetrics = mutableListOf<RepMetrics>()
    val allRepMetrics: List<RepMetrics> get() = _allRepMetrics.toList()

    val latestCompletedRepMetrics: RepMetrics?
        get() = _allRepMetrics.lastOrNull()

    var awaitingBaselineReturn: Boolean = false
        private set

    private var isRepInProgress: Boolean = false
    private var reachedTop: Boolean = false
    private var minHeelYThisRep: Float = 0.90f
    private var maxElevationThisRep: Float = 0.0f
    private var repStartTimestampMs: Long = 0L
    private var topTimestampMs: Long = 0L
    private var reversalCandidateTimestampMs: Long = 0L

    // Thresholds
    val defaultRiseThreshold: Float = 0.04f
    val nearBaselineThreshold: Float = 0.010f
    val motionStartThreshold: Float = 0.012f
    val reversalHysteresis: Float = 0.010f
    val minReversalSettleMs: Long = 200L

    var isDebugLoggingEnabled: Boolean = false
    var debugLogger: ((String) -> Unit)? = null

    private fun logDebug(msg: String) {
        if (!isDebugLoggingEnabled) return
        debugLogger?.invoke(msg)
        try {
            android.util.Log.d("CalfRaiseStateMachine", msg)
        } catch (_: Throwable) {
        }
    }

    /**
     * Explicitly sets standing baseline heel Y position.
     */
    fun calibrate(baselineY: Float) {
        baselineHeelY = baselineY
        minHeelYThisRep = baselineY
        isCalibrated = true
        calibrationSamples.clear()
        logDebug("[CALIBRATION] Explicit baseline calibrated at Y=${"%.4f".format(baselineY)}")
    }

    /**
     * Processes a camera frame with landmarks and visibility status.
     */
    fun processFrame(
        poseResult: PoseEstimationResult,
        isVisibilitySufficient: Boolean = true
    ): ExerciseState {
        val heelY = CalfRaiseGeometry.getHeelY(poseResult.landmarks) ?: baselineHeelY
        return processHeelY(heelY, poseResult.timestampMs, isVisibilitySufficient)
    }

    /**
     * Processes heel Y position with timestamp.
     */
    fun processHeelY(
        heelY: Float,
        timestampMs: Long,
        isVisibilitySufficient: Boolean = true
    ): ExerciseState {
        // Step 1: Initial standing baseline calibration
        if (!isCalibrated && isVisibilitySufficient) {
            if (calibrationSamples.isEmpty()) {
                calibrationStartTimestampMs = timestampMs
            }
            calibrationSamples.add(heelY)

            val durationMs = timestampMs - calibrationStartTimestampMs
            val minSample = calibrationSamples.minOrNull() ?: heelY
            val maxSample = calibrationSamples.maxOrNull() ?: heelY
            val sampleVariance = maxSample - minSample

            // Calibrate when stable over at least 5 samples and 500ms (or 10 samples)
            if (calibrationSamples.size >= 5 && (durationMs >= 500L || calibrationSamples.size >= 10)) {
                if (sampleVariance <= 0.025f) {
                    baselineHeelY = calibrationSamples.average().toFloat()
                    minHeelYThisRep = baselineHeelY
                    isCalibrated = true
                    logDebug("[CALIBRATION] Stable baseline captured at Y=${"%.4f".format(baselineHeelY)} from ${calibrationSamples.size} samples")
                    calibrationSamples.clear()
                } else if (calibrationSamples.size > 20) {
                    // Sliding window if variance was too large
                    calibrationSamples.removeAt(0)
                }
            }
        }

        val elevation = CalfRaiseGeometry.computeElevation(baselineHeelY, heelY)
        return processDisplacement(elevation, heelY, timestampMs, isVisibilitySufficient)
    }

    /**
     * Processes vertical elevation displacement directly.
     */
    fun processElevation(
        elevation: Float,
        timestampMs: Long,
        isVisibilitySufficient: Boolean = true
    ): ExerciseState {
        val heelY = baselineHeelY - elevation
        return processDisplacement(elevation, heelY, timestampMs, isVisibilitySufficient)
    }

    private fun processDisplacement(
        elevation: Float,
        heelY: Float,
        timestampMs: Long,
        isVisibilitySufficient: Boolean
    ): ExerciseState {
        logDebug("CALF_RAISE_FRAME: t=${timestampMs}ms | heelY=${"%.4f".format(heelY)} | elev=${"%.4f".format(elevation)} | phase=$currentPhase | inProgress=$isRepInProgress | vis=$isVisibilitySufficient")

        // Enforce D13 / Unit Test 7: Visibility gap mid-rep discards in-progress rep with re-arming guard
        if (!isVisibilitySufficient) {
            if (isRepInProgress) {
                logDebug("[VISIBILITY_DISCARD] Mid-rep attempt discarded at t=${timestampMs}ms | NOT counted as complete or incomplete")
                isRepInProgress = false
                reachedTop = false
                minHeelYThisRep = baselineHeelY
                maxElevationThisRep = 0.0f
                repStartTimestampMs = 0L
                topTimestampMs = 0L
                reversalCandidateTimestampMs = 0L
                // Guard: Require user to return to resting baseline before arming next rep
                awaitingBaselineReturn = true
            }
            currentPhase = ExercisePhase.BOTTOM
            val state = ExerciseState(
                phase = currentPhase,
                currentElbowAngle = elevation,
                currentHipLineAngle = 0.0f,
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

        val currentRiseThreshold = if (personalMaxReference != null) {
            maxOf(0.025f, 0.40f * personalMaxReference!!)
        } else {
            defaultRiseThreshold
        }

        val completeRep: (Long) -> Unit = { time ->
            val repIndex = _completeReps.size + 1
            val durationMs = time - repStartTimestampMs
            val rep = RepBoundary(
                repIndex = repIndex,
                startTimestampMs = repStartTimestampMs,
                bottomTimestampMs = topTimestampMs,
                endTimestampMs = time,
                durationMs = durationMs,
                minElbowAngle = maxElevationThisRep,
                isComplete = true
            )
            _completeReps.add(rep)
            newlyCompleted = rep

            // Update session personal max reference (D7 / Acceptance Criteria 2, 3, 4)
            val prevRef = personalMaxReference
            if (prevRef == null || maxElevationThisRep > prevRef) {
                personalMaxReference = maxElevationThisRep
                logDebug("[REFERENCE_UPDATE] Personal max reference updated to ${"%.4f".format(maxElevationThisRep)}")
            }

            // Compute relative ROM% based on the reference at this point in the session
            val repRom = CalfRaiseGeometry.computeRomPercent(maxElevationThisRep, personalMaxReference)
            val durationSec = durationMs / 1000.0f
            val tut = durationSec / config.tutBaseline

            val metrics = RepMetrics(
                repIndex = repIndex,
                romPercent = repRom,
                tutFactor = tut,
                confidence = 1.0f,
                durationSec = durationSec,
                minElbowAngle = maxElevationThisRep,
                startTimestampMs = repStartTimestampMs,
                endTimestampMs = time
            )
            _allRepMetrics.add(metrics)
            logDebug("[COMPLETE] Rep #$repIndex complete: elev=${"%.4f".format(maxElevationThisRep)}, ROM=${repRom}%, dur=${durationSec}s, TuT=${tut}x")

            isRepInProgress = false
            reachedTop = false
            currentPhase = ExercisePhase.BOTTOM
            reversalCandidateTimestampMs = 0L
        }

        val recordIncompleteRep: (Long, Float) -> Unit = { time, threshold ->
            val inc = IncompleteRep(
                attemptIndex = _incompleteReps.size + 1,
                startTimestampMs = repStartTimestampMs,
                reversalTimestampMs = time,
                minElbowAngleAchieved = maxElevationThisRep,
                reason = "Reversed before reaching rise threshold (achieved ${"%.4f".format(maxElevationThisRep)} vs >= ${"%.4f".format(threshold)})"
            )
            _incompleteReps.add(inc)
            newlyIncomplete = inc
            logDebug("[INCOMPLETE] Attempt #${inc.attemptIndex} incomplete: achieved=${inc.minElbowAngleAchieved}")

            isRepInProgress = false
            reachedTop = false
            currentPhase = ExercisePhase.BOTTOM
            reversalCandidateTimestampMs = 0L
        }

        when (currentPhase) {
            ExercisePhase.BOTTOM -> {
                isRepInProgress = false

                // D13 Guard: If recovering from visibility discard, wait until heel returns to baseline
                if (awaitingBaselineReturn) {
                    if (elevation <= nearBaselineThreshold) {
                        awaitingBaselineReturn = false
                        logDebug("[BASELINE_GUARD] Resting baseline restored (elev=${"%.4f".format(elevation)}). Re-armed.")
                    } else {
                        logDebug("[BASELINE_GUARD] Suppressing rep start during post-visibility recovery (elev=${"%.4f".format(elevation)})")
                    }
                } else if (elevation >= motionStartThreshold) {
                    // Heel starts lifting off baseline
                    isRepInProgress = true
                    repStartTimestampMs = timestampMs
                    minHeelYThisRep = heelY
                    maxElevationThisRep = elevation
                    reversalCandidateTimestampMs = 0L

                    if (elevation >= currentRiseThreshold) {
                        reachedTop = true
                        currentPhase = ExercisePhase.TOP
                        topTimestampMs = timestampMs
                        logDebug("[TRANSITION] BOTTOM -> TOP at t=${timestampMs}ms (elev=${"%.4f".format(elevation)} >= ${"%.4f".format(currentRiseThreshold)})")
                    } else {
                        reachedTop = false
                        currentPhase = ExercisePhase.ASCENDING
                        logDebug("[TRANSITION] BOTTOM -> ASCENDING at t=${timestampMs}ms (elev=${"%.4f".format(elevation)})")
                    }
                }
            }

            ExercisePhase.ASCENDING -> {
                if (elevation > maxElevationThisRep) {
                    maxElevationThisRep = elevation
                    minHeelYThisRep = heelY
                    reversalCandidateTimestampMs = 0L
                }

                // Check if user crossed the rise threshold
                if (elevation >= currentRiseThreshold) {
                    reachedTop = true
                    currentPhase = ExercisePhase.TOP
                    topTimestampMs = timestampMs
                    reversalCandidateTimestampMs = 0L
                    logDebug("[TRANSITION] ASCENDING -> TOP at t=${timestampMs}ms (elev=${"%.4f".format(elevation)} >= ${"%.4f".format(currentRiseThreshold)})")
                } else if (elevation <= nearBaselineThreshold) {
                    // Dropped directly back to baseline without reaching rise threshold
                    recordIncompleteRep(timestampMs, currentRiseThreshold)
                } else if (heelY >= (minHeelYThisRep + reversalHysteresis)) {
                    // Reversal detected before reaching rise threshold
                    currentPhase = ExercisePhase.DESCENDING
                    reversalCandidateTimestampMs = 0L
                    logDebug("[TRANSITION] ASCENDING -> DESCENDING (premature reversal before rise threshold)")
                }
            }

            ExercisePhase.TOP -> {
                if (elevation > maxElevationThisRep) {
                    maxElevationThisRep = elevation
                    minHeelYThisRep = heelY
                    topTimestampMs = timestampMs
                    reversalCandidateTimestampMs = 0L
                }

                // D12 Lesson: Top-hold settle time protection
                // Jitter during top hold is tolerated without dropping out of TOP prematurely
                val hasLowered = heelY >= (minHeelYThisRep + reversalHysteresis)

                if (hasLowered) {
                    if (elevation <= nearBaselineThreshold) {
                        // Directly returned to baseline in single leap
                        completeRep(timestampMs)
                    } else {
                        if (reversalCandidateTimestampMs == 0L) {
                            reversalCandidateTimestampMs = timestampMs
                        }
                        val settleDuration = timestampMs - reversalCandidateTimestampMs
                        val isDecisiveDescent = heelY >= (minHeelYThisRep + 0.020f) || elevation <= (currentRiseThreshold * 0.70f)

                        if (isDecisiveDescent || settleDuration >= minReversalSettleMs) {
                            currentPhase = ExercisePhase.DESCENDING
                            reversalCandidateTimestampMs = 0L
                            logDebug("[TRANSITION] TOP -> DESCENDING at t=${timestampMs}ms (settleDuration=${settleDuration}ms)")
                        }
                    }
                } else {
                    reversalCandidateTimestampMs = 0L
                }
            }

            ExercisePhase.DESCENDING -> {
                // User lowers heel back down to resting baseline
                if (elevation <= nearBaselineThreshold) {
                    if (reachedTop) {
                        completeRep(timestampMs)
                    } else {
                        recordIncompleteRep(timestampMs, currentRiseThreshold)
                    }
                }
            }
        }

        val state = ExerciseState(
            phase = currentPhase,
            currentElbowAngle = elevation,
            currentHipLineAngle = 0.0f,
            completeReps = _completeReps.toList(),
            incompleteReps = _incompleteReps.toList(),
            newlyCompletedRep = newlyCompleted,
            newlyDetectedIncompleteRep = newlyIncomplete,
            isRepInProgress = isRepInProgress,
            currentRepMinAngle = if (isRepInProgress) maxElevationThisRep else null
        )
        currentState = state
        return state
    }

    /**
     * Assembles FrameMetrics for Calf Raise honoring R7 refusal rules and displacement ROM.
     */
    fun getFrameMetrics(
        exerciseState: ExerciseState,
        poseResult: PoseEstimationResult,
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

        val heelY = CalfRaiseGeometry.getHeelY(poseResult.landmarks) ?: (baselineHeelY - exerciseState.currentElbowAngle)
        val currentElevation = CalfRaiseGeometry.computeElevation(baselineHeelY, heelY)
        val instantRom = if (personalMaxReference != null && personalMaxReference!! > 1e-4f) {
            CalfRaiseGeometry.computeRomPercent(currentElevation, personalMaxReference)
        } else {
            null
        }

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

    fun reset() {
        currentPhase = ExercisePhase.BOTTOM
        currentState = ExerciseState(
            phase = ExercisePhase.BOTTOM,
            currentElbowAngle = 0.0f,
            currentHipLineAngle = 0.0f
        )
        isCalibrated = false
        baselineHeelY = 0.90f
        calibrationSamples.clear()
        calibrationStartTimestampMs = 0L
        personalMaxReference = null
        _completeReps.clear()
        _incompleteReps.clear()
        _allRepMetrics.clear()
        isRepInProgress = false
        reachedTop = false
        minHeelYThisRep = 0.90f
        maxElevationThisRep = 0.0f
        repStartTimestampMs = 0L
        topTimestampMs = 0L
        reversalCandidateTimestampMs = 0L
        awaitingBaselineReturn = false
    }
}
