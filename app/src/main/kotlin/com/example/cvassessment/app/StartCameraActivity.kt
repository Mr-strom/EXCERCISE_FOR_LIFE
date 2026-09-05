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
import com.example.cvassessment.app.ui.FramingGuideOverlayView
import com.example.cvassessment.app.ui.PoseOverlayView
import com.example.cvassessment.sdk.pose.PoseEstimator
import com.example.cvassessment.sdk.pose.PoseLandmark
import com.example.cvassessment.sdk.pose.PoseLandmarkType
import com.example.cvassessment.sdk.spec.ExerciseRegistry
import kotlin.math.ceil

/**
 * Screen 2 — Start Camera with Interactive Framing Guide (per ANDROID_FLOW.md).
 *
 * Teaches user where and how to position themselves BEFORE starting the exercise:
 * 1. Framing Box Overlay: Center target zone labeled "Keep full body here".
 * 2. Real-Time Landmark Indicators: Required joints list (shoulders, elbows, wrists, hips, ankles)
 *    color-coded (>=0.60 green, 0.40..0.59 yellow, <0.40 red) updating every frame.
 * 3. Guided Instructions: Non-invasive step banner at top progressing from Step 1 to Step 4.
 * 4. Start Button Logic: Only enabled when ALL required landmarks are >= 0.60 for 2+ seconds sustained,
 *    showing live countdown timer "Ready in: 2 seconds..." and changing text to "START EXERCISE (Ready!)".
 */
class StartCameraActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_EXERCISE_ID = "EXTRA_EXERCISE_ID"
        const val EXTRA_EXERCISE_NAME = "EXTRA_EXERCISE_NAME"
        private const val TAG = "StartCameraActivity"
        private const val MODEL_ASSET = "pose_landmarker_full.task"
        private const val REQUIRED_SUSTAINED_DURATION_MS = 2000L
        private const val VISIBILITY_THRESHOLD = 0.60f
    }

    private lateinit var cameraCapturePipeline: CameraCapturePipeline
    private lateinit var poseEstimator: PoseEstimator

    private lateinit var previewView: PreviewView
    private lateinit var framingGuideOverlay: FramingGuideOverlayView
    private lateinit var poseOverlayView: PoseOverlayView

    private lateinit var tvExerciseTitle: TextView
    private lateinit var tvFramingGuidance: TextView
    private lateinit var tvGuidedStep: TextView
    private lateinit var tvCountdownTimer: TextView
    private lateinit var btnSwitchCamera: Button
    private lateinit var btnStartExercise: Button

    private lateinit var panelCameraError: LinearLayout
    private lateinit var tvErrorMessage: TextView
    private lateinit var btnRetryCamera: Button
    private lateinit var btnOpenSettings: Button

    // Landmark list TextView references
    private lateinit var landmarkViews: Map<Int, Pair<String, TextView>>

    private var exerciseId: String = "push_up"
    private var exerciseName: String = "Push-Up"

    // Timing state for the 2+ seconds sustained stability requirement
    private var sustainedStartTimeMs: Long = 0L

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
        framingGuideOverlay = findViewById(R.id.framingGuideOverlay)
        poseOverlayView = findViewById(R.id.startPoseOverlay)

        tvExerciseTitle = findViewById(R.id.tvStartExerciseTitle)
        tvFramingGuidance = findViewById(R.id.tvFramingGuidance)
        tvGuidedStep = findViewById(R.id.tvGuidedStep)
        tvCountdownTimer = findViewById(R.id.tvCountdownTimer)
        btnSwitchCamera = findViewById(R.id.btnSwitchCamera)
        btnStartExercise = findViewById(R.id.btnStartExercise)

        panelCameraError = findViewById(R.id.panelCameraError)
        tvErrorMessage = findViewById(R.id.tvErrorMessage)
        btnRetryCamera = findViewById(R.id.btnRetryCamera)
        btnOpenSettings = findViewById(R.id.btnOpenSettings)

        tvExerciseTitle.text = exerciseName

        // Required landmarks for Push-Up: shoulders, elbows, wrists, hips, ankles (10 joints)
        landmarkViews = mapOf(
            PoseLandmarkType.LEFT_SHOULDER to Pair("LEFT_SHOULDER", findViewById(R.id.tvLmLeftShoulder)),
            PoseLandmarkType.RIGHT_SHOULDER to Pair("RIGHT_SHOULDER", findViewById(R.id.tvLmRightShoulder)),
            PoseLandmarkType.LEFT_ELBOW to Pair("LEFT_ELBOW", findViewById(R.id.tvLmLeftElbow)),
            PoseLandmarkType.RIGHT_ELBOW to Pair("RIGHT_ELBOW", findViewById(R.id.tvLmRightElbow)),
            PoseLandmarkType.LEFT_WRIST to Pair("LEFT_WRIST", findViewById(R.id.tvLmLeftWrist)),
            PoseLandmarkType.RIGHT_WRIST to Pair("RIGHT_WRIST", findViewById(R.id.tvLmRightWrist)),
            PoseLandmarkType.LEFT_HIP to Pair("LEFT_HIP", findViewById(R.id.tvLmLeftHip)),
            PoseLandmarkType.RIGHT_HIP to Pair("RIGHT_HIP", findViewById(R.id.tvLmRightHip)),
            PoseLandmarkType.LEFT_ANKLE to Pair("LEFT_ANKLE", findViewById(R.id.tvLmLeftAnkle)),
            PoseLandmarkType.RIGHT_ANKLE to Pair("RIGHT_ANKLE", findViewById(R.id.tvLmRightAnkle))
        )

        // Load camera guidance notes from config registry
        try {
            val config = ExerciseRegistry.getConfig(exerciseId)
            tvFramingGuidance.text = config.cameraNotes
        } catch (e: Exception) {
            tvFramingGuidance.text = "Side view most accurate for elbow angle. Ensure whole body is visible."
        }

        // Initialize PoseEstimator for framing guide pre-check
        poseEstimator = PoseEstimator(this, MODEL_ASSET)

        // Initialize CameraCapturePipeline
        cameraCapturePipeline = CameraCapturePipeline(this)
        cameraCapturePipeline.setFrameCallback { frame, timestampMs ->
            processFrameForFramingGuide(frame, timestampMs)
        }

        btnSwitchCamera.setOnClickListener {
            poseOverlayView.clear()
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

    private fun processFrameForFramingGuide(frame: com.example.cvassessment.sdk.CameraFrame, timestampMs: Long) {
        val androidFrame = frame as? AndroidCameraFrame ?: return
        val bitmap = androidFrame.bitmap ?: return

        // 1. Detect body pose with BlazePose
        val poseResult = poseEstimator.detect(bitmap, timestampMs)
        val hasPose = poseResult.hasPose && poseResult.landmarks.isNotEmpty()
        val detectedLandmarks = poseResult.landmarks
        val landmarkMap = detectedLandmarks.associateBy { it.index }

        val isFront = (cameraCapturePipeline.currentLensFacing == CameraSelector.LENS_FACING_FRONT)

        // 2. Evaluate visibility for all 10 required landmarks
        var allAboveThreshold = hasPose
        val landmarkStats = mutableListOf<Pair<String, Float>>()

        landmarkViews.forEach { (index, info) ->
            val lm = landmarkMap[index]
            val vis = lm?.visibility ?: 0.0f
            landmarkStats.add(Pair(info.first, vis))
            if (vis < VISIBILITY_THRESHOLD) {
                allAboveThreshold = false
            }
        }

        // 3. Determine Guided Step (Step 1 to Step 4)
        val visibleRequiredCount = landmarkStats.count { it.second >= 0.40f }
        val avgTorsoX = listOfNotNull(
            landmarkMap[PoseLandmarkType.LEFT_SHOULDER]?.x,
            landmarkMap[PoseLandmarkType.RIGHT_SHOULDER]?.x,
            landmarkMap[PoseLandmarkType.LEFT_HIP]?.x,
            landmarkMap[PoseLandmarkType.RIGHT_HIP]?.x
        ).average()

        val isCentered = if (avgTorsoX.isNaN()) false else (avgTorsoX in 0.35..0.65)

        // 4. Manage 2-second stability countdown
        val isStableReady: Boolean
        val countdownMessage: String
        val currentStepText: String

        if (!hasPose || visibleRequiredCount < 6) {
            // User is either absent or too close / partially framed
            sustainedStartTimeMs = 0L
            isStableReady = false
            currentStepText = "Step 1: Stand back — we need to see your full body"
            countdownMessage = "Position full body inside the box..."
        } else if (!isCentered) {
            // User is detected but off to the side
            sustainedStartTimeMs = 0L
            isStableReady = false
            currentStepText = "Step 2: Move to center of frame"
            countdownMessage = "Move to center of frame..."
        } else if (!allAboveThreshold) {
            // User is centered, but joints are occluded or below 0.60
            sustainedStartTimeMs = 0L
            isStableReady = false
            currentStepText = "Step 3: Stand still — checking pose quality"
            countdownMessage = "Ensure arms and legs are not blocked..."
        } else {
            // All 10 required landmarks are >= 0.60 and user is centered
            if (sustainedStartTimeMs == 0L) {
                sustainedStartTimeMs = timestampMs
            }
            val elapsedMs = timestampMs - sustainedStartTimeMs

            if (elapsedMs < REQUIRED_SUSTAINED_DURATION_MS) {
                val remainingSec = ceil((REQUIRED_SUSTAINED_DURATION_MS - elapsedMs) / 1000.0).toInt()
                isStableReady = false
                currentStepText = "Step 3: Stand still — checking pose quality"
                countdownMessage = "Ready in: $remainingSec seconds..."
            } else {
                isStableReady = true
                currentStepText = "Step 4: Perfect! Tap START EXERCISE"
                countdownMessage = "✓ Ready to begin!"
            }
        }

        // 5. Update UI on Main Thread
        runOnUiThread {
            // Draw skeleton overlay
            if (hasPose) {
                poseOverlayView.updatePose(
                    detectedLandmarks = detectedLandmarks,
                    frameWidth = frame.width,
                    frameHeight = frame.height,
                    isFront = isFront
                )
            } else {
                poseOverlayView.clear()
            }

            // Update Step 1 Framing box satisfied status (turns green when stable)
            framingGuideOverlay.setTargetSatisfied(isStableReady)

            // Update Step 3 Guided Instructions text
            tvGuidedStep.text = currentStepText

            // Update Step 2 Landmark Indicators list (Name + Confidence % + Color)
            landmarkViews.forEach { (index, info) ->
                val vis = landmarkMap[index]?.visibility ?: 0.0f
                val pct = (vis * 100).toInt()
                val tv = info.second

                tv.text = "${info.first}: $pct%"

                // Color coding per specification:
                // GREEN if >= 0.60, YELLOW if 0.40..0.59, RED if < 0.40
                when {
                    vis >= 0.60f -> tv.setTextColor(Color.parseColor("#00E676")) // Bright green
                    vis >= 0.40f -> tv.setTextColor(Color.parseColor("#FFD54F")) // Amber yellow
                    else -> tv.setTextColor(Color.parseColor("#FF5252"))         // Red
                }
            }

            // Update Step 4 Button & Countdown indicator
            tvCountdownTimer.text = countdownMessage

            if (isStableReady) {
                tvCountdownTimer.setTextColor(Color.parseColor("#00E676"))
                btnStartExercise.isEnabled = true
                btnStartExercise.text = "START EXERCISE (Ready!)"
                btnStartExercise.setTextColor(Color.parseColor("#00E676"))
            } else {
                tvCountdownTimer.setTextColor(Color.parseColor("#FFB74D"))
                btnStartExercise.isEnabled = false
                btnStartExercise.text = "Start Exercise"
                btnStartExercise.setTextColor(Color.parseColor("#AAAAAA"))
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
            tvGuidedStep.text = "Step 1: Stand back — we need to see your full body"
            cameraCapturePipeline.startCamera(this, previewView) {
                updateSwitchButtonText()
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
            tvGuidedStep.text = "Camera Unavailable"
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
