package com.example.cvassessment.sdk.form

import com.example.cvassessment.sdk.FeedbackEvent
import com.example.cvassessment.sdk.FormError
import com.example.cvassessment.sdk.pose.PoseEstimationResult
import com.example.cvassessment.sdk.statemachine.ExerciseState
import com.example.cvassessment.sdk.statemachine.SidePlankGeometry
import com.example.cvassessment.sdk.statemachine.SidePlankSupportSide
import kotlin.math.abs

/**
 * Form Rule Engine for Side Plank per FORM_RULES.md, EXERCISE_SPEC.md #8, and METRICS_SPEC.md §6.
 *
 * Implements:
 * 1. postural_break: instantaneous wobble outside [165°, 195°] that recovers before hold_end (severity 0.6).
 * 2. hips_dropping: body_line_angle < 165° sustained >= 3 consecutive frames (severity 0.7).
 * 3. hips_piking: body_line_angle > 195° sustained >= 3 consecutive frames (severity 0.5).
 * 4. Throttled audio feedback gating (4000ms cooldown, >=0.6 confidence, highest severity first).
 * 5. Static hold Form Factor calculation per METRICS_SPEC.md §6 (deduplicated across hold).
 */
internal class SidePlankFormRuleEngine(
    val confidenceThreshold: Float = 0.6f,
    val persistenceFrames: Int = 3,
    val cooldownMs: Long = 4000L
) {
    private val consecutiveFrames = mutableMapOf<String, Int>()
    private val lastFeedbackTimestamps = mutableMapOf<String, Long>()

    private val _allSessionErrors = mutableListOf<FormError>()
    val allSessionErrors: List<FormError> get() = _allSessionErrors.toList()

    private val _allFeedbackEvents = mutableListOf<FeedbackEvent>()
    val allFeedbackEvents: List<FeedbackEvent> get() = _allFeedbackEvents.toList()

    fun reset() {
        consecutiveFrames.clear()
        lastFeedbackTimestamps.clear()
        _allSessionErrors.clear()
        _allFeedbackEvents.clear()
    }

    /**
     * Evaluates a camera frame with pose estimation landmarks.
     */
    fun processFrame(
        exerciseState: ExerciseState,
        poseResult: PoseEstimationResult,
        isVisibilitySufficient: Boolean,
        supportSide: SidePlankSupportSide = SidePlankSupportSide.UNKNOWN,
        isWobbleRecovered: Boolean = false
    ): FormRuleOutput {
        if (!isVisibilitySufficient || !poseResult.hasPose || poseResult.landmarks.isEmpty()) {
            return FormRuleOutput()
        }

        val bodyLineAngle = SidePlankGeometry.computeBodyLineAngle(poseResult.landmarks, supportSide)
        val confidence = poseResult.landmarks.map { it.visibility }.average().toFloat()

        return evaluateFrame(
            bodyLineAngle = bodyLineAngle,
            isHoldInProgress = exerciseState.isRepInProgress,
            timestampMs = poseResult.timestampMs,
            confidence = confidence,
            isVisibilitySufficient = isVisibilitySufficient,
            isWobbleRecovered = isWobbleRecovered
        )
    }

    /**
     * Core evaluation method accepting pre-computed angles and confidence.
     */
    fun evaluateFrame(
        bodyLineAngle: Float,
        isHoldInProgress: Boolean,
        timestampMs: Long,
        confidence: Float = 1.0f,
        isVisibilitySufficient: Boolean = true,
        isWobbleRecovered: Boolean = false
    ): FormRuleOutput {
        // Critical R7: Refuse evaluation if visibility is insufficient
        if (!isVisibilitySufficient) {
            return FormRuleOutput()
        }

        val activeErrorsThisFrame = mutableListOf<FormError>()

        // 1. hips_dropping: body_line_angle < 165° (severity 0.7)
        if (bodyLineAngle < 165.0f) {
            val count = (consecutiveFrames[SidePlankFormRules.HIPS_DROPPING.errorName] ?: 0) + 1
            consecutiveFrames[SidePlankFormRules.HIPS_DROPPING.errorName] = count
            val err = FormError(
                errorName = SidePlankFormRules.HIPS_DROPPING.errorName,
                confidence = confidence,
                repIndex = null,
                severity = SidePlankFormRules.HIPS_DROPPING.severity
            )
            activeErrorsThisFrame.add(err)
            _allSessionErrors.add(err)
        } else {
            consecutiveFrames[SidePlankFormRules.HIPS_DROPPING.errorName] = 0
        }

        // 2. hips_piking: body_line_angle > 195° (severity 0.5)
        if (bodyLineAngle > 195.0f) {
            val count = (consecutiveFrames[SidePlankFormRules.HIPS_PIKING.errorName] ?: 0) + 1
            consecutiveFrames[SidePlankFormRules.HIPS_PIKING.errorName] = count
            val err = FormError(
                errorName = SidePlankFormRules.HIPS_PIKING.errorName,
                confidence = confidence,
                repIndex = null,
                severity = SidePlankFormRules.HIPS_PIKING.severity
            )
            activeErrorsThisFrame.add(err)
            _allSessionErrors.add(err)
        } else {
            consecutiveFrames[SidePlankFormRules.HIPS_PIKING.errorName] = 0
        }

        // 3. postural_break: instantaneous wobble outside [165°, 195°] that recovered or occurs during hold
        val isDeviating = abs(180.0f - bodyLineAngle) > 15.0f
        if (isWobbleRecovered || (isHoldInProgress && isDeviating)) {
            val count = (consecutiveFrames[SidePlankFormRules.POSTURAL_BREAK.errorName] ?: 0) + 1
            consecutiveFrames[SidePlankFormRules.POSTURAL_BREAK.errorName] = count
            val err = FormError(
                errorName = SidePlankFormRules.POSTURAL_BREAK.errorName,
                confidence = confidence,
                repIndex = null,
                severity = SidePlankFormRules.POSTURAL_BREAK.severity
            )
            activeErrorsThisFrame.add(err)
            _allSessionErrors.add(err)
        } else if (!isDeviating) {
            consecutiveFrames[SidePlankFormRules.POSTURAL_BREAK.errorName] = 0
        }

        // 4. Audio Feedback Gating
        val candidateRules = mutableListOf<FormRuleDefinition>()

        if (confidence >= confidenceThreshold) {
            // Check persistence rule for hips_dropping (severity 0.7)
            if ((consecutiveFrames[SidePlankFormRules.HIPS_DROPPING.errorName] ?: 0) >= persistenceFrames) {
                candidateRules.add(SidePlankFormRules.HIPS_DROPPING)
            }

            // Check persistence rule for hips_piking (severity 0.5)
            if ((consecutiveFrames[SidePlankFormRules.HIPS_PIKING.errorName] ?: 0) >= persistenceFrames) {
                candidateRules.add(SidePlankFormRules.HIPS_PIKING)
            }

            // Check postural_break feedback eligibility (fires on wobble recovery)
            if (isWobbleRecovered) {
                candidateRules.add(SidePlankFormRules.POSTURAL_BREAK)
            }
        }

        // Pick highest severity candidate
        val bestCandidate = candidateRules.maxByOrNull { it.severity }
        var activeFeedbackEvent: FeedbackEvent? = null

        if (bestCandidate != null) {
            val lastTime = lastFeedbackTimestamps[bestCandidate.errorName] ?: -cooldownMs
            if (timestampMs - lastTime >= cooldownMs) {
                lastFeedbackTimestamps[bestCandidate.errorName] = timestampMs
                val event = FeedbackEvent(
                    message = bestCandidate.feedbackMessage,
                    timestampMs = timestampMs,
                    relatedError = bestCandidate.errorName
                )
                activeFeedbackEvent = event
                _allFeedbackEvents.add(event)
            }
        }

        return FormRuleOutput(
            activeErrors = activeErrorsThisFrame,
            allSessionErrors = _allSessionErrors.toList(),
            newFeedbackEvents = if (activeFeedbackEvent != null) listOf(activeFeedbackEvent) else emptyList(),
            allFeedbackEvents = _allFeedbackEvents.toList()
        )
    }

    /**
     * Computes session-level Form Factor per METRICS_SPEC.md §6 for static hold.
     * Deduplicates errors by errorName across the hold.
     */
    fun computeFormFactor(): Float {
        if (_allSessionErrors.isEmpty()) return 1.0f
        val uniqueErrors = _allSessionErrors.distinctBy { it.errorName }
        val totalDeduction = uniqueErrors.map { it.severity }.sum()
        return (1.0f - totalDeduction).coerceIn(0.0f, 1.0f)
    }
}
