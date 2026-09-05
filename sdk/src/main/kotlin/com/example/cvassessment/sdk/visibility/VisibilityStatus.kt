package com.example.cvassessment.sdk.visibility

/**
 * Visibility status emitted by the Visibility Gate (Module 3).
 * When INSUFFICIENT_VISIBILITY, downstream metric modules MUST be short-circuited.
 */
enum class VisibilityStatus {
    SUFFICIENT_VISIBILITY,
    INSUFFICIENT_VISIBILITY
}
