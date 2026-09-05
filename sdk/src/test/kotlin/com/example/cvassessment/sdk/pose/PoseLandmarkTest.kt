package com.example.cvassessment.sdk.pose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseLandmarkTest {

    @Test
    fun testAll33LandmarkNamesExist() {
        for (i in 0 until 33) {
            val name = PoseLandmarkType.getName(i)
            assertTrue("Landmark name for $i should not start with LANDMARK_", !name.startsWith("LANDMARK_"))
        }
        assertEquals("NOSE", PoseLandmarkType.getName(0))
        assertEquals("LEFT_SHOULDER", PoseLandmarkType.getName(11))
        assertEquals("RIGHT_SHOULDER", PoseLandmarkType.getName(12))
        assertEquals("LEFT_HIP", PoseLandmarkType.getName(23))
        assertEquals("RIGHT_HIP", PoseLandmarkType.getName(24))
        assertEquals("LEFT_ANKLE", PoseLandmarkType.getName(27))
        assertEquals("RIGHT_FOOT_INDEX", PoseLandmarkType.getName(32))
    }

    @Test
    fun testSkeletonConnectionsValid() {
        for ((start, end) in PoseLandmarkType.SKELETON_CONNECTIONS) {
            assertTrue("Connection start $start should be within 0..32", start in 0..32)
            assertTrue("Connection end $end should be within 0..32", end in 0..32)
        }
    }

    @Test
    fun testPoseEstimationResultAverageVisibility() {
        val landmarks = listOf(
            PoseLandmark(0, "NOSE", 0.5f, 0.5f, 0f, 0.9f),
            PoseLandmark(11, "LEFT_SHOULDER", 0.4f, 0.6f, 0f, 0.8f),
            PoseLandmark(12, "RIGHT_SHOULDER", 0.6f, 0.6f, 0f, 0.7f)
        )
        val result = PoseEstimationResult(landmarks, 1000L, true)
        assertTrue(result.hasPose)
        assertEquals(0.8f, result.getAverageVisibility(), 0.001f)

        val emptyResult = PoseEstimationResult.empty(1000L)
        assertFalse(emptyResult.hasPose)
        assertEquals(0.0f, emptyResult.getAverageVisibility(), 0.001f)
    }
}
