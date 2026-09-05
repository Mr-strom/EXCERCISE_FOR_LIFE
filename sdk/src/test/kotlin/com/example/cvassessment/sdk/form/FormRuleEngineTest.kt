package com.example.cvassessment.sdk.form

import com.example.cvassessment.sdk.metrics.RepMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for Module 6: Form Rule Engine.
 * Tests detection, persistence rules, cooldown throttling, and priority selection.
 */
class FormRuleEngineTest {

    private lateinit var engine: FormRuleEngine

    @Before
    fun setUp() {
        engine = FormRuleEngine()
    }

    /**
     * Unit Test 1: Single-frame noise triggering an error for 1 frame only ->
     * assert NO feedback fires (persistence rule protects against this).
     */
    @Test
    fun testSingleFrameNoiseFiresNoFeedback() {
        // Frame 1 (t=100ms): Clean alignment (180°)
        val out1 = engine.evaluateFrame(
            elbowAngle = 160f,
            hipLineAngle = 180f,
            isRepInProgress = true,
            currentRepIndex = 1,
            timestampMs = 100L,
            confidence = 1.0f
        )
        assertTrue("Frame 1 should emit no feedback", out1.newFeedbackEvents.isEmpty())

        // Frame 2 (t=133ms): Sensor jitter/noise (150° < 165° for 1 frame only)
        val out2 = engine.evaluateFrame(
            elbowAngle = 150f,
            hipLineAngle = 150f,
            isRepInProgress = true,
            currentRepIndex = 1,
            timestampMs = 133L,
            confidence = 1.0f
        )
        // Error is logged for transparency, but NO audio feedback is emitted
        assertEquals(1, out2.activeErrors.size)
        assertEquals("hips_dropping", out2.activeErrors[0].errorName)
        assertTrue("Single-frame noise must NOT trigger audio feedback", out2.newFeedbackEvents.isEmpty())

        // Frame 3 (t=166ms): Clean alignment returns (180°)
        val out3 = engine.evaluateFrame(
            elbowAngle = 140f,
            hipLineAngle = 180f,
            isRepInProgress = true,
            currentRepIndex = 1,
            timestampMs = 166L,
            confidence = 1.0f
        )
        assertTrue(out3.activeErrors.isEmpty())
        assertTrue("No feedback should fire after noise recovery", out3.newFeedbackEvents.isEmpty())
        assertTrue("Total session feedback events must remain empty", engine.allFeedbackEvents.isEmpty())
    }

    /**
     * Unit Test 2: Sustained error condition (>=3 frames) -> feedback fires once,
     * then does NOT fire again for 4000ms even if condition persists (cooldown works).
     */
    @Test
    fun testSustainedErrorFiresOnceAndEnforcesCooldown() {
        // Frame 1 (t=100ms): Frame 1 of hips dropping (count=1)
        val out1 = engine.evaluateFrame(
            elbowAngle = 140f,
            hipLineAngle = 155f,
            isRepInProgress = true,
            currentRepIndex = 1,
            timestampMs = 100L
        )
        assertTrue("Frame 1 must not trigger feedback (count=1 < 3)", out1.newFeedbackEvents.isEmpty())

        // Frame 2 (t=200ms): Frame 2 of hips dropping (count=2)
        val out2 = engine.evaluateFrame(
            elbowAngle = 130f,
            hipLineAngle = 155f,
            isRepInProgress = true,
            currentRepIndex = 1,
            timestampMs = 200L
        )
        assertTrue("Frame 2 must not trigger feedback (count=2 < 3)", out2.newFeedbackEvents.isEmpty())

        // Frame 3 (t=300ms): Frame 3 of hips dropping (count=3 -> PERSISTENCE SATISFIED!)
        val out3 = engine.evaluateFrame(
            elbowAngle = 120f,
            hipLineAngle = 155f,
            isRepInProgress = true,
            currentRepIndex = 1,
            timestampMs = 300L
        )
        assertEquals("Frame 3 must trigger exactly 1 feedback event", 1, out3.newFeedbackEvents.size)
        val event = out3.newFeedbackEvents.first()
        assertEquals("Keep your hips up.", event.message)
        assertEquals("hips_dropping", event.relatedError)
        assertEquals(300L, event.timestampMs)

        // Frames 4..6: Condition persists at t=1000ms, t=2000ms, t=3500ms
        // Cooldown is 4000ms: expires at 300 + 4000 = 4300ms.
        for (time in listOf(1000L, 2000L, 3500L, 4200L)) {
            val cooldownOut = engine.evaluateFrame(
                elbowAngle = 120f,
                hipLineAngle = 155f,
                isRepInProgress = true,
                currentRepIndex = 1,
                timestampMs = time
            )
            assertTrue("Cooldown must suppress feedback at t=$time", cooldownOut.newFeedbackEvents.isEmpty())
        }

        // Frame at t=4400ms: Cooldown has now expired (4400 - 300 = 4100 >= 4000ms)
        val outAfterCooldown = engine.evaluateFrame(
            elbowAngle = 120f,
            hipLineAngle = 155f,
            isRepInProgress = true,
            currentRepIndex = 1,
            timestampMs = 4400L
        )
        assertEquals("Feedback should fire again after 4000ms cooldown", 1, outAfterCooldown.newFeedbackEvents.size)
        assertEquals("Keep your hips up.", outAfterCooldown.newFeedbackEvents.first().message)
        assertEquals(2, engine.allFeedbackEvents.size)
    }

    /**
     * Unit Test 3: Two errors active simultaneously -> assert only highest-severity
     * one triggers audio, but both appear in formErrors output.
     */
    @Test
    fun testTwoErrorsSimultaneousHighestSeverityWins() {
        // Setup sustained hips_dropping for 2 prior frames
        engine.evaluateFrame(120f, 150f, true, 1, 100L)
        engine.evaluateFrame(100f, 150f, true, 1, 200L)

        // On frame 3 (rep completion frame at t=300ms):
        // 1. hips_dropping is active (severity 0.7) and sustained >= 3 frames
        // 2. insufficient_depth is active (romPercent = 45% < 60%, severity 0.6)
        val repMetrics = RepMetrics(
            repIndex = 1,
            romPercent = 45.0f,
            tutFactor = 1.0f,
            confidence = 0.9f,
            durationSec = 3.0f,
            minElbowAngle = 128.0f,
            startTimestampMs = 0L,
            endTimestampMs = 300L
        )

        val out = engine.evaluateFrame(
            elbowAngle = 160f,
            hipLineAngle = 150f, // hips_dropping
            isRepInProgress = false,
            currentRepIndex = 1,
            timestampMs = 300L,
            completedRepMetrics = repMetrics
        )

        // Assert BOTH errors appear in activeErrors output
        assertEquals("Both errors must appear in formErrors output", 2, out.activeErrors.size)
        val errorNames = out.activeErrors.map { it.errorName }.toSet()
        assertTrue(errorNames.contains("hips_dropping"))
        assertTrue(errorNames.contains("insufficient_depth"))

        // Assert ONLY highest-severity error triggers audio feedback (0.7 > 0.6)
        assertEquals("Only 1 feedback event allowed per frame", 1, out.newFeedbackEvents.size)
        val feedback = out.newFeedbackEvents.first()
        assertEquals("hips_dropping", feedback.relatedError)
        assertEquals("Keep your hips up.", feedback.message)
    }

    /**
     * Unit Test 4: Verify hips_dropping detection (hip_line_angle < 165°) works
     * correctly over multiple reps.
     */
    @Test
    fun testHipsDroppingDetectionOverMultipleReps() {
        var time = 1000L

        // Rep 1: User sags hips (hip_line_angle = 150°)
        for (i in 1..4) {
            engine.evaluateFrame(120f, 150f, true, 1, time)
            time += 200L
        }

        // Rep 2: User corrects form, perfectly straight (hip_line_angle = 180°)
        for (i in 1..4) {
            engine.evaluateFrame(120f, 180f, true, 2, time)
            time += 200L
        }

        // Rep 3: User fatigues and hips sag again (hip_line_angle = 155°)
        for (i in 1..4) {
            engine.evaluateFrame(120f, 155f, true, 3, time)
            time += 200L
        }

        val allErrors = engine.allSessionErrors.filter { it.errorName == "hips_dropping" }
        assertTrue("Should have logged hips_dropping errors", allErrors.isNotEmpty())

        val repIndices = allErrors.mapNotNull { it.repIndex }.toSet()
        assertTrue("Rep 1 must have hips_dropping logged", repIndices.contains(1))
        assertFalse("Rep 2 must NOT have hips_dropping logged", repIndices.contains(2))
        assertTrue("Rep 3 must have hips_dropping logged", repIndices.contains(3))
    }

    /**
     * Unit Test 5: Verify insufficient_depth detection (romPercent < 60% at rep end)
     * works correctly.
     */
    @Test
    fun testInsufficientDepthDetection() {
        // Rep 1: Clean depth (romPercent = 85.7% >= 60%)
        val cleanRep = RepMetrics(
            repIndex = 1,
            romPercent = 85.7f,
            tutFactor = 1.0f,
            confidence = 0.95f,
            durationSec = 3.0f,
            minElbowAngle = 100.0f,
            startTimestampMs = 0L,
            endTimestampMs = 3000L
        )
        val cleanOut = engine.evaluateFrame(
            elbowAngle = 160f,
            hipLineAngle = 180f,
            isRepInProgress = false,
            currentRepIndex = 1,
            timestampMs = 3000L,
            completedRepMetrics = cleanRep
        )
        assertFalse("Clean rep must not trigger insufficient_depth", cleanOut.activeErrors.any { it.errorName == "insufficient_depth" })
        assertTrue(cleanOut.newFeedbackEvents.isEmpty())

        // Rep 2: Shallow rep (romPercent = 50.0% < 60%)
        val shallowRep = RepMetrics(
            repIndex = 2,
            romPercent = 50.0f,
            tutFactor = 1.0f,
            confidence = 0.95f,
            durationSec = 3.0f,
            minElbowAngle = 125.0f,
            startTimestampMs = 4000L,
            endTimestampMs = 7000L
        )
        val shallowOut = engine.evaluateFrame(
            elbowAngle = 160f,
            hipLineAngle = 180f,
            isRepInProgress = false,
            currentRepIndex = 2,
            timestampMs = 7000L,
            completedRepMetrics = shallowRep
        )
        assertTrue("Shallow rep must trigger insufficient_depth", shallowOut.activeErrors.any { it.errorName == "insufficient_depth" })
        assertEquals(1, shallowOut.newFeedbackEvents.size)
        val event = shallowOut.newFeedbackEvents.first()
        assertEquals("insufficient_depth", event.relatedError)
        assertEquals("Go lower.", event.message)
        assertEquals(0.6f, shallowOut.activeErrors.first { it.errorName == "insufficient_depth" }.severity, 0.01f)
    }

    /**
     * Extra Test: Confidence below 0.6 threshold prevents audio feedback.
     */
    @Test
    fun testConfidenceThresholdPreventsFeedback() {
        // 3 consecutive frames with low tracking confidence (0.4 < 0.6)
        for (i in 1..3) {
            val out = engine.evaluateFrame(
                elbowAngle = 140f,
                hipLineAngle = 150f,
                isRepInProgress = true,
                currentRepIndex = 1,
                timestampMs = i * 100L,
                confidence = 0.4f // low confidence
            )
            assertTrue("Low confidence must NOT trigger audio feedback", out.newFeedbackEvents.isEmpty())
        }
        // However, error is still logged in session errors for data audit
        assertEquals(3, engine.allSessionErrors.size)
    }
}
