package com.omni.inbox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { OmniTheme { App() } }
    }

    override fun onResume() {
        super.onResume()
        // permissions / notification access may have changed in Settings
        resumed.value = resumed.value + 1
        SmsSync.start(this)
    }

    companion object {
        val resumed = MutableStateFlow(0)
    }
}
