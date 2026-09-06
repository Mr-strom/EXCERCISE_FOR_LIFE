package com.example.cvassessment.sdk.form

import com.example.cvassessment.sdk.FeedbackEvent
import com.example.cvassessment.sdk.FormError
import com.example.cvassessment.sdk.metrics.RepMetrics
import com.example.cvassessment.sdk.pose.PoseEstimationResult
import com.example.cvassessment.sdk.statemachine.ExercisePhase
import com.example.cvassessment.sdk.statemachine.ExerciseState
import com.example.cvassessment.sdk.statemachine.MountainClimberGeometry
import kotlin.math.max

/**
 * Form rule engine for Mountain Climber exercise analysis per FORM_RULES.md.
 *
 * Implements:
 * 1. incomplete_leg_drive: knee_drive_angle doesn't reach target depth (ROM < 60%) upon rep completion.
 * 2. hips_dropping: hip_line_angle < 165° for >= 3 consecutive frames (concurrent check).
 * 3. hips_piking: hip_line_angle > 195° for >= 3 consecutive frames (concurrent check).
 * 4. Audio Feedback Gating:
 *    - Confidence >= 0.60
 *    - Cooldown: 4000ms per errorName
 *    - Highest-severity selection if multiple errors eligible
 * 5. Form Factor calculation with per-rep error deduplication.
 */
class MountainClimberFormRuleEngine(
    private val confidenceThreshold: Float = 0.60f,
    private val cooldownMs: Long = 4000L,
    private val persistenceThresholdFrames: Int = 3
) {
    private val _allSessionErrors = mutableListOf<FormError>()
    val allSessionErrors: List<FormError> get() = _allSessionErrors.toList()

    private val _allFeedbackEvents = mutableListOf<FeedbackEvent>()
    val allFeedbackEvents: List<FeedbackEvent> get() = _allFeedbackEvents.toList()

    private val lastFeedbackTimestamps = mutableMapOf<String, Long>()

    // Persistence counters
    private var hipsDroppingConsecutiveFrames: Int = 0
    private var hipsPikingConsecutiveFrames: Int = 0

    // Rep completion tracking
    private var lastEvaluatedRepIndex: Int = -1

    fun processFrame(
        exerciseState: ExerciseState,
        poseResult: PoseEstimationResult,
        completedRepMetrics: RepMetrics? = null,
        isVisibilitySufficient: Boolean = true
    ): FormRuleOutput {
        val confidence = if (poseResult.landmarks.isNotEmpty()) {
            poseResult.landmarks.map { it.visibility }.average().toFloat()
        } else {
            1.0f
        }
        return evaluate(
            poseResult = poseResult,
            exerciseState = exerciseState,
            confidence = confidence,
            completedRepMetrics = completedRepMetrics,
            isVisibilitySufficient = isVisibilitySufficient
        )
    }

    fun evaluate(
        poseResult: PoseEstimationResult,
        exerciseState: ExerciseState,
        confidence: Float = 1.0f,
        completedRepMetrics: RepMetrics? = null,
        isVisibilitySufficient: Boolean = true
    ): FormRuleOutput {
        val kneeAngle = MountainClimberGeometry.computeActiveKneeAngle(poseResult.landmarks)
        val hipLineAngle = MountainClimberGeometry.computeHipLineAngle(poseResult.landmarks)

        return evaluateFrame(
            kneeAngle = kneeAngle,
            hipLineAngle = hipLineAngle,
            phase = exerciseState.phase,
            isRepInProgress = exerciseState.isRepInProgress,
            currentRepIndex = if (exerciseState.isRepInProgress) exerciseState.completeRepCount + 1 else exerciseState.completeRepCount,
            timestampMs = poseResult.timestampMs,
            confidence = confidence,
            completedRepMetrics = completedRepMetrics,
            isVisibilitySufficient = isVisibilitySufficient
        )
    }

    fun evaluateFrame(
        kneeAngle: Float,
        hipLineAngle: Float,
        phase: ExercisePhase,
        isRepInProgress: Boolean,
        currentRepIndex: Int? = null,
        timestampMs: Long,
        confidence: Float = 1.0f,
        completedRepMetrics: RepMetrics? = null,
        isVisibilitySufficient: Boolean = true
    ): FormRuleOutput {
        if (!isVisibilitySufficient) {
            return FormRuleOutput(
                activeErrors = emptyList(),
                allSessionErrors = _allSessionErrors.toList(),
                newFeedbackEvents = emptyList(),
                allFeedbackEvents = _allFeedbackEvents.toList()
            )
        }

        val activeErrorsThisFrame = mutableListOf<FormError>()

        // 1. Rep-completion rule: incomplete_leg_drive (ROM < 60%)
        if (completedRepMetrics != null && completedRepMetrics.repIndex != lastEvaluatedRepIndex) {
            lastEvaluatedRepIndex = completedRepMetrics.repIndex

            if (completedRepMetrics.romPercent < 60.0f) {
                val driveError = FormError(
                    errorName = MountainClimberFormRules.INCOMPLETE_LEG_DRIVE.errorName,
                    confidence = completedRepMetrics.confidence,
                    repIndex = completedRepMetrics.repIndex,
                    severity = MountainClimberFormRules.INCOMPLETE_LEG_DRIVE.severity
                )
                activeErrorsThisFrame.add(driveError)
                _allSessionErrors.add(driveError)
            }
        }

        // 2. Concurrent hip stability checks (evaluated on each frame)
        // A. hips_dropping: hip_line_angle < 165°
        if (hipLineAngle < 165.0f) {
            hipsDroppingConsecutiveFrames++
            if (hipsDroppingConsecutiveFrames >= persistenceThresholdFrames) {
                val sagError = FormError(
                    errorName = MountainClimberFormRules.HIPS_DROPPING.errorName,
                    confidence = confidence,
                    repIndex = currentRepIndex,
                    severity = MountainClimberFormRules.HIPS_DROPPING.severity
                )
                activeErrorsThisFrame.add(sagError)
                _allSessionErrors.add(sagError)
            }
        } else {
            hipsDroppingConsecutiveFrames = 0
        }

        // B. hips_piking: hip_line_angle > 195°
        if (hipLineAngle > 195.0f) {
            hipsPikingConsecutiveFrames++
            if (hipsPikingConsecutiveFrames >= persistenceThresholdFrames) {
                val pikeError = FormError(
                    errorName = MountainClimberFormRules.HIPS_PIKING.errorName,
                    confidence = confidence,
                    repIndex = currentRepIndex,
                    severity = MountainClimberFormRules.HIPS_PIKING.severity
                )
                activeErrorsThisFrame.add(pikeError)
                _allSessionErrors.add(pikeError)
            }
        } else {
            hipsPikingConsecutiveFrames = 0
        }

        // 3. Audio Feedback Gating: Confidence >= 0.6, Cooldown 4000ms, Highest-Severity Selection
        val newFeedbackEvents = mutableListOf<FeedbackEvent>()
        if (confidence >= confidenceThreshold && activeErrorsThisFrame.isNotEmpty()) {
            val eligibleCandidates = activeErrorsThisFrame.filter { error ->
                val lastTime = lastFeedbackTimestamps[error.errorName] ?: -cooldownMs
                (timestampMs - lastTime) >= cooldownMs
            }

            val highestSeverityError = eligibleCandidates.maxByOrNull { it.severity }
            if (highestSeverityError != null) {
                val ruleDef = MountainClimberFormRules.ALL_MOUNTAIN_CLIMBER_RULES.find { it.errorName == highestSeverityError.errorName }
                val message = ruleDef?.feedbackMessage ?: "Control your form."

                val event = FeedbackEvent(
                    message = message,
                    timestampMs = timestampMs,
                    relatedError = highestSeverityError.errorName
                )
                newFeedbackEvents.add(event)
                _allFeedbackEvents.add(event)
                lastFeedbackTimestamps[highestSeverityError.errorName] = timestampMs
            }
        }

        return FormRuleOutput(
            activeErrors = activeErrorsThisFrame,
            allSessionErrors = _allSessionErrors.toList(),
            newFeedbackEvents = newFeedbackEvents,
            allFeedbackEvents = _allFeedbackEvents.toList()
        )
    }

    /**
     * Deduplicated Form Factor calculation per METRICS_SPEC.md §3:
     * formFactor = clamp(1.0 - sum(distinctErrorPenalty), 0.0, 1.0)
     */
    fun computeFormFactor(totalReps: Int): Float {
        if (_allSessionErrors.isEmpty()) return 1.0f

        val effectiveReps = max(1, totalReps)
        val deduplicatedErrors = _allSessionErrors.distinctBy { Pair(it.errorName, it.repIndex ?: -1) }

        var penaltySum = 0.0f
        for (err in deduplicatedErrors) {
            penaltySum += (err.severity * 0.5f) / effectiveReps.toFloat()
        }

        return (1.0f - penaltySum).coerceIn(0.0f, 1.0f)
    }

    fun reset() {
        _allSessionErrors.clear()
        _allFeedbackEvents.clear()
        lastFeedbackTimestamps.clear()
        hipsDroppingConsecutiveFrames = 0
        hipsPikingConsecutiveFrames = 0
        lastEvaluatedRepIndex = -1
    }
}
