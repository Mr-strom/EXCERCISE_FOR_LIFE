package com.example.cvassessment.sdk

import com.example.cvassessment.sdk.form.BicepCurlFormRuleEngine
import com.example.cvassessment.sdk.form.CalfRaiseFormRuleEngine
import com.example.cvassessment.sdk.form.FormRuleEngine
import com.example.cvassessment.sdk.form.JumpingJackFormRuleEngine
import com.example.cvassessment.sdk.form.MountainClimberFormRuleEngine
import com.example.cvassessment.sdk.form.LungeFormRuleEngine
import com.example.cvassessment.sdk.form.PlankFormRuleEngine
import com.example.cvassessment.sdk.form.ShoulderPressFormRuleEngine
import com.example.cvassessment.sdk.form.SidePlankFormRuleEngine
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
import com.example.cvassessment.sdk.statemachine.CalfRaiseGeometry
import com.example.cvassessment.sdk.statemachine.CalfRaiseStateMachine
import com.example.cvassessment.sdk.statemachine.ExerciseStateMachine
import com.example.cvassessment.sdk.statemachine.JumpingJackGeometry
import com.example.cvassessment.sdk.statemachine.JumpingJackStateMachine
import com.example.cvassessment.sdk.statemachine.MountainClimberGeometry
import com.example.cvassessment.sdk.statemachine.MountainClimberStateMachine
import com.example.cvassessment.sdk.statemachine.LungeGeometry
import com.example.cvassessment.sdk.statemachine.LungeStateMachine
import com.example.cvassessment.sdk.statemachine.PlankGeometry
import com.example.cvassessment.sdk.statemachine.PlankStateMachine
import com.example.cvassessment.sdk.statemachine.ShoulderPressStateMachine
import com.example.cvassessment.sdk.statemachine.SidePlankGeometry
import com.example.cvassessment.sdk.statemachine.SidePlankStateMachine
import com.example.cvassessment.sdk.statemachine.SquatStateMachine
import com.example.cvassessment.sdk.visibility.BicepCurlVisibilityGate
import com.example.cvassessment.sdk.visibility.CalfRaiseVisibilityGate
import com.example.cvassessment.sdk.visibility.FrameVisibilityResult
import com.example.cvassessment.sdk.visibility.JumpingJackVisibilityGate
import com.example.cvassessment.sdk.visibility.MountainClimberVisibilityGate
import com.example.cvassessment.sdk.visibility.LungeVisibilityGate
import com.example.cvassessment.sdk.visibility.PlankVisibilityGate
import com.example.cvassessment.sdk.visibility.ShoulderPressVisibilityGate
import com.example.cvassessment.sdk.visibility.SidePlankVisibilityGate
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
    val isCalfRaise: Boolean = exerciseId.trim().lowercase() in listOf("calf_raise", "calfraise")
    val isPlank: Boolean = exerciseId.trim().lowercase() == "plank"
    val isSidePlank: Boolean = exerciseId.trim().lowercase() in listOf("side_plank", "sideplank")
    val isJumpingJack: Boolean = exerciseId.trim().lowercase() in listOf("jumping_jack", "jumpingjack")
    val isMountainClimber: Boolean = exerciseId.trim().lowercase() in listOf("mountain_climber", "mountainclimber")

    // Pipeline modules (enforced as internal to /sdk to maintain clean architectural boundary)
    internal val visibilityGate = VisibilityGate(exerciseId = exerciseId)
    internal val squatVisibilityGate = SquatVisibilityGate()
    internal val bicepCurlVisibilityGate = BicepCurlVisibilityGate()
    internal val shoulderPressVisibilityGate = ShoulderPressVisibilityGate()
    internal val lungeVisibilityGate = LungeVisibilityGate()
    internal val calfRaiseVisibilityGate = CalfRaiseVisibilityGate()
    internal val plankVisibilityGate = PlankVisibilityGate()
    internal val sidePlankVisibilityGate = SidePlankVisibilityGate()
    internal val jumpingJackVisibilityGate = JumpingJackVisibilityGate()
    internal val mountainClimberVisibilityGate = MountainClimberVisibilityGate()

    internal val stateMachine = ExerciseStateMachine(config = config)
    internal val squatStateMachine = SquatStateMachine(config = config)
    internal val bicepCurlStateMachine = BicepCurlStateMachine(config = config)
    internal val shoulderPressStateMachine = ShoulderPressStateMachine(config = config)
    internal val lungeStateMachine = LungeStateMachine(config = config)
    internal val calfRaiseStateMachine = CalfRaiseStateMachine(config = config)
    internal val plankStateMachine = PlankStateMachine(config = config)
    internal val sidePlankStateMachine = SidePlankStateMachine(config = config)
    internal val jumpingJackStateMachine = JumpingJackStateMachine(config = config)
    internal val mountainClimberStateMachine = MountainClimberStateMachine(config = config)

    internal val metricsEngine = MetricsEngine(config = config)

    internal val formRuleEngine = FormRuleEngine(exerciseId = exerciseId)
    internal val squatFormRuleEngine = SquatFormRuleEngine()
    internal val bicepCurlFormRuleEngine = BicepCurlFormRuleEngine()
    internal val shoulderPressFormRuleEngine = ShoulderPressFormRuleEngine()
    internal val lungeFormRuleEngine = LungeFormRuleEngine()
    internal val calfRaiseFormRuleEngine = CalfRaiseFormRuleEngine()
    internal val plankFormRuleEngine = PlankFormRuleEngine()
    internal val sidePlankFormRuleEngine = SidePlankFormRuleEngine()
    internal val jumpingJackFormRuleEngine = JumpingJackFormRuleEngine()
    internal val mountainClimberFormRuleEngine = MountainClimberFormRuleEngine()

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
     * Enables or disables temporary debug logging in LungeStateMachine and LungeVisibilityGate.
     */
    fun setLungeDebugLogging(enabled: Boolean, logger: ((String) -> Unit)? = null) {
        lungeStateMachine.isDebugLoggingEnabled = enabled
        lungeStateMachine.debugLogger = logger
        lungeVisibilityGate.isDebugLoggingEnabled = enabled
        lungeVisibilityGate.debugLogger = logger
    }

    /**
     * Enables or disables temporary debug logging in CalfRaiseStateMachine.
     */
    fun setCalfRaiseDebugLogging(enabled: Boolean, logger: ((String) -> Unit)? = null) {
        calfRaiseStateMachine.isDebugLoggingEnabled = enabled
        calfRaiseStateMachine.debugLogger = logger
    }

    /**
     * Enables or disables temporary debug logging in PlankStateMachine.
     */
    fun setPlankDebugLogging(enabled: Boolean, logger: ((String) -> Unit)? = null) {
        plankStateMachine.isDebugLoggingEnabled = enabled
        plankStateMachine.debugLogger = logger
    }

    /**
     * Enables or disables temporary debug logging in SidePlankStateMachine.
     */
    fun setSidePlankDebugLogging(enabled: Boolean, logger: ((String) -> Unit)? = null) {
        sidePlankStateMachine.isDebugLoggingEnabled = enabled
        sidePlankStateMachine.debugLogger = logger
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
            isMountainClimber -> mountainClimberVisibilityGate.checkFrame(poseResult)
            isSidePlank -> sidePlankVisibilityGate.checkFrame(poseResult, sidePlankStateMachine.supportSide)
            isPlank -> plankVisibilityGate.checkFrame(poseResult)
            isCalfRaise -> calfRaiseVisibilityGate.checkFrame(poseResult)
            isJumpingJack -> jumpingJackVisibilityGate.checkFrame(poseResult)
            isLunge -> lungeVisibilityGate.checkFrame(poseResult)
            isShoulderPress -> shoulderPressVisibilityGate.checkFrame(poseResult)
            isBicepCurl -> bicepCurlVisibilityGate.checkFrame(poseResult)
            isSquat -> squatVisibilityGate.checkFrame(poseResult)
            else -> visibilityGate.checkFrame(poseResult)
        }
        val isVisible = visResult.status == VisibilityStatus.SUFFICIENT_VISIBILITY

        val state = when {
            isMountainClimber -> mountainClimberStateMachine.processFrame(poseResult, isVisible)
            isSidePlank -> sidePlankStateMachine.processFrame(poseResult, isVisible)
            isPlank -> plankStateMachine.processFrame(poseResult, isVisible)
            isCalfRaise -> calfRaiseStateMachine.processFrame(poseResult, isVisible)
            isJumpingJack -> jumpingJackStateMachine.processFrame(poseResult, isVisible)
            isLunge -> lungeStateMachine.processFrame(poseResult, isVisible)
            isShoulderPress -> shoulderPressStateMachine.processFrame(poseResult, isVisible)
            isBicepCurl -> bicepCurlStateMachine.processFrame(poseResult, isVisible)
            isSquat -> squatStateMachine.processFrame(poseResult, isVisible)
            else -> stateMachine.processFrame(poseResult, isVisible)
        }
        val metrics = when {
            isMountainClimber -> mountainClimberStateMachine.getFrameMetrics(state, isVisible)
            isSidePlank -> sidePlankStateMachine.getFrameMetrics(state, isVisible)
            isPlank -> plankStateMachine.getFrameMetrics(state, isVisible)
            isCalfRaise -> calfRaiseStateMachine.getFrameMetrics(state, poseResult, isVisible)
            isJumpingJack -> jumpingJackStateMachine.getFrameMetrics(state, isVisible)
            else -> metricsEngine.processFrame(state, poseResult, isVisible)
        }

        // Non-side-view graceful degradation: scale confidence if in non-side view for lunge
        val effectiveConfidence = if (isLunge && !LungeGeometry.isSideView(poseResult.landmarks)) {
            metrics.confidence * 0.75f
        } else {
            metrics.confidence
        }

        val form = when {
            isMountainClimber -> {
                mountainClimberFormRuleEngine.processFrame(
                    exerciseState = state,
                    poseResult = poseResult,
                    completedRepMetrics = metrics.latestCompletedRepMetrics,
                    isVisibilitySufficient = isVisible
                )
            }
            isSidePlank -> {
                sidePlankFormRuleEngine.processFrame(
                    exerciseState = state,
                    poseResult = poseResult,
                    isVisibilitySufficient = isVisible,
                    supportSide = sidePlankStateMachine.supportSide,
                    isWobbleRecovered = sidePlankStateMachine.lastWobbleDetected
                )
            }
            isPlank -> {
                plankFormRuleEngine.processFrame(
                    exerciseState = state,
                    poseResult = poseResult,
                    isVisibilitySufficient = isVisible,
                    isWobbleRecovered = plankStateMachine.lastWobbleDetected
                )
            }
            isCalfRaise -> {
                calfRaiseFormRuleEngine.processFrame(
                    exerciseState = state,
                    poseResult = poseResult,
                    completedRepMetrics = metrics.latestCompletedRepMetrics,
                    isVisibilitySufficient = isVisible
                )
            }
            isJumpingJack -> {
                jumpingJackFormRuleEngine.processFrame(
                    exerciseState = state,
                    poseResult = poseResult,
                    completedRepMetrics = metrics.latestCompletedRepMetrics,
                    asymmetryDivergenceMs = jumpingJackStateMachine.lastRepAsymmetryDivergenceMs,
                    isVisibilitySufficient = isVisible
                )
            }
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

        if (isPlank || isSidePlank) {
            val holdDuration = if (isPlank) plankStateMachine.holdDurationSec else sidePlankStateMachine.holdDurationSec
            if (visResult.status == VisibilityStatus.INSUFFICIENT_VISIBILITY) {
                return FrameResult(
                    status = ValidationStatus.INSUFFICIENT_VISIBILITY,
                    confidence = 0.0f,
                    currentReps = null,
                    currentHoldSec = null,
                    instantRomPercent = null,
                    activeFeedback = null
                )
            }
            return FrameResult(
                status = ValidationStatus.VALID,
                confidence = effectiveConfidence,
                currentReps = null,
                currentHoldSec = holdDuration,
                instantRomPercent = metrics.instantRomPercent,
                activeFeedback = form.newFeedbackEvents.firstOrNull()
            )
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
        isSideViewOverride: Boolean? = null,
        heelY: Float? = null
    ): FrameResult {
        // Hard requirement for Calf Raise/Plank/Mountain Climber (side view required) and Jumping Jack (front view required)
        val visStatus = if (!isVisibilitySufficient || (isCalfRaise && isSideViewOverride == false) || (isPlank && isSideViewOverride == false) || (isMountainClimber && isSideViewOverride == false) || (isJumpingJack && isSideViewOverride == true)) {
            VisibilityStatus.INSUFFICIENT_VISIBILITY
        } else {
            VisibilityStatus.SUFFICIENT_VISIBILITY
        }
        val isVisSufficient = visStatus == VisibilityStatus.SUFFICIENT_VISIBILITY

        val state = when {
            isSidePlank -> sidePlankStateMachine.processAngle(hipLineAngle, timestampMs, isVisSufficient)
            isPlank -> plankStateMachine.processAngle(hipLineAngle, timestampMs, isVisSufficient)
            isCalfRaise -> {
                val actualHeelY = heelY ?: if (elbowAngle > 1.0f) (elbowAngle / 100.0f) else elbowAngle
                calfRaiseStateMachine.processHeelY(actualHeelY, timestampMs, isVisSufficient)
            }
            isMountainClimber -> mountainClimberStateMachine.processAngles(elbowAngle, hipLineAngle, timestampMs, isVisSufficient)
            isJumpingJack -> jumpingJackStateMachine.processAngles(elbowAngle, hipLineAngle, timestampMs, isVisSufficient)
            isLunge -> {
                lungeStateMachine.processAngles(
                    frontKneeAngle = elbowAngle,
                    torsoAngle = hipLineAngle,
                    timestampMs = timestampMs,
                    isVisibilitySufficient = isVisSufficient,
                    frontLeg = frontLeg
                )
            }
            isShoulderPress -> {
                shoulderPressStateMachine.processAngles(
                    leftAngle = elbowAngle,
                    rightAngle = rightElbowAngle ?: elbowAngle,
                    shoulderStabilityAngle = hipLineAngle,
                    timestampMs = timestampMs,
                    isVisibilitySufficient = isVisSufficient,
                    isSingleOrSynchronized = (rightElbowAngle == null)
                )
            }
            isBicepCurl -> {
                bicepCurlStateMachine.processAngles(
                    leftAngle = elbowAngle,
                    rightAngle = rightElbowAngle ?: elbowAngle,
                    shoulderStabilityAngle = hipLineAngle,
                    timestampMs = timestampMs,
                    isVisibilitySufficient = isVisSufficient,
                    isSingleOrSynchronized = (rightElbowAngle == null)
                )
            }
            isSquat -> squatStateMachine.processAngle(elbowAngle, hipLineAngle, timestampMs, isVisSufficient)
            else -> stateMachine.processAngle(elbowAngle, hipLineAngle, timestampMs, isVisSufficient)
        }

        val mockLandmarks = config.requiredLandmarkIndices.map { index ->
            PoseLandmark(index, "", 0.5f, 0.5f, 0.0f, visibility = if (isVisSufficient) confidence else 0.0f)
        }
        val mockPose = PoseEstimationResult(
            landmarks = mockLandmarks,
            timestampMs = timestampMs,
            hasPose = isVisSufficient
        )

        val metrics = when {
            isMountainClimber -> mountainClimberStateMachine.getFrameMetrics(state, isVisSufficient)
            isSidePlank -> sidePlankStateMachine.getFrameMetrics(state, isVisSufficient)
            isPlank -> plankStateMachine.getFrameMetrics(state, isVisSufficient)
            isCalfRaise -> calfRaiseStateMachine.getFrameMetrics(state, mockPose, isVisSufficient)
            isJumpingJack -> jumpingJackStateMachine.getFrameMetrics(state, isVisSufficient)
            else -> metricsEngine.processFrame(state, mockPose, isVisSufficient)
        }

        // Non-side-view degradation check for synthetic test sequences
        val effectiveConfidence = if (isLunge && isSideViewOverride == false) {
            confidence * 0.75f
        } else {
            confidence
        }

        val form = when {
            isMountainClimber -> {
                mountainClimberFormRuleEngine.evaluateFrame(
                    kneeAngle = elbowAngle,
                    hipLineAngle = hipLineAngle,
                    phase = state.phase,
                    isRepInProgress = state.isRepInProgress,
                    currentRepIndex = if (state.isRepInProgress) state.completeRepCount + 1 else state.completeRepCount,
                    timestampMs = timestampMs,
                    confidence = confidence,
                    completedRepMetrics = metrics.latestCompletedRepMetrics,
                    isVisibilitySufficient = isVisSufficient
                )
            }
            isSidePlank -> {
                sidePlankFormRuleEngine.evaluateFrame(
                    bodyLineAngle = hipLineAngle,
                    isHoldInProgress = state.isRepInProgress,
                    timestampMs = timestampMs,
                    confidence = confidence,
                    isVisibilitySufficient = isVisSufficient,
                    isWobbleRecovered = sidePlankStateMachine.lastWobbleDetected
                )
            }
            isPlank -> {
                plankFormRuleEngine.evaluateFrame(
                    hipLineAngle = hipLineAngle,
                    isHoldInProgress = state.isRepInProgress,
                    timestampMs = timestampMs,
                    confidence = confidence,
                    isVisibilitySufficient = isVisSufficient,
                    isWobbleRecovered = plankStateMachine.lastWobbleDetected
                )
            }
            isCalfRaise -> {
                calfRaiseFormRuleEngine.evaluateFrame(
                    elevation = state.currentElbowAngle,
                    phase = state.phase,
                    isRepInProgress = state.isRepInProgress,
                    currentRepIndex = if (state.isRepInProgress) state.completeRepCount + 1 else state.completeRepCount,
                    timestampMs = timestampMs,
                    confidence = effectiveConfidence,
                    completedRepMetrics = metrics.latestCompletedRepMetrics,
                    isVisibilitySufficient = isVisSufficient
                )
            }
            isJumpingJack -> {
                jumpingJackFormRuleEngine.evaluateFrame(
                    armAngle = elbowAngle,
                    legAngle = hipLineAngle,
                    phase = state.phase,
                    isRepInProgress = state.isRepInProgress,
                    currentRepIndex = if (state.isRepInProgress) state.completeRepCount + 1 else state.completeRepCount,
                    timestampMs = timestampMs,
                    confidence = confidence,
                    completedRepMetrics = metrics.latestCompletedRepMetrics,
                    asymmetryDivergenceMs = jumpingJackStateMachine.lastRepAsymmetryDivergenceMs,
                    isVisibilitySufficient = isVisSufficient
                )
            }
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
                    isVisibilitySufficient = isVisSufficient
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
                    isVisibilitySufficient = isVisSufficient
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
                    isVisibilitySufficient = isVisSufficient
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
                    isVisibilitySufficient = isVisSufficient
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
                    isVisibilitySufficient = isVisSufficient
                )
            }
        }

        if (isPlank || isSidePlank) {
            val holdDuration = if (isPlank) plankStateMachine.holdDurationSec else sidePlankStateMachine.holdDurationSec
            if (visStatus == VisibilityStatus.INSUFFICIENT_VISIBILITY) {
                return FrameResult(
                    status = ValidationStatus.INSUFFICIENT_VISIBILITY,
                    confidence = 0.0f,
                    currentReps = null,
                    currentHoldSec = null,
                    instantRomPercent = null,
                    activeFeedback = null
                )
            }
            return FrameResult(
                status = ValidationStatus.VALID,
                confidence = effectiveConfidence,
                currentReps = null,
                currentHoldSec = holdDuration,
                instantRomPercent = metrics.instantRomPercent,
                activeFeedback = form.newFeedbackEvents.firstOrNull()
            )
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
            isMountainClimber -> mountainClimberVisibilityGate.checkFrame(poseResult)
            isSidePlank -> sidePlankVisibilityGate.checkFrame(poseResult, sidePlankStateMachine.supportSide)
            isPlank -> plankVisibilityGate.checkFrame(poseResult)
            isCalfRaise -> calfRaiseVisibilityGate.checkFrame(poseResult)
            isJumpingJack -> jumpingJackVisibilityGate.checkFrame(poseResult)
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
            isMountainClimber -> mountainClimberVisibilityGate.totalFramesAnalyzed
            isSidePlank -> sidePlankVisibilityGate.totalFramesAnalyzed
            isPlank -> plankVisibilityGate.totalFramesAnalyzed
            isCalfRaise -> calfRaiseVisibilityGate.totalFramesAnalyzed
            isJumpingJack -> jumpingJackVisibilityGate.totalFramesAnalyzed
            isLunge -> lungeVisibilityGate.totalFramesAnalyzed
            isShoulderPress -> shoulderPressVisibilityGate.totalFramesAnalyzed
            isBicepCurl -> bicepCurlVisibilityGate.totalFramesAnalyzed
            isSquat -> squatVisibilityGate.totalFramesAnalyzed
            else -> visibilityGate.totalFramesAnalyzed
        }
        val visStatus = if (totalFrames > 0L) {
            when {
                isMountainClimber -> mountainClimberVisibilityGate.getSessionVisibilityStatus()
                isSidePlank -> sidePlankVisibilityGate.getSessionVisibilityStatus()
                isPlank -> plankVisibilityGate.getSessionVisibilityStatus()
                isCalfRaise -> calfRaiseVisibilityGate.getSessionVisibilityStatus()
                isJumpingJack -> jumpingJackVisibilityGate.getSessionVisibilityStatus()
                isLunge -> lungeVisibilityGate.getSessionVisibilityStatus()
                isShoulderPress -> shoulderPressVisibilityGate.getSessionVisibilityStatus()
                isBicepCurl -> bicepCurlVisibilityGate.getSessionVisibilityStatus()
                isSquat -> squatVisibilityGate.getSessionVisibilityStatus()
                else -> visibilityGate.getSessionVisibilityStatus()
            }
        } else {
            VisibilityStatus.SUFFICIENT_VISIBILITY
        }

        if (isPlank) {
            val status = if (visStatus == VisibilityStatus.SUFFICIENT_VISIBILITY) ValidationStatus.VALID else ValidationStatus.INSUFFICIENT_VISIBILITY
            val confidence = if (visStatus == VisibilityStatus.SUFFICIENT_VISIBILITY) 1.0f else 0.0f
            return outputGate.buildSessionResult(
                status = status,
                confidence = confidence,
                completeReps = null,
                incompleteReps = null,
                holdDurationSec = plankStateMachine.holdDurationSec,
                avgRepDurationSec = null,
                romPercent = plankStateMachine.averageRomPercent,
                tutFactor = plankStateMachine.tutFactor,
                formFactor = plankFormRuleEngine.computeFormFactor(),
                formErrors = plankFormRuleEngine.allSessionErrors,
                feedbackEvents = plankFormRuleEngine.allFeedbackEvents
            )
        }

        if (isSidePlank) {
            val status = if (visStatus == VisibilityStatus.SUFFICIENT_VISIBILITY) ValidationStatus.VALID else ValidationStatus.INSUFFICIENT_VISIBILITY
            val confidence = if (visStatus == VisibilityStatus.SUFFICIENT_VISIBILITY) 1.0f else 0.0f
            return outputGate.buildSessionResult(
                status = status,
                confidence = confidence,
                completeReps = null,
                incompleteReps = null,
                holdDurationSec = sidePlankStateMachine.holdDurationSec,
                avgRepDurationSec = null,
                romPercent = sidePlankStateMachine.averageRomPercent,
                tutFactor = sidePlankStateMachine.tutFactor,
                formFactor = sidePlankFormRuleEngine.computeFormFactor(),
                formErrors = sidePlankFormRuleEngine.allSessionErrors,
                feedbackEvents = sidePlankFormRuleEngine.allFeedbackEvents
            )
        }

        val currentState = when {
            isMountainClimber -> mountainClimberStateMachine.currentState
            isCalfRaise -> calfRaiseStateMachine.currentState
            isJumpingJack -> jumpingJackStateMachine.currentState
            isLunge -> lungeStateMachine.currentState
            isShoulderPress -> shoulderPressStateMachine.currentState
            isBicepCurl -> bicepCurlStateMachine.currentState
            isSquat -> squatStateMachine.currentState
            else -> stateMachine.currentState
        }
        val completeReps = when {
            isMountainClimber -> mountainClimberStateMachine.completeReps
            isCalfRaise -> calfRaiseStateMachine.completeReps
            isJumpingJack -> jumpingJackStateMachine.completeReps
            isLunge -> lungeStateMachine.completeReps
            isShoulderPress -> shoulderPressStateMachine.completeReps
            isBicepCurl -> bicepCurlStateMachine.completeReps
            isSquat -> squatStateMachine.completeReps
            else -> stateMachine.completeReps
        }
        val sessionErrors = when {
            isMountainClimber -> mountainClimberFormRuleEngine.allSessionErrors
            isCalfRaise -> calfRaiseFormRuleEngine.allSessionErrors
            isJumpingJack -> jumpingJackFormRuleEngine.allSessionErrors
            isLunge -> lungeFormRuleEngine.allSessionErrors
            isShoulderPress -> shoulderPressFormRuleEngine.allSessionErrors
            isBicepCurl -> bicepCurlFormRuleEngine.allSessionErrors
            isSquat -> squatFormRuleEngine.allSessionErrors
            else -> formRuleEngine.allSessionErrors
        }
        val feedbackEvents = when {
            isMountainClimber -> mountainClimberFormRuleEngine.allFeedbackEvents
            isCalfRaise -> calfRaiseFormRuleEngine.allFeedbackEvents
            isJumpingJack -> jumpingJackFormRuleEngine.allFeedbackEvents
            isLunge -> lungeFormRuleEngine.allFeedbackEvents
            isShoulderPress -> shoulderPressFormRuleEngine.allFeedbackEvents
            isBicepCurl -> bicepCurlFormRuleEngine.allFeedbackEvents
            isSquat -> squatFormRuleEngine.allFeedbackEvents
            else -> formRuleEngine.allFeedbackEvents
        }

        val sessionRepMetrics = if (isMountainClimber) {
            mountainClimberStateMachine.allRepMetrics
        } else if (isCalfRaise) {
            calfRaiseStateMachine.allRepMetrics
        } else if (isJumpingJack) {
            jumpingJackStateMachine.allRepMetrics
        } else {
            metricsEngine.allRepMetrics
        }

        val sessionConfidence = if (sessionRepMetrics.isNotEmpty()) {
            sessionRepMetrics.map { it.confidence }.average().toFloat()
        } else if (isMountainClimber && mountainClimberStateMachine.latestCompletedRepMetrics != null) {
            mountainClimberStateMachine.latestCompletedRepMetrics!!.confidence
        } else if (isCalfRaise && calfRaiseStateMachine.latestCompletedRepMetrics != null) {
            calfRaiseStateMachine.latestCompletedRepMetrics!!.confidence
        } else if (isJumpingJack && jumpingJackStateMachine.latestCompletedRepMetrics != null) {
            jumpingJackStateMachine.latestCompletedRepMetrics!!.confidence
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
                allRepMetrics = sessionRepMetrics,
                allFormErrors = sessionErrors,
                allFeedbackEvents = feedbackEvents
            )
        } else {
            return outputGate.assembleSessionResult(
                visibilityStatus = visStatus,
                sessionConfidence = sessionConfidence,
                exerciseState = currentState,
                allRepMetrics = sessionRepMetrics,
                allFormErrors = sessionErrors,
                allFeedbackEvents = feedbackEvents
            )
        }
    }

    /**
     * Metrics of the most recently completed repetition, or null if no reps have finished yet.
     */
    val latestCompletedRepMetrics: RepMetrics?
        get() = when {
            isMountainClimber -> mountainClimberStateMachine.latestCompletedRepMetrics
            isCalfRaise -> calfRaiseStateMachine.latestCompletedRepMetrics
            isJumpingJack -> jumpingJackStateMachine.latestCompletedRepMetrics
            else -> metricsEngine.latestCompletedRepMetrics
        }

    /**
     * History of all completed repetition metrics recorded so far in the session.
     */
    val allRepMetrics: List<RepMetrics>
        get() = when {
            isMountainClimber -> mountainClimberStateMachine.allRepMetrics
            isCalfRaise -> calfRaiseStateMachine.allRepMetrics
            isJumpingJack -> jumpingJackStateMachine.allRepMetrics
            else -> metricsEngine.allRepMetrics
        }

    /**
     * Computes the Form score (0-100%) for a specific completed repetition index based on detected form errors.
     */
    fun getRepFormScore(repIndex: Int): Int {
        val sessionErrors = when {
            isMountainClimber -> mountainClimberFormRuleEngine.allSessionErrors
            isCalfRaise -> calfRaiseFormRuleEngine.allSessionErrors
            isJumpingJack -> jumpingJackFormRuleEngine.allSessionErrors
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
            isMountainClimber -> {
                mountainClimberVisibilityGate.reset()
                mountainClimberStateMachine.reset()
                mountainClimberFormRuleEngine.reset()
            }
            isSidePlank -> {
                sidePlankVisibilityGate.reset()
                sidePlankStateMachine.reset()
                sidePlankFormRuleEngine.reset()
            }
            isPlank -> {
                plankVisibilityGate.reset()
                plankStateMachine.reset()
                plankFormRuleEngine.reset()
            }
            isCalfRaise -> {
                calfRaiseVisibilityGate.reset()
                calfRaiseStateMachine.reset()
                calfRaiseFormRuleEngine.reset()
            }
            isJumpingJack -> {
                jumpingJackVisibilityGate.reset()
                jumpingJackStateMachine.reset()
                jumpingJackFormRuleEngine.reset()
            }
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

