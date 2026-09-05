package com.example.cvassessment.sdk

import com.example.cvassessment.sdk.form.FormRuleEngine
import com.example.cvassessment.sdk.metrics.MetricsEngine
import com.example.cvassessment.sdk.output.OutputGate
import com.example.cvassessment.sdk.statemachine.ExerciseStateMachine
import com.example.cvassessment.sdk.visibility.VisibilityGate
import kotlin.reflect.KVisibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Acceptance criteria unit tests for Module 7 OutputGate and public ExerciseAnalyzer SDK facade.
 */
class ExerciseAnalyzerTest {

    /**
     * Unit Test 1: Reproduce the Push-Up VALID example from SDK_CONTRACT.md from
     * synthetic input -> output must match field-by-field, including nullability.
     *
     * Expected JSON from SDK_CONTRACT.md:
     * {
     *   "status": "VALID",
     *   "confidence": 0.96,
     *   "completeReps": 12,
     *   "incompleteReps": 1,
     *   "holdDurationSec": null,
     *   "avgRepDurationSec": 2.1,
     *   "romPercent": 91,
     *   "tutFactor": 1.00,
     *   "formFactor": 0.75,
     *   "formErrors": [
     *     { "errorName": "hips_dropping", "confidence": 0.82, "repIndex": 7 }
     *   ],
     *   "feedbackEvents": [
     *     { "message": "Keep your hips up.", "timestampMs": 1720003345000, "relatedError": "hips_dropping" }
     *   ]
     * }
     */
    @Test
    fun testReproducePushUpValidWorkedExample() {
        val analyzer = ExerciseAnalyzer("push_up", "Push-Up")

        val result = analyzer.outputGate.buildSessionResult(
            status = ValidationStatus.VALID,
            confidence = 0.96f,
            completeReps = 12,
            incompleteReps = 1,
            holdDurationSec = null,
            avgRepDurationSec = 2.1f,
            romPercent = 91.0f,
            tutFactor = 1.00f,
            formFactor = 0.75f,
            formErrors = listOf(
                FormError(errorName = "hips_dropping", confidence = 0.82f, repIndex = 7, severity = 0.7f)
            ),
            feedbackEvents = listOf(
                FeedbackEvent(message = "Keep your hips up.", timestampMs = 1720003345000L, relatedError = "hips_dropping")
            )
        )

        // Field-by-field exact match with SDK_CONTRACT.md
        assertEquals(ValidationStatus.VALID, result.status)
        assertEquals(0.96f, result.confidence, 0.001f)
        assertEquals(12, result.completeReps)
        assertEquals(1, result.incompleteReps)
        assertNull("holdDurationSec must be null for dynamic exercises", result.holdDurationSec)
        assertEquals(2.1f, result.avgRepDurationSec!!, 0.01f)
        assertEquals(91.0f, result.romPercent!!, 0.01f)
        assertEquals(1.00f, result.tutFactor!!, 0.01f)
        assertEquals(0.75f, result.formFactor!!, 0.01f)

        assertEquals(1, result.formErrors.size)
        val error = result.formErrors.first()
        assertEquals("hips_dropping", error.errorName)
        assertEquals(0.82f, error.confidence, 0.01f)
        assertEquals(7, error.repIndex)

        assertEquals(1, result.feedbackEvents.size)
        val event = result.feedbackEvents.first()
        assertEquals("Keep your hips up.", event.message)
        assertEquals(1720003345000L, event.timestampMs)
        assertEquals("hips_dropping", event.relatedError)
    }

    /**
     * Unit Test 2: Reproduce the INSUFFICIENT_VISIBILITY example from SDK_CONTRACT.md
     * -> all metric fields must be null, confidence = 0.0.
     *
     * Expected JSON from SDK_CONTRACT.md:
     * {
     *   "status": "INSUFFICIENT_VISIBILITY",
     *   "confidence": 0.0,
     *   "completeReps": null,
     *   "incompleteReps": null,
     *   "holdDurationSec": null,
     *   "avgRepDurationSec": null,
     *   "romPercent": null,
     *   "tutFactor": null,
     *   "formFactor": null,
     *   "formErrors": [],
     *   "feedbackEvents": []
     * }
     */
    @Test
    fun testReproduceInsufficientVisibilityWorkedExample() {
        val analyzer = ExerciseAnalyzer("push_up", "Push-Up")

        // Build result under insufficient visibility status
        val result = analyzer.outputGate.buildSessionResult(
            status = ValidationStatus.INSUFFICIENT_VISIBILITY,
            confidence = 0.0f,
            completeReps = 10,
            incompleteReps = 2,
            holdDurationSec = 30.0f,
            avgRepDurationSec = 2.0f,
            romPercent = 85.0f,
            tutFactor = 1.0f,
            formFactor = 0.9f
        )

        assertEquals(ValidationStatus.INSUFFICIENT_VISIBILITY, result.status)
        assertEquals(0.0f, result.confidence, 0.001f)
        assertNull("completeReps must be stripped to null", result.completeReps)
        assertNull("incompleteReps must be stripped to null", result.incompleteReps)
        assertNull("holdDurationSec must be null", result.holdDurationSec)
        assertNull("avgRepDurationSec must be stripped to null", result.avgRepDurationSec)
        assertNull("romPercent must be stripped to null", result.romPercent)
        assertNull("tutFactor must be stripped to null", result.tutFactor)
        assertNull("formFactor must be stripped to null", result.formFactor)
        assertTrue("formErrors must be empty", result.formErrors.isEmpty())
        assertTrue("feedbackEvents must be empty", result.feedbackEvents.isEmpty())
    }

    /**
     * Unit Test 3: Attempt to construct ExerciseAnalyzer with unknown exerciseId
     * -> assert throws UnknownExerciseException.
     */
    @Test(expected = UnknownExerciseException::class)
    fun testUnknownExerciseIdThrowsException() {
        ExerciseAnalyzer("unknown_exercise_xyz", "Unknown")
    }

    /**
     * Unit Test 4: Verify that internal modules (VisibilityGate, ExerciseStateMachine,
     * MetricsEngine, FormRuleEngine, OutputGate) are NOT accessible from outside /sdk
     * (enforce visibility via Kotlin internal modifiers).
     */
    @Test
    fun testInternalModulesEncapsulation() {
        // Enforce Kotlin internal modifier on internal pipeline modules
        assertEquals(
            "VisibilityGate must be internal",
            KVisibility.INTERNAL,
            VisibilityGate::class.visibility
        )
        assertEquals(
            "ExerciseStateMachine must be internal",
            KVisibility.INTERNAL,
            ExerciseStateMachine::class.visibility
        )
        assertEquals(
            "MetricsEngine must be internal",
            KVisibility.INTERNAL,
            MetricsEngine::class.visibility
        )
        assertEquals(
            "FormRuleEngine must be internal",
            KVisibility.INTERNAL,
            FormRuleEngine::class.visibility
        )
        assertEquals(
            "OutputGate must be internal",
            KVisibility.INTERNAL,
            OutputGate::class.visibility
        )

        // ExerciseAnalyzer must be public as the sole facade entry point
        assertEquals(
            "ExerciseAnalyzer must be public",
            KVisibility.PUBLIC,
            ExerciseAnalyzer::class.visibility
        )
    }

    /**
     * Unit Test 5: Full end-to-end synthetic session: feed 10 frames representing
     * 2 clean reps, 1 incomplete rep, mixed form errors -> assert SessionResult
     * aggregates correctly (completeReps=2, incompleteReps=1, formErrors list contains expected errors).
     */
    @Test
    fun testFullEndToEndSyntheticSession() {
        val analyzer = ExerciseAnalyzer("push_up", "Push-Up")

        // 10-frame synthetic session:
        // Frame 1: Top position
        analyzer.analyzeSyntheticFrame(elbowAngle = 165f, hipLineAngle = 180f, timestampMs = 1000L)

        // Rep 1: Descent with hips dropping (150° < 165°)
        analyzer.analyzeSyntheticFrame(elbowAngle = 120f, hipLineAngle = 150f, timestampMs = 2000L)
        analyzer.analyzeSyntheticFrame(elbowAngle = 80f, hipLineAngle = 150f, timestampMs = 3000L)
        analyzer.analyzeSyntheticFrame(elbowAngle = 165f, hipLineAngle = 150f, timestampMs = 4000L) // Rep 1 complete!

        // Rep 2: Clean descent and depth with straight back (180°)
        analyzer.analyzeSyntheticFrame(elbowAngle = 120f, hipLineAngle = 180f, timestampMs = 5000L)
        analyzer.analyzeSyntheticFrame(elbowAngle = 85f, hipLineAngle = 180f, timestampMs = 6000L)
        analyzer.analyzeSyntheticFrame(elbowAngle = 165f, hipLineAngle = 180f, timestampMs = 7000L) // Rep 2 complete!

        // Attempt 3: Incomplete rep (shallow descent then premature reversal to top)
        analyzer.analyzeSyntheticFrame(elbowAngle = 130f, hipLineAngle = 180f, timestampMs = 8000L)
        analyzer.analyzeSyntheticFrame(elbowAngle = 145f, hipLineAngle = 180f, timestampMs = 8500L)
        analyzer.analyzeSyntheticFrame(elbowAngle = 165f, hipLineAngle = 180f, timestampMs = 9000L) // Incomplete rep!

        // Retrieve the compiled session result
        val sessionResult = analyzer.getSessionResult()
        assertNotNull(sessionResult)

        assertEquals(ValidationStatus.VALID, sessionResult.status)
        assertEquals(2, sessionResult.completeReps)
        assertEquals(1, sessionResult.incompleteReps)
        assertNull(sessionResult.holdDurationSec)

        // ROM and TuT must be computed
        assertNotNull("romPercent must not be null for valid session", sessionResult.romPercent)
        assertNotNull("tutFactor must not be null for valid session", sessionResult.tutFactor)
        assertNotNull("avgRepDurationSec must not be null for valid session", sessionResult.avgRepDurationSec)
        assertNotNull("formFactor must not be null for valid session", sessionResult.formFactor)

        // Form errors list must contain the hips_dropping errors from Rep 1
        assertTrue(
            "formErrors list must contain hips_dropping error",
            sessionResult.formErrors.any { it.errorName == "hips_dropping" }
        )
    }
}
