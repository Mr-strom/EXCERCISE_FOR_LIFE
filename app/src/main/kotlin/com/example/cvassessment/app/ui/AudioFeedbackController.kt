package com.example.cvassessment.app.ui

import com.example.cvassessment.sdk.FeedbackEvent

/**
 * Catalog defining all required pre-recorded audio clip resource identifiers
 * across ALL 10 exercises per FORM_RULES.md, EXERCISE_SPEC.md, and VISIBILITY_POLICY.md.
 */
object FeedbackAudioCatalog {
    const val CLIP_KEEP_HIPS_UP = "keep_hips_up"
    const val CLIP_LOWER_HIPS_SLIGHTLY = "lower_hips_slightly"
    const val CLIP_GO_LOWER = "go_lower"
    const val CLIP_FULL_RANGE_OF_MOTION = "full_range_of_motion"
    const val CLIP_FULLY_EXTEND_AT_THE_TOP = "fully_extend_at_the_top"
    const val CLIP_PUSH_KNEES_OUT = "push_knees_out"
    const val CLIP_KEEP_CHEST_UP = "keep_chest_up"
    const val CLIP_CONTROL_MOVEMENT = "control_movement_avoid_swinging"
    const val CLIP_KEEP_BACK_STRAIGHT = "keep_back_straight"
    const val CLIP_KEEP_BOTH_SIDES_EVEN = "keep_both_sides_even"
    const val CLIP_KEEP_BODY_IN_STRAIGHT_LINE = "keep_body_in_straight_line"
    const val CLIP_SLOW_DOWN = "slow_down_control_movement"
    const val CLIP_SYNC_ARMS_AND_LEGS = "sync_arms_and_legs"
    const val CLIP_DRIVE_KNEE_FORWARD = "drive_knee_further_forward"
    const val CLIP_CANT_SEE_YOU = "cant_see_you"

    /**
     * Complete list of expected audio clip filenames across all 10 exercises.
     * Audio files placed in `app/src/main/res/raw/` can be either .mp3 or .wav.
     */
    val EXPECTED_CLIP_FILENAMES = listOf(
        "$CLIP_KEEP_HIPS_UP.mp3",
        "$CLIP_LOWER_HIPS_SLIGHTLY.mp3",
        "$CLIP_GO_LOWER.mp3",
        "$CLIP_FULL_RANGE_OF_MOTION.mp3",
        "$CLIP_FULLY_EXTEND_AT_THE_TOP.mp3",
        "$CLIP_PUSH_KNEES_OUT.mp3",
        "$CLIP_KEEP_CHEST_UP.mp3",
        "$CLIP_CONTROL_MOVEMENT.mp3",
        "$CLIP_KEEP_BACK_STRAIGHT.mp3",
        "$CLIP_KEEP_BOTH_SIDES_EVEN.mp3",
        "$CLIP_KEEP_BODY_IN_STRAIGHT_LINE.mp3",
        "$CLIP_SLOW_DOWN.mp3",
        "$CLIP_SYNC_ARMS_AND_LEGS.mp3",
        "$CLIP_DRIVE_KNEE_FORWARD.mp3",
        "$CLIP_CANT_SEE_YOU.mp3"
    )

    /**
     * Resolves the corresponding raw resource name from errorName or feedback message text.
     */
    fun resolveResourceName(errorName: String?, message: String?): String? {
        val err = errorName?.trim()?.lowercase()
        val msg = message?.trim()?.lowercase() ?: ""

        return when {
            err == "hips_dropping" || msg.contains("keep your hips up") -> CLIP_KEEP_HIPS_UP
            err == "hips_piking" || msg.contains("lower your hips") -> CLIP_LOWER_HIPS_SLIGHTLY
            msg.contains("full range of motion") -> CLIP_FULL_RANGE_OF_MOTION
            err == "insufficient_depth" || msg.contains("go lower") -> CLIP_GO_LOWER
            err == "incomplete_lockout" || msg.contains("fully extend") -> CLIP_FULLY_EXTEND_AT_THE_TOP
            err == "knee_valgus" || msg.contains("push your knees out") -> CLIP_PUSH_KNEES_OUT
            err == "excessive_lean" || msg.contains("keep your chest up") -> CLIP_KEEP_CHEST_UP
            err == "excessive_momentum" || msg.contains("avoid swinging") -> CLIP_CONTROL_MOVEMENT
            err == "back_arching" || msg.contains("keep your back straight") -> CLIP_KEEP_BACK_STRAIGHT
            err == "asymmetric_movement" || msg.contains("keep both sides even") -> CLIP_KEEP_BOTH_SIDES_EVEN
            err == "postural_break" || msg.contains("straight line") -> CLIP_KEEP_BODY_IN_STRAIGHT_LINE
            err == "rushing_tempo" || msg.contains("slow down") -> CLIP_SLOW_DOWN
            err == "asymmetric_jack" || msg.contains("sync your arms") -> CLIP_SYNC_ARMS_AND_LEGS
            err == "incomplete_leg_drive" || msg.contains("drive your knee") -> CLIP_DRIVE_KNEE_FORWARD
            err == "cant_see_you" || err == "insufficient_visibility" || msg.contains("can't see you") || msg.contains("cant see you") -> CLIP_CANT_SEE_YOU
            else -> null
        }
    }
}

/**
 * Controller responsible for playing pre-recorded audio clips with a seamless, graceful
 * fallback to TextToSpeech when an audio file is missing or fails to play.
 */
class AudioFeedbackController(
    private val playClipDelegate: (resourceName: String) -> Boolean,
    private val ttsFallbackDelegate: (message: String) -> Boolean,
    private val logInfo: (String) -> Unit = {},
    private val logWarn: (String) -> Unit = {},
    private val logError: (String) -> Unit = {}
) {
    var lastPlayedClip: String? = null
        private set

    var lastSpokenFallback: String? = null
        private set

    val logs = mutableListOf<String>()

    private fun logI(msg: String) {
        logs.add("INFO: $msg")
        logInfo(msg)
    }

    private fun logW(msg: String) {
        logs.add("WARN: $msg")
        logWarn(msg)
    }

    private fun logE(msg: String) {
        logs.add("ERROR: $msg")
        logError(msg)
    }

    /**
     * Plays the audio clip matching [errorName] or [message].
     * If no audio clip is found or playback cannot start, falls back to TTS.
     */
    fun playFeedback(errorName: String?, message: String): Boolean {
        val resourceName = FeedbackAudioCatalog.resolveResourceName(errorName, message)
        if (resourceName != null) {
            val clipPlayed = try {
                playClipDelegate(resourceName)
            } catch (e: Exception) {
                logW("Clip playback error for '$resourceName': ${e.message}")
                false
            }

            if (clipPlayed) {
                logI("Playing pre-recorded audio clip: $resourceName for '$message'")
                lastPlayedClip = resourceName
                return true
            } else {
                logI("Audio clip '$resourceName' missing or unavailable, falling back to TTS for: \"$message\"")
            }
        } else {
            logI("No audio clip mapped for error='$errorName', message='$message', falling back to TTS")
        }

        // Graceful fallback to TTS
        lastSpokenFallback = message
        return ttsFallbackDelegate(message)
    }

    /**
     * Convenience overload for [FeedbackEvent].
     */
    fun playFeedback(event: FeedbackEvent): Boolean {
        return playFeedback(event.relatedError, event.message)
    }
}
