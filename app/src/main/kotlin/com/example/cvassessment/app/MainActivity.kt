package com.example.cvassessment.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.example.cvassessment.app.camera.AndroidCameraFrame
import com.example.cvassessment.app.camera.CameraCapturePipeline
import com.example.cvassessment.app.ui.PoseOverlayView
import com.example.cvassessment.sdk.ExerciseAnalyzer
import com.example.cvassessment.sdk.pose.PoseEstimator

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val MODEL_ASSET = "pose_landmarker_full.task"
    }

    private lateinit var cameraCapturePipeline: CameraCapturePipeline
    private lateinit var poseEstimator: PoseEstimator
    private lateinit var previewView: PreviewView
    private lateinit var poseOverlayView: PoseOverlayView
    private lateinit var statsTextView: TextView
    private lateinit var switchCameraButton: Button

    private var poseFrameCount = 0L
    private var lastPoseState = "NO PERSON DETECTED"

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.i(TAG, "Camera permission granted by user")
            startCameraPreview()
        } else {
            Log.w(TAG, "Camera permission denied by user")
            statsTextView.text = "Camera permission denied. Please grant permission in Settings."
            Toast.makeText(this, "Camera permission is required for live preview", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize SDK analyzer instance
        val analyzer = ExerciseAnalyzer(exerciseId = "push_up", exerciseName = "Push-Up")
        Log.i(TAG, "ExerciseAnalyzer initialized for: ${analyzer.exerciseName}")

        // Initialize Module 2 (Pose Estimation Layer) with local asset model
        poseEstimator = PoseEstimator(this, MODEL_ASSET)

        // Initialize Module 1 (Camera Capture Pipeline)
        cameraCapturePipeline = CameraCapturePipeline(this)

        // Wire camera frame callback directly into PoseEstimator
        cameraCapturePipeline.setFrameCallback { frame, timestampMs ->
            processPoseFrame(frame, timestampMs)
        }

        setupUI()
        checkAndRequestCameraPermission()
    }

    private fun processPoseFrame(frame: com.example.cvassessment.sdk.CameraFrame, timestampMs: Long) {
        val androidFrame = frame as? AndroidCameraFrame
        val bitmap = androidFrame?.bitmap ?: return

        val poseResult = poseEstimator.detect(bitmap, timestampMs)
        poseFrameCount++

        val isFront = (cameraCapturePipeline.currentLensFacing == CameraSelector.LENS_FACING_FRONT)

        if (poseResult.hasPose && poseResult.landmarks.isNotEmpty()) {
            val landmarks = poseResult.landmarks
            val avgVis = poseResult.getAverageVisibility()
            lastPoseState = "TRACKING (${landmarks.size} pts | avg vis ${String.format("%.2f", avgVis)})"

            // Log all 33 landmark visibility scores periodically
            if (poseFrameCount % 15L == 0L || poseFrameCount <= 3L) {
                val sb = StringBuilder("BlazePose 33 Landmarks Visibility (Frame #$poseFrameCount):\n")
                landmarks.forEach { lm ->
                    val status = if (lm.visibility < 0.5f) " [LOW_VIS]" else ""
                    sb.append(String.format("  [%02d] %-18s: vis=%.3f, pres=%.3f, (x=%.2f, y=%.2f, z=%.2f)%s\n",
                        lm.index, lm.name, lm.visibility, lm.presence, lm.x, lm.y, lm.z, status))
                }
                Log.d("PoseTracking", sb.toString())
            }

            // Update overlay on UI thread
            runOnUiThread {
                poseOverlayView.updatePose(
                    detectedLandmarks = landmarks,
                    frameWidth = frame.width,
                    frameHeight = frame.height,
                    isFront = isFront
                )
            }
        } else {
            lastPoseState = "NO PERSON DETECTED"
            runOnUiThread {
                poseOverlayView.clear()
            }
        }
    }

    private fun setupUI() {
        val rootLayout = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }

        // 1. Live Camera PreviewView
        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        rootLayout.addView(previewView)

        // 2. Visual Skeleton Overlay View on top of camera preview
        poseOverlayView = PoseOverlayView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        rootLayout.addView(poseOverlayView)

        // 3. Real-time Frame Stats HUD Overlay
        statsTextView = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP
                topMargin = 48
                leftMargin = 32
                rightMargin = 32
            }
            setBackgroundColor(Color.argb(180, 0, 0, 0))
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(24, 20, 24, 20)
            text = "Camera: Initializing...\nFPS: -- | Pose: Detecting..."
        }
        rootLayout.addView(statsTextView)

        // 4. Camera Lens Toggle Button (Rear/Front)
        switchCameraButton = Button(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = 64
            }
            text = "Switch to Front Camera"
            setOnClickListener {
                poseOverlayView.clear()
                cameraCapturePipeline.toggleCamera(this@MainActivity, previewView)
                updateSwitchButtonText()
            }
        }
        rootLayout.addView(switchCameraButton)

        setContentView(rootLayout)

        // Attach listener to update HUD with observed frame rate, timestamps, and pose status
        cameraCapturePipeline.onFrameStatsListener = { fps, count, deltaMs, isFront ->
            val lensName = if (isFront) "Front" else "Rear (Default)"
            statsTextView.text = "Camera: $lensName | FPS: ${String.format("%.1f", fps)} (${deltaMs}ms)\nPose: $lastPoseState | Frames: $count"
        }
    }

    private fun updateSwitchButtonText() {
        val nextLens = if (cameraCapturePipeline.currentLensFacing == CameraSelector.LENS_FACING_BACK) {
            "Front"
        } else {
            "Rear"
        }
        switchCameraButton.text = "Switch to $nextLens Camera"
    }

    private fun checkAndRequestCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                Log.i(TAG, "Camera permission already granted")
                startCameraPreview()
            }
            else -> {
                Log.i(TAG, "Requesting CAMERA permission")
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun startCameraPreview() {
        cameraCapturePipeline.startCamera(this, previewView) {
            updateSwitchButtonText()
            Log.i(TAG, "Camera preview started successfully")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        poseEstimator.close()
        cameraCapturePipeline.stopCamera()
    }
}
