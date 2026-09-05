package com.example.cvassessment.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ExerciseAnalyzerTest {

    @Test
    fun testValidExerciseInitialization() {
        val analyzer = ExerciseAnalyzer("push_up", "Push-Up")
        val sessionResult = analyzer.getSessionResult()
        assertNotNull(sessionResult)
        assertEquals(ValidationStatus.VALID, sessionResult.status)
    }

    @Test(expected = UnknownExerciseException::class)
    fun testUnknownExerciseThrowsException() {
        ExerciseAnalyzer("unknown_exercise_xyz", "Unknown")
    }
}
