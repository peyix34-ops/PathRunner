package com.novastudio.pathrunner

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val best = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_BEST, 0)
        findViewById<TextView>(R.id.bestScoreText).text = "Best Score: $best"

        findViewById<Button>(R.id.playButton).setOnClickListener {
            startActivity(Intent(this, GameActivity::class.java))
        }
        findViewById<Button>(R.id.quitButton).setOnClickListener {
            finish()
        }
    }

    companion object {
        const val PREFS = "path_runner"
        const val KEY_BEST = "best"
    }
}
