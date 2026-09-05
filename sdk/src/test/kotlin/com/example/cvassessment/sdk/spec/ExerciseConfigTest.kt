package com.example.cvassessment.sdk.spec

import com.example.cvassessment.sdk.UnknownExerciseException
import com.example.cvassessment.sdk.pose.PoseLandmarkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Unit tests verifying ExerciseConfig shape and ExerciseRegistry loading behavior.
 */
class ExerciseConfigTest {

    @Test
    fun testPushUpConfigLoadsCorrectly() {
        val config = ExerciseRegistry.getConfig("push_up")
        assertNotNull("Push-up config should not be null", config)

        // 1. exerciseId
        assertEquals("push_up", config.exerciseId)

        // 2. category: dynamic_rep
        assertEquals(ExerciseCategory.DYNAMIC_REP, config.category)
        assertEquals("dynamic_rep", config.category.identifier)
        assertEquals("dynamic_rep", config.category.toString())

        // 3. primaryLandmarks: [shoulders, elbows, wrists, hips, ankles]
        val expectedLandmarks = listOf("shoulders", "elbows", "wrists", "hips", "ankles")
        assertEquals(expectedLandmarks, config.primaryLandmarks)

        // 4. trackedAngles: [elbow_angle, hip_line_angle]
        val expectedTrackedAngles = listOf("elbow_angle", "hip_line_angle")
        assertEquals(expectedTrackedAngles, config.trackedAngles)

        // 5. phases: { top: <condition>, bottom: <condition> }
        assertTrue("Phases should contain 'top'", config.phases.containsKey("top"))
        assertTrue("Phases should contain 'bottom'", config.phases.containsKey("bottom"))

        val topPhase = config.phases["top"]!!
        assertEquals("top", topPhase.phaseName)
        assertEquals("elbow_angle", topPhase.trackedAngleName)
        assertEquals(AngleComparison.GREATER_THAN, topPhase.comparison)
        assertEquals(160.0f, topPhase.thresholdAngle, 0.01f)

        val bottomPhase = config.phases["bottom"]!!
        assertEquals("bottom", bottomPhase.phaseName)
        assertEquals("elbow_angle", bottomPhase.trackedAngleName)
        assertEquals(AngleComparison.LESS_THAN, bottomPhase.comparison)
        assertEquals(90.0f, bottomPhase.thresholdAngle, 0.01f)

        // Verify phase match conditions
        assertTrue("170 deg elbow angle should match top phase", topPhase.matches(170.0f))
        assertFalse("120 deg elbow angle should not match top phase", topPhase.matches(120.0f))
        assertTrue("80 deg elbow angle should match bottom phase", bottomPhase.matches(80.0f))
        assertFalse("120 deg elbow angle should not match bottom phase", bottomPhase.matches(120.0f))

        // 6. romDefinition: { fullExpectedAngle, minimumAcceptablePercent }
        val rom = config.romDefinition
        assertNotNull("ROM definition must not be null", rom)
        assertEquals(90.0f, rom.fullExpectedAngle, 0.01f)
        assertEquals(60.0f, rom.minimumAcceptablePercent, 0.01f)
        assertEquals("elbow_angle", rom.trackedAngleName)

        // 7. tutBaseline: 4.0 (seconds per rep)
        assertEquals(4.0f, config.tutBaseline, 0.01f)

        // 8. cameraNotes: "side view most accurate..."
        assertTrue(
            "Camera notes should mention side view accuracy",
            config.cameraNotes.contains("side view most accurate")
        )

        // Also test convenience load method
        val loadedConfig = ExerciseConfig.load("push_up")
        assertEquals(config, loadedConfig)

        // Underlying landmarks indices for BlazePose
        val expectedLandmarkIndices = listOf(
            PoseLandmarkType.LEFT_SHOULDER,  // 11
            PoseLandmarkType.RIGHT_SHOULDER, // 12
            PoseLandmarkType.LEFT_ELBOW,     // 13
            PoseLandmarkType.RIGHT_ELBOW,    // 14
            PoseLandmarkType.LEFT_WRIST,     // 15
            PoseLandmarkType.RIGHT_WRIST,    // 16
            PoseLandmarkType.LEFT_HIP,       // 23
            PoseLandmarkType.RIGHT_HIP,      // 24
            PoseLandmarkType.LEFT_ANKLE,     // 27
            PoseLandmarkType.RIGHT_ANKLE     // 28
        )
        assertEquals(expectedLandmarkIndices, config.requiredLandmarkIndices)
    }

    @Test
    fun testQueryingUnknownExerciseThrowsException() {
        try {
            ExerciseRegistry.getConfig("unknown_exercise_xyz")
            fail("Querying unknown exerciseId should have thrown UnknownExerciseException")
        } catch (e: UnknownExerciseException) {
            assertTrue(
                "Exception message should mention unknown exercise ID",
                e.message?.contains("unknown_exercise_xyz") == true
            )
        }

        try {
            ExerciseConfig.load("non_existent_exercise")
            fail("ExerciseConfig.load with unknown ID should have thrown UnknownExerciseException")
        } catch (e: UnknownExerciseException) {
            assertTrue(
                "Exception message should mention non_existent_exercise",
                e.message?.contains("non_existent_exercise") == true
            )
        }
    }

    @Test
    fun testCaseInsensitiveAndWhitespaceTolerance() {
        val configUpper = ExerciseRegistry.getConfig("PUSH_UP")
        assertEquals("push_up", configUpper.exerciseId)

        val configSpaced = ExerciseRegistry.getConfig("  push_up  ")
        assertEquals("push_up", configSpaced.exerciseId)
    }

    @Test
    fun testIsSupported() {
        assertTrue(ExerciseRegistry.isSupported("push_up"))
        assertTrue(ExerciseRegistry.isSupported("PUSH_UP"))
        assertFalse(ExerciseRegistry.isSupported("unknown_exercise"))
        assertFalse(ExerciseRegistry.isSupported(""))
    }
}
