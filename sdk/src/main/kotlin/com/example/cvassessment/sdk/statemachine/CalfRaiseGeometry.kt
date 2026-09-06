package com.example.cvassessment.sdk.statemachine

import com.example.cvassessment.sdk.pose.PoseLandmark
import com.example.cvassessment.sdk.pose.PoseLandmarkType
import kotlin.math.abs

/**
 * Geometric calculations for Calf Raise exercise analysis per EXERCISE_SPEC.md and DECISIONS.md D7.
 *
 * Implements:
 * 1. Strict side (profile) view detection:
 *    - Heel vertical displacement cannot be reliably detected from front/45° view.
 *    - In profile view, horizontal X-distance between hips/shoulders is compressed (< 0.08f)
 *      or depth (Z) difference is pronounced (> 0.25f).
 * 2. Heel vertical position extraction:
 *    - Tracks heel landmarks (LEFT_HEEL #29, RIGHT_HEEL #30) with fallback to ankles (#27, #28).
 * 3. Vertical displacement / elevation calculation:
 *    - In image coordinates, Y increases downward.
 *    - Elevation = baselineHeelY - currentHeelY (moving upward decreases Y, increasing elevation).
 * 4. Relative ROM% calculation based on session personal max reference.
 */
object CalfRaiseGeometry {

    /**
     * Detects whether the camera is in side (profile) view.
     * Side view is a HARD requirement for Calf Raise per EXERCISE_SPEC.md.
     */
    fun isSideView(landmarks: List<PoseLandmark>): Boolean {
        if (landmarks.isEmpty()) return true

        val leftHip = landmarks.find { it.index == PoseLandmarkType.LEFT_HIP }
        val rightHip = landmarks.find { it.index == PoseLandmarkType.RIGHT_HIP }
        val leftShoulder = landmarks.find { it.index == PoseLandmarkType.LEFT_SHOULDER }
        val rightShoulder = landmarks.find { it.index == PoseLandmarkType.RIGHT_SHOULDER }

        // If neither hip is detected, default to true so tracking isn't prematurely aborted
        if (leftHip == null && rightHip == null) return true

        val hipWidth = if (leftHip != null && rightHip != null) abs(leftHip.x - rightHip.x) else 0.0f
        val shoulderWidth = if (leftShoulder != null && rightShoulder != null) {
            abs(leftShoulder.x - rightShoulder.x)
        } else {
            hipWidth
        }

        // Profile view: horizontal distance in X is compressed
        if (hipWidth < 0.08f || shoulderWidth < 0.08f) {
            return true
        }

        // Check depth difference: in profile, one side is deeper than the other
        val hipDepthDiff = if (leftHip != null && rightHip != null) abs(leftHip.z - rightHip.z) else 0.0f
        if (hipDepthDiff > 0.25f) {
            return true
        }

        return false
    }

    /**
     * Extracts normalized Y position of the primary visible heel(s) from landmarks.
     * Uses MediaPipe landmarks 29 (LEFT_HEEL) and 30 (RIGHT_HEEL), with fallback to ankles (27, 28).
     */
    fun getHeelY(landmarks: List<PoseLandmark>, visibilityThreshold: Float = 0.4f): Float? {
        val leftHeel = landmarks.find { it.index == PoseLandmarkType.LEFT_HEEL }
        val rightHeel = landmarks.find { it.index == PoseLandmarkType.RIGHT_HEEL }

        val visibleHeels = listOfNotNull(leftHeel, rightHeel).filter { it.visibility >= visibilityThreshold }
        if (visibleHeels.isNotEmpty()) {
            return visibleHeels.map { it.y }.average().toFloat()
        }

        // Fallback to any present heel if visibility threshold was not met
        val anyHeels = listOfNotNull(leftHeel, rightHeel)
        if (anyHeels.isNotEmpty()) {
            return anyHeels.map { it.y }.average().toFloat()
        }

        // Fallback to ankles if heels are missing from the model output
        val leftAnkle = landmarks.find { it.index == PoseLandmarkType.LEFT_ANKLE }
        val rightAnkle = landmarks.find { it.index == PoseLandmarkType.RIGHT_ANKLE }
        val visibleAnkles = listOfNotNull(leftAnkle, rightAnkle).filter { it.visibility >= visibilityThreshold }
        if (visibleAnkles.isNotEmpty()) {
            return visibleAnkles.map { it.y }.average().toFloat()
        }

        val anyAnkles = listOfNotNull(leftAnkle, rightAnkle)
        if (anyAnkles.isNotEmpty()) {
            return anyAnkles.map { it.y }.average().toFloat()
        }

        return null
    }

    /**
     * Computes upward elevation displacement from calibrated standing baseline.
     * In image coordinates, Y increases downward, so upward heel movement means currentY < baselineY.
     */
    fun computeElevation(baselineY: Float, currentY: Float): Float {
        return (baselineY - currentY).coerceAtLeast(0.0f)
    }

    /**
     * Computes relative ROM% based on personal max reference elevation for the session.
     * Per DECISIONS.md D7 and EXERCISE_SPEC.md:
     * - The first completed rep establishes the reference (ROM% = 100%).
     * - Subsequent reps score proportional to this reference.
     */
    fun computeRomPercent(elevation: Float, personalMaxReference: Float?): Float {
        if (personalMaxReference == null || personalMaxReference <= 1e-4f) {
            return 100.0f
        }
        return ((elevation / personalMaxReference) * 100.0f).coerceIn(0.0f, 100.0f)
    }
}
