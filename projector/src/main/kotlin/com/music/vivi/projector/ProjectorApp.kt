package com.music.vivi.projector

import android.app.Application

class ProjectorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val settings = Settings(this)
        settings.apply()
        PlayerController.init(this, settings)
    }
}
