package com.omni.inbox

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * Inline replies through the source app's own notification "Reply" action, like Android Auto
 * and watches do. Actions only live as long as the notification, so this is in-memory.
 */
object Replies {
    private val actions = HashMap<String, Notification.Action>()

    fun remember(key: String, n: Notification) {
        val withInput = n.actions?.filter { !it.remoteInputs.isNullOrEmpty() }.orEmpty()
        val reply = withInput.firstOrNull { it.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY }
            ?: withInput.firstOrNull()
            ?: return
        synchronized(actions) { actions[key] = reply }
    }

    fun canReply(key: String) = synchronized(actions) { key in actions }

    /** Returns false when the notification (and its reply action) is gone. */
    fun send(ctx: Context, key: String, text: String): Boolean {
        val action = synchronized(actions) { actions[key] } ?: return false
        val inputs = action.remoteInputs
        val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
        val intent = Intent()
        RemoteInput.addResultsToIntent(inputs, intent, results)
        return try {
            action.actionIntent.send(ctx, 0, intent)
            true
        } catch (_: PendingIntent.CanceledException) {
            synchronized(actions) { actions.remove(key) }
            false
        }
    }
}
