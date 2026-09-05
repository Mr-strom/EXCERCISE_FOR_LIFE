package com.example.cvassessment.sdk.form

import com.example.cvassessment.sdk.FeedbackEvent
import com.example.cvassessment.sdk.FormError
import com.example.cvassessment.sdk.metrics.RepMetrics
import com.example.cvassessment.sdk.pose.PoseEstimationResult
import com.example.cvassessment.sdk.pose.PoseLandmark
import com.example.cvassessment.sdk.statemachine.BicepCurlGeometry
import com.example.cvassessment.sdk.statemachine.ExercisePhase
import com.example.cvassessment.sdk.statemachine.ExerciseState
import kotlin.math.abs

/**
 * Form Rule Engine for Bicep Curl per FORM_RULES.md.
 *
 * Implements:
 * 1. excessive_momentum: severity 0.55, feedback: "Control the movement, avoid swinging."
 *    Triggers when shoulder stability angle changes beyond threshold during the curl.
 * 2. back_arching: severity 0.65, feedback: "Keep your back straight."
 *    Triggers when hip-shoulder vertical line deviates beyond threshold from vertical during the curl.
 * 3. asymmetric_movement: severity 0.50, feedback: "Keep both sides even."
 *    Triggers when left vs right elbow angle difference exceeds threshold during synchronized curling.
 *    View-aware: strictly skips check when only one arm is visible (side view).
 * 4. insufficient_depth: severity 0.60, feedback: "Full range of motion."
 *    Triggers upon rep completion when romPercent < 60%.
 *
 * Enforces Global Gating:
 * - Confidence >= 0.6
 * - Persistence >= 3 consecutive frames for movement/posture errors
 * - Cooldown of 4000ms per errorName
 * - Highest severity priority selection for audio feedback
 */
internal class BicepCurlFormRuleEngine(
    val exerciseId: String = "bicep_curl",
    val confidenceThreshold: Float = 0.6f,
    val minPersistenceFrames: Int = 3,
    val cooldownMs: Long = 4000L,
    val momentumAngleThreshold: Float = 15.0f,
    val backArchingAngleThreshold: Float = 18.0f,
    val asymmetricAngleThreshold: Float = 25.0f
) {
    private val consecutiveFrames = mutableMapOf<String, Int>()
    private val lastFeedbackTimestamps = mutableMapOf<String, Long>()

    private val _allSessionErrors = mutableListOf<FormError>()
    val allSessionErrors: List<FormError> get() = _allSessionErrors.toList()

    private val _allFeedbackEvents = mutableListOf<FeedbackEvent>()
    val allFeedbackEvents: List<FeedbackEvent> get() = _allFeedbackEvents.toList()

    var lastSkipReason: String? = null
        private set

    private var baselineTorsoAngle: Float = 0.0f
    private var lastEvaluatedRepIndex: Int? = null

    /**
     * Process full pose estimation result.
     */
    fun processFrame(
        exerciseState: ExerciseState,
        poseResult: PoseEstimationResult,
        completedRepMetrics: RepMetrics? = null,
        isVisibilitySufficient: Boolean = true
    ): FormRuleOutput {
        val armAngles = BicepCurlGeometry.computeElbowAngles(poseResult.landmarks)
        val stabilityAngle = BicepCurlGeometry.computeTorsoVerticalAngle(poseResult.landmarks)
        val isSide = BicepCurlGeometry.isSideView(poseResult.landmarks)

        return evaluateFrame(
            leftElbowAngle = armAngles.leftElbowAngle,
            rightElbowAngle = armAngles.rightElbowAngle,
            shoulderStabilityAngle = stabilityAngle,
            phase = exerciseState.phase,
            isRepInProgress = exerciseState.isRepInProgress,
            currentRepIndex = if (exerciseState.isRepInProgress) exerciseState.completeRepCount + 1 else exerciseState.completeRepCount,
            timestampMs = poseResult.timestampMs,
            confidence = if (poseResult.landmarks.isNotEmpty()) poseResult.landmarks.map { it.visibility }.average().toFloat() else 1.0f,
            completedRepMetrics = completedRepMetrics,
            landmarks = poseResult.landmarks,
            isSideViewOverride = isSide,
            isVisibilitySufficient = isVisibilitySufficient
        )
    }

    /**
     * Direct frame evaluation with explicit angles and parameters (ideal for unit testing).
     */
    fun evaluateFrame(
        leftElbowAngle: Float,
        rightElbowAngle: Float = leftElbowAngle,
        shoulderStabilityAngle: Float = 0.0f,
        phase: ExercisePhase = ExercisePhase.BOTTOM,
        isRepInProgress: Boolean = false,
        currentRepIndex: Int? = null,
        timestampMs: Long,
        confidence: Float = 1.0f,
        completedRepMetrics: RepMetrics? = null,
        landmarks: List<PoseLandmark> = emptyList(),
        isSideViewOverride: Boolean? = null,
        isVisibilitySufficient: Boolean = true
    ): FormRuleOutput {
        if (!isVisibilitySufficient) {
            consecutiveFrames.clear()
            return FormRuleOutput(
                activeErrors = emptyList(),
                allSessionErrors = _allSessionErrors.toList(),
                newFeedbackEvents = emptyList(),
                allFeedbackEvents = _allFeedbackEvents.toList()
            )
        }

        val activeErrorsThisFrame = mutableListOf<FormError>()

        // 1. Check Asymmetric Movement (View-Aware)
        val isSideView = isSideViewOverride ?: if (landmarks.isNotEmpty()) {
            BicepCurlGeometry.isSideView(landmarks)
        } else {
            false
        }

        if (isSideView) {
            lastSkipReason = "Side-view detected: only one arm visible for asymmetric_movement evaluation"
            consecutiveFrames[BicepCurlFormRules.ASYMMETRIC_MOVEMENT.errorName] = 0
        } else {
            lastSkipReason = null
            val angleDiff = abs(leftElbowAngle - rightElbowAngle)
            val isAsymmetric = angleDiff >= asymmetricAngleThreshold && isRepInProgress

            val count = (consecutiveFrames[BicepCurlFormRules.ASYMMETRIC_MOVEMENT.errorName] ?: 0) + (if (isAsymmetric) 1 else 0)
            consecutiveFrames[BicepCurlFormRules.ASYMMETRIC_MOVEMENT.errorName] = if (isAsymmetric) count else 0

            if (isAsymmetric && count >= minPersistenceFrames) {
                val error = FormError(
                    errorName = BicepCurlFormRules.ASYMMETRIC_MOVEMENT.errorName,
                    confidence = confidence,
                    repIndex = currentRepIndex,
                    severity = BicepCurlFormRules.ASYMMETRIC_MOVEMENT.severity
                )
                activeErrorsThisFrame.add(error)
                _allSessionErrors.add(error)
            }
        }

        // 2. Check Excessive Momentum (Body Swinging / Shoulder Displacement)
        if (!isRepInProgress) {
            baselineTorsoAngle = shoulderStabilityAngle
            consecutiveFrames[BicepCurlFormRules.EXCESSIVE_MOMENTUM.errorName] = 0
        } else {
            val momentumDeviation = abs(shoulderStabilityAngle - baselineTorsoAngle)
            val isMomentum = momentumDeviation >= momentumAngleThreshold

            val count = (consecutiveFrames[BicepCurlFormRules.EXCESSIVE_MOMENTUM.errorName] ?: 0) + (if (isMomentum) 1 else 0)
            consecutiveFrames[BicepCurlFormRules.EXCESSIVE_MOMENTUM.errorName] = if (isMomentum) count else 0

            if (isMomentum && count >= minPersistenceFrames) {
                val error = FormError(
                    errorName = BicepCurlFormRules.EXCESSIVE_MOMENTUM.errorName,
                    confidence = confidence,
                    repIndex = currentRepIndex,
                    severity = BicepCurlFormRules.EXCESSIVE_MOMENTUM.severity
                )
                activeErrorsThisFrame.add(error)
                _allSessionErrors.add(error)
            }
        }

        // 3. Check Back Arching (Spine deviation from vertical)
        val isArching = shoulderStabilityAngle >= backArchingAngleThreshold && isRepInProgress
        val archCount = (consecutiveFrames[BicepCurlFormRules.BACK_ARCHING.errorName] ?: 0) + (if (isArching) 1 else 0)
        consecutiveFrames[BicepCurlFormRules.BACK_ARCHING.errorName] = if (isArching) archCount else 0

        if (isArching && archCount >= minPersistenceFrames) {
            val error = FormError(
                errorName = BicepCurlFormRules.BACK_ARCHING.errorName,
                confidence = confidence,
                repIndex = currentRepIndex,
                severity = BicepCurlFormRules.BACK_ARCHING.severity
            )
            activeErrorsThisFrame.add(error)
            _allSessionErrors.add(error)
        }

        // 4. Check Insufficient Depth on Completed Rep
        if (completedRepMetrics != null && completedRepMetrics.repIndex != lastEvaluatedRepIndex) {
            lastEvaluatedRepIndex = completedRepMetrics.repIndex
            if (completedRepMetrics.romPercent < 60.0f) {
                val depthError = FormError(
                    errorName = BicepCurlFormRules.INSUFFICIENT_DEPTH.errorName,
                    confidence = completedRepMetrics.confidence,
                    repIndex = completedRepMetrics.repIndex,
                    severity = BicepCurlFormRules.INSUFFICIENT_DEPTH.severity
                )
                activeErrorsThisFrame.add(depthError)
                _allSessionErrors.add(depthError)
            }
        }

        // Feedback Gating: Confidence >= 0.6, Cooldown 4000ms, Highest-Severity Selection
        val newFeedbackEvents = mutableListOf<FeedbackEvent>()
        if (confidence >= confidenceThreshold && activeErrorsThisFrame.isNotEmpty()) {
            val eligibleCandidates = activeErrorsThisFrame.filter { error ->
                val lastTime = lastFeedbackTimestamps[error.errorName] ?: -cooldownMs
                (timestampMs - lastTime) >= cooldownMs
            }

            val highestSeverityError = eligibleCandidates.maxByOrNull { it.severity }
            if (highestSeverityError != null) {
                val ruleDef = BicepCurlFormRules.ALL_BICEP_CURL_RULES.find { it.errorName == highestSeverityError.errorName }
                val message = ruleDef?.feedbackMessage ?: "Control your form."

                val event = FeedbackEvent(
                    message = message,
                    timestampMs = timestampMs,
                    relatedError = highestSeverityError.errorName
                )
                newFeedbackEvents.add(event)
                _allFeedbackEvents.add(event)
                lastFeedbackTimestamps[highestSeverityError.errorName] = timestampMs
            }
        }

        return FormRuleOutput(
            activeErrors = activeErrorsThisFrame,
            allSessionErrors = _allSessionErrors.toList(),
            newFeedbackEvents = newFeedbackEvents,
            allFeedbackEvents = _allFeedbackEvents.toList()
        )
    }

    /**
     * Resets all internal state and error tracking.
     */
    fun reset() {
        consecutiveFrames.clear()
        lastFeedbackTimestamps.clear()
        _allSessionErrors.clear()
        _allFeedbackEvents.clear()
        lastSkipReason = null
        baselineTorsoAngle = 0.0f
        lastEvaluatedRepIndex = null
    }
}
