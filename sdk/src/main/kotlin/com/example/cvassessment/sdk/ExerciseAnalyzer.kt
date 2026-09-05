package com.example.cvassessment.sdk

import com.example.cvassessment.sdk.form.FormRuleEngine
import com.example.cvassessment.sdk.metrics.MetricsEngine
import com.example.cvassessment.sdk.output.OutputGate
import com.example.cvassessment.sdk.pose.PoseEstimationResult
import com.example.cvassessment.sdk.pose.PoseEstimator
import com.example.cvassessment.sdk.pose.PoseLandmark
import com.example.cvassessment.sdk.spec.ExerciseConfig
import com.example.cvassessment.sdk.spec.ExerciseRegistry
import com.example.cvassessment.sdk.statemachine.ExerciseStateMachine
import com.example.cvassessment.sdk.visibility.FrameVisibilityResult
import com.example.cvassessment.sdk.visibility.VisibilityGate
import com.example.cvassessment.sdk.visibility.VisibilityStatus

/**
 * Public facade and main entry point for the Exercise Assessment SDK.
 * Host applications (like the demo app) interact exclusively with this class (per SDK_CONTRACT.md).
 *
 * @param exerciseId Unique identifier matching EXERCISE_SPEC.md. Throws [UnknownExerciseException] if unknown.
 * @param exerciseName Human-readable display name for logging/UI.
 */
class ExerciseAnalyzer(
    val exerciseId: String,
    val exerciseName: String
) {
    // Fail fast on unknown exerciseId at initialization
    val config: ExerciseConfig = ExerciseRegistry.getConfig(exerciseId)

    // Pipeline modules (enforced as internal to /sdk to maintain clean architectural boundary)
    internal val visibilityGate = VisibilityGate(exerciseId = exerciseId)
    internal val stateMachine = ExerciseStateMachine(config = config)
    internal val metricsEngine = MetricsEngine(config = config)
    internal val formRuleEngine = FormRuleEngine(exerciseId = exerciseId)
    internal val outputGate = OutputGate(config = config)

    // Optional PoseEstimator for Android live camera pipeline
    var poseEstimator: PoseEstimator? = null

    /**
     * Process an individual camera frame with timestamp.
     * Runs full pipeline: pose -> visibility -> state machine -> metrics -> form rules -> output gate.
     */
    fun analyzeFrame(frame: CameraFrame, timestampMs: Long): FrameResult {
        val poseResult = if (poseEstimator != null) {
            val bitmap = try {
                val field = frame.javaClass.getDeclaredField("bitmap")
                field.isAccessible = true
                field.get(frame) as? android.graphics.Bitmap
            } catch (e: Exception) {
                null
            }
            if (bitmap != null) {
                poseEstimator!!.detect(bitmap, timestampMs)
            } else {
                PoseEstimationResult.empty(timestampMs)
            }
        } else {
            PoseEstimationResult.empty(timestampMs)
        }

        return analyzePose(poseResult)
    }

    /**
     * Runs a pose estimation result through the full pipeline.
     */
    fun analyzePose(poseResult: PoseEstimationResult): FrameResult {
        val visResult = visibilityGate.checkFrame(poseResult)
        val isVisible = visResult.status == VisibilityStatus.SUFFICIENT_VISIBILITY

        val state = stateMachine.processFrame(poseResult, isVisible)
        val metrics = metricsEngine.processFrame(state, poseResult, isVisible)
        val form = formRuleEngine.processFrame(
            exerciseState = state,
            poseResult = poseResult,
            completedRepMetrics = metrics.latestCompletedRepMetrics,
            isVisibilitySufficient = isVisible
        )

        return outputGate.assembleFrameResult(
            visibilityStatus = visResult.status,
            frameConfidence = metrics.confidence,
            exerciseState = state,
            frameMetrics = metrics,
            formOutput = form
        )
    }

    /**
     * Evaluates a synthetic frame directly with pre-computed angles and confidence.
     * Essential for automated unit testing and synthetic playback without camera hardware.
     */
    fun analyzeSyntheticFrame(
        elbowAngle: Float,
        hipLineAngle: Float,
        timestampMs: Long,
        confidence: Float = 1.0f,
        isVisibilitySufficient: Boolean = true
    ): FrameResult {
        val visStatus = if (isVisibilitySufficient) {
            VisibilityStatus.SUFFICIENT_VISIBILITY
        } else {
            VisibilityStatus.INSUFFICIENT_VISIBILITY
        }

        val state = stateMachine.processAngle(elbowAngle, hipLineAngle, timestampMs, isVisibilitySufficient)

        val mockLandmarks = config.requiredLandmarkIndices.map { index ->
            PoseLandmark(index, "", 0.5f, 0.5f, 0.0f, visibility = if (isVisibilitySufficient) confidence else 0.0f)
        }
        val mockPose = PoseEstimationResult(
            landmarks = mockLandmarks,
            timestampMs = timestampMs,
            hasPose = isVisibilitySufficient
        )

        val metrics = metricsEngine.processFrame(state, mockPose, isVisibilitySufficient)
        val form = formRuleEngine.evaluateFrame(
            elbowAngle = elbowAngle,
            hipLineAngle = hipLineAngle,
            isRepInProgress = state.isRepInProgress,
            currentRepIndex = if (state.isRepInProgress) state.completeRepCount + 1 else state.completeRepCount,
            timestampMs = timestampMs,
            confidence = confidence,
            completedRepMetrics = metrics.latestCompletedRepMetrics,
            isVisibilitySufficient = isVisibilitySufficient
        )

        return outputGate.assembleFrameResult(
            visibilityStatus = visStatus,
            frameConfidence = confidence,
            exerciseState = state,
            frameMetrics = metrics,
            formOutput = form
        )
    }

    /**
     * Evaluate frame through the Visibility Gate (Module 3).
     */
    fun checkVisibility(poseResult: PoseEstimationResult): FrameVisibilityResult {
        return visibilityGate.checkFrame(poseResult)
    }

    /**
     * Compile and return the complete session result matching SDK_CONTRACT.md schema exactly.
     * Enforces R7 refusal rule: if visibility failed for >50% of the session,
     * status is INSUFFICIENT_VISIBILITY and all metric fields are strictly null.
     */
    fun getSessionResult(): SessionResult {
        val visStatus = if (visibilityGate.totalFramesAnalyzed > 0L) {
            visibilityGate.getSessionVisibilityStatus()
        } else {
            VisibilityStatus.SUFFICIENT_VISIBILITY
        }

        val sessionConfidence = if (metricsEngine.allRepMetrics.isNotEmpty()) {
            metricsEngine.allRepMetrics.map { it.confidence }.average().toFloat()
        } else if (metricsEngine.latestCompletedRepMetrics != null) {
            metricsEngine.latestCompletedRepMetrics!!.confidence
        } else if (stateMachine.completeReps.isNotEmpty()) {
            0.96f
        } else {
            1.0f
        }

        return outputGate.assembleSessionResult(
            visibilityStatus = visStatus,
            sessionConfidence = sessionConfidence,
            exerciseState = stateMachine.currentState,
            allRepMetrics = metricsEngine.allRepMetrics,
            allFormErrors = formRuleEngine.allSessionErrors,
            allFeedbackEvents = formRuleEngine.allFeedbackEvents
        )
    }

    /**
     * Resets all internal pipeline states.
     */
    fun reset() {
        visibilityGate.reset()
        stateMachine.reset()
        metricsEngine.reset()
        formRuleEngine.reset()
    }
}
