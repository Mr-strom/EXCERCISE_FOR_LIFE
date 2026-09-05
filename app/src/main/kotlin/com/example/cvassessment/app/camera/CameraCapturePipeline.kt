package com.example.cvassessment.app.camera

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.cvassessment.sdk.CameraFrame
import com.example.cvassessment.sdk.FrameCallback
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Module 1 (Camera Input Layer) implementation using Android CameraX.
 * Captures frames from rear/front camera and delivers raw frames + timestamps
 * to the registered FrameCallback for SDK consumption.
 */
class CameraCapturePipeline(private val context: Context) {

    companion object {
        private const val TAG = "CameraCapturePipeline"
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // Default to rear camera per ARCHITECTURE.md and requirements
    var currentLensFacing: Int = CameraSelector.LENS_FACING_BACK

    private var frameCallback: FrameCallback? = null

    // Frame timing and rate tracking metrics
    private var frameCount: Long = 0L
    private var lastFrameTimeMs: Long = 0L
    private var smoothedFps: Float = 0f

    // Callback for UI updates (e.g. FPS display)
    var onFrameStatsListener: ((fps: Float, frameCount: Long, deltaMs: Long, isFront: Boolean) -> Unit)? = null

    /**
     * Register the frame callback that /sdk will consume.
     */
    fun setFrameCallback(callback: FrameCallback?) {
        this.frameCallback = callback
    }

    /**
     * Start the camera stream binding preview to the given PreviewView.
     */
    fun startCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView, onStarted: (() -> Unit)? = null) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases(lifecycleOwner, previewView)
            onStarted?.invoke()
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Toggle between rear (default) and front camera.
     */
    fun toggleCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        currentLensFacing = if (currentLensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        Log.i(TAG, "Toggling camera to lens facing: ${if (currentLensFacing == CameraSelector.LENS_FACING_BACK) "BACK" else "FRONT"}")
        bindCameraUseCases(lifecycleOwner, previewView)
    }

    private fun bindCameraUseCases(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val provider = cameraProvider ?: run {
            Log.e(TAG, "CameraProvider not initialized yet")
            return
        }

        // Unbind previous use cases before rebinding
        provider.unbindAll()

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(currentLensFacing)
            .build()

        // 1. Preview use case
        val preview = Preview.Builder()
            .build()
            .also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

        // 2. ImageAnalysis use case to feed raw frames to callback
        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
            val timestampMs = SystemClock.elapsedRealtime()
            val deltaMs = if (lastFrameTimeMs > 0) timestampMs - lastFrameTimeMs else 0
            lastFrameTimeMs = timestampMs
            frameCount++

            // Calculate rolling smoothed FPS
            if (deltaMs > 0) {
                val instantaneousFps = 1000f / deltaMs
                smoothedFps = if (smoothedFps == 0f) instantaneousFps else (0.9f * smoothedFps + 0.1f * instantaneousFps)
            }

            // Log frame delivery and timestamp to prove consistent rate
            if (frameCount % 15L == 0L || frameCount <= 5L) {
                Log.d(
                    TAG,
                    "Frame #$frameCount delivered: ${imageProxy.width}x${imageProxy.height}, rot=${imageProxy.imageInfo.rotationDegrees}°, timestampMs=$timestampMs, delta=${deltaMs}ms, rollingFps=${String.format("%.1f", smoothedFps)}"
                )
            }

            var rotatedBitmap: android.graphics.Bitmap? = null
            try {
                val originalBitmap = imageProxy.toBitmap()
                val matrix = android.graphics.Matrix().apply {
                    postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                    if (currentLensFacing == CameraSelector.LENS_FACING_FRONT) {
                        postScale(-1f, 1f)
                    }
                }
                rotatedBitmap = android.graphics.Bitmap.createBitmap(
                    originalBitmap,
                    0,
                    0,
                    originalBitmap.width,
                    originalBitmap.height,
                    matrix,
                    true
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error converting imageProxy to Bitmap", e)
            }

            val frame: CameraFrame = AndroidCameraFrame(
                width = rotatedBitmap?.width ?: imageProxy.width,
                height = rotatedBitmap?.height ?: imageProxy.height,
                rotationDegrees = imageProxy.imageInfo.rotationDegrees,
                timestampMs = timestampMs,
                bitmap = rotatedBitmap
            )

            try {
                // Deliver to SDK consumer
                frameCallback?.onFrame(frame, timestampMs)
            } catch (e: Exception) {
                Log.e(TAG, "Error in frame callback delivery", e)
            } finally {
                // Ensure imageProxy is always closed so CameraX can produce subsequent frames
                imageProxy.close()
            }

            // Notify UI listener on main thread if attached
            onFrameStatsListener?.let { listener ->
                val fps = smoothedFps
                val count = frameCount
                val isFront = (currentLensFacing == CameraSelector.LENS_FACING_FRONT)
                previewView.post {
                    listener(fps, count, deltaMs, isFront)
                }
            }
        }

        try {
            provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalysis
            )
            Log.i(TAG, "Camera bound successfully to lifecycle. Lens: ${if (currentLensFacing == CameraSelector.LENS_FACING_BACK) "BACK" else "FRONT"}")
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    /**
     * Stop and release resources when activity is destroyed.
     */
    fun stopCamera() {
        cameraProvider?.unbindAll()
        if (!analysisExecutor.isShutdown) {
            analysisExecutor.shutdown()
        }
    }
}
