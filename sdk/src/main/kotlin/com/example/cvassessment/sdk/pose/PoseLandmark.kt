package com.example.cvassessment.sdk.pose

/**
 * Represents a single 3D body landmark extracted by MediaPipe BlazePose.
 *
 * @param index Landmark index (0..32)
 * @param name Semantic name (e.g. "LEFT_SHOULDER", "RIGHT_KNEE")
 * @param x Normalized horizontal coordinate (0.0..1.0)
 * @param y Normalized vertical coordinate (0.0..1.0)
 * @param z Estimated depth coordinate (smaller is closer to camera)
 * @param visibility Confidence score that landmark is not occluded (0.0..1.0)
 * @param presence Confidence score that landmark is present in the frame (0.0..1.0)
 */
data class PoseLandmark(
    val index: Int,
    val name: String,
    val x: Float,
    val y: Float,
    val z: Float,
    val visibility: Float,
    val presence: Float = 1.0f
)
