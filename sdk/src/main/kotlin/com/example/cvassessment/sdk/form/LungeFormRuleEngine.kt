package com.example.cvassessment.sdk.form

import com.example.cvassessment.sdk.FeedbackEvent
import com.example.cvassessment.sdk.FormError
import com.example.cvassessment.sdk.metrics.RepMetrics
import com.example.cvassessment.sdk.pose.PoseEstimationResult
import com.example.cvassessment.sdk.pose.PoseLandmark
import com.example.cvassessment.sdk.statemachine.ExercisePhase
import com.example.cvassessment.sdk.statemachine.ExerciseState
import com.example.cvassessment.sdk.statemachine.LungeGeometry
import kotlin.math.abs

/**
 * Form Rule Engine for Lunge per FORM_RULES.md and EXERCISE_SPEC.md.
 *
 * Implements:
 * 1. insufficient_depth: severity 0.60, feedback: "Go lower."
 *    Triggers upon rep completion when romPercent < 60%.
 * 2. asymmetric_movement: severity 0.50, feedback: "Keep both sides even."
 *    Cross-repetition evaluation: during alternating lunges, compares depth (ROM) and tempo
 *    between left-leg-forward and right-leg-forward reps.
 *
 * Enforces Global Gating:
 * - Confidence >= 0.6
 * - Cooldown of 4000ms per errorName
 * - Highest-severity priority selection for audio feedback
 */
internal class LungeFormRuleEngine(
    val exerciseId: String = "lunge",
    val confidenceThreshold: Float = 0.6f,
    val cooldownMs: Long = 4000L,
    val asymmetricRomThreshold: Float = 20.0f,
    val asymmetricDurationThreshold: Float = 1.5f
) {
    private val lastFeedbackTimestamps = mutableMapOf<String, Long>()

    private val _allSessionErrors = mutableListOf<FormError>()
    val allSessionErrors: List<FormError> get() = _allSessionErrors.toList()

    private val _allFeedbackEvents = mutableListOf<FeedbackEvent>()
    val allFeedbackEvents: List<FeedbackEvent> get() = _allFeedbackEvents.toList()

    private var lastEvaluatedRepIndex: Int? = null
    private var lastLeftLegRepMetrics: RepMetrics? = null
    private var lastRightLegRepMetrics: RepMetrics? = null

    /**
     * Process full pose estimation result.
     */
    fun processFrame(
        exerciseState: ExerciseState,
        poseResult: PoseEstimationResult,
        completedRepMetrics: RepMetrics? = null,
        isVisibilitySufficient: Boolean = true,
        frontLeg: LungeGeometry.LegSide? = null
    ): FormRuleOutput {
        val detectedFrontLeg = frontLeg ?: LungeGeometry.identifyFrontLeg(poseResult.landmarks)

        return evaluateFrame(
            frontKneeAngle = exerciseState.currentElbowAngle,
            torsoAngle = exerciseState.currentHipLineAngle,
            phase = exerciseState.phase,
            isRepInProgress = exerciseState.isRepInProgress,
            currentRepIndex = if (exerciseState.isRepInProgress) exerciseState.completeRepCount + 1 else exerciseState.completeRepCount,
            timestampMs = poseResult.timestampMs,
            confidence = if (poseResult.landmarks.isNotEmpty()) poseResult.landmarks.map { it.visibility }.average().toFloat() else 1.0f,
            completedRepMetrics = completedRepMetrics,
            frontLeg = detectedFrontLeg,
            isVisibilitySufficient = isVisibilitySufficient
        )
    }

    /**
     * Direct frame evaluation with explicit parameters (ideal for unit testing).
     */
    fun evaluateFrame(
        frontKneeAngle: Float,
        torsoAngle: Float = 0.0f,
        phase: ExercisePhase = ExercisePhase.TOP,
        isRepInProgress: Boolean = false,
        currentRepIndex: Int? = null,
        timestampMs: Long,
        confidence: Float = 1.0f,
        completedRepMetrics: RepMetrics? = null,
        frontLeg: LungeGeometry.LegSide = LungeGeometry.LegSide.LEFT,
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

        // Rep-completion evaluations: insufficient_depth and cross-rep asymmetric_movement
        if (completedRepMetrics != null && completedRepMetrics.repIndex != lastEvaluatedRepIndex) {
            lastEvaluatedRepIndex = completedRepMetrics.repIndex

            // 1. insufficient_depth: romPercent < 60%
            if (completedRepMetrics.romPercent < 60.0f) {
                val depthError = FormError(
                    errorName = LungeFormRules.INSUFFICIENT_DEPTH.errorName,
                    confidence = completedRepMetrics.confidence,
                    repIndex = completedRepMetrics.repIndex,
                    severity = LungeFormRules.INSUFFICIENT_DEPTH.severity
                )
                activeErrorsThisFrame.add(depthError)
                _allSessionErrors.add(depthError)
            }

            // 2. asymmetric_movement: cross-rep comparison between alternating left vs right reps
            val oppositeMetrics = if (frontLeg == LungeGeometry.LegSide.LEFT) lastRightLegRepMetrics else lastLeftLegRepMetrics

            if (oppositeMetrics != null) {
                val romDiff = abs(completedRepMetrics.romPercent - oppositeMetrics.romPercent)
                val durationDiff = abs(completedRepMetrics.durationSec - oppositeMetrics.durationSec)

                if (romDiff >= asymmetricRomThreshold || durationDiff >= asymmetricDurationThreshold) {
                    val asymError = FormError(
                        errorName = LungeFormRules.ASYMMETRIC_MOVEMENT.errorName,
                        confidence = completedRepMetrics.confidence,
                        repIndex = completedRepMetrics.repIndex,
                        severity = LungeFormRules.ASYMMETRIC_MOVEMENT.severity
                    )
                    activeErrorsThisFrame.add(asymError)
                    _allSessionErrors.add(asymError)
                }
            }

            // Update tracked metrics per leg
            if (frontLeg == LungeGeometry.LegSide.LEFT) {
                lastLeftLegRepMetrics = completedRepMetrics
            } else {
                lastRightLegRepMetrics = completedRepMetrics
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
                val ruleDef = LungeFormRules.ALL_LUNGE_RULES.find { it.errorName == highestSeverityError.errorName }
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
        lastLeftLegRepMetrics = null
        lastRightLegRepMetrics = null
    }
}
