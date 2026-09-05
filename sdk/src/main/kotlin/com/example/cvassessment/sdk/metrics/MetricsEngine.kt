package com.example.cvassessment.sdk.metrics

import com.example.cvassessment.sdk.pose.PoseEstimationResult
import com.example.cvassessment.sdk.pose.PoseLandmark
import com.example.cvassessment.sdk.spec.ExerciseConfig
import com.example.cvassessment.sdk.statemachine.ExerciseState
import com.example.cvassessment.sdk.statemachine.RepBoundary

/**
 * Module 5: Metrics Engine.
 *
 * Implements exact math for:
 * 1. ROM% (METRICS_SPEC.md §4)
 * 2. TuT Factor (METRICS_SPEC.md §5)
 * 3. Confidence Score (METRICS_SPEC.md §7: 40% visibility + 40% pattern match + 20% trajectory smoothness)
 *
 * CRITICAL RULE (R7): When visibility is insufficient, metrics are NEVER forced to a number;
 * they must be strictly null.
 */
internal class MetricsEngine(
    val config: ExerciseConfig = ExerciseConfig.PUSH_UP
) {
    private val repMetricsHistory = mutableListOf<RepMetrics>()
    var latestRepMetrics: RepMetrics? = null
        private set

    val allRepMetrics: List<RepMetrics> get() = repMetricsHistory.toList()
    val latestCompletedRepMetrics: RepMetrics? get() = latestRepMetrics

    // Accumulator for the currently active rep attempt
    private val activeRepAngles = mutableListOf<Float>()
    private val activeRepVisibilities = mutableListOf<Float>()

    /**
     * Compute Range of Motion percentage per METRICS_SPEC.md §4:
     *
     * romPercent = clamp(
     *   (actual_extremum_angle - starting_angle) / (target_extremum_angle - starting_angle) * 100,
     *   0, 100
     * )
     *
     * For Push-Up: starting_angle = 160° (lockout), target = 90° (bottom).
     */
    fun calculateRomPercent(
        actualExtremumAngle: Float,
        startingAngle: Float = config.romDefinition.startingAngle,
        targetExtremumAngle: Float = config.romDefinition.fullExpectedAngle
    ): Float {
        val denominator = targetExtremumAngle - startingAngle
        if (Math.abs(denominator) < 1e-4f) return 0.0f
        val rawPercent = ((actualExtremumAngle - startingAngle) / denominator) * 100.0f
        return rawPercent.coerceIn(0.0f, 100.0f)
    }

    /**
     * Compute Time Under Tension Factor per METRICS_SPEC.md §5:
     *
     * tutFactor = actual_rep_duration_sec / tutBaseline_sec
     *
     * For Push-Up: tutBaseline = 4.0s (from EXERCISE_SPEC.md).
     */
    fun calculateTutFactor(
        actualRepDurationSec: Float,
        tutBaselineSec: Float = config.tutBaseline
    ): Float {
        if (tutBaselineSec <= 0.0f) return 1.0f
        return actualRepDurationSec / tutBaselineSec
    }

    /**
     * Compute Confidence Score per METRICS_SPEC.md §7:
     *
     * confidence = (
     *   0.40 * landmark_visibility_confidence +
     *   0.40 * phase_pattern_match_confidence +
     *   0.20 * angle_trajectory_smoothness
     * )
     */
    fun calculateConfidence(
        landmarkVisibility: Float,
        phasePatternMatch: Float = 1.0f,
        trajectorySmoothness: Float = 1.0f
    ): Float {
        val score = (0.40f * landmarkVisibility.coerceIn(0.0f, 1.0f)) +
                    (0.40f * phasePatternMatch.coerceIn(0.0f, 1.0f)) +
                    (0.20f * trajectorySmoothness.coerceIn(0.0f, 1.0f))
        return score.coerceIn(0.0f, 1.0f)
    }

    /**
     * Quantifies trajectory smoothness by penalizing erratic angle jerk.
     * Smooth human biomechanics yield values near 1.0; tracking artifacts and jumps drop toward 0.0.
     */
    fun calculateTrajectorySmoothness(angles: List<Float>): Float {
        if (angles.size < 3) return 1.0f

        var totalJerk = 0.0f
        var count = 0
        for (i in 1 until angles.size - 1) {
            val secondDiff = Math.abs(angles[i + 1] - (2 * angles[i]) + angles[i - 1])
            totalJerk += secondDiff
            count++
        }

        if (count == 0) return 1.0f
        val avgJerk = totalJerk / count
        val smoothness = 1.0f - (avgJerk / 25.0f)
        return smoothness.coerceIn(0.0f, 1.0f)
    }

    /**
     * Computes the average visibility across the exercise's required landmarks.
     */
    fun calculateLandmarkVisibility(landmarks: List<PoseLandmark>): Float {
        if (landmarks.isEmpty()) return 0.0f
        val required = config.requiredLandmarkIndices
        val relevantLandmarks = if (required.isNotEmpty()) {
            landmarks.filter { it.index in required }
        } else {
            landmarks
        }
        if (relevantLandmarks.isEmpty()) return 0.0f
        return relevantLandmarks.map { it.visibility }.average().toFloat()
    }

    /**
     * Computes [RepMetrics] for a completed repetition.
     *
     * CRITICAL RULE: If [isVisibilitySufficient] is false, this method returns null!
     */
    fun computeRepMetrics(
        rep: RepBoundary,
        landmarkVisibility: Float = 1.0f,
        angleTrajectory: List<Float> = emptyList(),
        phasePatternMatch: Float = 1.0f,
        isVisibilitySufficient: Boolean = true
    ): RepMetrics? {
        if (!isVisibilitySufficient) {
            return null
        }

        val rom = calculateRomPercent(rep.minElbowAngle)
        val durationSec = rep.durationMs / 1000.0f
        val tut = calculateTutFactor(durationSec)
        val smoothness = calculateTrajectorySmoothness(angleTrajectory)
        val conf = calculateConfidence(landmarkVisibility, phasePatternMatch, smoothness)

        return RepMetrics(
            repIndex = rep.repIndex,
            romPercent = rom,
            tutFactor = tut,
            confidence = conf,
            durationSec = durationSec,
            minElbowAngle = rep.minElbowAngle,
            startTimestampMs = rep.startTimestampMs,
            endTimestampMs = rep.endTimestampMs,
            landmarkVisibilityConfidence = landmarkVisibility,
            phasePatternMatchConfidence = phasePatternMatch,
            trajectorySmoothnessConfidence = smoothness
        )
    }

    /**
     * Processes per-frame outputs from the ExerciseStateMachine and PoseEstimator.
     *
     * Returns a [FrameMetrics] object.
     * If [isVisibilitySufficient] is false, [FrameMetrics.romPercent] and [FrameMetrics.tutFactor]
     * are strictly null.
     */
    fun processFrame(
        exerciseState: ExerciseState,
        poseResult: PoseEstimationResult,
        isVisibilitySufficient: Boolean = true
    ): FrameMetrics {
        // Enforce R7 refusal rule
        if (!isVisibilitySufficient) {
            activeRepAngles.clear()
            activeRepVisibilities.clear()
            return FrameMetrics(
                romPercent = null,
                tutFactor = null,
                confidence = 0.0f,
                instantRomPercent = null,
                latestCompletedRepMetrics = null,
                allRepMetrics = repMetricsHistory.toList(),
                isVisibilitySufficient = false
            )
        }

        val frameVisibility = calculateLandmarkVisibility(poseResult.landmarks)

        // Buffer active rep angle and visibility trajectory
        if (exerciseState.isRepInProgress) {
            activeRepAngles.add(exerciseState.currentElbowAngle)
            activeRepVisibilities.add(frameVisibility)
        }

        // Finalize completed rep metrics
        if (exerciseState.newlyCompletedRep != null) {
            val rep = exerciseState.newlyCompletedRep
            val avgVis = if (activeRepVisibilities.isNotEmpty()) {
                activeRepVisibilities.average().toFloat()
            } else {
                frameVisibility
            }
            val metrics = computeRepMetrics(
                rep = rep,
                landmarkVisibility = avgVis,
                angleTrajectory = activeRepAngles.toList(),
                phasePatternMatch = 1.0f,
                isVisibilitySufficient = true
            )
            if (metrics != null) {
                repMetricsHistory.add(metrics)
                latestRepMetrics = metrics
            }
            activeRepAngles.clear()
            activeRepVisibilities.clear()
        }

        val instantRom = calculateRomPercent(exerciseState.currentElbowAngle)
        val overallConfidence = latestRepMetrics?.confidence ?: calculateConfidence(frameVisibility)

        return FrameMetrics(
            romPercent = latestRepMetrics?.romPercent,
            tutFactor = latestRepMetrics?.tutFactor,
            confidence = overallConfidence,
            instantRomPercent = instantRom,
            latestCompletedRepMetrics = latestRepMetrics,
            allRepMetrics = repMetricsHistory.toList(),
            isVisibilitySufficient = true
        )
    }

    /**
     * Resets internal metrics history and buffers.
     */
    fun reset() {
        repMetricsHistory.clear()
        latestRepMetrics = null
        activeRepAngles.clear()
        activeRepVisibilities.clear()
    }
}
