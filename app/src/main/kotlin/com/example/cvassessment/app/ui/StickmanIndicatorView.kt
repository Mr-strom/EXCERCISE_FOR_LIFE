package com.example.cvassessment.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

/**
 * Clean, modern stick-figure UI illustration that visually communicates tracking quality.
 *
 * Replaces raw debug percentage lists with an intuitive human silhouette:
 * - Green limbs/torso = well-tracked body parts (visibility >= 0.40).
 * - Red limbs/torso = poorly-tracked body parts (visibility < 0.40), with a red alert glow.
 * - Soft translucent gray = neutral placement guide when no person is detected.
 * - Subtle breathing pulse animation while the 7-second setup analysis is active.
 */
class StickmanIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = context.resources.displayMetrics.density

    // Body part tracking confidences (0.0 to 1.0)
    var hasPose: Boolean = false
        private set
    var leftArmQuality: Float = 1.0f
        private set
    var rightArmQuality: Float = 1.0f
        private set
    var torsoQuality: Float = 1.0f
        private set
    var leftLegQuality: Float = 1.0f
        private set
    var rightLegQuality: Float = 1.0f
        private set

    // Colors per spec: Green (>=0.6), Red (<0.4), Yellow (0.4-0.6)
    val colorGood = Color.parseColor("#00E676")       // Vibrant Green (>= 0.6)
    val colorWarning = Color.parseColor("#FFD600")    // Amber Yellow (0.4 to 0.6)
    val colorBad = Color.parseColor("#FF5252")        // Alert Red (< 0.4)
    val colorGlow = Color.parseColor("#80FF1744")      // Red Alert Glow
    val colorNeutral = Color.parseColor("#60FFFFFF")   // Soft silhouette guide

    // Paints
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 6f * density
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 14f * density
        color = colorGlow
    }

    // Breathing pulse animation
    private var pulseScale: Float = 1.0f
    private var pulseAnimator: ValueAnimator? = null
    var isAnalyzing: Boolean = false
        private set

    init {
        startBreathingAnimation()
    }

    fun startBreathingAnimation() {
        if (pulseAnimator != null) return
        isAnalyzing = true
        pulseAnimator = ValueAnimator.ofFloat(0.96f, 1.04f).apply {
            duration = 1100L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                pulseScale = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stopBreathingAnimation() {
        isAnalyzing = false
        pulseAnimator?.cancel()
        pulseAnimator = null
        pulseScale = 1.0f
        invalidate()
    }

    /**
     * Updates limb tracking scores to update stickman colors.
     */
    fun updateTrackingQuality(
        hasPose: Boolean,
        leftArmScore: Float = 1.0f,
        rightArmScore: Float = 1.0f,
        torsoScore: Float = 1.0f,
        leftLegScore: Float = 1.0f,
        rightLegScore: Float = 1.0f
    ) {
        this.hasPose = hasPose
        this.leftArmQuality = leftArmScore
        this.rightArmQuality = rightArmScore
        this.torsoQuality = torsoScore
        this.leftLegQuality = leftLegScore
        this.rightLegQuality = rightLegScore
        invalidate()
    }

    enum class ColorTier {
        NEUTRAL,
        GOOD,
        WARNING,
        BAD
    }

    companion object {
        fun evaluateColorTier(score: Float, hasPose: Boolean): ColorTier {
            if (!hasPose) return ColorTier.NEUTRAL
            return when {
                score >= 0.60f -> ColorTier.GOOD       // GREEN: avg visibility >= 0.6
                score < 0.40f -> ColorTier.BAD         // RED: avg visibility < 0.4
                else -> ColorTier.WARNING              // YELLOW: in between (0.4 <= score < 0.6)
            }
        }
    }

    fun getColorForScore(score: Float): Int {
        return when (evaluateColorTier(score, hasPose)) {
            ColorTier.NEUTRAL -> colorNeutral
            ColorTier.GOOD -> colorGood
            ColorTier.WARNING -> colorWarning
            ColorTier.BAD -> colorBad
        }
    }

    private fun isPartBad(score: Float): Boolean {
        return hasPose && score < 0.40f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cx = w / 2f
        val cy = h / 2f

        canvas.save()
        // Apply breathing pulse scale around center
        canvas.scale(pulseScale, pulseScale, cx, cy)

        // Reference proportions based on view height
        val figureHeight = h * 0.78f
        val figureWidth = w * 0.58f

        val headRadius = figureHeight * 0.085f
        val headCenterY = cy - figureHeight * 0.38f

        val neckY = headCenterY + headRadius + 6f * density
        val pelvisY = cy + figureHeight * 0.08f

        val shoulderY = neckY + figureHeight * 0.03f
        val shoulderHalfWidth = figureWidth * 0.28f
        val leftShoulderX = cx - shoulderHalfWidth
        val rightShoulderX = cx + shoulderHalfWidth

        val elbowY = cy - figureHeight * 0.02f
        val elbowHalfWidth = figureWidth * 0.42f
        val leftElbowX = cx - elbowHalfWidth
        val rightElbowX = cx + elbowHalfWidth

        val wristY = cy + figureHeight * 0.12f
        val wristHalfWidth = figureWidth * 0.36f
        val leftWristX = cx - wristHalfWidth
        val rightWristX = cx + wristHalfWidth

        val hipHalfWidth = figureWidth * 0.16f
        val leftHipX = cx - hipHalfWidth
        val rightHipX = cx + hipHalfWidth

        val kneeY = cy + figureHeight * 0.28f
        val kneeHalfWidth = figureWidth * 0.22f
        val leftKneeX = cx - kneeHalfWidth
        val rightKneeX = cx + kneeHalfWidth

        val ankleY = cy + figureHeight * 0.46f
        val ankleHalfWidth = figureWidth * 0.25f
        val leftAnkleX = cx - ankleHalfWidth
        val rightAnkleX = cx + ankleHalfWidth

        // 1. Glow highlights on poorly tracked limbs (drawn behind)
        if (isPartBad(torsoQuality)) {
            canvas.drawCircle(cx, headCenterY, headRadius + 4f * density, glowPaint)
            canvas.drawLine(cx, neckY, cx, pelvisY, glowPaint)
        }
        if (isPartBad(leftArmQuality)) {
            canvas.drawLine(cx, neckY, leftShoulderX, shoulderY, glowPaint)
            canvas.drawLine(leftShoulderX, shoulderY, leftElbowX, elbowY, glowPaint)
            canvas.drawLine(leftElbowX, elbowY, leftWristX, wristY, glowPaint)
        }
        if (isPartBad(rightArmQuality)) {
            canvas.drawLine(cx, neckY, rightShoulderX, shoulderY, glowPaint)
            canvas.drawLine(rightShoulderX, shoulderY, rightElbowX, elbowY, glowPaint)
            canvas.drawLine(rightElbowX, elbowY, rightWristX, wristY, glowPaint)
        }
        if (isPartBad(leftLegQuality)) {
            canvas.drawLine(cx, pelvisY, leftHipX, pelvisY, glowPaint)
            canvas.drawLine(leftHipX, pelvisY, leftKneeX, kneeY, glowPaint)
            canvas.drawLine(leftKneeX, kneeY, leftAnkleX, ankleY, glowPaint)
        }
        if (isPartBad(rightLegQuality)) {
            canvas.drawLine(cx, pelvisY, rightHipX, pelvisY, glowPaint)
            canvas.drawLine(rightHipX, pelvisY, rightKneeX, kneeY, glowPaint)
            canvas.drawLine(rightKneeX, kneeY, rightAnkleX, ankleY, glowPaint)
        }

        // 2. Head & Torso
        val torsoColor = getColorForScore(torsoQuality)
        strokePaint.color = torsoColor
        fillPaint.color = torsoColor

        // Head (filled circle with inner cut or styled ring)
        canvas.drawCircle(cx, headCenterY, headRadius, strokePaint)
        canvas.drawCircle(cx, headCenterY, headRadius * 0.5f, fillPaint)

        // Spine / Torso line
        canvas.drawLine(cx, neckY, cx, pelvisY, strokePaint)

        // Pelvis crossbar
        canvas.drawLine(leftHipX, pelvisY, rightHipX, pelvisY, strokePaint)

        // 3. Left Arm (Shoulder -> Elbow -> Wrist)
        val leftArmColor = getColorForScore(leftArmQuality)
        strokePaint.color = leftArmColor
        canvas.drawLine(cx, neckY, leftShoulderX, shoulderY, strokePaint)
        canvas.drawLine(leftShoulderX, shoulderY, leftElbowX, elbowY, strokePaint)
        canvas.drawLine(leftElbowX, elbowY, leftWristX, wristY, strokePaint)

        // Joint dot at wrist
        fillPaint.color = leftArmColor
        canvas.drawCircle(leftWristX, wristY, 3.5f * density, fillPaint)

        // 4. Right Arm (Shoulder -> Elbow -> Wrist)
        val rightArmColor = getColorForScore(rightArmQuality)
        strokePaint.color = rightArmColor
        canvas.drawLine(cx, neckY, rightShoulderX, shoulderY, strokePaint)
        canvas.drawLine(rightShoulderX, shoulderY, rightElbowX, elbowY, strokePaint)
        canvas.drawLine(rightElbowX, elbowY, rightWristX, wristY, strokePaint)

        // Joint dot at wrist
        fillPaint.color = rightArmColor
        canvas.drawCircle(rightWristX, wristY, 3.5f * density, fillPaint)

        // 5. Left Leg (Hip -> Knee -> Ankle)
        val leftLegColor = getColorForScore(leftLegQuality)
        strokePaint.color = leftLegColor
        canvas.drawLine(leftHipX, pelvisY, leftKneeX, kneeY, strokePaint)
        canvas.drawLine(leftKneeX, kneeY, leftAnkleX, ankleY, strokePaint)

        // Joint dot at ankle
        fillPaint.color = leftLegColor
        canvas.drawCircle(leftAnkleX, ankleY, 3.5f * density, fillPaint)

        // 6. Right Leg (Hip -> Knee -> Ankle)
        val rightLegColor = getColorForScore(rightLegQuality)
        strokePaint.color = rightLegColor
        canvas.drawLine(rightHipX, pelvisY, rightKneeX, kneeY, strokePaint)
        canvas.drawLine(rightKneeX, kneeY, rightAnkleX, ankleY, strokePaint)

        // Joint dot at ankle
        fillPaint.color = rightLegColor
        canvas.drawCircle(rightAnkleX, ankleY, 3.5f * density, fillPaint)

        canvas.restore()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopBreathingAnimation()
    }
}
