package com.example.cvassessment.sdk.form

import com.example.cvassessment.sdk.FeedbackEvent
import com.example.cvassessment.sdk.FormError
import com.example.cvassessment.sdk.metrics.RepMetrics
import com.example.cvassessment.sdk.pose.PoseEstimationResult
import com.example.cvassessment.sdk.pose.PoseLandmark
import com.example.cvassessment.sdk.statemachine.ExercisePhase
import com.example.cvassessment.sdk.statemachine.ExerciseState
import com.example.cvassessment.sdk.statemachine.SquatGeometry

/**
 * Form Rule Engine for Squat per FORM_RULES.md.
 *
 * Implements:
 * 1. knee_valgus: severity 0.75, feedback: "Push your knees out."
 *    View-aware: strictly requires front-view camera angle. If side-view (profile)
 *    is detected, skips valgus detection to avoid false positives and logs the reason.
 * 2. insufficient_depth: severity 0.6, feedback: "Go lower." (when romPercent < 60%)
 * 3. excessive_lean: severity 0.5, feedback: "Keep your chest up." (when hipAngle < 65°)
 *
 * Enforces Global Gating Rules:
 * 1. Confidence threshold: confidence >= 0.6
 * 2. Persistence requirement: >= 3 consecutive frames for movement/posture errors
 * 3. Cooldown: 4000ms cooldown per errorName
 * 4. Priority selection: highest-severity candidate wins audio feedback
 * 5. Full session audit logging of all detected errors
 */
internal class SquatFormRuleEngine(
    val exerciseId: String = "squat",
    val confidenceThreshold: Float = 0.6f,
    val minPersistenceFrames: Int = 3,
    val cooldownMs: Long = 4000L
) {
    private val consecutiveFrames = mutableMapOf<String, Int>()
    private val lastFeedbackTimestamps = mutableMapOf<String, Long>()

    private val _allSessionErrors = mutableListOf<FormError>()
    val allSessionErrors: List<FormError> get() = _allSessionErrors.toList()

    private val _allFeedbackEvents = mutableListOf<FeedbackEvent>()
    val allFeedbackEvents: List<FeedbackEvent> get() = _allFeedbackEvents.toList()

    var lastSkipReason: String? = null
        private set

    /**
     * Evaluates a frame with explicit angles, landmarks, and rep metrics.
     */
    fun evaluateFrame(
        kneeAngle: Float,
        hipAngle: Float,
        phase: ExercisePhase,
        isRepInProgress: Boolean,
        currentRepIndex: Int?,
        timestampMs: Long,
        confidence: Float = 1.0f,
        completedRepMetrics: RepMetrics? = null,
        landmarks: List<PoseLandmark> = emptyList(),
        isSideViewOverride: Boolean? = null,
        isValgusOverride: Boolean? = null,
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
        // 1. Knee Valgus Check (View-Aware)
        // -------------------------------------------------------------
        val isSideView = isSideViewOverride ?: if (landmarks.isNotEmpty()) {
            SquatGeometry.isSideView(landmarks)
        } else {
            false
        }

        if (isSideView) {
            lastSkipReason = "Side-view detected (unreliable for frontal knee tracking)"
            // Log skip reason per requirement
            println("SquatFormRuleEngine: Skipping knee_valgus detection: Side-view detected (unreliable for frontal knee tracking)")
            consecutiveFrames[SquatFormRules.KNEE_VALGUS.errorName] = 0
        } else {
            lastSkipReason = null
            val isValgus = isValgusOverride ?: if (landmarks.isNotEmpty()) {
                val check = SquatGeometry.detectKneeValgus(landmarks, phase)
                check.isValgus
            } else {
                false
            }

            if (isValgus) {
                val count = (consecutiveFrames[SquatFormRules.KNEE_VALGUS.errorName] ?: 0) + 1
                consecutiveFrames[SquatFormRules.KNEE_VALGUS.errorName] = count
                activeErrorsThisFrame.add(
                    FormError(
                        errorName = SquatFormRules.KNEE_VALGUS.errorName,
                        confidence = confidence,
                        repIndex = currentRepIndex,
                        severity = SquatFormRules.KNEE_VALGUS.severity
                    )
                )
            } else {
                consecutiveFrames[SquatFormRules.KNEE_VALGUS.errorName] = 0
            }
        }

        // -------------------------------------------------------------
        // 2. Excessive Forward Lean (Optional stretch check)
        // -------------------------------------------------------------
        if ((phase == ExercisePhase.DESCENDING || phase == ExercisePhase.BOTTOM) && hipAngle < 65.0f) {
            val count = (consecutiveFrames[SquatFormRules.EXCESSIVE_LEAN.errorName] ?: 0) + 1
            consecutiveFrames[SquatFormRules.EXCESSIVE_LEAN.errorName] = count
            activeErrorsThisFrame.add(
                FormError(
                    errorName = SquatFormRules.EXCESSIVE_LEAN.errorName,
                    confidence = confidence,
                    repIndex = currentRepIndex,
                    severity = SquatFormRules.EXCESSIVE_LEAN.severity
                )
            )
        } else {
            consecutiveFrames[SquatFormRules.EXCESSIVE_LEAN.errorName] = 0
        }

        // -------------------------------------------------------------
        // 3. Insufficient Depth (Rep-completion check)
        // -------------------------------------------------------------
        if (completedRepMetrics != null) {
            if (completedRepMetrics.romPercent < 60.0f) {
                activeErrorsThisFrame.add(
                    FormError(
                        errorName = SquatFormRules.INSUFFICIENT_DEPTH.errorName,
                        confidence = confidence,
                        repIndex = completedRepMetrics.repIndex,
                        severity = SquatFormRules.INSUFFICIENT_DEPTH.severity
                    )
                )
            }
        }

        // Rule 5: Record all detected errors for session audit
        _allSessionErrors.addAll(activeErrorsThisFrame)

        // -------------------------------------------------------------
        // 4. Audio Feedback Gating Rules
        // -------------------------------------------------------------
        val candidateFeedback = mutableListOf<Triple<FormError, String, Float>>()

        for (error in activeErrorsThisFrame) {
            // Rule 1: Confidence threshold >= 0.6
            if (error.confidence < confidenceThreshold) {
                continue
            }

            // Rule 2: Persistence requirement (>= 3 consecutive frames for posture/movement errors)
            val isMovementError = error.errorName == SquatFormRules.KNEE_VALGUS.errorName ||
                                  error.errorName == SquatFormRules.EXCESSIVE_LEAN.errorName
            if (isMovementError) {
                val frames = consecutiveFrames[error.errorName] ?: 0
                if (frames < minPersistenceFrames) {
                    continue
                }
            }

            // Rule 3: Cooldown (4000ms per errorName)
            val lastTime = lastFeedbackTimestamps[error.errorName] ?: -cooldownMs
            if (timestampMs - lastTime < cooldownMs) {
                continue
            }

            val message = when (error.errorName) {
                SquatFormRules.KNEE_VALGUS.errorName -> SquatFormRules.KNEE_VALGUS.feedbackMessage
                SquatFormRules.INSUFFICIENT_DEPTH.errorName -> SquatFormRules.INSUFFICIENT_DEPTH.feedbackMessage
                SquatFormRules.EXCESSIVE_LEAN.errorName -> SquatFormRules.EXCESSIVE_LEAN.feedbackMessage
                else -> "Check your form."
            }

            candidateFeedback.add(Triple(error, message, error.severity))
        }

        // Rule 4: One feedback message at a time (highest-severity wins)
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
     * Process directly with pipeline outputs.
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
            kneeAngle = exerciseState.currentElbowAngle,
            hipAngle = exerciseState.currentHipLineAngle,
            phase = exerciseState.phase,
            isRepInProgress = exerciseState.isRepInProgress,
            currentRepIndex = currentRepIndex,
            timestampMs = poseResult.timestampMs,
            confidence = confidence,
            completedRepMetrics = completedRepMetrics,
            landmarks = poseResult.landmarks,
            isVisibilitySufficient = isVisibilitySufficient
        )
    }

    fun reset() {
        consecutiveFrames.clear()
        lastFeedbackTimestamps.clear()
        _allSessionErrors.clear()
        _allFeedbackEvents.clear()
        lastSkipReason = null
    }
}
