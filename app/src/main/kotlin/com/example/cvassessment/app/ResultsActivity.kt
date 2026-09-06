package com.example.cvassessment.app

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.cvassessment.sdk.SessionResult
import com.example.cvassessment.sdk.ValidationStatus

/**
 * Screen 4 — View Results (per ANDROID_FLOW.md).
 *
 * Displays full SessionResult schema per SDK_CONTRACT.md in human-readable form.
 * - Displays Status, Confidence, Rep counts, ROM%, TuT Factor, Form Factor.
 * - Displays list of form errors encountered.
 * - If status is INSUFFICIENT_VISIBILITY, clearly shows explanatory notice.
 * - Explicitly NO score field per R9 (scoring is the platform's job, not the SDK).
 * - "Done" button returns to Screen 1 (Exercise Select).
 */
class ResultsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_EXERCISE_NAME = "EXTRA_EXERCISE_NAME"
        const val EXTRA_SESSION_RESULT = "EXTRA_SESSION_RESULT"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_results)

        val exerciseName = intent.getStringExtra(EXTRA_EXERCISE_NAME) ?: "Push-Up"

        // Retrieve SessionResult from Intent or SessionDataHolder fallback
        @Suppress("DEPRECATION")
        val sessionResult: SessionResult? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(EXTRA_SESSION_RESULT, SessionResult::class.java)
        } else {
            intent.getSerializableExtra(EXTRA_SESSION_RESULT) as? SessionResult
        } ?: SessionDataHolder.latestResult

        // UI references
        val tvExerciseTitle = findViewById<TextView>(R.id.tvResultsExerciseTitle)
        val tvResultStatus = findViewById<TextView>(R.id.tvResultStatus)
        val tvResultConfidence = findViewById<TextView>(R.id.tvResultConfidence)
        val tvNotice = findViewById<TextView>(R.id.tvInsufficientVisibilityNotice)
        val tvCompleteReps = findViewById<TextView>(R.id.tvCompleteReps)
        val tvIncompleteReps = findViewById<TextView>(R.id.tvIncompleteReps)
        val tvAvgDuration = findViewById<TextView>(R.id.tvAvgDuration)
        val tvRomPercent = findViewById<TextView>(R.id.tvRomPercent)
        val tvTutFactor = findViewById<TextView>(R.id.tvTutFactor)
        val tvFormFactor = findViewById<TextView>(R.id.tvFormFactor)
        val tvFormErrorsList = findViewById<TextView>(R.id.tvFormErrorsList)
        val tvFeedbackEventsList = findViewById<TextView>(R.id.tvFeedbackEventsList)
        val btnDone = findViewById<Button>(R.id.btnDone)

        tvExerciseTitle.text = exerciseName

        if (sessionResult != null) {
            // Validation Status
            tvResultStatus.text = sessionResult.status.name
            when (sessionResult.status) {
                ValidationStatus.VALID -> {
                    tvResultStatus.setTextColor(Color.parseColor("#4CAF50"))
                    tvNotice.visibility = View.GONE
                }
                ValidationStatus.INVALID -> {
                    tvResultStatus.setTextColor(Color.parseColor("#FF9800"))
                    tvNotice.visibility = View.GONE
                }
                ValidationStatus.INSUFFICIENT_VISIBILITY -> {
                    tvResultStatus.setTextColor(Color.parseColor("#E53935"))
                    tvNotice.visibility = View.VISIBLE
                }
            }

            // Confidence
            val confPct = (sessionResult.confidence * 100).toInt()
            tvResultConfidence.text = "${sessionResult.confidence} ($confPct%)"

            // Reps & Durations
            if (sessionResult.holdDurationSec != null) {
                tvCompleteReps.text = "${sessionResult.holdDurationSec}s (Hold Duration)"
                tvIncompleteReps.text = "N/A (Static Hold)"
                tvAvgDuration.text = "N/A (Static Hold)"
            } else {
                tvCompleteReps.text = sessionResult.completeReps?.toString() ?: "N/A (Suppressed)"
                tvIncompleteReps.text = sessionResult.incompleteReps?.toString() ?: "N/A (Suppressed)"
                tvAvgDuration.text = if (sessionResult.avgRepDurationSec != null) "${sessionResult.avgRepDurationSec} s" else "N/A"
            }

            // Algorithmic Metrics
            val rom = sessionResult.romPercent
            tvRomPercent.text = if (rom != null) "${rom.toInt()}%" else "N/A (Suppressed)"
            tvTutFactor.text = sessionResult.tutFactor?.toString() ?: "N/A (Suppressed)"
            tvFormFactor.text = sessionResult.formFactor?.toString() ?: "N/A (Suppressed)"

            // Form Errors List
            if (sessionResult.formErrors.isEmpty()) {
                tvFormErrorsList.text = "• None detected"
                tvFormErrorsList.setTextColor(Color.parseColor("#81C784"))
            } else {
                val sb = StringBuilder()
                sessionResult.formErrors.forEach { err ->
                    val repText = if (err.repIndex != null) " (Rep ${err.repIndex})" else ""
                    val conf = (err.confidence * 100).toInt()
                    sb.append("• ${err.errorName}$repText — Severity: ${err.severity}, Conf: $conf%\n")
                }
                tvFormErrorsList.text = sb.toString().trimEnd()
                tvFormErrorsList.setTextColor(Color.parseColor("#FFB74D"))
            }

            // Feedback Events Log
            if (sessionResult.feedbackEvents.isEmpty()) {
                tvFeedbackEventsList.text = "• None"
            } else {
                val sb = StringBuilder()
                sessionResult.feedbackEvents.forEach { event ->
                    sb.append("• \"${event.message}\"\n")
                }
                tvFeedbackEventsList.text = sb.toString().trimEnd()
            }
        } else {
            tvResultStatus.text = "NO DATA"
            tvResultConfidence.text = "--"
        }

        // Return to Screen 1
        btnDone.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
            finish()
        }
    }
}
