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
        const val LOW_LIGHT_THRESHOLD_LUMINANCE = 35.0f

        /**
         * Computes average perceptual luminance (0..255) of a Bitmap
         * using ITU-R BT.601 standard weights: Y = 0.299*R + 0.587*G + 0.114*B.
         */
        fun computeAverageLuminance(bitmap: android.graphics.Bitmap): Float {
            return computeLuminance(bitmap.width, bitmap.height) { x, y -> bitmap.getPixel(x, y) }
        }

        /**
         * Computes perceptual luminance given dimensions and a pixel provider lambda.
         * Testable on standard JVM without requiring native Android Bitmap runtime.
         */
        fun computeLuminance(width: Int, height: Int, getPixel: (x: Int, y: Int) -> Int): Float {
            var totalLuminance = 0.0
            var count = 0
            val stepX = (width / 16).coerceAtLeast(1)
            val stepY = (height / 16).coerceAtLeast(1)
            for (y in 0 until height step stepY) {
                for (x in 0 until width step stepX) {
                    val pixel = getPixel(x, y)
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    val lum = 0.299f * r + 0.587f * g + 0.114f * b
                    totalLuminance += lum
                    count++
                }
            }
            return if (count > 0) (totalLuminance / count).toFloat() else 128f
        }
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

    // Low light tracking metrics
    private var currentLuminance: Float = 128f
    private var currentIsLowLight: Boolean = false

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
        // Enforce COMPATIBLE mode (TextureView) to eliminate black preview screens
        // caused by SurfaceView surface detachment and layout-timing issues
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            // Ensure previewView is attached and laid out before binding use cases
            previewView.post {
                bindCameraUseCases(lifecycleOwner, previewView)
                onStarted?.invoke()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Toggle between rear (default) and front camera.
     */
    fun toggleCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
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

        // Enforce COMPATIBLE mode (TextureView) for reliable preview rendering
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE

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

            // Track frame luminance to detect low light
            if (rotatedBitmap != null && (frameCount % 10L == 0L || frameCount <= 3L)) {
                try {
                    currentLuminance = computeAverageLuminance(rotatedBitmap)
                    currentIsLowLight = currentLuminance < LOW_LIGHT_THRESHOLD_LUMINANCE
                } catch (e: Exception) {
                    Log.e(TAG, "Error computing luminance", e)
                }
            }

            val frame: CameraFrame = AndroidCameraFrame(
                width = rotatedBitmap?.width ?: imageProxy.width,
                height = rotatedBitmap?.height ?: imageProxy.height,
                rotationDegrees = imageProxy.imageInfo.rotationDegrees,
                timestampMs = timestampMs,
                bitmap = rotatedBitmap,
                isLowLight = currentIsLowLight,
                averageLuminance = currentLuminance
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
