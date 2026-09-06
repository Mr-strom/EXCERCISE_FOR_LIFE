package com.example.cvassessment.app.camera

import com.example.cvassessment.sdk.CameraFrame
import com.example.cvassessment.sdk.FrameCallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraCapturePipelineTest {

    @Test
    fun testAndroidCameraFrameProperties() {
        val timestamp = 1720000000000L
        val frame = AndroidCameraFrame(
            width = 1280,
            height = 720,
            rotationDegrees = 90,
            timestampMs = timestamp
        )

        assertEquals(1280, frame.width)
        assertEquals(720, frame.height)
        assertEquals(90, frame.rotationDegrees)
        assertEquals(timestamp, frame.timestampMs)
    }

    @Test
    fun testFrameCallbackRateAndTimestampConsistency() {
        val receivedFrames = mutableListOf<CameraFrame>()
        val receivedTimestamps = mutableListOf<Long>()

        val callback = FrameCallback { frame, timestampMs ->
            receivedFrames.add(frame)
            receivedTimestamps.add(timestampMs)
        }

        // Simulate a 30 FPS frame sequence (interval ~33ms)
        val frameCount = 30
        val baseTimestamp = 1000L
        val intervalMs = 33L

        for (i in 0 until frameCount) {
            val ts = baseTimestamp + (i * intervalMs)
            val frame = AndroidCameraFrame(
                width = 640,
                height = 480,
                rotationDegrees = 0,
                timestampMs = ts
            )
            callback.onFrame(frame, ts)
        }

        assertEquals(frameCount, receivedFrames.size)
        assertEquals(frameCount, receivedTimestamps.size)

        // Verify monotonic timestamps and consistent deltas
        for (i in 1 until frameCount) {
            val delta = receivedTimestamps[i] - receivedTimestamps[i - 1]
            assertEquals(intervalMs, delta)
            assertTrue(receivedTimestamps[i] > receivedTimestamps[i - 1])
        }

        // Calculate observed FPS over 1 second of frames
        val totalDurationMs = receivedTimestamps.last() - receivedTimestamps.first()
        val observedFps = (frameCount - 1).toFloat() / (totalDurationMs / 1000f)
        assertTrue("Observed FPS should be ~30 FPS, got: $observedFps", observedFps in 29f..31f)
    }

    @Test
    fun testAndroidCameraFrameLowLightProperties() {
        val normalFrame = AndroidCameraFrame(
            width = 1280,
            height = 720,
            rotationDegrees = 0,
            timestampMs = 1000L
        )
        org.junit.Assert.assertFalse(normalFrame.isLowLight)
        assertEquals(128f, normalFrame.averageLuminance)

        val lowLightFrame = AndroidCameraFrame(
            width = 1280,
            height = 720,
            rotationDegrees = 0,
            timestampMs = 1033L,
            isLowLight = true,
            averageLuminance = 22.5f
        )
        assertTrue(lowLightFrame.isLowLight)
        assertEquals(22.5f, lowLightFrame.averageLuminance)
    }

    @Test
    fun testComputeLuminanceBrightLighting() {
        // Simulate a bright room where pixels have high RGB values (e.g. RGB = 180, 190, 200)
        val brightPixel = (0xFF shl 24) or (180 shl 16) or (190 shl 8) or 200
        val luminance = CameraCapturePipeline.computeLuminance(640, 480) { _, _ -> brightPixel }

        // Expected Y = 0.299*180 + 0.587*190 + 0.114*200 = 53.82 + 111.53 + 22.8 = 188.15
        assertTrue("Bright room luminance should be > 150, got: $luminance", luminance > 150f)
        org.junit.Assert.assertFalse(
            "Bright room should not trigger low light warning",
            luminance < CameraCapturePipeline.LOW_LIGHT_THRESHOLD_LUMINANCE
        )
    }

    @Test
    fun testComputeLuminanceDimLighting() {
        // Simulate a dim room where pixels have low RGB values (e.g. RGB = 18, 20, 22)
        val dimPixel = (0xFF shl 24) or (18 shl 16) or (20 shl 8) or 22
        val luminance = CameraCapturePipeline.computeLuminance(640, 480) { _, _ -> dimPixel }

        // Expected Y = 0.299*18 + 0.587*20 + 0.114*22 = 5.382 + 11.74 + 2.508 = 19.63
        assertTrue("Dim room luminance should be < 30, got: $luminance", luminance < 30f)
        assertTrue(
            "Dim room must trigger low light warning (< 35.0)",
            luminance < CameraCapturePipeline.LOW_LIGHT_THRESHOLD_LUMINANCE
        )
    }
}
