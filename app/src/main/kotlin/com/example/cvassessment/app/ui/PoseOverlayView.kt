package com.example.cvassessment.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.example.cvassessment.sdk.pose.PoseLandmark
import com.example.cvassessment.sdk.pose.PoseLandmarkType

/**
 * Visual debug overlay view drawn on top of the camera preview.
 * Renders the 33-point BlazePose skeleton with visibility-coded joint indicators.
 */
class PoseOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var landmarks: List<PoseLandmark> = emptyList()
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1
    private var isFrontCamera: Boolean = false

    private val linePaint = Paint().apply {
        color = Color.parseColor("#00E5FF") // Bright cyan
        strokeWidth = 8f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    private val pointHighVisPaint = Paint().apply {
        color = Color.parseColor("#00E676") // Green
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val pointMedVisPaint = Paint().apply {
        color = Color.parseColor("#FFD600") // Yellow
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val pointLowVisPaint = Paint().apply {
        color = Color.parseColor("#FF1744") // Red
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val pointRingPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    /**
     * Update the landmark list from a frame analysis result.
     */
    fun updatePose(
        detectedLandmarks: List<PoseLandmark>,
        frameWidth: Int,
        frameHeight: Int,
        isFront: Boolean
    ) {
        this.landmarks = detectedLandmarks
        this.imageWidth = if (frameWidth > 0) frameWidth else 1
        this.imageHeight = if (frameHeight > 0) frameHeight else 1
        this.isFrontCamera = isFront
        postInvalidate()
    }

    fun clear() {
        this.landmarks = emptyList()
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (landmarks.isEmpty() || width == 0 || height == 0) return

        // Compute aspect-fill scaling to match PreviewView.ScaleType.FILL_CENTER
        val scale = maxOf(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
        val offsetX = (width - imageWidth * scale) / 2f
        val offsetY = (height - imageHeight * scale) / 2f

        fun toScreenX(normX: Float): Float {
            return normX * imageWidth * scale + offsetX
        }

        fun toScreenY(normY: Float): Float {
            return normY * imageHeight * scale + offsetY
        }

        // 1. Draw skeleton connection bones
        for ((startIdx, endIdx) in PoseLandmarkType.SKELETON_CONNECTIONS) {
            if (startIdx < landmarks.size && endIdx < landmarks.size) {
                val p1 = landmarks[startIdx]
                val p2 = landmarks[endIdx]

                // Only render connection if both landmarks have reasonable visibility
                if (p1.visibility >= 0.25f && p2.visibility >= 0.25f) {
                    canvas.drawLine(
                        toScreenX(p1.x),
                        toScreenY(p1.y),
                        toScreenX(p2.x),
                        toScreenY(p2.y),
                        linePaint
                    )
                }
            }
        }

        // 2. Draw landmark joint points with visibility-coded colors
        for (landmark in landmarks) {
            val sx = toScreenX(landmark.x)
            val sy = toScreenY(landmark.y)

            val paint = when {
                landmark.visibility >= 0.75f -> pointHighVisPaint
                landmark.visibility >= 0.50f -> pointMedVisPaint
                else -> pointLowVisPaint
            }

            val radius = if (landmark.index in 11..28) 12f else 8f

            canvas.drawCircle(sx, sy, radius, paint)
            canvas.drawCircle(sx, sy, radius, pointRingPaint)
        }
    }
}
