package com.example.cvassessment.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Screen 1 — Exercise Select (per ANDROID_FLOW.md).
 *
 * User selects an exercise from the available list.
 * Currently hardcoded to Push-Up for this slice (other exercises fan out in Phase 2).
 * Tapping an exercise passes exerciseId and exerciseName to Screen 2 (StartCameraActivity).
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_exercise_select)

        val cardPushUp = findViewById<View>(R.id.cardPushUp)
        cardPushUp.setOnClickListener {
            SessionDataHolder.selectedExerciseId = "push_up"
            SessionDataHolder.selectedExerciseName = "Push-Up"

            val intent = Intent(this, StartCameraActivity::class.java).apply {
                putExtra(StartCameraActivity.EXTRA_EXERCISE_ID, "push_up")
                putExtra(StartCameraActivity.EXTRA_EXERCISE_NAME, "Push-Up")
            }
            startActivity(intent)
        }

        val cardBicepCurl = findViewById<View>(R.id.cardBicepCurl)
        cardBicepCurl.setOnClickListener {
            SessionDataHolder.selectedExerciseId = "bicep_curl"
            SessionDataHolder.selectedExerciseName = "Bicep Curl"

            val intent = Intent(this, StartCameraActivity::class.java).apply {
                putExtra(StartCameraActivity.EXTRA_EXERCISE_ID, "bicep_curl")
                putExtra(StartCameraActivity.EXTRA_EXERCISE_NAME, "Bicep Curl")
            }
            startActivity(intent)
        }

        val cardShoulderPress = findViewById<View>(R.id.cardShoulderPress)
        cardShoulderPress.setOnClickListener {
            SessionDataHolder.selectedExerciseId = "shoulder_press"
            SessionDataHolder.selectedExerciseName = "Shoulder Press"

            val intent = Intent(this, StartCameraActivity::class.java).apply {
                putExtra(StartCameraActivity.EXTRA_EXERCISE_ID, "shoulder_press")
                putExtra(StartCameraActivity.EXTRA_EXERCISE_NAME, "Shoulder Press")
            }
            startActivity(intent)
        }

        val cardSquat = findViewById<View>(R.id.cardSquat)
        cardSquat.setOnClickListener {
            SessionDataHolder.selectedExerciseId = "squat"
            SessionDataHolder.selectedExerciseName = "Squat"

            val intent = Intent(this, StartCameraActivity::class.java).apply {
                putExtra(StartCameraActivity.EXTRA_EXERCISE_ID, "squat")
                putExtra(StartCameraActivity.EXTRA_EXERCISE_NAME, "Squat")
            }
            startActivity(intent)
        }

        val cardLunge = findViewById<View>(R.id.cardLunge)
        cardLunge.setOnClickListener {
            SessionDataHolder.selectedExerciseId = "lunge"
            SessionDataHolder.selectedExerciseName = "Lunge"

            val intent = Intent(this, StartCameraActivity::class.java).apply {
                putExtra(StartCameraActivity.EXTRA_EXERCISE_ID, "lunge")
                putExtra(StartCameraActivity.EXTRA_EXERCISE_NAME, "Lunge")
            }
            startActivity(intent)
        }

        val cardCalfRaise = findViewById<View>(R.id.cardCalfRaise)
        cardCalfRaise.setOnClickListener {
            SessionDataHolder.selectedExerciseId = "calf_raise"
            SessionDataHolder.selectedExerciseName = "Calf Raise"

            val intent = Intent(this, StartCameraActivity::class.java).apply {
                putExtra(StartCameraActivity.EXTRA_EXERCISE_ID, "calf_raise")
                putExtra(StartCameraActivity.EXTRA_EXERCISE_NAME, "Calf Raise")
            }
            startActivity(intent)
        }

        val cardPlank = findViewById<View>(R.id.cardPlank)
        cardPlank.setOnClickListener {
            SessionDataHolder.selectedExerciseId = "plank"
            SessionDataHolder.selectedExerciseName = "Plank"

            val intent = Intent(this, StartCameraActivity::class.java).apply {
                putExtra(StartCameraActivity.EXTRA_EXERCISE_ID, "plank")
                putExtra(StartCameraActivity.EXTRA_EXERCISE_NAME, "Plank")
            }
            startActivity(intent)
        }
    }
}
