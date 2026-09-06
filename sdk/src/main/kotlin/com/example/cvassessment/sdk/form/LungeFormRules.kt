package com.example.cvassessment.sdk.form

/**
 * Form errors applicable to Lunge per FORM_RULES.md and EXERCISE_SPEC.md.
 */
object LungeFormRules {

    /**
     * 1. insufficient_depth: rep completes but romPercent for that rep < 60%
     * (front_knee_angle failed to reach target depth tolerance).
     * Severity: 0.60.
     * Feedback message: "Go lower."
     */
    val INSUFFICIENT_DEPTH = FormRuleDefinition(
        errorName = "insufficient_depth",
        severity = 0.60f,
        feedbackMessage = "Go lower.",
        description = "rep completes but front_knee_angle does not reach target lunge depth (romPercent < 60%)"
    )

    /**
     * 2. asymmetric_movement: during alternating lunges, compares depth and tempo
     * consistency between left-leg-forward and right-leg-forward reps.
     * Note: Unlike Bicep Curl (which compares simultaneous bilateral arm angles within the same frame),
     * Lunge evaluates cross-repetition symmetry between alternating left-forward vs right-forward reps.
     * Severity: 0.50.
     * Feedback message: "Keep both sides even."
     */
    val ASYMMETRIC_MOVEMENT = FormRuleDefinition(
        errorName = "asymmetric_movement",
        severity = 0.50f,
        feedbackMessage = "Keep both sides even.",
        description = "significant depth or tempo asymmetry between alternating left-forward and right-forward lunges"
    )

    val ALL_LUNGE_RULES = listOf(
        INSUFFICIENT_DEPTH,
        ASYMMETRIC_MOVEMENT
    )
}
