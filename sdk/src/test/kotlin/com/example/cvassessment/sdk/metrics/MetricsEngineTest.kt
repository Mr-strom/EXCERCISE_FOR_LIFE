package com.example.cvassessment.sdk.metrics

import com.example.cvassessment.sdk.pose.PoseEstimationResult
import com.example.cvassessment.sdk.pose.PoseLandmark
import com.example.cvassessment.sdk.pose.PoseLandmarkType
import com.example.cvassessment.sdk.spec.ExerciseConfig
import com.example.cvassessment.sdk.statemachine.ExercisePhase
import com.example.cvassessment.sdk.statemachine.ExerciseState
import com.example.cvassessment.sdk.statemachine.RepBoundary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for Module 5: Metrics Engine.
 * Tests ROM%, TuT Factor, Confidence score, and R7 refusal enforcement.
 */
class MetricsEngineTest {

    private lateinit var metricsEngine: MetricsEngine

    @Before
    fun setUp() {
        metricsEngine = MetricsEngine(ExerciseConfig.PUSH_UP)
    }

    /**
     * Unit Test 1: Hand-calculate ROM for 3 known angle inputs (85°, 100°, 120°),
     * feed to metrics engine, assert output matches hand-calculated values.
     *
     * Hand calculations per METRICS_SPEC.md §4:
     * Formula: clamp((actual - starting) / (target - starting) * 100, 0, 100)
     * For Push-Up: starting = 160°, target = 90°, denominator = (90 - 160) = -70
     *
     * 1. 85°: (85 - 160) / -70 * 100 = -75 / -70 * 100 = 107.142857% -> clamped to 100.0%
     * 2. 100°: (100 - 160) / -70 * 100 = -60 / -70 * 100 = 85.714286% (85.7%)
     * 3. 120°: (120 - 160) / -70 * 100 = -40 / -70 * 100 = 57.142857% (57.1%)
     */
    @Test
    fun testRomHandCalculatedValues() {
        // 1. Angle 85°
        val rom85 = metricsEngine.calculateRomPercent(85.0f)
        val expected85 = 100.0f
        assertEquals("ROM for 85° should clamp to 100%", expected85, rom85, 0.01f)

        // 2. Angle 100°
        val rom100 = metricsEngine.calculateRomPercent(100.0f)
        val expected100 = 85.714286f
        assertEquals("ROM for 100° should be ~85.71%", expected100, rom100, 0.01f)

        // 3. Angle 120°
        val rom120 = metricsEngine.calculateRomPercent(120.0f)
        val expected120 = 57.142857f
        assertEquals("ROM for 120° should be ~57.14%", expected120, rom120, 0.01f)

        // Verify via computeRepMetrics
        val rep100 = RepBoundary(
            repIndex = 1,
            startTimestampMs = 1000L,
            bottomTimestampMs = 2500L,
            endTimestampMs = 4000L,
            durationMs = 3000L,
            minElbowAngle = 100.0f
        )
        val metrics = metricsEngine.computeRepMetrics(rep100)
        assertNotNull(metrics)
        assertEquals(85.714286f, metrics!!.romPercent, 0.01f)
    }

    /**
     * Unit Test 2: Hand-calculate TuT for 3 known durations (1.0s, 4.0s, 8.0s),
     * assert output matches.
     *
     * Hand calculations per METRICS_SPEC.md §5:
     * Formula: actual_duration_sec / tutBaseline_sec
     * For Push-Up: tutBaseline = 4.0s
     *
     * 1. 1.0s: 1.0 / 4.0 = 0.25 (rushing)
     * 2. 4.0s: 4.0 / 4.0 = 1.00 (exact baseline tempo)
     * 3. 8.0s: 8.0 / 4.0 = 2.00 (slow / controlled)
     */
    @Test
    fun testTutHandCalculatedValues() {
        val tut1 = metricsEngine.calculateTutFactor(1.0f)
        assertEquals(0.25f, tut1, 0.001f)

        val tut4 = metricsEngine.calculateTutFactor(4.0f)
        assertEquals(1.00f, tut4, 0.001f)

        val tut8 = metricsEngine.calculateTutFactor(8.0f)
        assertEquals(2.00f, tut8, 0.001f)

        // Verify with RepBoundary duration in ms
        val rep4s = RepBoundary(
            repIndex = 1,
            startTimestampMs = 1000L,
            bottomTimestampMs = 3000L,
            endTimestampMs = 5000L,
            durationMs = 4000L,
            minElbowAngle = 90.0f
        )
        val metrics4s = metricsEngine.computeRepMetrics(rep4s)
        assertNotNull(metrics4s)
        assertEquals(1.00f, metrics4s!!.tutFactor, 0.001f)
    }

    /**
     * Unit Test 3: Synthetic rep with known average visibility -> assert confidence
     * score is in expected range.
     *
     * Hand calculation per METRICS_SPEC.md §7:
     * confidence = 0.40 * visibility + 0.40 * phase_pattern + 0.20 * smoothness
     *
     * Given:
     * - landmark visibility = 0.85
     * - clean phase pattern match = 1.0
     * - trajectory smoothness = 0.90
     *
     * Expected:
     * (0.40 * 0.85) + (0.40 * 1.00) + (0.20 * 0.90) = 0.34 + 0.40 + 0.18 = 0.92
     */
    @Test
    fun testConfidenceScoreInExpectedRange() {
        val visibility = 0.85f
        val phaseMatch = 1.00f
        val smoothness = 0.90f

        val score = metricsEngine.calculateConfidence(visibility, phaseMatch, smoothness)
        val expected = 0.92f

        assertEquals(expected, score, 0.001f)
        assertTrue("Confidence must be in range [0.90..0.95]", score in 0.90f..0.95f)

        // Also test another set of inputs: visibility = 0.60, phaseMatch = 0.80, smoothness = 0.70
        // (0.40 * 0.60) + (0.40 * 0.80) + (0.20 * 0.70) = 0.24 + 0.32 + 0.14 = 0.70
        val lowerScore = metricsEngine.calculateConfidence(0.60f, 0.80f, 0.70f)
        assertEquals(0.70f, lowerScore, 0.001f)
    }

    /**
     * Unit Test 4: Edge cases: ROM exactly 0% and 100% clamp boundaries.
     */
    @Test
    fun testRomClampBoundaries() {
        // 0% Clamp boundaries (at or beyond 160° top lockout)
        val rom160 = metricsEngine.calculateRomPercent(160.0f)
        assertEquals("160° lockout should yield exactly 0% ROM", 0.0f, rom160, 0.001f)

        val rom170 = metricsEngine.calculateRomPercent(170.0f)
        assertEquals("170° over-lockout should clamp to 0% ROM", 0.0f, rom170, 0.001f)

        val rom180 = metricsEngine.calculateRomPercent(180.0f)
        assertEquals("180° straight arm should clamp to 0% ROM", 0.0f, rom180, 0.001f)

        // 100% Clamp boundaries (at or deeper than 90° bottom target)
        val rom90 = metricsEngine.calculateRomPercent(90.0f)
        assertEquals("90° exact bottom depth should yield exactly 100% ROM", 100.0f, rom90, 0.001f)

        val rom85 = metricsEngine.calculateRomPercent(85.0f)
        assertEquals("85° deep push-up should clamp to 100% ROM", 100.0f, rom85, 0.001f)

        val rom50 = metricsEngine.calculateRomPercent(50.0f)
        assertEquals("50° very deep push-up should clamp to 100% ROM", 100.0f, rom50, 0.001f)
    }

    /**
     * Unit Test 5: CRITICAL - verify metrics are NOT computed at all for a rep
     * where Visibility Gate marked visibility as insufficient.
     * Metrics must be null/unavailable, never forced to a number.
     */
    @Test
    fun testMetricsRefusedWhenVisibilityInsufficient() {
        val rep = RepBoundary(
            repIndex = 1,
            startTimestampMs = 1000L,
            bottomTimestampMs = 2500L,
            endTimestampMs = 4000L,
            durationMs = 3000L,
            minElbowAngle = 85.0f
        )

        // When visibility is marked insufficient, computeRepMetrics MUST return null
        val metricsWhenInsufficient = metricsEngine.computeRepMetrics(
            rep = rep,
            landmarkVisibility = 0.3f,
            isVisibilitySufficient = false
        )
        assertNull(
            "CRITICAL R7: RepMetrics must be null when visibility is insufficient",
            metricsWhenInsufficient
        )

        // Test frame-level processing when visibility is insufficient
        val exerciseState = ExerciseState(
            phase = ExercisePhase.TOP,
            currentElbowAngle = 85.0f,
            currentHipLineAngle = 180.0f,
            completeReps = listOf(rep),
            newlyCompletedRep = rep,
            isRepInProgress = false
        )
        val mockPoseResult = PoseEstimationResult(
            landmarks = listOf(
                PoseLandmark(PoseLandmarkType.LEFT_SHOULDER, "LEFT_SHOULDER", 0.5f, 0.2f, 0f, 0.2f),
                PoseLandmark(PoseLandmarkType.LEFT_ELBOW, "LEFT_ELBOW", 0.5f, 0.5f, 0f, 0.2f)
            ),
            timestampMs = 4000L,
            hasPose = true
        )

        val frameMetrics = metricsEngine.processFrame(
            exerciseState = exerciseState,
            poseResult = mockPoseResult,
            isVisibilitySufficient = false
        )

        // Verify metrics are strictly null, NEVER forced to a fallback number
        assertNull("romPercent MUST be null under insufficient visibility", frameMetrics.romPercent)
        assertNull("tutFactor MUST be null under insufficient visibility", frameMetrics.tutFactor)
        assertNull("instantRomPercent MUST be null under insufficient visibility", frameMetrics.instantRomPercent)
        assertNull("latestCompletedRepMetrics MUST be null under insufficient visibility", frameMetrics.latestCompletedRepMetrics)
        assertEquals(0.0f, frameMetrics.confidence, 0.001f)
        assertEquals(false, frameMetrics.isVisibilitySufficient)
    }
}
