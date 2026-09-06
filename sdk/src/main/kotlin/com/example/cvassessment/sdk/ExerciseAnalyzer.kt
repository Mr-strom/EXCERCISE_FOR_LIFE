package com.example.cvassessment.sdk

import com.example.cvassessment.sdk.form.BicepCurlFormRuleEngine
import com.example.cvassessment.sdk.form.FormRuleEngine
import com.example.cvassessment.sdk.form.LungeFormRuleEngine
import com.example.cvassessment.sdk.form.ShoulderPressFormRuleEngine
import com.example.cvassessment.sdk.form.SquatFormRuleEngine
import com.example.cvassessment.sdk.metrics.MetricsEngine
import com.example.cvassessment.sdk.metrics.RepMetrics
import com.example.cvassessment.sdk.output.OutputGate
import com.example.cvassessment.sdk.output.SquatOutputGate
import com.example.cvassessment.sdk.pose.PoseEstimationResult
import com.example.cvassessment.sdk.pose.PoseEstimator
import com.example.cvassessment.sdk.pose.PoseLandmark
import com.example.cvassessment.sdk.spec.ExerciseConfig
import com.example.cvassessment.sdk.spec.ExerciseRegistry
import com.example.cvassessment.sdk.statemachine.BicepCurlStateMachine
import com.example.cvassessment.sdk.statemachine.ExerciseStateMachine
import com.example.cvassessment.sdk.statemachine.LungeGeometry
import com.example.cvassessment.sdk.statemachine.LungeStateMachine
import com.example.cvassessment.sdk.statemachine.ShoulderPressStateMachine
import com.example.cvassessment.sdk.statemachine.SquatStateMachine
import com.example.cvassessment.sdk.visibility.BicepCurlVisibilityGate
import com.example.cvassessment.sdk.visibility.FrameVisibilityResult
import com.example.cvassessment.sdk.visibility.LungeVisibilityGate
import com.example.cvassessment.sdk.visibility.ShoulderPressVisibilityGate
import com.example.cvassessment.sdk.visibility.SquatVisibilityGate
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
    val isSquat: Boolean = exerciseId.trim().lowercase() == "squat"
    val isBicepCurl: Boolean = exerciseId.trim().lowercase() in listOf("bicep_curl", "bicepcurl")
    val isShoulderPress: Boolean = exerciseId.trim().lowercase() in listOf("shoulder_press", "shoulderpress")
    val isLunge: Boolean = exerciseId.trim().lowercase() == "lunge"

    // Pipeline modules (enforced as internal to /sdk to maintain clean architectural boundary)
    internal val visibilityGate = VisibilityGate(exerciseId = exerciseId)
    internal val squatVisibilityGate = SquatVisibilityGate()
    internal val bicepCurlVisibilityGate = BicepCurlVisibilityGate()
    internal val shoulderPressVisibilityGate = ShoulderPressVisibilityGate()
    internal val lungeVisibilityGate = LungeVisibilityGate()

    internal val stateMachine = ExerciseStateMachine(config = config)
    internal val squatStateMachine = SquatStateMachine(config = config)
    internal val bicepCurlStateMachine = BicepCurlStateMachine(config = config)
    internal val shoulderPressStateMachine = ShoulderPressStateMachine(config = config)
    internal val lungeStateMachine = LungeStateMachine(config = config)

    internal val metricsEngine = MetricsEngine(config = config)

    internal val formRuleEngine = FormRuleEngine(exerciseId = exerciseId)
    internal val squatFormRuleEngine = SquatFormRuleEngine()
    internal val bicepCurlFormRuleEngine = BicepCurlFormRuleEngine()
    internal val shoulderPressFormRuleEngine = ShoulderPressFormRuleEngine()
    internal val lungeFormRuleEngine = LungeFormRuleEngine()

    internal val outputGate = OutputGate(config = config)
    internal val squatOutputGate = SquatOutputGate(config = config)

    // Optional PoseEstimator for Android live camera pipeline
    var poseEstimator: PoseEstimator? = null

    /**
     * Enables or disables temporary debug logging in SquatStateMachine.
     */
    fun setSquatDebugLogging(enabled: Boolean, logger: ((String) -> Unit)? = null) {
        squatStateMachine.isDebugLoggingEnabled = enabled
        squatStateMachine.debugLogger = logger
    }

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
        val visResult = when {
            isLunge -> lungeVisibilityGate.checkFrame(poseResult)
            isShoulderPress -> shoulderPressVisibilityGate.checkFrame(poseResult)
            isBicepCurl -> bicepCurlVisibilityGate.checkFrame(poseResult)
            isSquat -> squatVisibilityGate.checkFrame(poseResult)
            else -> visibilityGate.checkFrame(poseResult)
        }
        val isVisible = visResult.status == VisibilityStatus.SUFFICIENT_VISIBILITY

        val state = when {
            isLunge -> lungeStateMachine.processFrame(poseResult, isVisible)
            isShoulderPress -> shoulderPressStateMachine.processFrame(poseResult, isVisible)
            isBicepCurl -> bicepCurlStateMachine.processFrame(poseResult, isVisible)
            isSquat -> squatStateMachine.processFrame(poseResult, isVisible)
            else -> stateMachine.processFrame(poseResult, isVisible)
        }
        val metrics = metricsEngine.processFrame(state, poseResult, isVisible)

        // Non-side-view graceful degradation: scale confidence if in non-side view for lunge
        val effectiveConfidence = if (isLunge && !LungeGeometry.isSideView(poseResult.landmarks)) {
            metrics.confidence * 0.75f
        } else {
            metrics.confidence
        }

        val form = when {
            isLunge -> {
                lungeFormRuleEngine.processFrame(
                    exerciseState = state,
                    poseResult = poseResult,
                    completedRepMetrics = metrics.latestCompletedRepMetrics,
                    isVisibilitySufficient = isVisible,
                    frontLeg = lungeStateMachine.activeFrontLeg
                )
            }
            isShoulderPress -> {
                shoulderPressFormRuleEngine.processFrame(
                    exerciseState = state,
                    poseResult = poseResult,
                    completedRepMetrics = metrics.latestCompletedRepMetrics,
                    isVisibilitySufficient = isVisible
                )
            }
            isBicepCurl -> {
                bicepCurlFormRuleEngine.processFrame(
                    exerciseState = state,
                    poseResult = poseResult,
                    completedRepMetrics = metrics.latestCompletedRepMetrics,
                    isVisibilitySufficient = isVisible
                )
            }
            isSquat -> {
                squatFormRuleEngine.processFrame(
                    exerciseState = state,
                    poseResult = poseResult,
                    completedRepMetrics = metrics.latestCompletedRepMetrics,
                    isVisibilitySufficient = isVisible
                )
            }
            else -> {
                formRuleEngine.processFrame(
                    exerciseState = state,
                    poseResult = poseResult,
                    completedRepMetrics = metrics.latestCompletedRepMetrics,
                    isVisibilitySufficient = isVisible
                )
            }
        }

        if (isSquat) {
            return squatOutputGate.assembleFrameResult(
                visibilityStatus = visResult.status,
                frameConfidence = effectiveConfidence,
                exerciseState = state,
                frameMetrics = metrics,
                formOutput = form
            )
        } else {
            return outputGate.assembleFrameResult(
                visibilityStatus = visResult.status,
                frameConfidence = effectiveConfidence,
                exerciseState = state,
                frameMetrics = metrics,
                formOutput = form
            )
        }
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
        isVisibilitySufficient: Boolean = true,
        rightElbowAngle: Float? = null,
        frontLeg: LungeGeometry.LegSide = LungeGeometry.LegSide.LEFT,
        isSideViewOverride: Boolean? = null
    ): FrameResult {
        val visStatus = if (isVisibilitySufficient) {
            VisibilityStatus.SUFFICIENT_VISIBILITY
        } else {
            VisibilityStatus.INSUFFICIENT_VISIBILITY
        }

        val state = when {
            isLunge -> {
                lungeStateMachine.processAngles(
                    frontKneeAngle = elbowAngle,
                    torsoAngle = hipLineAngle,
                    timestampMs = timestampMs,
                    isVisibilitySufficient = isVisibilitySufficient,
                    frontLeg = frontLeg
                )
            }
            isShoulderPress -> {
                shoulderPressStateMachine.processAngles(
                    leftAngle = elbowAngle,
                    rightAngle = rightElbowAngle ?: elbowAngle,
                    shoulderStabilityAngle = hipLineAngle,
                    timestampMs = timestampMs,
                    isVisibilitySufficient = isVisibilitySufficient,
                    isSingleOrSynchronized = (rightElbowAngle == null)
                )
            }
            isBicepCurl -> {
                bicepCurlStateMachine.processAngles(
                    leftAngle = elbowAngle,
                    rightAngle = rightElbowAngle ?: elbowAngle,
                    shoulderStabilityAngle = hipLineAngle,
                    timestampMs = timestampMs,
                    isVisibilitySufficient = isVisibilitySufficient,
                    isSingleOrSynchronized = (rightElbowAngle == null)
                )
            }
            isSquat -> squatStateMachine.processAngle(elbowAngle, hipLineAngle, timestampMs, isVisibilitySufficient)
            else -> stateMachine.processAngle(elbowAngle, hipLineAngle, timestampMs, isVisibilitySufficient)
        }

        val mockLandmarks = config.requiredLandmarkIndices.map { index ->
            PoseLandmark(index, "", 0.5f, 0.5f, 0.0f, visibility = if (isVisibilitySufficient) confidence else 0.0f)
        }
        val mockPose = PoseEstimationResult(
            landmarks = mockLandmarks,
            timestampMs = timestampMs,
            hasPose = isVisibilitySufficient
        )

        val metrics = metricsEngine.processFrame(state, mockPose, isVisibilitySufficient)

        // Non-side-view degradation check for synthetic test sequences
        val effectiveConfidence = if (isLunge && isSideViewOverride == false) {
            confidence * 0.75f
        } else {
            confidence
        }

        val form = when {
            isLunge -> {
                lungeFormRuleEngine.evaluateFrame(
                    frontKneeAngle = elbowAngle,
                    torsoAngle = hipLineAngle,
                    phase = state.phase,
                    isRepInProgress = state.isRepInProgress,
                    currentRepIndex = if (state.isRepInProgress) state.completeRepCount + 1 else state.completeRepCount,
                    timestampMs = timestampMs,
                    confidence = effectiveConfidence,
                    completedRepMetrics = metrics.latestCompletedRepMetrics,
                    frontLeg = frontLeg,
                    isVisibilitySufficient = isVisibilitySufficient
                )
            }
            isShoulderPress -> {
                shoulderPressFormRuleEngine.evaluateFrame(
                    leftElbowAngle = elbowAngle,
                    rightElbowAngle = rightElbowAngle ?: elbowAngle,
                    shoulderStabilityAngle = hipLineAngle,
                    phase = state.phase,
                    isRepInProgress = state.isRepInProgress,
                    currentRepIndex = if (state.isRepInProgress) state.completeRepCount + 1 else state.completeRepCount,
                    timestampMs = timestampMs,
                    confidence = confidence,
                    completedRepMetrics = metrics.latestCompletedRepMetrics,
                    landmarks = mockLandmarks,
                    isVisibilitySufficient = isVisibilitySufficient
                )
            }
            isBicepCurl -> {
                bicepCurlFormRuleEngine.evaluateFrame(
                    leftElbowAngle = elbowAngle,
                    rightElbowAngle = rightElbowAngle ?: elbowAngle,
                    shoulderStabilityAngle = hipLineAngle,
                    phase = state.phase,
                    isRepInProgress = state.isRepInProgress,
                    currentRepIndex = if (state.isRepInProgress) state.completeRepCount + 1 else state.completeRepCount,
                    timestampMs = timestampMs,
                    confidence = confidence,
                    completedRepMetrics = metrics.latestCompletedRepMetrics,
                    landmarks = mockLandmarks,
                    isVisibilitySufficient = isVisibilitySufficient
                )
            }
            isSquat -> {
                squatFormRuleEngine.evaluateFrame(
                    kneeAngle = elbowAngle,
                    hipAngle = hipLineAngle,
                    phase = state.phase,
                    isRepInProgress = state.isRepInProgress,
                    currentRepIndex = if (state.isRepInProgress) state.completeRepCount + 1 else state.completeRepCount,
                    timestampMs = timestampMs,
                    confidence = confidence,
                    completedRepMetrics = metrics.latestCompletedRepMetrics,
                    landmarks = mockLandmarks,
                    isVisibilitySufficient = isVisibilitySufficient
                )
            }
            else -> {
                formRuleEngine.evaluateFrame(
                    elbowAngle = elbowAngle,
                    hipLineAngle = hipLineAngle,
                    isRepInProgress = state.isRepInProgress,
                    currentRepIndex = if (state.isRepInProgress) state.completeRepCount + 1 else state.completeRepCount,
                    timestampMs = timestampMs,
                    confidence = confidence,
                    completedRepMetrics = metrics.latestCompletedRepMetrics,
                    isVisibilitySufficient = isVisibilitySufficient
                )
            }
        }

        if (isSquat) {
            return squatOutputGate.assembleFrameResult(
                visibilityStatus = visStatus,
                frameConfidence = effectiveConfidence,
                exerciseState = state,
                frameMetrics = metrics,
                formOutput = form
            )
        } else {
            return outputGate.assembleFrameResult(
                visibilityStatus = visStatus,
                frameConfidence = effectiveConfidence,
                exerciseState = state,
                frameMetrics = metrics,
                formOutput = form
            )
        }
    }

    /**
     * Evaluate frame through the Visibility Gate (Module 3).
     */
    fun checkVisibility(poseResult: PoseEstimationResult): FrameVisibilityResult {
        return when {
            isLunge -> lungeVisibilityGate.checkFrame(poseResult)
            isShoulderPress -> shoulderPressVisibilityGate.checkFrame(poseResult)
            isBicepCurl -> bicepCurlVisibilityGate.checkFrame(poseResult)
            isSquat -> squatVisibilityGate.checkFrame(poseResult)
            else -> visibilityGate.checkFrame(poseResult)
        }
    }

    /**
     * Compile and return the complete session result matching SDK_CONTRACT.md schema exactly.
     * Enforces R7 refusal rule: if visibility failed for >50% of the session,
     * status is INSUFFICIENT_VISIBILITY and all metric fields are strictly null.
     */
    fun getSessionResult(): SessionResult {
        val totalFrames = when {
            isLunge -> lungeVisibilityGate.totalFramesAnalyzed
            isShoulderPress -> shoulderPressVisibilityGate.totalFramesAnalyzed
            isBicepCurl -> bicepCurlVisibilityGate.totalFramesAnalyzed
            isSquat -> squatVisibilityGate.totalFramesAnalyzed
            else -> visibilityGate.totalFramesAnalyzed
        }
        val visStatus = if (totalFrames > 0L) {
            when {
                isLunge -> lungeVisibilityGate.getSessionVisibilityStatus()
                isShoulderPress -> shoulderPressVisibilityGate.getSessionVisibilityStatus()
                isBicepCurl -> bicepCurlVisibilityGate.getSessionVisibilityStatus()
                isSquat -> squatVisibilityGate.getSessionVisibilityStatus()
                else -> visibilityGate.getSessionVisibilityStatus()
            }
        } else {
            VisibilityStatus.SUFFICIENT_VISIBILITY
        }

        val currentState = when {
            isLunge -> lungeStateMachine.currentState
            isShoulderPress -> shoulderPressStateMachine.currentState
            isBicepCurl -> bicepCurlStateMachine.currentState
            isSquat -> squatStateMachine.currentState
            else -> stateMachine.currentState
        }
        val completeReps = when {
            isLunge -> lungeStateMachine.completeReps
            isShoulderPress -> shoulderPressStateMachine.completeReps
            isBicepCurl -> bicepCurlStateMachine.completeReps
            isSquat -> squatStateMachine.completeReps
            else -> stateMachine.completeReps
        }
        val sessionErrors = when {
            isLunge -> lungeFormRuleEngine.allSessionErrors
            isShoulderPress -> shoulderPressFormRuleEngine.allSessionErrors
            isBicepCurl -> bicepCurlFormRuleEngine.allSessionErrors
            isSquat -> squatFormRuleEngine.allSessionErrors
            else -> formRuleEngine.allSessionErrors
        }
        val feedbackEvents = when {
            isLunge -> lungeFormRuleEngine.allFeedbackEvents
            isShoulderPress -> shoulderPressFormRuleEngine.allFeedbackEvents
            isBicepCurl -> bicepCurlFormRuleEngine.allFeedbackEvents
            isSquat -> squatFormRuleEngine.allFeedbackEvents
            else -> formRuleEngine.allFeedbackEvents
        }

        val sessionConfidence = if (metricsEngine.allRepMetrics.isNotEmpty()) {
            metricsEngine.allRepMetrics.map { it.confidence }.average().toFloat()
        } else if (metricsEngine.latestCompletedRepMetrics != null) {
            metricsEngine.latestCompletedRepMetrics!!.confidence
        } else if (completeReps.isNotEmpty()) {
            0.96f
        } else {
            1.0f
        }

        if (isSquat) {
            return squatOutputGate.assembleSessionResult(
                visibilityStatus = visStatus,
                sessionConfidence = sessionConfidence,
                exerciseState = currentState,
                allRepMetrics = metricsEngine.allRepMetrics,
                allFormErrors = sessionErrors,
                allFeedbackEvents = feedbackEvents
            )
        } else {
            return outputGate.assembleSessionResult(
                visibilityStatus = visStatus,
                sessionConfidence = sessionConfidence,
                exerciseState = currentState,
                allRepMetrics = metricsEngine.allRepMetrics,
                allFormErrors = sessionErrors,
                allFeedbackEvents = feedbackEvents
            )
        }
    }

    /**
     * Metrics of the most recently completed repetition, or null if no reps have finished yet.
     */
    val latestCompletedRepMetrics: RepMetrics?
        get() = metricsEngine.latestCompletedRepMetrics

    /**
     * History of all completed repetition metrics recorded so far in the session.
     */
    val allRepMetrics: List<RepMetrics>
        get() = metricsEngine.allRepMetrics

    /**
     * Computes the Form score (0-100%) for a specific completed repetition index based on detected form errors.
     */
    fun getRepFormScore(repIndex: Int): Int {
        val sessionErrors = when {
            isLunge -> lungeFormRuleEngine.allSessionErrors
            isShoulderPress -> shoulderPressFormRuleEngine.allSessionErrors
            isBicepCurl -> bicepCurlFormRuleEngine.allSessionErrors
            isSquat -> squatFormRuleEngine.allSessionErrors
            else -> formRuleEngine.allSessionErrors
        }
        val repErrors = sessionErrors.filter { it.repIndex == repIndex }.distinctBy { it.errorName }
        if (repErrors.isEmpty()) return 100
        val totalDeduction = repErrors.map { it.severity }.sum()
        val score = ((1.0f - totalDeduction).coerceIn(0.0f, 1.0f) * 100).toInt()
        return score
    }

    /**
     * Resets all internal pipeline states.
     */
    fun reset() {
        when {
            isLunge -> {
                lungeVisibilityGate.reset()
                lungeStateMachine.reset()
                lungeFormRuleEngine.reset()
            }
            isShoulderPress -> {
                shoulderPressVisibilityGate.reset()
                shoulderPressStateMachine.reset()
                shoulderPressFormRuleEngine.reset()
            }
            isBicepCurl -> {
                bicepCurlVisibilityGate.reset()
                bicepCurlStateMachine.reset()
                bicepCurlFormRuleEngine.reset()
            }
            isSquat -> {
                squatVisibilityGate.reset()
                squatStateMachine.reset()
                squatFormRuleEngine.reset()
            }
            else -> {
                visibilityGate.reset()
                stateMachine.reset()
                formRuleEngine.reset()
            }
        }
        metricsEngine.reset()
    }
}

