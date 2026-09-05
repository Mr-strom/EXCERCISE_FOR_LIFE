package com.example.cvassessment.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.example.cvassessment.app.camera.CameraCapturePipeline
import com.example.cvassessment.sdk.ExerciseAnalyzer

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var cameraCapturePipeline: CameraCapturePipeline
    private lateinit var previewView: PreviewView
    private lateinit var statsTextView: TextView
    private lateinit var switchCameraButton: Button

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

        // Initialize SDK analyzer instance (proves clean integration)
        val analyzer = ExerciseAnalyzer(exerciseId = "push_up", exerciseName = "Push-Up")
        Log.i(TAG, "ExerciseAnalyzer initialized for: ${analyzer.exerciseName}")

        // Initialize Camera Capture Pipeline (Module 1)
        cameraCapturePipeline = CameraCapturePipeline(this)

        // Register frame callback that delivers raw frames + timestamps for SDK consumption
        cameraCapturePipeline.setFrameCallback { frame, timestampMs ->
            // Frame callback delivering raw frames + wall-clock timestamps
            // In Task 3, MediaPipe Pose Landmarker will consume this directly
        }

        setupUI()
        checkAndRequestCameraPermission()
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

        // 2. Real-time Frame Stats HUD Overlay
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
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(24, 20, 24, 20)
            text = "Camera: Initializing...\nFPS: -- | Frame count: 0"
        }
        rootLayout.addView(statsTextView)

        // 3. Camera Lens Toggle Button (Rear/Front)
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
                cameraCapturePipeline.toggleCamera(this@MainActivity, previewView)
                updateSwitchButtonText()
            }
        }
        rootLayout.addView(switchCameraButton)

        setContentView(rootLayout)

        // Attach listener to update HUD with observed frame rate and timestamps
        cameraCapturePipeline.onFrameStatsListener = { fps, count, deltaMs, isFront ->
            val lensName = if (isFront) "Front" else "Rear (Default)"
            statsTextView.text = "Camera: $lensName\nFPS: ${String.format("%.1f", fps)} | Frames: $count | Delta: ${deltaMs}ms"
        }
    }

    private fun updateSwitchButtonText() {
        val nextLens = if (cameraCapturePipeline.currentLensFacing == androidx.camera.core.CameraSelector.LENS_FACING_BACK) {
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
        cameraCapturePipeline.stopCamera()
    }
}
