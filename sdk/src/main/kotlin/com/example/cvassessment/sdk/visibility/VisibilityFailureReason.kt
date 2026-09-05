package com.example.cvassessment.sdk.visibility

/**
 * Diagnostic reasons indicating why a frame or session failed the Visibility Gate.
 */
enum class VisibilityFailureReason {
    /**
     * User's body or required landmarks are outside or crossing the camera frame boundaries,
     * or fewer than 60% of required landmarks are present.
     */
    BODY_OUT_OF_FRAME,

    /**
     * One or more required landmarks fell below the visibility threshold (0.5)
     * for more than MAX_MISSING_FRAMES consecutive frames.
     */
    LOW_CONFIDENCE,

    /**
     * Required landmarks are persistently occluded by objects or body parts.
     */
    EXCESSIVE_OCCLUSION,

    /**
     * No person or pose was detected in the frame.
     */
    NO_POSE_DETECTED
}
