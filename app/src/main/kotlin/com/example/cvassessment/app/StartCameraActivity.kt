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
import com.example.cvassessment.app.ui.SetupAnalysisEvaluator
import com.example.cvassessment.app.ui.StickmanIndicatorView
import com.example.cvassessment.sdk.pose.PoseEstimator
import com.example.cvassessment.sdk.pose.PoseLandmarkType
import com.example.cvassessment.sdk.spec.ExerciseRegistry
import kotlin.math.ceil

/**
 * Screen 2 — Start Camera with Guided Setup Analysis (Redesigned).
 *
 * User-friendly, non-intrusive setup flow:
 * 1. Default Camera: Front-facing camera default (with toggle to Rear).
 * 2. Visual Stickman Indicator: Intuitive body silhouette color-coded per tracking quality (no raw percentages).
 * 3. 7-Second Analysis Phase: Evaluates setup quality and averages confidences over 7 seconds.
 * 4. Single Actionable Message: Shows exactly ONE human-readable diagnosis (e.g. "Great! We can see you clearly"
 *    or "Move back — we can't see your full body").
 * 5. Reliable Start Exercise: Button ALWAYS enables after the 7s analysis ("Start Exercise" if good,
 *    or "Start Anyway" with a "Re-check" option if quality is borderline).
 */
class StartCameraActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_EXERCISE_ID = "EXTRA_EXERCISE_ID"
        const val EXTRA_EXERCISE_NAME = "EXTRA_EXERCISE_NAME"
        private const val TAG = "StartCameraActivity"
        private const val MODEL_ASSET = "pose_landmarker_full.task"
        const val ANALYSIS_DURATION_MS = 7000L
    }

    private lateinit var cameraCapturePipeline: CameraCapturePipeline
    private lateinit var poseEstimator: PoseEstimator
    private val setupAnalysisEvaluator = SetupAnalysisEvaluator()

    private lateinit var previewView: PreviewView
    private lateinit var stickmanView: StickmanIndicatorView

    private lateinit var tvExerciseTitle: TextView
    private lateinit var tvFramingGuidance: TextView
    private lateinit var btnSwitchCamera: Button

    private lateinit var llAnalyzingProgress: LinearLayout
    private lateinit var tvAnalyzingCountdown: TextView
    private lateinit var tvResultIcon: TextView
    private lateinit var tvStatusHeadline: TextView
    private lateinit var tvStatusTip: TextView

    private lateinit var btnReanalyze: Button
    private lateinit var btnStartExercise: Button

    private lateinit var panelCameraError: LinearLayout
    private lateinit var tvErrorMessage: TextView
    private lateinit var btnRetryCamera: Button
    private lateinit var btnOpenSettings: Button

    private var exerciseId: String = "push_up"
    private var exerciseName: String = "Push-Up"

    // 7-second setup analysis timing and state
    private var analysisStartTimeMs: Long = 0L
    private var isAnalysisComplete: Boolean = false
    private var lastEvaluationResult: SetupAnalysisEvaluator.SetupEvaluationResult? = null

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
        stickmanView = findViewById(R.id.stickmanView)

        tvExerciseTitle = findViewById(R.id.tvStartExerciseTitle)
        tvFramingGuidance = findViewById(R.id.tvFramingGuidance)
        btnSwitchCamera = findViewById(R.id.btnSwitchCamera)

        llAnalyzingProgress = findViewById(R.id.llAnalyzingProgress)
        tvAnalyzingCountdown = findViewById(R.id.tvAnalyzingCountdown)
        tvResultIcon = findViewById(R.id.tvResultIcon)
        tvStatusHeadline = findViewById(R.id.tvStatusHeadline)
        tvStatusTip = findViewById(R.id.tvStatusTip)

        btnReanalyze = findViewById(R.id.btnReanalyze)
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
            tvFramingGuidance.text = "Side view most accurate for elbow angle. Ensure whole body is visible."
        }

        // Initialize PoseEstimator
        poseEstimator = PoseEstimator(this, MODEL_ASSET)

        // Initialize CameraCapturePipeline with FRONT camera as default (requirement 1)
        cameraCapturePipeline = CameraCapturePipeline(this)
        cameraCapturePipeline.currentLensFacing = CameraSelector.LENS_FACING_FRONT
        updateSwitchButtonText()

        cameraCapturePipeline.setFrameCallback { frame, timestampMs ->
            processFrameForSetupAnalysis(frame, timestampMs)
        }

        btnSwitchCamera.setOnClickListener {
            cameraCapturePipeline.toggleCamera(this, previewView)
            updateSwitchButtonText()
            restartAnalysis()
        }

        btnReanalyze.setOnClickListener {
            restartAnalysis()
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

    private fun restartAnalysis() {
        analysisStartTimeMs = 0L
        isAnalysisComplete = false
        lastEvaluationResult = null
        setupAnalysisEvaluator.reset()
        stickmanView.startBreathingAnimation()

        runOnUiThread {
            llAnalyzingProgress.visibility = View.VISIBLE
            tvResultIcon.visibility = View.GONE
            btnReanalyze.visibility = View.GONE
            btnStartExercise.isEnabled = false
            btnStartExercise.text = "Analyzing (7s)..."
            tvStatusHeadline.text = "Stand still, we're analyzing your setup..."
            tvStatusTip.text = "Keep your full body visible in the frame"
        }
    }

    private fun processFrameForSetupAnalysis(frame: com.example.cvassessment.sdk.CameraFrame, timestampMs: Long) {
        val androidFrame = frame as? AndroidCameraFrame ?: return
        val bitmap = androidFrame.bitmap ?: return

        // 1. Detect body pose with BlazePose
        val poseResult = poseEstimator.detect(bitmap, timestampMs)
        val hasPose = poseResult.hasPose && poseResult.landmarks.isNotEmpty()
        val detectedLandmarks = poseResult.landmarks
        val landmarkMap = detectedLandmarks.associateBy { it.index }

        // Compute real-time limb scores for stickman visual feedback during analysis
        val leftArmScore = listOfNotNull(
            landmarkMap[PoseLandmarkType.LEFT_SHOULDER]?.visibility,
            landmarkMap[PoseLandmarkType.LEFT_ELBOW]?.visibility,
            landmarkMap[PoseLandmarkType.LEFT_WRIST]?.visibility
        ).let { if (it.isEmpty()) 0f else it.average().toFloat() }

        val rightArmScore = listOfNotNull(
            landmarkMap[PoseLandmarkType.RIGHT_SHOULDER]?.visibility,
            landmarkMap[PoseLandmarkType.RIGHT_ELBOW]?.visibility,
            landmarkMap[PoseLandmarkType.RIGHT_WRIST]?.visibility
        ).let { if (it.isEmpty()) 0f else it.average().toFloat() }

        val torsoScore = listOfNotNull(
            landmarkMap[PoseLandmarkType.LEFT_SHOULDER]?.visibility,
            landmarkMap[PoseLandmarkType.RIGHT_SHOULDER]?.visibility,
            landmarkMap[PoseLandmarkType.LEFT_HIP]?.visibility,
            landmarkMap[PoseLandmarkType.RIGHT_HIP]?.visibility
        ).let { if (it.isEmpty()) 0f else it.average().toFloat() }

        val leftLegScore = listOfNotNull(
            landmarkMap[PoseLandmarkType.LEFT_HIP]?.visibility,
            landmarkMap[PoseLandmarkType.LEFT_ANKLE]?.visibility
        ).let { if (it.isEmpty()) 0f else it.average().toFloat() }

        val rightLegScore = listOfNotNull(
            landmarkMap[PoseLandmarkType.RIGHT_HIP]?.visibility,
            landmarkMap[PoseLandmarkType.RIGHT_ANKLE]?.visibility
        ).let { if (it.isEmpty()) 0f else it.average().toFloat() }

        // 2. Manage 7-second setup analysis window
        if (analysisStartTimeMs == 0L) {
            analysisStartTimeMs = timestampMs
        }

        val elapsedMs = timestampMs - analysisStartTimeMs

        if (!isAnalysisComplete) {
            // Accumulate samples continuously during the 7 seconds
            setupAnalysisEvaluator.recordSample(detectedLandmarks, hasPose)

            if (elapsedMs < ANALYSIS_DURATION_MS) {
                val remainingSec = ceil((ANALYSIS_DURATION_MS - elapsedMs) / 1000.0).toInt().coerceAtLeast(1)

                runOnUiThread {
                    stickmanView.updateTrackingQuality(
                        hasPose = hasPose,
                        leftArmScore = leftArmScore,
                        rightArmScore = rightArmScore,
                        torsoScore = torsoScore,
                        leftLegScore = leftLegScore,
                        rightLegScore = rightLegScore
                    )
                    tvAnalyzingCountdown.text = "Analyzing setup... ${remainingSec}s"
                    btnStartExercise.text = "Analyzing (${remainingSec}s)..."
                    btnStartExercise.isEnabled = false
                }
            } else {
                // 7-second analysis window completed: compute final verdict
                isAnalysisComplete = true
                val result = setupAnalysisEvaluator.evaluate()
                lastEvaluationResult = result

                runOnUiThread {
                    stickmanView.stopBreathingAnimation()
                    stickmanView.updateTrackingQuality(
                        hasPose = hasPose,
                        leftArmScore = result.leftArmScore,
                        rightArmScore = result.rightArmScore,
                        torsoScore = result.torsoScore,
                        leftLegScore = result.leftLegScore,
                        rightLegScore = result.rightLegScore
                    )

                    llAnalyzingProgress.visibility = View.GONE
                    tvResultIcon.visibility = View.VISIBLE
                    tvStatusHeadline.text = result.headline
                    tvStatusTip.text = result.actionableTip

                    if (result.isGood) {
                        tvResultIcon.text = "✓"
                        tvResultIcon.setTextColor(Color.parseColor("#00E676"))
                        btnStartExercise.isEnabled = true
                        btnStartExercise.text = "START EXERCISE"
                        btnStartExercise.setBackgroundColor(Color.parseColor("#00E676"))
                        btnStartExercise.setTextColor(Color.parseColor("#000000"))
                        btnReanalyze.visibility = View.GONE
                    } else {
                        tvResultIcon.text = "⚠️"
                        tvResultIcon.setTextColor(Color.parseColor("#FFA726"))
                        btnStartExercise.isEnabled = true // Always interactable after analysis!
                        btnStartExercise.text = "Start Anyway"
                        btnStartExercise.setBackgroundColor(Color.parseColor("#EF6C00"))
                        btnStartExercise.setTextColor(Color.parseColor("#FFFFFF"))
                        btnReanalyze.visibility = View.VISIBLE
                    }
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
            cameraCapturePipeline.startCamera(this, previewView) {
                updateSwitchButtonText()
            }
            restartAnalysis()
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
            tvStatusHeadline.text = "Camera Unavailable"
            tvStatusTip.text = "Grant camera permission or retry to continue"
        }
    }

    private fun hideCameraError() {
        panelCameraError.visibility = View.GONE
    }

    private fun updateSwitchButtonText() {
        val nextLens = if (cameraCapturePipeline.currentLensFacing == CameraSelector.LENS_FACING_FRONT) {
            "Rear"
        } else {
            "Front"
        }
        btnSwitchCamera.text = "Switch to $nextLens"
    }

    override fun onDestroy() {
        super.onDestroy()
        poseEstimator.close()
        cameraCapturePipeline.stopCamera()
    }
}
