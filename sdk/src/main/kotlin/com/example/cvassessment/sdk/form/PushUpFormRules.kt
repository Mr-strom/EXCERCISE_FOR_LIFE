package com.example.cvassessment.sdk.form

/**
 * Metadata definition for a form error rule per FORM_RULES.md.
 */
data class FormRuleDefinition(
    val errorName: String,
    val severity: Float,
    val feedbackMessage: String,
    val description: String
)

/**
 * Form errors applicable to Push-Up per FORM_RULES.md.
 */
object PushUpFormRules {
    /**
     * 1. hips_dropping: hip_line_angle < 165° (severity 0.7, message: "Keep your hips up.")
     */
    val HIPS_DROPPING = FormRuleDefinition(
        errorName = "hips_dropping",
        severity = 0.7f,
        feedbackMessage = "Keep your hips up.",
        description = "hip_line_angle deviates below 165° (hips sagging toward ground)"
    )

    /**
     * 2. hips_piking: hip_line_angle > 195° (severity 0.5, message: "Lower your hips slightly.")
     */
    val HIPS_PIKING = FormRuleDefinition(
        errorName = "hips_piking",
        severity = 0.5f,
        feedbackMessage = "Lower your hips slightly.",
        description = "hip_line_angle deviates above 195° (hips raised too high)"
    )

    /**
     * 3. insufficient_depth: rep completes but romPercent < 60% (severity 0.6, message: "Go lower.")
     */
    val INSUFFICIENT_DEPTH = FormRuleDefinition(
        errorName = "insufficient_depth",
        severity = 0.6f,
        feedbackMessage = "Go lower.",
        description = "rep completes but romPercent for that rep < 60%"
    )

    /**
     * 4. incomplete_lockout: rep completes but top elbow_angle doesn't return within tolerance of full extension (severity 0.4)
     */
    val INCOMPLETE_LOCKOUT = FormRuleDefinition(
        errorName = "incomplete_lockout",
        severity = 0.4f,
        feedbackMessage = "Fully extend at the top.",
        description = "rep completes but top elbow_angle doesn't return within tolerance of full extension"
    )

    val ALL_PUSH_UP_RULES = listOf(
        HIPS_DROPPING,
        HIPS_PIKING,
        INSUFFICIENT_DEPTH,
        INCOMPLETE_LOCKOUT
    )
}
