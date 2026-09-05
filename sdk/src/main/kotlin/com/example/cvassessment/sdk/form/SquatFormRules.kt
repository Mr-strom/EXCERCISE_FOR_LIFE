package com.example.cvassessment.sdk.form

/**
 * Form errors applicable to Squat per FORM_RULES.md.
 */
object SquatFormRules {

    /**
     * 1. knee_valgus: knee landmark x-position moves medially past threshold relative to ankle-hip line
     * during descent or bottom phase.
     * Severity: 0.75 (higher — injury-relevant).
     * Feedback message: "Push your knees out."
     */
    val KNEE_VALGUS = FormRuleDefinition(
        errorName = "knee_valgus",
        severity = 0.75f,
        feedbackMessage = "Push your knees out.",
        description = "knee landmark moves medially past threshold relative to ankle-hip line during descent/bottom"
    )

    /**
     * 2. insufficient_depth: rep completes but romPercent for that rep < 60%.
     * Severity: 0.6.
     * Feedback message: "Go lower."
     */
    val INSUFFICIENT_DEPTH = FormRuleDefinition(
        errorName = "insufficient_depth",
        severity = 0.6f,
        feedbackMessage = "Go lower.",
        description = "rep completes but romPercent for that rep < 60%"
    )

    /**
     * 3. excessive_lean: torso leans forward excessively (shoulder-hip-knee angle < 65°).
     * Severity: 0.5.
     * Feedback message: "Keep your chest up."
     */
    val EXCESSIVE_LEAN = FormRuleDefinition(
        errorName = "excessive_lean",
        severity = 0.5f,
        feedbackMessage = "Keep your chest up.",
        description = "torso leans excessively forward during squat (hip angle < 65°)"
    )

    val ALL_SQUAT_RULES = listOf(
        KNEE_VALGUS,
        INSUFFICIENT_DEPTH,
        EXCESSIVE_LEAN
    )
}
