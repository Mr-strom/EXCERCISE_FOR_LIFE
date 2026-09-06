package com.example.cvassessment.sdk.form

/**
 * Form errors applicable to Shoulder Press per FORM_RULES.md and EXERCISE_SPEC.md.
 */
object ShoulderPressFormRules {

    /**
     * 1. excessive_momentum: shoulder_stability deviation beyond threshold during the press.
     * Severity: 0.55.
     * Feedback message: "Control the movement, avoid swinging."
     */
    val EXCESSIVE_MOMENTUM = FormRuleDefinition(
        errorName = "excessive_momentum",
        severity = 0.55f,
        feedbackMessage = "Control the movement, avoid swinging.",
        description = "body swinging / shoulder displacement during press to assist the lift"
    )

    /**
     * 2. back_arching: hip-shoulder vertical deviation beyond threshold from vertical during the press.
     * Severity: 0.65.
     * Feedback message: "Keep your back straight."
     */
    val BACK_ARCHING = FormRuleDefinition(
        errorName = "back_arching",
        severity = 0.65f,
        feedbackMessage = "Keep your back straight.",
        description = "hip-shoulder vertical line deviates beyond threshold from vertical"
    )

    /**
     * 3. incomplete_lockout: rep completes but top elbow_angle doesn't reach top tolerance (e.g. < 145°).
     * Severity: 0.40.
     * Feedback message: "Fully extend at the top."
     */
    val INCOMPLETE_LOCKOUT = FormRuleDefinition(
        errorName = "incomplete_lockout",
        severity = 0.40f,
        feedbackMessage = "Fully extend at the top.",
        description = "rep completes but top elbow_angle doesn't reach top tolerance of full extension"
    )

    /**
     * 4. asymmetric_movement: left vs right arm angle difference exceeds threshold during synchronized press.
     * Severity: 0.50.
     * Feedback message: "Keep both sides even."
     * Note: Skipped if only one arm is visible (side view).
     */
    val ASYMMETRIC_MOVEMENT = FormRuleDefinition(
        errorName = "asymmetric_movement",
        severity = 0.50f,
        feedbackMessage = "Keep both sides even.",
        description = "left vs right arm angle difference exceeds threshold during shoulder press"
    )

    /**
     * 5. insufficient_depth: rep completes but romPercent < 60%.
     * Severity: 0.60.
     * Feedback message: "Full range of motion."
     */
    val INSUFFICIENT_DEPTH = FormRuleDefinition(
        errorName = "insufficient_depth",
        severity = 0.60f,
        feedbackMessage = "Full range of motion.",
        description = "rep completes but romPercent for that rep < 60%"
    )

    val ALL_SHOULDER_PRESS_RULES = listOf(
        EXCESSIVE_MOMENTUM,
        BACK_ARCHING,
        INCOMPLETE_LOCKOUT,
        ASYMMETRIC_MOVEMENT,
        INSUFFICIENT_DEPTH
    )
}
