package com.example.cvassessment.sdk.form

import com.example.cvassessment.sdk.FeedbackEvent
import com.example.cvassessment.sdk.FormError

/**
 * Output emitted by Module 6 (Form Rule Engine).
 *
 * @param activeErrors Form errors active on the current frame
 * @param allSessionErrors Cumulative list of all form errors detected in the session
 * @param newFeedbackEvents Audio feedback events triggered on this frame (strictly throttled)
 * @param allFeedbackEvents Cumulative list of all audio feedback events emitted in the session
 */
data class FormRuleOutput(
    val activeErrors: List<FormError> = emptyList(),
    val allSessionErrors: List<FormError> = emptyList(),
    val newFeedbackEvents: List<FeedbackEvent> = emptyList(),
    val allFeedbackEvents: List<FeedbackEvent> = emptyList()
)
