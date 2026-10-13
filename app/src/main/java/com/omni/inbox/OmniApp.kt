package com.omni.inbox

import android.app.Application

class OmniApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Telegram.start(this)
        Thread { runCatching { Housekeeping.cleanIfDue(this) } }.start()
    }
}
