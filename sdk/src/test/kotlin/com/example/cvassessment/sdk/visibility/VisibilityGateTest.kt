package com.example.cvassessment.sdk.visibility

import com.example.cvassessment.sdk.pose.PoseEstimationResult
import com.example.cvassessment.sdk.pose.PoseLandmark
import com.example.cvassessment.sdk.pose.PoseLandmarkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VisibilityGateTest {

    private lateinit var visibilityGate: VisibilityGate

    @Before
    fun setUp() {
        visibilityGate = VisibilityGate(exerciseId = "push_up")
    }

    private fun createSyntheticPose(
        overrideVisibility: Map<Int, Float> = emptyMap(),
        overrideCoordinates: Map<Int, Pair<Float, Float>> = emptyMap(),
        missingIndices: Set<Int> = emptySet(),
        timestampMs: Long = 1000L
    ): PoseEstimationResult {
        val landmarks = mutableListOf<PoseLandmark>()
        for (i in 0 until 33) {
            if (i in missingIndices) continue
            val vis = overrideVisibility[i] ?: 0.9f
            val (x, y) = overrideCoordinates[i] ?: (0.5f to 0.5f)
            landmarks.add(
                PoseLandmark(
                    index = i,
                    name = PoseLandmarkType.getName(i),
                    x = x,
                    y = y,
                    z = 0.0f,
                    visibility = vis
                )
            )
        }
        return PoseEstimationResult(landmarks, timestampMs, true)
    }

    /**
     * Unit test 1: Feed synthetic landmarks where all required ones are present
     * with visibility >= 0.4 -> assert SUFFICIENT_VISIBILITY.
     */
    @Test
    fun test1_AllRequiredLandmarksPresentWithHighVisibility_ReturnsSufficient() {
        val pose = createSyntheticPose() // Default visibility is 0.9 >= 0.4
        val result = visibilityGate.checkFrame(pose)

        assertEquals(VisibilityStatus.SUFFICIENT_VISIBILITY, result.status)
        assertTrue(result.isSufficient)
        assertTrue(result.failureReasons.isEmpty())
        assertTrue(result.missingLandmarkIndices.isEmpty())
    }

    /**
     * Unit test 2: Feed synthetic landmarks where a required landmark drops to
     * visibility < 0.4 for 1 frame only -> assert still SUFFICIENT (no false positive).
     */
    @Test
    fun test2_SingleFrameLowVisibilityDrop_ReturnsSufficientDueToGracePeriod() {
        // Frame 1: Left elbow drops to 0.3 (< 0.4)
        val lowVisPose = createSyntheticPose(
            overrideVisibility = mapOf(PoseLandmarkType.LEFT_ELBOW to 0.3f)
        )
        val result = visibilityGate.checkFrame(lowVisPose)

        // Must still be SUFFICIENT_VISIBILITY because 1 missing frame <= MAX_MISSING_FRAMES (7)
        assertEquals(VisibilityStatus.SUFFICIENT_VISIBILITY, result.status)
        assertTrue(result.isSufficient)
        assertTrue(result.failureReasons.isEmpty())
        assertEquals(1, result.consecutiveMissingCounts[PoseLandmarkType.LEFT_ELBOW])

        // Frame 2: Visibility recovers to 0.8 -> counter resets
        val recoveredPose = createSyntheticPose(
            overrideVisibility = mapOf(PoseLandmarkType.LEFT_ELBOW to 0.8f)
        )
        val recoveredResult = visibilityGate.checkFrame(recoveredPose)
        assertEquals(VisibilityStatus.SUFFICIENT_VISIBILITY, recoveredResult.status)
        assertEquals(0, recoveredResult.consecutiveMissingCounts[PoseLandmarkType.LEFT_ELBOW])
    }

    /**
     * Unit test 3: Feed synthetic landmarks where a required landmark stays < 0.4
     * for 8+ consecutive frames -> assert INSUFFICIENT_VISIBILITY with reason LOW_CONFIDENCE.
     */
    @Test
    fun test3_EightConsecutiveFramesLowVisibility_ReturnsInsufficientWithLowConfidence() {
        val lowVisPose = createSyntheticPose(
            overrideVisibility = mapOf(PoseLandmarkType.RIGHT_WRIST to 0.2f)
        )

        // Frames 1..7: within MAX_MISSING_FRAMES tolerance (7) (still SUFFICIENT)
        for (f in 1..7) {
            val intermediateResult = visibilityGate.checkFrame(lowVisPose)
            assertEquals(
                "Frame $f should be within grace period",
                VisibilityStatus.SUFFICIENT_VISIBILITY,
                intermediateResult.status
            )
        }

        // Frame 8: exceeds MAX_MISSING_FRAMES (7) -> must trigger INSUFFICIENT_VISIBILITY
        val frame8Result = visibilityGate.checkFrame(lowVisPose)
        assertEquals(VisibilityStatus.INSUFFICIENT_VISIBILITY, frame8Result.status)
        assertFalse(frame8Result.isSufficient)
        assertTrue(
            "Failure reasons should contain LOW_CONFIDENCE",
            frame8Result.failureReasons.contains(VisibilityFailureReason.LOW_CONFIDENCE)
        )
        assertEquals(8, frame8Result.consecutiveMissingCounts[PoseLandmarkType.RIGHT_WRIST])
    }

    /**
     * Unit test 4: Simulate a full session where 60% of frames fail visibility ->
     * assert session-level status = INSUFFICIENT_VISIBILITY.
     */
    @Test
    fun test4_SessionSixtyPercentFailing_ReturnsSessionInsufficientVisibility() {
        val validPose = createSyntheticPose()
        val invalidPose = createSyntheticPose(
            overrideCoordinates = mapOf(PoseLandmarkType.LEFT_SHOULDER to (0.0f to 0.5f)) // Out of frame
        )

        val totalFrames = 100
        val failingFrames = 60 // 60% failure rate > 50% SESSION_FAILURE_THRESHOLD

        for (i in 1..totalFrames) {
            if (i <= failingFrames) {
                visibilityGate.checkFrame(invalidPose)
            } else {
                visibilityGate.checkFrame(validPose)
            }
        }

        assertEquals(100L, visibilityGate.totalFramesAnalyzed)
        assertEquals(60L, visibilityGate.failedVisibilityFrames)
        assertEquals(0.60f, visibilityGate.getSessionFailureRate(), 0.001f)
        assertEquals(VisibilityStatus.INSUFFICIENT_VISIBILITY, visibilityGate.getSessionVisibilityStatus())
    }

    /**
     * Unit test 5: Simulate out-of-frame (required landmarks at frame boundary or
     * missing entirely) -> assert INSUFFICIENT_VISIBILITY with reason BODY_OUT_OF_FRAME.
     */
    @Test
    fun test5_RequiredLandmarksAtFrameBoundaryOrMissing_ReturnsInsufficientWithBodyOutOfFrame() {
        // Case A: Required landmark at extreme frame boundary (x = 0.0)
        val boundaryPose = createSyntheticPose(
            overrideCoordinates = mapOf(PoseLandmarkType.LEFT_ANKLE to (0.005f to 0.5f))
        )
        val boundaryResult = visibilityGate.checkFrame(boundaryPose)
        assertEquals(VisibilityStatus.INSUFFICIENT_VISIBILITY, boundaryResult.status)
        assertTrue(
            "Should fail with BODY_OUT_OF_FRAME",
            boundaryResult.failureReasons.contains(VisibilityFailureReason.BODY_OUT_OF_FRAME)
        )

        // Case B: Required landmark missing entirely from landmark list
        visibilityGate.reset()
        val missingLandmarkPose = createSyntheticPose(
            missingIndices = setOf(PoseLandmarkType.RIGHT_HIP)
        )
        val missingResult = visibilityGate.checkFrame(missingLandmarkPose)
        assertEquals(VisibilityStatus.INSUFFICIENT_VISIBILITY, missingResult.status)
        assertTrue(
            "Should fail with BODY_OUT_OF_FRAME",
            missingResult.failureReasons.contains(VisibilityFailureReason.BODY_OUT_OF_FRAME)
        )

        // Case C: No pose detected at all
        visibilityGate.reset()
        val emptyPose = PoseEstimationResult.empty(1000L)
        val emptyResult = visibilityGate.checkFrame(emptyPose)
        assertEquals(VisibilityStatus.INSUFFICIENT_VISIBILITY, emptyResult.status)
        assertTrue(
            "Should fail with BODY_OUT_OF_FRAME",
            emptyResult.failureReasons.contains(VisibilityFailureReason.BODY_OUT_OF_FRAME)
        )
        assertTrue(
            "Should fail with NO_POSE_DETECTED",
            emptyResult.failureReasons.contains(VisibilityFailureReason.NO_POSE_DETECTED)
        )
    }
}
