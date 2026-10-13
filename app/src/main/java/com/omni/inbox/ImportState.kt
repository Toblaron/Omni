package com.omni.inbox

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** A chat export waiting for the user to confirm who "me" is. */
object ImportState {
    sealed interface State {
        data object Reading : State
        data class Ready(val parsed: WhatsAppImport.Parsed) : State
        data class Failed(val reason: String) : State
    }

    val state = MutableStateFlow<State?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun load(ctx: Context, uri: Uri) {
        state.value = State.Reading
        val app = ctx.applicationContext
        scope.launch {
            state.value = runCatching { WhatsAppImport.read(app, uri) }.fold(
                { if (it.lines.isEmpty()) State.Failed("No WhatsApp messages found in that file.") else State.Ready(it) },
                { State.Failed(it.message ?: "Couldn't read that file.") },
            )
        }
    }

    fun clear() { state.value = null }
}
