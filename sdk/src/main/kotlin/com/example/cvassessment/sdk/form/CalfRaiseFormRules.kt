package com.example.cvassessment.sdk.form

/**
 * Form errors applicable to Calf Raise per FORM_RULES.md and EXERCISE_SPEC.md.
 */
object CalfRaiseFormRules {

    /**
     * 1. insufficient_depth: rep completes but romPercent for that rep < 60%
     * (heel failed to achieve at least 60% of personal max reference elevation).
     * Severity: 0.60.
     * Feedback message: "Full range of motion."
     */
    val INSUFFICIENT_DEPTH = FormRuleDefinition(
        errorName = "insufficient_depth",
        severity = 0.60f,
        feedbackMessage = "Full range of motion.",
        description = "rep completes but heel elevation does not reach 60% of personal max reference (romPercent < 60%)"
    )

    /**
     * 2. rushing_tempo: tutFactor < 0.6 for 2+ consecutive reps.
     * Severity: 0.45.
     * Feedback message: "Slow down, control the movement."
     */
    val RUSHING_TEMPO = FormRuleDefinition(
        errorName = "rushing_tempo",
        severity = 0.45f,
        feedbackMessage = "Slow down, control the movement.",
        description = "repetition tempo is too fast (tutFactor < 0.6) for 2 or more consecutive reps"
    )

    val ALL_CALF_RAISE_RULES = listOf(
        INSUFFICIENT_DEPTH,
        RUSHING_TEMPO
    )
}
