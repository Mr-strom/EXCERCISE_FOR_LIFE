package com.example.cvassessment.app.camera

import android.graphics.Bitmap
import com.example.cvassessment.sdk.CameraFrame

/**
 * Concrete implementation of CameraFrame delivered by Android CameraX.
 */
data class AndroidCameraFrame(
    override val width: Int,
    override val height: Int,
    override val rotationDegrees: Int,
    override val timestampMs: Long,
    val bitmap: Bitmap? = null
) : CameraFrame
