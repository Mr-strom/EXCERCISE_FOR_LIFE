package com.example.cvassessment.sdk.output

import com.example.cvassessment.sdk.FeedbackEvent
import com.example.cvassessment.sdk.FormError
import com.example.cvassessment.sdk.FrameResult
import com.example.cvassessment.sdk.SessionResult
import com.example.cvassessment.sdk.ValidationStatus
import com.example.cvassessment.sdk.form.FormRuleOutput
import com.example.cvassessment.sdk.metrics.FrameMetrics
import com.example.cvassessment.sdk.metrics.RepMetrics
import com.example.cvassessment.sdk.spec.ExerciseCategory
import com.example.cvassessment.sdk.spec.ExerciseConfig
import com.example.cvassessment.sdk.statemachine.ExerciseState
import com.example.cvassessment.sdk.visibility.VisibilityStatus
import kotlin.math.roundToInt

/**
 * Module 7: Output / Confidence Gate.
 *
 * Final aggregation and gating layer per ARCHITECTURE.md and SDK_CONTRACT.md.
 * Enforces non-negotiable R7 refusal rules:
 * - If visibility is insufficient or overall confidence is too low,
 *   strips all metrics (ROM, TuT, Form Factor, rep count, hold duration) -> strictly null.
 * - Formats and rounds output values to match SDK_CONTRACT.md and METRICS_SPEC.md.
 */
internal class OutputGate(
    val config: ExerciseConfig = ExerciseConfig.PUSH_UP
) {

    /**
     * Assembles the final [SessionResult] object matching SDK_CONTRACT.md schema exactly.
     */
    fun assembleSessionResult(
        visibilityStatus: VisibilityStatus,
        sessionConfidence: Float,
        exerciseState: ExerciseState,
        allRepMetrics: List<RepMetrics>,
        allFormErrors: List<FormError>,
        allFeedbackEvents: List<FeedbackEvent>
    ): SessionResult {
        // Critical R7: Under insufficient visibility, every metric field MUST be null.
        if (visibilityStatus == VisibilityStatus.INSUFFICIENT_VISIBILITY) {
            return SessionResult(
                status = ValidationStatus.INSUFFICIENT_VISIBILITY,
                confidence = 0.0f,
                completeReps = null,
                incompleteReps = null,
                holdDurationSec = null,
                avgRepDurationSec = null,
                romPercent = null,
                tutFactor = null,
                formFactor = null,
                formErrors = emptyList(),
                feedbackEvents = emptyList()
            )
        }

        val isDynamic = config.category == ExerciseCategory.DYNAMIC_REP
        val completeRepsCount = if (isDynamic) exerciseState.completeRepCount else null
        val incompleteRepsCount = if (isDynamic) exerciseState.incompleteRepCount else null
        val holdDurationSec = if (!isDynamic) 0.0f else null

        // avgRepDurationSec = sum(rep_duration) / count(complete_reps), rounded to 1 decimal
        val avgDuration = if (isDynamic && exerciseState.completeRepCount > 0) {
            val totalDurationSec = exerciseState.completeReps.map { it.durationMs }.sum() / 1000.0f
            roundTo1Decimal(totalDurationSec / exerciseState.completeRepCount)
        } else {
            null
        }

        // Best rep metrics per DECISIONS.md D4
        val bestRep = allRepMetrics.maxByOrNull { it.confidence }
        val romPercent = bestRep?.romPercent?.let { roundToWhole(it) }

        // Consistent TuT Factor: derived directly from average rep duration and baseline
        // so that (tutFactor * tutBaseline ≈ avgRepDurationSec)
        val tutFactor = if (avgDuration != null && config.tutBaseline > 0.0f) {
            roundTo2Decimals(avgDuration / config.tutBaseline)
        } else {
            bestRep?.tutFactor?.let { roundTo2Decimals(it) }
        }

        // Form Factor calculation per METRICS_SPEC.md §6 with per-rep deduplication
        val formFactor = computeFormFactor(exerciseState.completeRepCount, allFormErrors)

        val validationStatus = if (sessionConfidence >= 0.5f) {
            ValidationStatus.VALID
        } else {
            ValidationStatus.INVALID
        }

        // If status is not VALID, metrics must be stripped per SDK_CONTRACT.md
        if (validationStatus != ValidationStatus.VALID) {
            return SessionResult(
                status = validationStatus,
                confidence = roundTo2Decimals(sessionConfidence),
                completeReps = completeRepsCount,
                incompleteReps = incompleteRepsCount,
                holdDurationSec = holdDurationSec,
                avgRepDurationSec = avgDuration,
                romPercent = null,
                tutFactor = null,
                formFactor = null,
                formErrors = allFormErrors,
                feedbackEvents = allFeedbackEvents
            )
        }

        return SessionResult(
            status = ValidationStatus.VALID,
            confidence = roundTo2Decimals(sessionConfidence),
            completeReps = completeRepsCount,
            incompleteReps = incompleteRepsCount,
            holdDurationSec = holdDurationSec,
            avgRepDurationSec = avgDuration,
            romPercent = romPercent,
            tutFactor = tutFactor,
            formFactor = formFactor?.let { roundTo2Decimals(it) },
            formErrors = allFormErrors,
            feedbackEvents = allFeedbackEvents
        )
    }

    /**
     * Builds and sanitizes a [SessionResult] directly from raw provided fields.
     * Enforces the exact output gate nullability and rounding rules.
     */
    fun buildSessionResult(
        status: ValidationStatus,
        confidence: Float,
        completeReps: Int? = null,
        incompleteReps: Int? = null,
        holdDurationSec: Float? = null,
        avgRepDurationSec: Float? = null,
        romPercent: Float? = null,
        tutFactor: Float? = null,
        formFactor: Float? = null,
        formErrors: List<FormError> = emptyList(),
        feedbackEvents: List<FeedbackEvent> = emptyList()
    ): SessionResult {
        if (status == ValidationStatus.INSUFFICIENT_VISIBILITY) {
            return SessionResult(
                status = ValidationStatus.INSUFFICIENT_VISIBILITY,
                confidence = 0.0f,
                completeReps = null,
                incompleteReps = null,
                holdDurationSec = null,
                avgRepDurationSec = null,
                romPercent = null,
                tutFactor = null,
                formFactor = null,
                formErrors = emptyList(),
                feedbackEvents = emptyList()
            )
        }

        val isDynamic = config.category == ExerciseCategory.DYNAMIC_REP

        val sanitizedCompleteReps = if (isDynamic) completeReps else null
        val sanitizedIncompleteReps = if (isDynamic) incompleteReps else null
        val sanitizedHoldDuration = if (!isDynamic) holdDurationSec else null
        val sanitizedAvgDuration = if (isDynamic && (completeReps ?: 0) > 0) {
            avgRepDurationSec?.let { roundTo1Decimal(it) }
        } else {
            null
        }

        // Form Factor with deduplication
        val computedFormFactor = formFactor ?: computeFormFactor(completeReps ?: 0, formErrors)

        val computedTutFactor = if (tutFactor != null) {
            roundTo2Decimals(tutFactor)
        } else if (sanitizedAvgDuration != null && config.tutBaseline > 0.0f) {
            roundTo2Decimals(sanitizedAvgDuration / config.tutBaseline)
        } else {
            null
        }

        if (status != ValidationStatus.VALID) {
            return SessionResult(
                status = status,
                confidence = roundTo2Decimals(confidence),
                completeReps = sanitizedCompleteReps,
                incompleteReps = sanitizedIncompleteReps,
                holdDurationSec = sanitizedHoldDuration,
                avgRepDurationSec = sanitizedAvgDuration,
                romPercent = null,
                tutFactor = null,
                formFactor = null,
                formErrors = formErrors,
                feedbackEvents = feedbackEvents
            )
        }

        return SessionResult(
            status = ValidationStatus.VALID,
            confidence = roundTo2Decimals(confidence),
            completeReps = sanitizedCompleteReps,
            incompleteReps = sanitizedIncompleteReps,
            holdDurationSec = sanitizedHoldDuration,
            avgRepDurationSec = sanitizedAvgDuration,
            romPercent = romPercent?.let { roundToWhole(it) },
            tutFactor = computedTutFactor,
            formFactor = computedFormFactor?.let { roundTo2Decimals(it) },
            formErrors = formErrors,
            feedbackEvents = feedbackEvents
        )
    }

    /**
     * Assembles the per-frame [FrameResult].
     */
    fun assembleFrameResult(
        visibilityStatus: VisibilityStatus,
        frameConfidence: Float,
        exerciseState: ExerciseState,
        frameMetrics: FrameMetrics,
        formOutput: FormRuleOutput
    ): FrameResult {
        if (visibilityStatus == VisibilityStatus.INSUFFICIENT_VISIBILITY) {
            return FrameResult(
                status = ValidationStatus.INSUFFICIENT_VISIBILITY,
                confidence = 0.0f,
                currentReps = null,
                currentHoldSec = null,
                instantRomPercent = null,
                activeFeedback = null
            )
        }

        val isDynamic = config.category == ExerciseCategory.DYNAMIC_REP
        val currentReps = if (isDynamic) exerciseState.completeRepCount else null
        val instantRom = frameMetrics.instantRomPercent?.let { roundToWhole(it) }

        return FrameResult(
            status = ValidationStatus.VALID,
            confidence = roundTo2Decimals(frameConfidence),
            currentReps = currentReps,
            currentHoldSec = null,
            instantRomPercent = instantRom,
            activeFeedback = formOutput.newFeedbackEvents.firstOrNull()
        )
    }

    /**
     * Form Factor per METRICS_SPEC.md §6:
     * formFactor = 1 - (weighted_sum_of_active_form_error_severities / max_possible_severity)
     *
     * Deduplicates errors by (errorName, repIndex) so that multiple frame detections of the
     * same ongoing issue within one rep count ONCE per rep for Form Factor calculation.
     */
    fun computeFormFactor(completeRepsCount: Int, formErrors: List<FormError>): Float? {
        if (completeRepsCount == 0) return null
        if (formErrors.isEmpty()) return 1.0f

        // Deduplicate errors by (errorName, repIndex)
        val repErrors = formErrors
            .filter { it.repIndex != null }
            .distinctBy { Pair(it.errorName, it.repIndex) }

        val sessionLevelErrors = formErrors
            .filter { it.repIndex == null }
            .distinctBy { it.errorName }

        val totalDeduction = repErrors.sumOf { it.severity.toDouble() }.toFloat() +
                             sessionLevelErrors.sumOf { it.severity.toDouble() }.toFloat()

        val score = 1.0f - (totalDeduction / completeRepsCount)
        return score.coerceIn(0.0f, 1.0f)
    }

    private fun roundTo1Decimal(value: Float): Float =
        (value * 10.0f).roundToInt() / 10.0f

    private fun roundTo2Decimals(value: Float): Float =
        (value * 100.0f).roundToInt() / 100.0f

    private fun roundToWhole(value: Float): Float =
        value.roundToInt().toFloat()
}
