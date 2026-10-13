package com.omni.inbox

import android.app.Application
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

class OmniApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Telegram.start(this)
        Assist.check()
        Thread { runCatching { Housekeeping.cleanIfDue(this) } }.start()
    }

    companion object {
        lateinit var instance: OmniApp
            private set
    }
}

/**
 * For work that must finish even when the screen that started it closes (a dialog dismissing
 * itself, leaving a chat mid-send). Screen-bound coroutine scopes are cancelled on exit.
 */
internal val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main +
    CoroutineExceptionHandler { _, e -> android.util.Log.e("OmniInbox", "background action failed", e) })

/**
 * A translated string (values/ English, values-nb/ Norwegian), usable from composables, toasts,
 * notifications and background work alike. A language change recreates the activity, so the UI
 * picks up the new strings on its next composition.
 */
fun s(@StringRes id: Int, vararg args: Any): String = OmniApp.instance.getString(id, *args)

fun plural(@PluralsRes id: Int, n: Int): String = OmniApp.instance.resources.getQuantityString(id, n, n)
