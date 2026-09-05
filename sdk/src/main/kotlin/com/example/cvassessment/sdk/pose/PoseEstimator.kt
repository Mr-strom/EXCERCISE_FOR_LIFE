package com.example.cvassessment.sdk.pose

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker

/**
 * Module 2 (Pose Estimation Layer) wrapper around MediaPipe BlazePose.
 * Loads the local model asset and extracts 33 3D landmarks with visibility scores.
 */
class PoseEstimator(
    private val context: Context,
    private val modelAssetPath: String = "pose_landmarker_full.task",
    private val minPoseDetectionConfidence: Float = 0.5f,
    private val minPosePresenceConfidence: Float = 0.5f,
    private val minTrackingConfidence: Float = 0.5f
) : AutoCloseable {

    companion object {
        private const val TAG = "PoseEstimator"
    }

    private var poseLandmarker: PoseLandmarker? = null

    init {
        initializeLandmarker()
    }

    private fun initializeLandmarker() {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(modelAssetPath)
                .build()

            val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setMinPoseDetectionConfidence(minPoseDetectionConfidence)
                .setMinPosePresenceConfidence(minPosePresenceConfidence)
                .setMinTrackingConfidence(minTrackingConfidence)
                .setRunningMode(RunningMode.IMAGE)
                .build()

            poseLandmarker = PoseLandmarker.createFromOptions(context, options)
            Log.i(TAG, "MediaPipe BlazePose initialized successfully from asset: $modelAssetPath")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaPipe PoseLandmarker from asset: $modelAssetPath", e)
        }
    }

    /**
     * Process a camera frame bitmap and extract 33 landmarks with visibility scores.
     *
     * @param bitmap Right-side up frame bitmap matching camera orientation
     * @param timestampMs Frame wall-clock timestamp
     * @return PoseEstimationResult containing 33 landmarks or empty result if no pose detected
     */
    fun detect(bitmap: Bitmap, timestampMs: Long): PoseEstimationResult {
        val landmarker = poseLandmarker ?: return PoseEstimationResult.empty(timestampMs)

        return try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = landmarker.detect(mpImage)

            val landmarkList = result.landmarks()
            if (landmarkList.isEmpty() || landmarkList[0].isEmpty()) {
                PoseEstimationResult.empty(timestampMs)
            } else {
                val detected = landmarkList[0].mapIndexed { index, normalizedLandmark ->
                    PoseLandmark(
                        index = index,
                        name = PoseLandmarkType.getName(index),
                        x = normalizedLandmark.x(),
                        y = normalizedLandmark.y(),
                        z = normalizedLandmark.z(),
                        visibility = if (normalizedLandmark.visibility().isPresent) {
                            normalizedLandmark.visibility().get()
                        } else {
                            1.0f
                        },
                        presence = if (normalizedLandmark.presence().isPresent) {
                            normalizedLandmark.presence().get()
                        } else {
                            1.0f
                        }
                    )
                }
                PoseEstimationResult(
                    landmarks = detected,
                    timestampMs = timestampMs,
                    hasPose = true
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing pose detection on frame", e)
            PoseEstimationResult.empty(timestampMs)
        }
    }

    override fun close() {
        try {
            poseLandmarker?.close()
            poseLandmarker = null
            Log.i(TAG, "PoseLandmarker released")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing PoseLandmarker", e)
        }
    }
}
