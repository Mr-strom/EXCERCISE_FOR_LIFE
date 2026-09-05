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
import com.example.cvassessment.sdk.ExerciseAnalyzer
import com.example.cvassessment.sdk.ValidationStatus
import com.example.cvassessment.sdk.pose.PoseEstimator
import java.util.Locale

/**
 * Screen 3 — Perform Exercise / Live Analysis (per ANDROID_FLOW.md).
 *
 * Real-time SDK-driven computer vision assessment while the user exercises.
 * - Live camera preview with BlazePose skeleton overlay.
 * - Real-time rep counter updated per-frame from SDK FrameResult.
 * - Subtle ValidationStatus chip ("TRACKING" vs "INSUFFICIENT_VISIBILITY").
 * - Prominent on-screen banner when INSUFFICIENT_VISIBILITY triggers (R10.2).
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
    private lateinit var tvLiveFeedback: TextView
    private lateinit var btnLiveFlipCamera: Button
    private lateinit var btnEndSession: Button

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
        tvLiveFeedback = findViewById(R.id.tvLiveFeedback)
        btnLiveFlipCamera = findViewById(R.id.btnLiveFlipCamera)
        btnEndSession = findViewById(R.id.btnEndSession)

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

        // 2. Feed into full SDK analysis pipeline
        val frameResult = analyzer.analyzePose(poseResult)

        val isFront = (cameraCapturePipeline.currentLensFacing == CameraSelector.LENS_FACING_FRONT)

        // 3. Update UI on main thread
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

            // Update ValidationStatus indicator & banner
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
