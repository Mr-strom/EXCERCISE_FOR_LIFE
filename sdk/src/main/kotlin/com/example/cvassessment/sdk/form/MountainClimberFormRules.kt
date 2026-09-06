package com.example.cvassessment.sdk.form

/**
 * Form rule definitions for Mountain Climber exercise analysis per FORM_RULES.md.
 *
 * Governs:
 * 1. incomplete_leg_drive: knee_drive_angle doesn't reach target depth on a given rep (severity 0.5)
 * 2. hips_dropping: hip_line_angle < 165° sag during active plank/rep motion (severity 0.7)
 * 3. hips_piking: hip_line_angle > 195° pike during active plank/rep motion (severity 0.5)
 */
object MountainClimberFormRules {

    val INCOMPLETE_LEG_DRIVE = FormRuleDefinition(
        errorName = "incomplete_leg_drive",
        description = "Knee drive angle did not reach target depth (ROM < 60%)",
        severity = 0.50f,
        feedbackMessage = "Drive your knee further forward."
    )

    val HIPS_DROPPING = FormRuleDefinition(
        errorName = "hips_dropping",
        description = "Hips sagging below straight line (hip_line_angle < 165°)",
        severity = 0.70f,
        feedbackMessage = "Keep your hips up."
    )

    val HIPS_PIKING = FormRuleDefinition(
        errorName = "hips_piking",
        description = "Hips piking upward into an A-frame (hip_line_angle > 195°)",
        severity = 0.50f,
        feedbackMessage = "Lower your hips slightly."
    )

    val ALL_MOUNTAIN_CLIMBER_RULES = listOf(
        INCOMPLETE_LEG_DRIVE,
        HIPS_DROPPING,
        HIPS_PIKING
    )
}
