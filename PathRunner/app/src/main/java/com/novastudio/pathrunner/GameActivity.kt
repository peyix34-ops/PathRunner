package com.novastudio.pathrunner

import android.app.Activity
import android.os.Bundle

class GameActivity : Activity() {

    private lateinit var gameView: GameView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gameView = GameView(this)
        setContentView(gameView)
    }

    override fun onResume() {
        super.onResume()
        gameView.resumeGame()
    }

    override fun onPause() {
        gameView.pauseGame()
        super.onPause()
    }
}
