package com.example.cvassessment.sdk.form

/**
 * Form errors applicable to Bicep Curl per FORM_RULES.md and EXERCISE_SPEC.md.
 */
object BicepCurlFormRules {

    /**
     * 1. excessive_momentum: shoulder_stability angle changes by more than threshold
     * during the curl (indicates body swinging/momentum).
     * Severity: 0.55.
     * Feedback message: "Control the movement, avoid swinging."
     */
    val EXCESSIVE_MOMENTUM = FormRuleDefinition(
        errorName = "excessive_momentum",
        severity = 0.55f,
        feedbackMessage = "Control the movement, avoid swinging.",
        description = "body swinging / shoulder displacement during curl to assist the lift"
    )

    /**
     * 2. back_arching: lower-back landmark proxy (hip-shoulder vertical line) deviates
     * beyond threshold from vertical during the curl.
     * Severity: 0.65.
     * Feedback message: "Keep your back straight."
     */
    val BACK_ARCHING = FormRuleDefinition(
        errorName = "back_arching",
        severity = 0.65f,
        feedbackMessage = "Keep your back straight.",
        description = "torso/hip-shoulder line deviates beyond threshold from vertical"
    )

    /**
     * 3. asymmetric_movement: left vs right elbow angle difference exceeds threshold
     * during a synchronized rep phase.
     * Severity: 0.50.
     * Feedback message: "Keep both sides even."
     * Note: Skipped if only one arm is visible (side view).
     */
    val ASYMMETRIC_MOVEMENT = FormRuleDefinition(
        errorName = "asymmetric_movement",
        severity = 0.50f,
        feedbackMessage = "Keep both sides even.",
        description = "left vs right elbow angle difference exceeds threshold during curl"
    )

    /**
     * 4. insufficient_depth: rep completes but romPercent for that rep < 60%.
     * Severity: 0.60.
     * Feedback message: "Full range of motion."
     */
    val INSUFFICIENT_DEPTH = FormRuleDefinition(
        errorName = "insufficient_depth",
        severity = 0.60f,
        feedbackMessage = "Full range of motion.",
        description = "rep completes but romPercent for that rep < 60%"
    )

    val ALL_BICEP_CURL_RULES = listOf(
        EXCESSIVE_MOMENTUM,
        BACK_ARCHING,
        ASYMMETRIC_MOVEMENT,
        INSUFFICIENT_DEPTH
    )
}
