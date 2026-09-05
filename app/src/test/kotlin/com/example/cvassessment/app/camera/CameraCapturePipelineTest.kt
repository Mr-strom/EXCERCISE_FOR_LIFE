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
}
