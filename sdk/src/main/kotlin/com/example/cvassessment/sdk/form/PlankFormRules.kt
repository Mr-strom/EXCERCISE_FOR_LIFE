package com.example.cvassessment.sdk.form

/**
 * Form errors applicable to Plank per FORM_RULES.md.
 */
object PlankFormRules {

    /**
     * 1. postural_break: instantaneous hip_line_angle deviates beyond tolerance for shorter than hold_end threshold
     *    (wobble that recovers) (severity 0.6, message: "Keep your body in a straight line.")
     */
    val POSTURAL_BREAK = FormRuleDefinition(
        errorName = "postural_break",
        severity = 0.6f,
        feedbackMessage = "Keep your body in a straight line.",
        description = "instantaneous hip_line_angle deviates beyond tolerance for shorter than hold_end threshold"
    )

    /**
     * 2. hips_dropping: hip_line_angle deviates below 165° (severity 0.7, message: "Keep your hips up.")
     */
    val HIPS_DROPPING = FormRuleDefinition(
        errorName = "hips_dropping",
        severity = 0.7f,
        feedbackMessage = "Keep your hips up.",
        description = "hip_line_angle deviates below 165° (hips sagging toward ground)"
    )

    /**
     * 3. hips_piking: hip_line_angle deviates above 195° (severity 0.5, message: "Lower your hips slightly.")
     */
    val HIPS_PIKING = FormRuleDefinition(
        errorName = "hips_piking",
        severity = 0.5f,
        feedbackMessage = "Lower your hips slightly.",
        description = "hip_line_angle deviates above 195° (hips raised too high)"
    )

    val ALL_PLANK_RULES = listOf(
        POSTURAL_BREAK,
        HIPS_DROPPING,
        HIPS_PIKING
    )
}
