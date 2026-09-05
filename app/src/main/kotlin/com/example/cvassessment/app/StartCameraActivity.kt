package com.example.cvassessment.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.example.cvassessment.app.camera.AndroidCameraFrame
import com.example.cvassessment.app.camera.CameraCapturePipeline
import com.example.cvassessment.sdk.pose.PoseEstimator
import com.example.cvassessment.sdk.spec.ExerciseRegistry

/**
 * Screen 2 — Start Camera (per ANDROID_FLOW.md).
 *
 * Confirms camera permission and user framing before live analysis begins.
 * - Live camera preview (rear camera default, toggle to front camera available).
 * - Displays on-screen framing guidance from EXERCISE_SPEC.md cameraNotes.
 * - "Start" button is disabled until camera permission is granted AND at least
 *   a minimal body pose is detected in the preview.
 * - Handles camera unavailable error and permission denial with retry / settings actions.
 */
class StartCameraActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_EXERCISE_ID = "EXTRA_EXERCISE_ID"
        const val EXTRA_EXERCISE_NAME = "EXTRA_EXERCISE_NAME"
        private const val TAG = "StartCameraActivity"
        private const val MODEL_ASSET = "pose_landmarker_full.task"
    }

    private lateinit var cameraCapturePipeline: CameraCapturePipeline
    private lateinit var poseEstimator: PoseEstimator

    private lateinit var previewView: PreviewView
    private lateinit var tvExerciseTitle: TextView
    private lateinit var tvFramingGuidance: TextView
    private lateinit var tvDetectionStatus: TextView
    private lateinit var btnSwitchCamera: Button
    private lateinit var btnStartExercise: Button
    private lateinit var panelCameraError: LinearLayout
    private lateinit var tvErrorMessage: TextView
    private lateinit var btnRetryCamera: Button
    private lateinit var btnOpenSettings: Button

    private var exerciseId: String = "push_up"
    private var exerciseName: String = "Push-Up"
    private var isPoseDetected = false

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.i(TAG, "Camera permission granted")
            hideCameraError()
            startCameraPreview()
        } else {
            Log.w(TAG, "Camera permission denied")
            showCameraError(
                message = "Camera permission is required to analyze exercises. Please grant permission in Settings.",
                showSettingsButton = true
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_start_camera)

        exerciseId = intent.getStringExtra(EXTRA_EXERCISE_ID) ?: "push_up"
        exerciseName = intent.getStringExtra(EXTRA_EXERCISE_NAME) ?: "Push-Up"

        // Initialize UI view references
        previewView = findViewById(R.id.startCameraPreview)
        tvExerciseTitle = findViewById(R.id.tvStartExerciseTitle)
        tvFramingGuidance = findViewById(R.id.tvFramingGuidance)
        tvDetectionStatus = findViewById(R.id.tvDetectionStatus)
        btnSwitchCamera = findViewById(R.id.btnSwitchCamera)
        btnStartExercise = findViewById(R.id.btnStartExercise)
        panelCameraError = findViewById(R.id.panelCameraError)
        tvErrorMessage = findViewById(R.id.tvErrorMessage)
        btnRetryCamera = findViewById(R.id.btnRetryCamera)
        btnOpenSettings = findViewById(R.id.btnOpenSettings)

        tvExerciseTitle.text = exerciseName

        // Load camera guidance notes from config registry
        try {
            val config = ExerciseRegistry.getConfig(exerciseId)
            tvFramingGuidance.text = config.cameraNotes
        } catch (e: Exception) {
            tvFramingGuidance.text = "Position camera to capture your entire body throughout the movement."
        }

        // Initialize PoseEstimator for pre-check
        poseEstimator = PoseEstimator(this, MODEL_ASSET)

        // Initialize CameraCapturePipeline
        cameraCapturePipeline = CameraCapturePipeline(this)
        cameraCapturePipeline.setFrameCallback { frame, timestampMs ->
            processFrameForPosePreCheck(frame, timestampMs)
        }

        btnSwitchCamera.setOnClickListener {
            cameraCapturePipeline.toggleCamera(this, previewView)
            updateSwitchButtonText()
        }

        btnStartExercise.setOnClickListener {
            val intent = Intent(this, LiveAnalysisActivity::class.java).apply {
                putExtra(LiveAnalysisActivity.EXTRA_EXERCISE_ID, exerciseId)
                putExtra(LiveAnalysisActivity.EXTRA_EXERCISE_NAME, exerciseName)
                putExtra(LiveAnalysisActivity.EXTRA_LENS_FACING, cameraCapturePipeline.currentLensFacing)
            }
            startActivity(intent)
            finish()
        }

        btnRetryCamera.setOnClickListener {
            hideCameraError()
            checkAndRequestCameraPermission()
        }

        btnOpenSettings.setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        }

        checkAndRequestCameraPermission()
    }

    private fun processFrameForPosePreCheck(frame: com.example.cvassessment.sdk.CameraFrame, timestampMs: Long) {
        val androidFrame = frame as? AndroidCameraFrame ?: return
        val bitmap = androidFrame.bitmap ?: return

        val poseResult = poseEstimator.detect(bitmap, timestampMs)
        val hasPose = poseResult.hasPose && poseResult.landmarks.isNotEmpty()

        if (hasPose != isPoseDetected) {
            isPoseDetected = hasPose
            runOnUiThread {
                if (hasPose) {
                    tvDetectionStatus.text = "✓ Person detected — ready to begin!"
                    tvDetectionStatus.setTextColor(Color.parseColor("#4CAF50"))
                    btnStartExercise.isEnabled = true
                } else {
                    tvDetectionStatus.text = "Waiting for person to enter frame..."
                    tvDetectionStatus.setTextColor(Color.parseColor("#FFB74D"))
                    btnStartExercise.isEnabled = false
                }
            }
        }
    }

    private fun checkAndRequestCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                hideCameraError()
                startCameraPreview()
            }
            else -> {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun startCameraPreview() {
        try {
            tvDetectionStatus.text = "Starting camera..."
            tvDetectionStatus.setTextColor(Color.parseColor("#FFB74D"))
            cameraCapturePipeline.startCamera(this, previewView) {
                updateSwitchButtonText()
                runOnUiThread {
                    tvDetectionStatus.text = "Waiting for person to enter frame..."
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start camera", e)
            showCameraError("Camera unavailable or currently in use by another app.", showSettingsButton = false)
        }
    }

    private fun showCameraError(message: String, showSettingsButton: Boolean) {
        runOnUiThread {
            panelCameraError.visibility = View.VISIBLE
            tvErrorMessage.text = message
            btnOpenSettings.visibility = if (showSettingsButton) View.VISIBLE else View.GONE
            btnStartExercise.isEnabled = false
            tvDetectionStatus.text = "Camera Unavailable"
            tvDetectionStatus.setTextColor(Color.parseColor("#E53935"))
        }
    }

    private fun hideCameraError() {
        panelCameraError.visibility = View.GONE
    }

    private fun updateSwitchButtonText() {
        val nextLens = if (cameraCapturePipeline.currentLensFacing == CameraSelector.LENS_FACING_BACK) {
            "Front"
        } else {
            "Rear"
        }
        btnSwitchCamera.text = "Switch to $nextLens"
    }

    override fun onDestroy() {
        super.onDestroy()
        poseEstimator.close()
        cameraCapturePipeline.stopCamera()
    }
}
