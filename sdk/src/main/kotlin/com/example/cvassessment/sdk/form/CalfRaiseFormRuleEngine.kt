package com.example.cvassessment.sdk.form

import com.example.cvassessment.sdk.FeedbackEvent
import com.example.cvassessment.sdk.FormError
import com.example.cvassessment.sdk.metrics.RepMetrics
import com.example.cvassessment.sdk.pose.PoseEstimationResult
import com.example.cvassessment.sdk.statemachine.ExercisePhase
import com.example.cvassessment.sdk.statemachine.ExerciseState

/**
 * Form Rule Engine for Calf Raise per FORM_RULES.md and EXERCISE_SPEC.md.
 *
 * Implements:
 * 1. insufficient_depth: severity 0.60, feedback: "Full range of motion."
 *    Triggers upon rep completion when romPercent < 60%.
 * 2. rushing_tempo: severity 0.45, feedback: "Slow down, control the movement."
 *    Triggers when tutFactor < 0.6 for 2+ consecutive reps.
 *
 * Enforces Global Feedback Gating:
 * - Confidence >= 0.6
 * - Cooldown of 4000ms per errorName
 * - Highest-severity priority selection for audio feedback
 */
internal class CalfRaiseFormRuleEngine(
    val exerciseId: String = "calf_raise",
    val confidenceThreshold: Float = 0.6f,
    val cooldownMs: Long = 4000L
) {
    private val lastFeedbackTimestamps = mutableMapOf<String, Long>()

    private val _allSessionErrors = mutableListOf<FormError>()
    val allSessionErrors: List<FormError> get() = _allSessionErrors.toList()

    private val _allFeedbackEvents = mutableListOf<FeedbackEvent>()
    val allFeedbackEvents: List<FeedbackEvent> get() = _allFeedbackEvents.toList()

    private var lastEvaluatedRepIndex: Int? = null
    private var consecutiveFastReps: Int = 0

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

        return evaluateFrame(
            elevation = exerciseState.currentElbowAngle,
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
        elevation: Float,
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

        // Evaluate per-rep completion rules
        if (completedRepMetrics != null && completedRepMetrics.repIndex != lastEvaluatedRepIndex) {
            lastEvaluatedRepIndex = completedRepMetrics.repIndex

            // 1. insufficient_depth: romPercent < 60%
            if (completedRepMetrics.romPercent < 60.0f) {
                val depthError = FormError(
                    errorName = CalfRaiseFormRules.INSUFFICIENT_DEPTH.errorName,
                    confidence = completedRepMetrics.confidence,
                    repIndex = completedRepMetrics.repIndex,
                    severity = CalfRaiseFormRules.INSUFFICIENT_DEPTH.severity
                )
                activeErrorsThisFrame.add(depthError)
                _allSessionErrors.add(depthError)
            }

            // 2. rushing_tempo: tutFactor < 0.6 for 2+ consecutive reps
            if (completedRepMetrics.tutFactor < 0.6f) {
                consecutiveFastReps++
                if (consecutiveFastReps >= 2) {
                    val tempoError = FormError(
                        errorName = CalfRaiseFormRules.RUSHING_TEMPO.errorName,
                        confidence = completedRepMetrics.confidence,
                        repIndex = completedRepMetrics.repIndex,
                        severity = CalfRaiseFormRules.RUSHING_TEMPO.severity
                    )
                    activeErrorsThisFrame.add(tempoError)
                    _allSessionErrors.add(tempoError)
                }
            } else {
                consecutiveFastReps = 0
            }
        }

        // Global Feedback Gating:
        val newFeedbackEvents = mutableListOf<FeedbackEvent>()
        if (confidence >= confidenceThreshold && activeErrorsThisFrame.isNotEmpty()) {
            val eligibleCandidates = activeErrorsThisFrame.filter { error ->
                val lastTime = lastFeedbackTimestamps[error.errorName] ?: -cooldownMs
                (timestampMs - lastTime) >= cooldownMs
            }

            val highestSeverityError = eligibleCandidates.maxByOrNull { it.severity }
            if (highestSeverityError != null) {
                val ruleDef = CalfRaiseFormRules.ALL_CALF_RAISE_RULES.find { it.errorName == highestSeverityError.errorName }
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

    fun reset() {
        lastFeedbackTimestamps.clear()
        _allSessionErrors.clear()
        _allFeedbackEvents.clear()
        lastEvaluatedRepIndex = null
        consecutiveFastReps = 0
    }
}
