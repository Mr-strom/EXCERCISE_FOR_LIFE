package com.example.cvassessment.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.media.MediaPlayer
import android.os.Build
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
import com.example.cvassessment.app.ui.AudioFeedbackController
import com.example.cvassessment.app.ui.FeedbackAudioCatalog
import com.example.cvassessment.app.ui.PoseOverlayView
import com.example.cvassessment.app.ui.PositionGuidanceEvaluator
import com.example.cvassessment.app.ui.TtsFeedbackController
import com.example.cvassessment.sdk.ExerciseAnalyzer
import com.example.cvassessment.sdk.ValidationStatus
import com.example.cvassessment.sdk.pose.PoseEstimator
import java.util.Locale

/**
 * Screen 3 — Perform Exercise / Live Analysis (per ANDROID_FLOW.md).
 *
 * Real-time SDK-driven computer vision assessment while the user exercises.
 * - Live camera preview with BlazePose skeleton overlay.
 * - Large, clear Rep counter.
 * - Simple status chip: "Tracking well" (green) or "Adjust position" (red).
 * - Completed rep metrics (ROM / TuT / Form) displayed only when a rep completes.
 * - Simplified INSUFFICIENT_VISIBILITY messaging: single actionable line matching Screen 2.
 * - Pre-recorded audio clip playback via Android MediaPlayer with seamless TextToSpeech fallback.
 * - "End Session" button finalizes the session and transitions to Screen 4.
 */
class LiveAnalysisActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    companion object {
        const val EXTRA_EXERCISE_ID = "EXTRA_EXERCISE_ID"
        const val EXTRA_EXERCISE_NAME = "EXTRA_EXERCISE_NAME"
        const val EXTRA_LENS_FACING = "EXTRA_LENS_FACING"
        const val EXTRA_SIMULATE_FORM_ERROR = "EXTRA_SIMULATE_FORM_ERROR"
        const val ACTION_TEST_FORM_ERROR = "com.example.cvassessment.TEST_FORM_ERROR"
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
    private lateinit var tvCompletedRepMetrics: TextView
    private lateinit var bannerInsufficientVisibility: LinearLayout
    private lateinit var tvInsufficientMessage: TextView
    private lateinit var tvLiveFeedback: TextView
    private lateinit var btnLiveFlipCamera: Button
    private lateinit var btnEndSession: Button

    // Position Guidance UI (subtle, bottom of screen)
    private lateinit var llPositionGuidance: LinearLayout
    private lateinit var tvPositionGuidance: TextView
    private lateinit var tvPositionGuidanceIcon: TextView

    private var mediaPlayer: MediaPlayer? = null
    internal lateinit var audioFeedbackController: AudioFeedbackController
    private var tts: TextToSpeech? = null
    internal lateinit var ttsController: TtsFeedbackController
    private var lastSpokenFeedback: String? = null
    private var lastVisibilityWarningTimestamp = 0L
    private var lastCompletedRepCount: Int = 0

    private var exerciseId: String = "push_up"
    private var exerciseName: String = "Push-Up"
    private var initialLensFacing: Int = CameraSelector.LENS_FACING_BACK

    private val testReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_TEST_FORM_ERROR) {
                val error = intent.getStringExtra("error") ?: "hips_dropping"
                triggerFormErrorForTesting(error)
            }
        }
    }

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
        tvCompletedRepMetrics = findViewById(R.id.tvCompletedRepMetrics)
        bannerInsufficientVisibility = findViewById(R.id.bannerInsufficientVisibility)
        tvInsufficientMessage = findViewById(R.id.tvInsufficientMessage)
        tvLiveFeedback = findViewById(R.id.tvLiveFeedback)
        btnLiveFlipCamera = findViewById(R.id.btnLiveFlipCamera)
        btnEndSession = findViewById(R.id.btnEndSession)

        // Position Guidance references
        llPositionGuidance = findViewById(R.id.llPositionGuidance)
        tvPositionGuidance = findViewById(R.id.tvPositionGuidance)
        tvPositionGuidanceIcon = findViewById(R.id.tvPositionGuidanceIcon)

        tvExerciseName.text = exerciseName

        // Long press on exercise title allows QA tester to manually trigger hips_dropping form error
        tvExerciseName.setOnLongClickListener {
            triggerFormErrorForTesting("hips_dropping")
            true
        }

        // Register QA test broadcast receiver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(testReceiver, IntentFilter(ACTION_TEST_FORM_ERROR), RECEIVER_EXPORTED)
        } else {
            registerReceiver(testReceiver, IntentFilter(ACTION_TEST_FORM_ERROR))
        }

        // Initialize Android TextToSpeech for fallback
        ttsController = TtsFeedbackController(
            speakDelegate = { text ->
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "feedback_${System.currentTimeMillis()}") ?: TextToSpeech.ERROR
            },
            setLanguageDelegate = { locale ->
                tts?.setLanguage(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED
            },
            logInfo = { msg -> Log.i(TAG, msg) },
            logWarn = { msg -> Log.w(TAG, msg) },
            logError = { msg -> Log.e(TAG, msg) }
        )
        tts = TextToSpeech(this, this)

        // Initialize AudioFeedbackController with MediaPlayer and TTS fallback
        audioFeedbackController = AudioFeedbackController(
            playClipDelegate = { resourceName ->
                playPreRecordedClip(resourceName)
            },
            ttsFallbackDelegate = { text ->
                ttsController.speak(text)
            },
            logInfo = { msg -> Log.i(TAG, msg) },
            logWarn = { msg -> Log.w(TAG, msg) },
            logError = { msg -> Log.e(TAG, msg) }
        )

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

        // Check if simulation extra was passed
        if (intent.getBooleanExtra(EXTRA_SIMULATE_FORM_ERROR, false)) {
            previewView.postDelayed({
                triggerFormErrorForTesting("hips_dropping")
            }, 1000L)
        }

        // Enable debug logging for Squat if active
        if (exerciseId == "squat") {
            analyzer.setSquatDebugLogging(true) { msg ->
                Log.d("SquatStateMachine", msg)
            }
        }
        // Debug logging for Lunge if explicitly requested via intent
        if (exerciseId == "lunge" && intent.getBooleanExtra("EXTRA_ENABLE_DEBUG_LOGGING", false)) {
            analyzer.setLungeDebugLogging(true) { msg ->
                Log.d("LungeDebug", msg)
            }
        }

        // Start live camera stream
        cameraCapturePipeline.startCamera(this, previewView)
    }

    private fun processLiveFrame(frame: com.example.cvassessment.sdk.CameraFrame, timestampMs: Long) {
        val androidFrame = frame as? AndroidCameraFrame ?: return
        val bitmap = androidFrame.bitmap ?: return

        // 1. Extract 33 pose landmarks
        val poseResult = poseEstimator.detect(bitmap, timestampMs)

        // 2. Evaluate proactive position guidance
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

            // 1. Position Guidance Messages (subtle, bottom of screen)
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

            // 2. Status Chip: "Tracking well" (green) or "Adjust position" (red)
            val isTrackingWell = (frameResult.status == ValidationStatus.VALID && !guidanceResult.isWarning)
            if (isTrackingWell) {
                chipValidationStatus.text = "Tracking well"
                chipValidationStatus.setBackgroundColor(Color.parseColor("#4CAF50"))
            } else {
                chipValidationStatus.text = "Adjust position"
                chipValidationStatus.setBackgroundColor(Color.parseColor("#E53935"))
            }

            // 3. INSUFFICIENT_VISIBILITY Banner: brief, non-alarming, single actionable line
            if (frameResult.status == ValidationStatus.INSUFFICIENT_VISIBILITY) {
                bannerInsufficientVisibility.visibility = View.VISIBLE
                tvInsufficientMessage.text = guidanceResult.actionableInsufficientMessage

                // Throttled audible visibility reminder
                val now = SystemClock.elapsedRealtime()
                if (now - lastVisibilityWarningTimestamp > VISIBILITY_AUDIO_COOLDOWN_MS) {
                    lastVisibilityWarningTimestamp = now
                    playAudioFeedback(FeedbackAudioCatalog.CLIP_CANT_SEE_YOU, guidanceResult.actionableInsufficientMessage)
                }
            } else {
                bannerInsufficientVisibility.visibility = View.GONE
            }

            // 4. Rep Counter (large, clear) & Completed Rep Metrics (ROM / TuT / Form upon completion)
            val isVisibilityInsufficient = (frameResult.status == ValidationStatus.INSUFFICIENT_VISIBILITY)
            val isVisibilityWarningActive = (isVisibilityInsufficient || guidanceResult.isWarning)

            if (isVisibilityInsufficient) {
                // Per R7 refusal rule: Do not force or display unreliable metrics
                tvRepCounter.text = "--"
            } else {
                val currentReps = frameResult.currentReps ?: 0
                tvRepCounter.text = currentReps.toString()

                // When a rep completes under valid visibility, update ROM/TuT/Form numbers
                if (currentReps > lastCompletedRepCount && !isVisibilityWarningActive) {
                    lastCompletedRepCount = currentReps
                    val latestRep = analyzer.latestCompletedRepMetrics
                    if (latestRep != null) {
                        val formScore = analyzer.getRepFormScore(currentReps)
                        val tutStr = String.format(Locale.US, "%.1f", latestRep.tutFactor)
                        tvCompletedRepMetrics.text = "Rep $currentReps: ROM ${latestRep.romPercent.toInt()}% • TuT ${tutStr}x • Form $formScore%"
                    }
                }
            }

            // D13: Enforce R7 UI metric freezing: Hide completed rep metrics during active visibility failure or guidance warning
            if (isVisibilityWarningActive) {
                tvCompletedRepMetrics.visibility = View.GONE
            } else if (lastCompletedRepCount > 0 && tvCompletedRepMetrics.text.isNotEmpty()) {
                tvCompletedRepMetrics.visibility = View.VISIBLE
            }

            // 5. Form Rule Audio & Banner Feedback
            frameResult.activeFeedback?.let { feedback ->
                val msg = feedback.message
                if (msg.isNotEmpty() && msg != lastSpokenFeedback) {
                    lastSpokenFeedback = msg
                    tvLiveFeedback.text = msg
                    tvLiveFeedback.visibility = View.VISIBLE
                    playAudioFeedback(feedback.relatedError, msg)
                }
            }
        }
    }

    /**
     * Plays pre-recorded audio clip via MediaPlayer if present in res/raw.
     * Returns true if playback succeeded, false otherwise.
     */
    private fun playPreRecordedClip(resourceName: String): Boolean {
        return try {
            val resId = resources.getIdentifier(resourceName, "raw", packageName)
            if (resId == 0) {
                return false
            }

            mediaPlayer?.release()
            mediaPlayer = MediaPlayer.create(this, resId)?.apply {
                setOnCompletionListener { mp ->
                    mp.release()
                    if (mediaPlayer === mp) {
                        mediaPlayer = null
                    }
                }
                setOnErrorListener { mp, what, extra ->
                    Log.w(TAG, "MediaPlayer error ($what, $extra) on clip $resourceName")
                    mp.release()
                    if (mediaPlayer === mp) {
                        mediaPlayer = null
                    }
                    true
                }
                start()
            }
            mediaPlayer != null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play pre-recorded audio clip $resourceName: ${e.message}")
            false
        }
    }

    /**
     * Plays audio feedback via pre-recorded clip, falling back to TextToSpeech if clip is missing.
     */
    fun playAudioFeedback(errorName: String?, message: String) {
        audioFeedbackController.playFeedback(errorName, message)
    }

    /**
     * Speaks audio feedback via TextToSpeech with explicit logging and ready queuing.
     * Maintained for backward compatibility.
     */
    fun speakAudioFeedback(text: String) {
        playAudioFeedback(null, text)
    }

    override fun onInit(status: Int) {
        ttsController.onInit(status)
    }

    /**
     * QA testing helper: manually triggers a form error condition and spoken feedback.
     */
    fun triggerFormErrorForTesting(errorName: String = "hips_dropping") {
        val msg = when (errorName) {
            "hips_dropping" -> "Keep your hips up."
            "hips_piking" -> "Lower your hips slightly."
            "insufficient_depth" -> "Go lower."
            "incomplete_lockout" -> "Fully extend at the top."
            "knee_valgus" -> "Push your knees out."
            "excessive_lean" -> "Keep your chest up."
            else -> "Keep your hips up."
        }
        Log.i(TAG, "Manually triggering form error condition for QA: $errorName -> \"$msg\"")
        runOnUiThread {
            tvLiveFeedback.text = msg
            tvLiveFeedback.visibility = View.VISIBLE
            playAudioFeedback(errorName, msg)
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
        try {
            unregisterReceiver(testReceiver)
        } catch (e: Exception) {
            // Receiver might not have been registered
        }
        mediaPlayer?.release()
        mediaPlayer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        poseEstimator.close()
        cameraCapturePipeline.stopCamera()
    }
}

