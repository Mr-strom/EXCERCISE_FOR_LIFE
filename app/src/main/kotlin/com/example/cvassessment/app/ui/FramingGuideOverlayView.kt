package com.example.cvassessment.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Screen 2 Framing Guide Overlay.
 *
 * Step 1: Draws a semi-transparent white rectangle in the center of the camera preview
 * labeled "Keep full body here" as the target zone for user positioning.
 * The border turns bright green when the user's full body is properly framed and stable.
 */
class FramingGuideOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var isTargetSatisfied: Boolean = false

    private val fillPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.argb(20, 255, 255, 255)
        isAntiAlias = true
    }

    private val strokePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.argb(220, 255, 255, 255)
        isAntiAlias = true
    }

    private val cornerPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
        isAntiAlias = true
    }

    private val labelBgPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.argb(200, 18, 18, 18)
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 14f * resources.displayMetrics.density
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private val boxRect = RectF()
    private val labelRect = RectF()

    fun setTargetSatisfied(satisfied: Boolean) {
        if (isTargetSatisfied != satisfied) {
            isTargetSatisfied = satisfied
            if (satisfied) {
                strokePaint.color = Color.parseColor("#00E676") // Bright green
                cornerPaint.color = Color.parseColor("#00E676")
            } else {
                strokePaint.color = Color.argb(220, 255, 255, 255) // Semi-transparent white
                cornerPaint.color = Color.WHITE
            }
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Target zone: center of camera preview with margins for UI controls
        val left = w * 0.10f
        val right = w * 0.90f
        val top = h * 0.12f
        val bottom = h * 0.82f

        boxRect.set(left, top, right, bottom)
        val cornerRadius = 24f

        // 1. Semi-transparent background fill
        canvas.drawRoundRect(boxRect, cornerRadius, cornerRadius, fillPaint)

        // 2. Framing box outline
        canvas.drawRoundRect(boxRect, cornerRadius, cornerRadius, strokePaint)

        // 3. Corner emphasis brackets (viewfinder aesthetic)
        val cornerLen = 36f
        // Top-left
        canvas.drawLine(left, top + cornerRadius, left, top + cornerRadius + cornerLen, cornerPaint)
        canvas.drawLine(left + cornerRadius, top, left + cornerRadius + cornerLen, top, cornerPaint)
        // Top-right
        canvas.drawLine(right, top + cornerRadius, right, top + cornerRadius + cornerLen, cornerPaint)
        canvas.drawLine(right - cornerRadius, top, right - cornerRadius - cornerLen, top, cornerPaint)
        // Bottom-left
        canvas.drawLine(left, bottom - cornerRadius, left, bottom - cornerRadius - cornerLen, cornerPaint)
        canvas.drawLine(left + cornerRadius, bottom, left + cornerRadius + cornerLen, bottom, cornerPaint)
        // Bottom-right
        canvas.drawLine(right, bottom - cornerRadius, right, bottom - cornerRadius - cornerLen, cornerPaint)
        canvas.drawLine(right - cornerRadius, bottom, right - cornerRadius - cornerLen, bottom, cornerPaint)

        // 4. Label badge at top of box: "Keep full body here"
        val labelText = "Keep full body here"
        val textWidth = textPaint.measureText(labelText)
        val textHeight = textPaint.textSize
        val centerX = (left + right) / 2f
        val labelTop = top + 14f
        val labelPaddingX = 24f
        val labelPaddingY = 12f

        labelRect.set(
            centerX - (textWidth / 2f) - labelPaddingX,
            labelTop,
            centerX + (textWidth / 2f) + labelPaddingX,
            labelTop + textHeight + (labelPaddingY * 2f)
        )

        canvas.drawRoundRect(labelRect, 16f, 16f, labelBgPaint)
        canvas.drawText(labelText, centerX, labelTop + textHeight + (labelPaddingY * 0.5f), textPaint)
    }
}
