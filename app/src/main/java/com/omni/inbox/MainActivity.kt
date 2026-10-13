package com.omni.inbox

import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.MutableStateFlow

/** FragmentActivity because BiometricPrompt needs one. */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applySecureFlag()
        setContent { OmniTheme { App() } }
        handleShare(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** A WhatsApp chat export shared into the app. */
    private fun handleShare(intent: android.content.Intent?) {
        if (intent?.action != android.content.Intent.ACTION_SEND) return
        val uri = androidx.core.content.IntentCompat.getParcelableExtra(intent, android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)
            ?: return
        ImportState.load(this, uri)
    }

    override fun onStart() {
        super.onStart()
        if (AppLock.isEnabled(this) && SystemClock.elapsedRealtime() - AppLock.lastSeen > AppLock.GRACE_MS) {
            AppLock.locked.value = true
            unlock()
        }
    }

    override fun onStop() {
        super.onStop()
        if (!AppLock.locked.value) AppLock.lastSeen = SystemClock.elapsedRealtime()
    }

    override fun onResume() {
        super.onResume()
        // permissions / notification access may have changed in Settings
        resumed.value = resumed.value + 1
        SmsSync.start(this)
    }

    /** Hides content from the recents thumbnail and screenshots while the lock is on. */
    fun applySecureFlag() {
        if (AppLock.isEnabled(this)) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun unlock() {
        if (!AppLock.available(this)) { AppLock.locked.value = false; return }
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                AppLock.locked.value = false
                AppLock.lastSeen = SystemClock.elapsedRealtime()
            }
        }).authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Omni Inbox")
                .setSubtitle("Unlock your messages")
                .setAllowedAuthenticators(AppLock.AUTHENTICATORS)
                .build()
        )
    }

    companion object {
        val resumed = MutableStateFlow(0)
    }
}

object AppLock {
    const val GRACE_MS = 60_000L
    const val AUTHENTICATORS = BIOMETRIC_STRONG or DEVICE_CREDENTIAL
    val locked = MutableStateFlow(false)
    var lastSeen = Long.MIN_VALUE / 2

    private fun prefs(ctx: android.content.Context) = ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)

    /** On by default whenever the phone has a screen lock or biometrics. */
    fun isEnabled(ctx: android.content.Context) = prefs(ctx).getBoolean("app_lock", true) && available(ctx)
    fun setEnabled(ctx: android.content.Context, on: Boolean) = prefs(ctx).edit().putBoolean("app_lock", on).apply()

    fun available(ctx: android.content.Context) =
        BiometricManager.from(ctx).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS
}
