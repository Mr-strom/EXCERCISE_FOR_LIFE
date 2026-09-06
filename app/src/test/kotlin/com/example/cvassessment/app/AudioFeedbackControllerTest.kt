package com.example.cvassessment.app

import com.example.cvassessment.app.ui.AudioFeedbackController
import com.example.cvassessment.app.ui.FeedbackAudioCatalog
import com.example.cvassessment.sdk.FeedbackEvent
import com.example.cvassessment.sdk.form.BicepCurlFormRules
import com.example.cvassessment.sdk.form.CalfRaiseFormRules
import com.example.cvassessment.sdk.form.JumpingJackFormRules
import com.example.cvassessment.sdk.form.LungeFormRules
import com.example.cvassessment.sdk.form.MountainClimberFormRules
import com.example.cvassessment.sdk.form.PlankFormRules
import com.example.cvassessment.sdk.form.PushUpFormRules
import com.example.cvassessment.sdk.form.ShoulderPressFormRules
import com.example.cvassessment.sdk.form.SidePlankFormRules
import com.example.cvassessment.sdk.form.SquatFormRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying pre-recorded audio clip playback and graceful TTS fallback across all 10 exercises.
 */
class AudioFeedbackControllerTest {

    @Test
    fun testFeedbackAudioCatalogHasAllRequiredFilenames() {
        val expected = listOf(
            "keep_hips_up.mp3",
            "lower_hips_slightly.mp3",
            "go_lower.mp3",
            "full_range_of_motion.mp3",
            "fully_extend_at_the_top.mp3",
            "push_knees_out.mp3",
            "keep_chest_up.mp3",
            "control_movement_avoid_swinging.mp3",
            "keep_back_straight.mp3",
            "keep_both_sides_even.mp3",
            "keep_body_in_straight_line.mp3",
            "slow_down_control_movement.mp3",
            "sync_arms_and_legs.mp3",
            "drive_knee_further_forward.mp3",
            "cant_see_you.mp3"
        )
        assertEquals(expected, FeedbackAudioCatalog.EXPECTED_CLIP_FILENAMES)
    }

    @Test
    fun testAllTenExerciseRulesResolveToExpectedAudioClips() {
        // Push-Up rules
        assertEquals("keep_hips_up", FeedbackAudioCatalog.resolveResourceName("hips_dropping", "Keep your hips up."))
        assertEquals("lower_hips_slightly", FeedbackAudioCatalog.resolveResourceName("hips_piking", "Lower your hips slightly."))
        assertEquals("go_lower", FeedbackAudioCatalog.resolveResourceName("insufficient_depth", "Go lower."))
        assertEquals("fully_extend_at_the_top", FeedbackAudioCatalog.resolveResourceName("incomplete_lockout", "Fully extend at the top."))

        // Squat rules
        assertEquals("push_knees_out", FeedbackAudioCatalog.resolveResourceName("knee_valgus", "Push your knees out."))
        assertEquals("keep_chest_up", FeedbackAudioCatalog.resolveResourceName("excessive_lean", "Keep your chest up."))

        // Bicep Curl & Shoulder Press rules
        assertEquals("control_movement_avoid_swinging", FeedbackAudioCatalog.resolveResourceName("excessive_momentum", "Control the movement, avoid swinging."))
        assertEquals("keep_back_straight", FeedbackAudioCatalog.resolveResourceName("back_arching", "Keep your back straight."))
        assertEquals("keep_both_sides_even", FeedbackAudioCatalog.resolveResourceName("asymmetric_movement", "Keep both sides even."))
        assertEquals("full_range_of_motion", FeedbackAudioCatalog.resolveResourceName("insufficient_depth", "Full range of motion."))

        // Plank & Side Plank rules
        assertEquals("keep_body_in_straight_line", FeedbackAudioCatalog.resolveResourceName("postural_break", "Keep your body in a straight line."))

        // Calf Raise & Jumping Jack rules
        assertEquals("slow_down_control_movement", FeedbackAudioCatalog.resolveResourceName("rushing_tempo", "Slow down, control the movement."))
        assertEquals("sync_arms_and_legs", FeedbackAudioCatalog.resolveResourceName("asymmetric_jack", "Sync your arms and legs."))

        // Mountain Climber rules
        assertEquals("drive_knee_further_forward", FeedbackAudioCatalog.resolveResourceName("incomplete_leg_drive", "Drive your knee further forward."))

        // Visibility warning
        assertEquals("cant_see_you", FeedbackAudioCatalog.resolveResourceName("cant_see_you", "Can't see you clearly — adjust your position"))
        assertEquals("cant_see_you", FeedbackAudioCatalog.resolveResourceName(null, "Can't see you clearly — step into camera view"))

        // Comprehensive verification: EVERY rule in all 10 catalogs resolves to a valid clip!
        val allExerciseRules = listOf(
            PushUpFormRules.ALL_PUSH_UP_RULES,
            SquatFormRules.ALL_SQUAT_RULES,
            BicepCurlFormRules.ALL_BICEP_CURL_RULES,
            ShoulderPressFormRules.ALL_SHOULDER_PRESS_RULES,
            LungeFormRules.ALL_LUNGE_RULES,
            CalfRaiseFormRules.ALL_CALF_RAISE_RULES,
            PlankFormRules.ALL_PLANK_RULES,
            SidePlankFormRules.ALL_SIDE_PLANK_RULES,
            JumpingJackFormRules.ALL_JUMPING_JACK_RULES,
            MountainClimberFormRules.ALL_MOUNTAIN_CLIMBER_RULES
        ).flatten()

        for (rule in allExerciseRules) {
            val clip = FeedbackAudioCatalog.resolveResourceName(rule.errorName, rule.feedbackMessage)
            assertNotNull("Rule '${rule.errorName}' with msg '${rule.feedbackMessage}' must resolve to an audio clip", clip)
        }
    }

    @Test
    fun testPlayingFeedbackEventPlaysCorrectPreRecordedClipWhenAvailable() {
        val playedClips = mutableListOf<String>()
        val ttsSpoken = mutableListOf<String>()

        val controller = AudioFeedbackController(
            playClipDelegate = { clipName ->
                playedClips.add(clipName)
                true // Clip exists and plays successfully
            },
            ttsFallbackDelegate = { msg ->
                ttsSpoken.add(msg)
                true
            }
        )

        val event = FeedbackEvent(
            message = "Push your knees out.",
            timestampMs = 1500L,
            relatedError = "knee_valgus"
        )

        val result = controller.playFeedback(event)
        assertTrue("Playback should report success", result)
        assertEquals(listOf("push_knees_out"), playedClips)
        assertEquals("push_knees_out", controller.lastPlayedClip)
        assertTrue("TTS fallback should NOT be called when audio clip is available", ttsSpoken.isEmpty())
        assertNull(controller.lastSpokenFallback)
    }

    @Test
    fun testMissingClipFallsBackToTtsGracefullyWithoutCrashing() {
        val playedClipsAttempted = mutableListOf<String>()
        val ttsSpoken = mutableListOf<String>()

        val controller = AudioFeedbackController(
            playClipDelegate = { clipName ->
                playedClipsAttempted.add(clipName)
                false // Clip file does not exist in res/raw (missing)
            },
            ttsFallbackDelegate = { msg ->
                ttsSpoken.add(msg)
                true
            }
        )

        val event = FeedbackEvent(
            message = "Keep your chest up.",
            timestampMs = 2000L,
            relatedError = "excessive_lean"
        )

        val result = controller.playFeedback(event)
        assertTrue("Fallback TTS should handle playback", result)
        assertEquals(listOf("keep_chest_up"), playedClipsAttempted)
        assertEquals(listOf("Keep your chest up."), ttsSpoken)
        assertEquals("Keep your chest up.", controller.lastSpokenFallback)
        assertTrue(controller.logs.any { it.contains("missing or unavailable, falling back to TTS") })
    }

    @Test
    fun testUnmappedMessageFallsBackToTtsGracefully() {
        val playedClipsAttempted = mutableListOf<String>()
        val ttsSpoken = mutableListOf<String>()

        val controller = AudioFeedbackController(
            playClipDelegate = { clipName ->
                playedClipsAttempted.add(clipName)
                true
            },
            ttsFallbackDelegate = { msg ->
                ttsSpoken.add(msg)
                true
            }
        )

        val result = controller.playFeedback("custom_error", "Check your form.")
        assertTrue("TTS fallback should speak unmapped message", result)
        assertTrue("No clip should have been attempted for unmapped message", playedClipsAttempted.isEmpty())
        assertEquals(listOf("Check your form."), ttsSpoken)
        assertEquals("Check your form.", controller.lastSpokenFallback)
    }

    @Test
    fun testClipPlaybackExceptionFallsBackToTtsGracefully() {
        val ttsSpoken = mutableListOf<String>()

        val controller = AudioFeedbackController(
            playClipDelegate = {
                throw IllegalStateException("Simulated MediaPlayer failure")
            },
            ttsFallbackDelegate = { msg ->
                ttsSpoken.add(msg)
                true
            }
        )

        val result = controller.playFeedback("hips_dropping", "Keep your hips up.")
        assertTrue("Should succeed via TTS fallback", result)
        assertEquals(listOf("Keep your hips up."), ttsSpoken)
        assertTrue(controller.logs.any { it.contains("Clip playback error for 'keep_hips_up'") })
    }
}
