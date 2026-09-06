package com.example.cvassessment.sdk.form

import com.example.cvassessment.sdk.FeedbackEvent
import com.example.cvassessment.sdk.FormError
import com.example.cvassessment.sdk.metrics.RepMetrics
import com.example.cvassessment.sdk.pose.PoseEstimationResult
import com.example.cvassessment.sdk.statemachine.ExercisePhase
import com.example.cvassessment.sdk.statemachine.ExerciseState

/**
 * Form Rule Engine for Jumping Jack per FORM_RULES.md and EXERCISE_SPEC.md #9.
 *
 * Implements:
 * 1. asymmetric_jack: Severity 0.35, Feedback: "Sync your arms and legs."
 *    Triggered when arm and leg open phase transitions diverge by > 180ms.
 * 2. rushing_tempo: Severity 0.45, Feedback: "Slow down, control the movement."
 *    Triggered when tutFactor < 0.60 (< 0.72s duration for 1.2s baseline) for 2+ consecutive reps.
 * 3. insufficient_depth: Severity 0.60, Feedback: "Full range of motion."
 *    Triggered upon rep completion when combined romPercent < 60%.
 *
 * Enforces Global Feedback Gating:
 * - Confidence >= 0.6
 * - Cooldown of 4000ms per errorName
 * - Priority selection: highest-severity candidate emitted for audio feedback
 */
internal class JumpingJackFormRuleEngine(
    val exerciseId: String = "jumping_jack",
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
        asymmetryDivergenceMs: Long? = null,
        isVisibilitySufficient: Boolean = true
    ): FormRuleOutput {
        val confidence = if (poseResult.landmarks.isNotEmpty()) {
            poseResult.landmarks.map { it.visibility }.average().toFloat()
        } else {
            1.0f
        }

        return evaluateFrame(
            armAngle = exerciseState.currentElbowAngle,
            legAngle = exerciseState.currentHipLineAngle,
            phase = exerciseState.phase,
            isRepInProgress = exerciseState.isRepInProgress,
            currentRepIndex = if (exerciseState.isRepInProgress) exerciseState.completeRepCount + 1 else exerciseState.completeRepCount,
            timestampMs = poseResult.timestampMs,
            confidence = confidence,
            completedRepMetrics = completedRepMetrics,
            asymmetryDivergenceMs = asymmetryDivergenceMs,
            isVisibilitySufficient = isVisibilitySufficient
        )
    }

    fun evaluateFrame(
        armAngle: Float,
        legAngle: Float,
        phase: ExercisePhase,
        isRepInProgress: Boolean,
        currentRepIndex: Int? = null,
        timestampMs: Long,
        confidence: Float = 1.0f,
        completedRepMetrics: RepMetrics? = null,
        asymmetryDivergenceMs: Long? = null,
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

        // Rep-completion rule evaluations
        if (completedRepMetrics != null && completedRepMetrics.repIndex != lastEvaluatedRepIndex) {
            lastEvaluatedRepIndex = completedRepMetrics.repIndex

            // 1. insufficient_depth: combined romPercent < 60%
            if (completedRepMetrics.romPercent < 60.0f) {
                val depthError = FormError(
                    errorName = JumpingJackFormRules.INSUFFICIENT_DEPTH.errorName,
                    confidence = completedRepMetrics.confidence,
                    repIndex = completedRepMetrics.repIndex,
                    severity = JumpingJackFormRules.INSUFFICIENT_DEPTH.severity
                )
                activeErrorsThisFrame.add(depthError)
                _allSessionErrors.add(depthError)
            }

            // 2. asymmetric_jack: divergence between arm and leg open timestamps > 180ms
            if (asymmetryDivergenceMs != null && asymmetryDivergenceMs > 180L) {
                val asymError = FormError(
                    errorName = JumpingJackFormRules.ASYMMETRIC_JACK.errorName,
                    confidence = completedRepMetrics.confidence,
                    repIndex = completedRepMetrics.repIndex,
                    severity = JumpingJackFormRules.ASYMMETRIC_JACK.severity
                )
                activeErrorsThisFrame.add(asymError)
                _allSessionErrors.add(asymError)
            }

            // 3. rushing_tempo: tutFactor < 0.6 for 2+ consecutive reps
            if (completedRepMetrics.tutFactor < 0.60f) {
                consecutiveFastReps++
                if (consecutiveFastReps >= 2) {
                    val tempoError = FormError(
                        errorName = JumpingJackFormRules.RUSHING_TEMPO.errorName,
                        confidence = completedRepMetrics.confidence,
                        repIndex = completedRepMetrics.repIndex,
                        severity = JumpingJackFormRules.RUSHING_TEMPO.severity
                    )
                    activeErrorsThisFrame.add(tempoError)
                    _allSessionErrors.add(tempoError)
                }
            } else {
                consecutiveFastReps = 0
            }
        }

        // Feedback Gating: Confidence >= 0.6, Cooldown 4000ms, Highest-Severity Selection
        val newFeedbackEvents = mutableListOf<FeedbackEvent>()
        if (confidence >= confidenceThreshold && activeErrorsThisFrame.isNotEmpty()) {
            val eligibleCandidates = activeErrorsThisFrame.filter { error ->
                val lastTime = lastFeedbackTimestamps[error.errorName] ?: -cooldownMs
                (timestampMs - lastTime) >= cooldownMs
            }

            val highestSeverityError = eligibleCandidates.maxByOrNull { it.severity }
            if (highestSeverityError != null) {
                val ruleDef = JumpingJackFormRules.ALL_JUMPING_JACK_RULES.find { it.errorName == highestSeverityError.errorName }
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
     * Computes session-level Form Factor (0.0 - 1.0) with per-rep error deduplication.
     */
    fun computeFormFactor(): Float {
        if (_allSessionErrors.isEmpty()) return 1.0f
        val errorsByRep = _allSessionErrors.groupBy { it.repIndex ?: 0 }
        if (errorsByRep.isEmpty()) return 1.0f
        val repScores = errorsByRep.values.map { repErrors ->
            val totalDeduction = repErrors.distinctBy { it.errorName }.sumOf { it.severity.toDouble() }.toFloat()
            (1.0f - totalDeduction).coerceIn(0.0f, 1.0f)
        }
        return repScores.average().toFloat()
    }

    fun reset() {
        lastFeedbackTimestamps.clear()
        _allSessionErrors.clear()
        _allFeedbackEvents.clear()
        lastEvaluatedRepIndex = null
        consecutiveFastReps = 0
    }
}
