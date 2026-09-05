package com.example.cvassessment.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import com.example.cvassessment.app.camera.AndroidCameraFrame
import com.example.cvassessment.app.camera.CameraCapturePipeline
import com.example.cvassessment.app.ui.PoseOverlayView
import com.example.cvassessment.app.ui.PositionGuidanceEvaluator
import com.example.cvassessment.sdk.ExerciseAnalyzer
import com.example.cvassessment.sdk.ValidationStatus
import com.example.cvassessment.sdk.pose.PoseEstimator
import com.example.cvassessment.sdk.pose.PoseLandmarkType
import java.util.Locale

/**
 * Screen 3 — Perform Exercise / Live Analysis (per ANDROID_FLOW.md).
 *
 * Real-time SDK-driven computer vision assessment while the user exercises.
 * - Live camera preview with BlazePose skeleton overlay.
 * - Real-time rep counter updated per-frame from SDK FrameResult.
 * - Subtle ValidationStatus chip ("TRACKING" vs "INSUFFICIENT_VISIBILITY").
 * - Prominent on-screen banner when INSUFFICIENT_VISIBILITY triggers (R10.2).
 * - "Why: <Landmark> dropped" diagnostic explanation on visibility failure.
 * - Mini Landmark Visibility Panel at top-right with real-time green/yellow/red and red flash.
 * - Proactive Position Guidance at bottom ("Full body visible", "Move left", etc.).
 * - Spoken audio feedback cues via Android TextToSpeech for SDK FeedbackEvents.
 * - "End Session" button finalizes the session and transitions to Screen 4.
 */
class LiveAnalysisActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    companion object {
        const val EXTRA_EXERCISE_ID = "EXTRA_EXERCISE_ID"
        const val EXTRA_EXERCISE_NAME = "EXTRA_EXERCISE_NAME"
        const val EXTRA_LENS_FACING = "EXTRA_LENS_FACING"
        private const val TAG = "LiveAnalysisActivity"
        private const val MODEL_ASSET = "pose_landmarker_full.task"
        private const val VISIBILITY_AUDIO_COOLDOWN_MS = 5000L
    }

    private lateinit var cameraCapturePipeline: CameraCapturePipeline
    private lateinit var poseEstimator: PoseEstimator
    private lateinit var analyzer: ExerciseAnalyzer

    private lateinit var previewView: PreviewView
    private lateinit var poseOverlayView: PoseOverlayView
    private lateinit var tvExerciseName: TextView
    private lateinit var chipValidationStatus: TextView
    private lateinit var tvRepCounter: TextView
    private lateinit var tvInstantRom: TextView
    private lateinit var bannerInsufficientVisibility: LinearLayout
    private lateinit var tvInsufficientWhy: TextView
    private lateinit var tvLiveFeedback: TextView
    private lateinit var btnLiveFlipCamera: Button
    private lateinit var btnEndSession: Button

    // 1. Mini Landmark Visibility Panel UI
    private lateinit var llMiniLandmarkPanel: LinearLayout
    private lateinit var tvMiniPanelHeader: TextView
    private val miniLandmarkViews = mutableListOf<Pair<Int, Pair<String, TextView>>>()

    // 2. Position Guidance UI (subtle, bottom of screen)
    private lateinit var llPositionGuidance: LinearLayout
    private lateinit var tvPositionGuidance: TextView
    private lateinit var tvPositionGuidanceIcon: TextView

    private var tts: TextToSpeech? = null
    private var isTtsInitialized = false
    private var lastSpokenFeedback: String? = null
    private var lastVisibilityWarningTimestamp = 0L

    private var exerciseId: String = "push_up"
    private var exerciseName: String = "Push-Up"
    private var initialLensFacing: Int = CameraSelector.LENS_FACING_BACK

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_analysis)

        exerciseId = intent.getStringExtra(EXTRA_EXERCISE_ID) ?: "push_up"
        exerciseName = intent.getStringExtra(EXTRA_EXERCISE_NAME) ?: "Push-Up"
        initialLensFacing = intent.getIntExtra(EXTRA_LENS_FACING, CameraSelector.LENS_FACING_BACK)

        // Initialize view references
        previewView = findViewById(R.id.livePreviewView)
        poseOverlayView = findViewById(R.id.livePoseOverlay)
        tvExerciseName = findViewById(R.id.tvLiveExerciseName)
        chipValidationStatus = findViewById(R.id.chipValidationStatus)
        tvRepCounter = findViewById(R.id.tvRepCounter)
        tvInstantRom = findViewById(R.id.tvInstantRom)
        bannerInsufficientVisibility = findViewById(R.id.bannerInsufficientVisibility)
        tvInsufficientWhy = findViewById(R.id.tvInsufficientWhy)
        tvLiveFeedback = findViewById(R.id.tvLiveFeedback)
        btnLiveFlipCamera = findViewById(R.id.btnLiveFlipCamera)
        btnEndSession = findViewById(R.id.btnEndSession)

        // Mini Landmark Visibility Panel references
        llMiniLandmarkPanel = findViewById(R.id.llMiniLandmarkPanel)
        tvMiniPanelHeader = findViewById(R.id.tvMiniPanelHeader)

        miniLandmarkViews.clear()
        miniLandmarkViews.add(Pair(PoseLandmarkType.LEFT_SHOULDER, Pair("LEFT_SHOULDER", findViewById(R.id.tvMiniLmLeftShoulder))))
        miniLandmarkViews.add(Pair(PoseLandmarkType.RIGHT_SHOULDER, Pair("RIGHT_SHOULDER", findViewById(R.id.tvMiniLmRightShoulder))))
        miniLandmarkViews.add(Pair(PoseLandmarkType.LEFT_ELBOW, Pair("LEFT_ELBOW", findViewById(R.id.tvMiniLmLeftElbow))))
        miniLandmarkViews.add(Pair(PoseLandmarkType.RIGHT_ELBOW, Pair("RIGHT_ELBOW", findViewById(R.id.tvMiniLmRightElbow))))
        miniLandmarkViews.add(Pair(PoseLandmarkType.LEFT_WRIST, Pair("LEFT_WRIST", findViewById(R.id.tvMiniLmLeftWrist))))
        miniLandmarkViews.add(Pair(PoseLandmarkType.RIGHT_WRIST, Pair("RIGHT_WRIST", findViewById(R.id.tvMiniLmRightWrist))))
        miniLandmarkViews.add(Pair(PoseLandmarkType.LEFT_HIP, Pair("LEFT_HIP", findViewById(R.id.tvMiniLmLeftHip))))
        miniLandmarkViews.add(Pair(PoseLandmarkType.RIGHT_HIP, Pair("RIGHT_HIP", findViewById(R.id.tvMiniLmRightHip))))
        miniLandmarkViews.add(Pair(PoseLandmarkType.LEFT_ANKLE, Pair("LEFT_ANKLE", findViewById(R.id.tvMiniLmLeftAnkle))))
        miniLandmarkViews.add(Pair(PoseLandmarkType.RIGHT_ANKLE, Pair("RIGHT_ANKLE", findViewById(R.id.tvMiniLmRightAnkle))))

        // Position Guidance references
        llPositionGuidance = findViewById(R.id.llPositionGuidance)
        tvPositionGuidance = findViewById(R.id.tvPositionGuidance)
        tvPositionGuidanceIcon = findViewById(R.id.tvPositionGuidanceIcon)

        tvExerciseName.text = exerciseName

        // Initialize Android TextToSpeech for real-time audio guidance
        tts = TextToSpeech(this, this)

        // Initialize SDK analyzer
        analyzer = ExerciseAnalyzer(exerciseId = exerciseId, exerciseName = exerciseName)
        Log.i(TAG, "ExerciseAnalyzer initialized for $exerciseName")

        // Initialize PoseEstimator
        poseEstimator = PoseEstimator(this, MODEL_ASSET)
        analyzer.poseEstimator = poseEstimator

        // Initialize CameraCapturePipeline with requested lens facing
        cameraCapturePipeline = CameraCapturePipeline(this)
        cameraCapturePipeline.currentLensFacing = initialLensFacing
        cameraCapturePipeline.setFrameCallback { frame, timestampMs ->
            processLiveFrame(frame, timestampMs)
        }

        btnLiveFlipCamera.setOnClickListener {
            poseOverlayView.clear()
            cameraCapturePipeline.toggleCamera(this, previewView)
        }

        btnEndSession.setOnClickListener {
            finalizeSession()
        }

        // Start live camera stream
        cameraCapturePipeline.startCamera(this, previewView)
    }

    private fun processLiveFrame(frame: com.example.cvassessment.sdk.CameraFrame, timestampMs: Long) {
        val androidFrame = frame as? AndroidCameraFrame ?: return
        val bitmap = androidFrame.bitmap ?: return

        // 1. Extract 33 pose landmarks
        val poseResult = poseEstimator.detect(bitmap, timestampMs)

        // 2. Evaluate proactive position guidance & landmark diagnosis
        val guidanceResult = PositionGuidanceEvaluator.evaluate(
            landmarks = poseResult.landmarks,
            hasPose = poseResult.hasPose
        )

        // 3. Feed into full SDK analysis pipeline
        val frameResult = analyzer.analyzePose(poseResult)

        val isFront = (cameraCapturePipeline.currentLensFacing == CameraSelector.LENS_FACING_FRONT)

        // 4. Update UI on main thread
        runOnUiThread {
            // Update skeleton overlay
            if (poseResult.hasPose && poseResult.landmarks.isNotEmpty()) {
                poseOverlayView.updatePose(
                    detectedLandmarks = poseResult.landmarks,
                    frameWidth = frame.width,
                    frameHeight = frame.height,
                    isFront = isFront
                )
            } else {
                poseOverlayView.clear()
            }

            // 1. Update Mini Landmark Visibility Panel (top-right corner, small)
            val landmarkMap = poseResult.landmarks.associateBy { it.index }
            val isFlashTick = (SystemClock.elapsedRealtime() / 400) % 2L == 0L
            val anyBelow04 = guidanceResult.lowConfidenceIndices.isNotEmpty()

            miniLandmarkViews.forEach { (index, info) ->
                val vis = landmarkMap[index]?.visibility ?: 0.0f
                val pct = (vis * 100).toInt()
                val tv = info.second
                tv.text = "${info.first}: $pct%"

                // Color coding per specification:
                // GREEN if >= 0.60, YELLOW if 0.40..0.59, RED if < 0.40
                when {
                    vis >= 0.60f -> {
                        tv.setTextColor(Color.parseColor("#00E676")) // Bright green
                        tv.setBackgroundColor(Color.TRANSPARENT)
                    }
                    vis >= 0.40f -> {
                        tv.setTextColor(Color.parseColor("#FFD54F")) // Amber yellow
                        tv.setBackgroundColor(Color.TRANSPARENT)
                    }
                    else -> {
                        // Drops below 0.4: flash red
                        tv.setTextColor(Color.parseColor("#FF5252"))
                        if (isFlashTick) {
                            tv.setBackgroundColor(Color.parseColor("#80D32F2F"))
                        } else {
                            tv.setBackgroundColor(Color.TRANSPARENT)
                        }
                    }
                }
            }

            if (anyBelow04) {
                if (isFlashTick) {
                    tvMiniPanelHeader.text = "⚠️ LOW VISIBILITY"
                    tvMiniPanelHeader.setTextColor(Color.parseColor("#FF5252"))
                    llMiniLandmarkPanel.setBackgroundColor(Color.parseColor("#E63E0000"))
                } else {
                    tvMiniPanelHeader.text = "LANDMARKS"
                    tvMiniPanelHeader.setTextColor(Color.parseColor("#FF8A80"))
                    llMiniLandmarkPanel.setBackgroundColor(Color.parseColor("#CC0D0D0D"))
                }
            } else {
                tvMiniPanelHeader.text = "LANDMARKS"
                tvMiniPanelHeader.setTextColor(Color.parseColor("#888888"))
                llMiniLandmarkPanel.setBackgroundColor(Color.parseColor("#CC0D0D0D"))
            }

            // 2. Update Position Guidance Messages (subtle, bottom of screen)
            tvPositionGuidance.text = guidanceResult.guidanceMessage
            if (guidanceResult.isWarning) {
                tvPositionGuidance.setTextColor(Color.parseColor("#FFB74D"))
                tvPositionGuidanceIcon.text = "⚠️"
                tvPositionGuidanceIcon.setTextColor(Color.parseColor("#FFB74D"))
                llPositionGuidance.setBackgroundColor(Color.parseColor("#D92D1B00"))
            } else {
                tvPositionGuidance.setTextColor(Color.parseColor("#00E676"))
                tvPositionGuidanceIcon.text = "✓"
                tvPositionGuidanceIcon.setTextColor(Color.parseColor("#00E676"))
                llPositionGuidance.setBackgroundColor(Color.parseColor("#B3111111"))
            }

            // 3. Update ValidationStatus indicator & INSUFFICIENT_VISIBILITY Banner with "Why:"
            when (frameResult.status) {
                ValidationStatus.VALID -> {
                    chipValidationStatus.text = "TRACKING"
                    chipValidationStatus.setBackgroundColor(Color.parseColor("#4CAF50"))
                    bannerInsufficientVisibility.visibility = View.GONE
                }
                ValidationStatus.INSUFFICIENT_VISIBILITY -> {
                    chipValidationStatus.text = "INSUFFICIENT VISIBILITY"
                    chipValidationStatus.setBackgroundColor(Color.parseColor("#E53935"))
                    bannerInsufficientVisibility.visibility = View.VISIBLE
                    tvInsufficientWhy.text = guidanceResult.insufficientWhyMessage

                    // Throttled audible visibility reminder
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastVisibilityWarningTimestamp > VISIBILITY_AUDIO_COOLDOWN_MS) {
                        lastVisibilityWarningTimestamp = now
                        speakAudioFeedback("Can't see you clearly, adjust your position.")
                    }
                }
                ValidationStatus.INVALID -> {
                    chipValidationStatus.text = "INVALID"
                    chipValidationStatus.setBackgroundColor(Color.parseColor("#FF9800"))
                    bannerInsufficientVisibility.visibility = View.GONE
                }
            }

            // Update Real-time Rep Counter
            if (frameResult.status == ValidationStatus.INSUFFICIENT_VISIBILITY) {
                // Per R7 refusal rule: Do not force or display unreliable metrics
                tvRepCounter.text = "--"
                tvInstantRom.text = "ROM: Unavailable"
            } else {
                tvRepCounter.text = (frameResult.currentReps ?: 0).toString()
                val instantRom = frameResult.instantRomPercent
                tvInstantRom.text = if (instantRom != null) "ROM: ${instantRom.toInt()}%" else "ROM: --"
            }

            // Playback Form Rule Audio Feedback
            frameResult.activeFeedback?.let { feedback ->
                val msg = feedback.message
                if (msg.isNotEmpty() && msg != lastSpokenFeedback) {
                    lastSpokenFeedback = msg
                    tvLiveFeedback.text = msg
                    tvLiveFeedback.visibility = View.VISIBLE
                    speakAudioFeedback(msg)
                }
            }
        }
    }

    private fun speakAudioFeedback(text: String) {
        if (isTtsInitialized && tts != null) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "feedback_${System.currentTimeMillis()}")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            isTtsInitialized = (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED)
            Log.i(TAG, "TTS initialized successfully: $isTtsInitialized")
        } else {
            Log.w(TAG, "TTS initialization failed")
        }
    }

    private fun finalizeSession() {
        val sessionResult = analyzer.getSessionResult()
        SessionDataHolder.latestResult = sessionResult

        val intent = Intent(this, ResultsActivity::class.java).apply {
            putExtra(ResultsActivity.EXTRA_EXERCISE_NAME, exerciseName)
            putExtra(ResultsActivity.EXTRA_SESSION_RESULT, sessionResult)
        }
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.stop()
        tts?.shutdown()
        poseEstimator.close()
        cameraCapturePipeline.stopCamera()
    }
}
