package com.example.cvassessment.sdk.form

import com.example.cvassessment.sdk.FeedbackEvent
import com.example.cvassessment.sdk.FormError
import com.example.cvassessment.sdk.metrics.RepMetrics
import com.example.cvassessment.sdk.pose.PoseEstimationResult
import com.example.cvassessment.sdk.statemachine.ExerciseState

/**
 * Module 6: Form Rule Engine for Push-Up per FORM_RULES.md.
 *
 * Implements detection for:
 * 1. hips_dropping (severity 0.7, message: "Keep your hips up.")
 * 2. hips_piking (severity 0.5, message: "Lower your hips slightly.")
 * 3. insufficient_depth (severity 0.6, message: "Go lower.")
 * 4. incomplete_lockout (severity 0.4, message: "Fully extend at the top.")
 *
 * Enforces NON-NEGOTIABLE Global Gating Rules:
 * 1. Confidence threshold: confidence >= 0.6 for audio feedback
 * 2. Persistence requirement: >= 3 consecutive frames before triggering
 * 3. Cooldown: 4000ms cooldown per errorName
 * 4. One feedback message at a time: highest-severity candidate selected; all errors logged
 */
class FormRuleEngine(
    val exerciseId: String = "push_up",
    val confidenceThreshold: Float = 0.6f,
    val minPersistenceFrames: Int = 3,
    val cooldownMs: Long = 4000L
) {
    // Consecutive frames tracking for frame-level posture errors
    private val consecutiveFrames = mutableMapOf<String, Int>()

    // Last audio feedback timestamp per errorName
    private val lastFeedbackTimestamps = mutableMapOf<String, Long>()

    // Session-level audit history
    private val _allSessionErrors = mutableListOf<FormError>()
    val allSessionErrors: List<FormError> get() = _allSessionErrors.toList()

    private val _allFeedbackEvents = mutableListOf<FeedbackEvent>()
    val allFeedbackEvents: List<FeedbackEvent> get() = _allFeedbackEvents.toList()

    /**
     * Evaluates form directly with explicit angle values and rep metadata.
     * Essential for unit testing and direct pipeline consumption.
     */
    fun evaluateFrame(
        elbowAngle: Float,
        hipLineAngle: Float,
        isRepInProgress: Boolean,
        currentRepIndex: Int?,
        timestampMs: Long,
        confidence: Float = 1.0f,
        completedRepMetrics: RepMetrics? = null,
        isVisibilitySufficient: Boolean = true
    ): FormRuleOutput {
        if (!isVisibilitySufficient) {
            consecutiveFrames.clear()
            return FormRuleOutput(
                activeErrors = emptyList(),
                allSessionErrors = _allSessionErrors.toList(),
                newFeedbackEvents = emptyList(),
                allFeedbackEvents = _allFeedbackEvents.toList()
            )
        }

        val activeErrorsThisFrame = mutableListOf<FormError>()

        // -------------------------------------------------------------
        // 1. Frame-based posture checks
        // -------------------------------------------------------------

        // hips_dropping: hip_line_angle < 165° (severity 0.7)
        if (hipLineAngle < 165.0f) {
            val count = (consecutiveFrames[PushUpFormRules.HIPS_DROPPING.errorName] ?: 0) + 1
            consecutiveFrames[PushUpFormRules.HIPS_DROPPING.errorName] = count
            activeErrorsThisFrame.add(
                FormError(
                    errorName = PushUpFormRules.HIPS_DROPPING.errorName,
                    confidence = confidence,
                    repIndex = currentRepIndex,
                    severity = PushUpFormRules.HIPS_DROPPING.severity
                )
            )
        } else {
            consecutiveFrames[PushUpFormRules.HIPS_DROPPING.errorName] = 0
        }

        // hips_piking: hip_line_angle > 195° (severity 0.5)
        if (hipLineAngle > 195.0f) {
            val count = (consecutiveFrames[PushUpFormRules.HIPS_PIKING.errorName] ?: 0) + 1
            consecutiveFrames[PushUpFormRules.HIPS_PIKING.errorName] = count
            activeErrorsThisFrame.add(
                FormError(
                    errorName = PushUpFormRules.HIPS_PIKING.errorName,
                    confidence = confidence,
                    repIndex = currentRepIndex,
                    severity = PushUpFormRules.HIPS_PIKING.severity
                )
            )
        } else {
            consecutiveFrames[PushUpFormRules.HIPS_PIKING.errorName] = 0
        }

        // -------------------------------------------------------------
        // 2. Rep-completion checks
        // -------------------------------------------------------------
        if (completedRepMetrics != null) {
            // insufficient_depth: romPercent < 60% (severity 0.6)
            if (completedRepMetrics.romPercent < 60.0f) {
                activeErrorsThisFrame.add(
                    FormError(
                        errorName = PushUpFormRules.INSUFFICIENT_DEPTH.errorName,
                        confidence = confidence,
                        repIndex = completedRepMetrics.repIndex,
                        severity = PushUpFormRules.INSUFFICIENT_DEPTH.severity
                    )
                )
            }

            // incomplete_lockout: top elbow angle < 155° (severity 0.4)
            if (elbowAngle < 155.0f) {
                activeErrorsThisFrame.add(
                    FormError(
                        errorName = PushUpFormRules.INCOMPLETE_LOCKOUT.errorName,
                        confidence = confidence,
                        repIndex = completedRepMetrics.repIndex,
                        severity = PushUpFormRules.INCOMPLETE_LOCKOUT.severity
                    )
                )
            }
        }

        // Rule 5: ALL detected errors are always recorded in session output (data logging is never throttled)
        _allSessionErrors.addAll(activeErrorsThisFrame)

        // -------------------------------------------------------------
        // 3. Audio Feedback Gating Rules
        // -------------------------------------------------------------
        val candidateFeedback = mutableListOf<Triple<FormError, String, Float>>()

        for (error in activeErrorsThisFrame) {
            // Global Gating Rule 1: Confidence threshold >= 0.6
            if (error.confidence < confidenceThreshold) {
                continue
            }

            // Global Gating Rule 2: Persistence requirement (>= 3 consecutive frames for posture errors)
            val isPostureError = error.errorName == PushUpFormRules.HIPS_DROPPING.errorName ||
                                 error.errorName == PushUpFormRules.HIPS_PIKING.errorName
            if (isPostureError) {
                val frames = consecutiveFrames[error.errorName] ?: 0
                if (frames < minPersistenceFrames) {
                    continue
                }
            }

            // Global Gating Rule 3: Cooldown (4000ms per errorName)
            val lastTime = lastFeedbackTimestamps[error.errorName] ?: -cooldownMs
            if (timestampMs - lastTime < cooldownMs) {
                continue
            }

            val message = when (error.errorName) {
                PushUpFormRules.HIPS_DROPPING.errorName -> PushUpFormRules.HIPS_DROPPING.feedbackMessage
                PushUpFormRules.HIPS_PIKING.errorName -> PushUpFormRules.HIPS_PIKING.feedbackMessage
                PushUpFormRules.INSUFFICIENT_DEPTH.errorName -> PushUpFormRules.INSUFFICIENT_DEPTH.feedbackMessage
                PushUpFormRules.INCOMPLETE_LOCKOUT.errorName -> PushUpFormRules.INCOMPLETE_LOCKOUT.feedbackMessage
                else -> "Check your form."
            }

            candidateFeedback.add(Triple(error, message, error.severity))
        }

        // Global Gating Rule 4: One feedback message at a time (highest-severity wins)
        val newEvents = mutableListOf<FeedbackEvent>()
        if (candidateFeedback.isNotEmpty()) {
            val winner = candidateFeedback.maxByOrNull { it.third }!!
            val event = FeedbackEvent(
                message = winner.second,
                timestampMs = timestampMs,
                relatedError = winner.first.errorName
            )
            lastFeedbackTimestamps[winner.first.errorName] = timestampMs
            _allFeedbackEvents.add(event)
            newEvents.add(event)
        }

        return FormRuleOutput(
            activeErrors = activeErrorsThisFrame.toList(),
            allSessionErrors = _allSessionErrors.toList(),
            newFeedbackEvents = newEvents,
            allFeedbackEvents = _allFeedbackEvents.toList()
        )
    }

    /**
     * Process directly with Module 4 and Module 2 outputs.
     */
    fun processFrame(
        exerciseState: ExerciseState,
        poseResult: PoseEstimationResult,
        completedRepMetrics: RepMetrics? = null,
        isVisibilitySufficient: Boolean = true
    ): FormRuleOutput {
        val currentRepIndex = if (exerciseState.isRepInProgress) {
            exerciseState.completeRepCount + 1
        } else {
            exerciseState.completeReps.lastOrNull()?.repIndex
        }

        val confidence = poseResult.getAverageVisibility()

        return evaluateFrame(
            elbowAngle = exerciseState.currentElbowAngle,
            hipLineAngle = exerciseState.currentHipLineAngle,
            isRepInProgress = exerciseState.isRepInProgress,
            currentRepIndex = currentRepIndex,
            timestampMs = poseResult.timestampMs,
            confidence = confidence,
            completedRepMetrics = completedRepMetrics,
            isVisibilitySufficient = isVisibilitySufficient
        )
    }

    /**
     * Reset all internal frame counters, cooldown timers, and session histories.
     */
    fun reset() {
        consecutiveFrames.clear()
        lastFeedbackTimestamps.clear()
        _allSessionErrors.clear()
        _allFeedbackEvents.clear()
    }
}
