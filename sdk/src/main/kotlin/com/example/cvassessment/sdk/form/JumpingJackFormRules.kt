package com.example.cvassessment.sdk.form

/**
 * Form error definitions applicable to Jumping Jack per FORM_RULES.md and EXERCISE_SPEC.md #9.
 */
object JumpingJackFormRules {

    /**
     * 1. asymmetric_jack: arm and leg phase transitions don't occur within the same short time window (divergence > 180ms).
     * Severity: 0.35.
     * Feedback message: "Sync your arms and legs."
     */
    val ASYMMETRIC_JACK = FormRuleDefinition(
        errorName = "asymmetric_jack",
        severity = 0.35f,
        feedbackMessage = "Sync your arms and legs.",
        description = "arm and leg phase transitions don't occur within the same short time window (> 180ms divergence)"
    )

    /**
     * 2. rushing_tempo: tutFactor < 0.6 for 2+ consecutive reps (< 0.72s for 1.2s baseline).
     * Severity: 0.45.
     * Feedback message: "Slow down, control the movement."
     */
    val RUSHING_TEMPO = FormRuleDefinition(
        errorName = "rushing_tempo",
        severity = 0.45f,
        feedbackMessage = "Slow down, control the movement.",
        description = "repetition tempo is too fast (tutFactor < 0.6) for 2 or more consecutive reps"
    )

    /**
     * 3. insufficient_depth: rep completes but combined romPercent < 60%.
     * Severity: 0.60.
     * Feedback message: "Full range of motion."
     */
    val INSUFFICIENT_DEPTH = FormRuleDefinition(
        errorName = "insufficient_depth",
        severity = 0.60f,
        feedbackMessage = "Full range of motion.",
        description = "rep completes but combined romPercent for that rep < 60%"
    )

    val ALL_JUMPING_JACK_RULES = listOf(
        ASYMMETRIC_JACK,
        RUSHING_TEMPO,
        INSUFFICIENT_DEPTH
    )
}
