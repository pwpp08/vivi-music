package com.music.vivi.projector

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = Settings(this)
        setContent {
            ViviProjectorTheme {
                Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                    ProjectorRoot(settings)
                }
            }
        }
    }

    // Remote media keys work anywhere in the app.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> PlayerController.toggle()
            KeyEvent.KEYCODE_MEDIA_NEXT -> PlayerController.next()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> PlayerController.previous()
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }
}
