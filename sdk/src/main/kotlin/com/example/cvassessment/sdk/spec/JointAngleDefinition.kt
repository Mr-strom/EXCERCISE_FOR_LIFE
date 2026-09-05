package com.example.cvassessment.sdk.spec

/**
 * Definition of a tracked joint angle formed by three landmark points (First -> Vertex -> Last).
 *
 * @param angleName Unique identifier (e.g. "elbow_angle", "hip_line_angle")
 * @param firstJoint Landmark index of first point
 * @param vertexJoint Landmark index of vertex point (angle measured here)
 * @param lastJoint Landmark index of third point
 * @param description Semantic explanation of this angle's role in the exercise
 */
data class JointAngleDefinition(
    val angleName: String,
    val firstJoint: Int,
    val vertexJoint: Int,
    val lastJoint: Int,
    val description: String = ""
)
