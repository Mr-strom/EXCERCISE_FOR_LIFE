package com.example.cvassessment.app.ui

import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Manages TextToSpeech initialization, readiness lifecycle, message queuing,
 * and explicit diagnostics logging for real-time exercise feedback.
 */
class TtsFeedbackController(
    private val speakDelegate: (String) -> Int,
    private val setLanguageDelegate: (Locale) -> Int,
    private val logInfo: (String) -> Unit,
    private val logWarn: (String) -> Unit = { },
    private val logError: (String) -> Unit = { }
) {
    var isInitialized: Boolean = false
        private set

    var pendingMessage: String? = null
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
     * Handles TextToSpeech.OnInitListener callback.
     */
    fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val langResult = setLanguageDelegate(Locale.US)
            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                isInitialized = false
                logE("TTS initialization failed: Locale.US is not supported or missing data (result code: $langResult)")
            } else {
                isInitialized = true
                logI("TTS initialized successfully (status: SUCCESS)")
                pendingMessage?.let { queued ->
                    logI("Playing queued feedback message after init: $queued")
                    pendingMessage = null
                    speak(queued)
                }
            }
        } else {
            isInitialized = false
            logE("TTS initialization failed with code: $status (TextToSpeech.ERROR)")
        }
    }

    /**
     * Speaks audio text or queues it if TTS is still initializing.
     */
    fun speak(text: String): Boolean {
        if (!isInitialized) {
            logW("TTS not initialized yet. Queuing message: $text")
            pendingMessage = text
            return false
        }
        logI("TTS speaking: $text")
        val result = speakDelegate(text)
        if (result != TextToSpeech.SUCCESS) {
            logW("TTS speak failed with result code: $result")
            return false
        }
        return true
    }
}
