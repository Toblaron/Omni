package com.omni.inbox

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle

/**
 * Inline replies through the source app's own notification "Reply" action, like Android Auto
 * and watches do. Actions only live as long as the notification, so this is in-memory.
 * The notification's tap action is kept too: it opens the exact chat in the source app.
 */
object Replies {
    private val actions = HashMap<String, Notification.Action>()
    private val opens = HashMap<String, PendingIntent>()

    fun remember(key: String, n: Notification) {
        n.contentIntent?.let { synchronized(opens) { opens[key] = it } }
        val withInput = n.actions?.filter { !it.remoteInputs.isNullOrEmpty() }.orEmpty()
        val reply = withInput.firstOrNull { it.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY }
            ?: withInput.firstOrNull()
            ?: return
        synchronized(actions) { actions[key] = reply }
    }

    fun canReply(key: String) = synchronized(actions) { key in actions }

    fun openFromNotification(ctx: Context, key: String): Boolean {
        val pi = synchronized(opens) { opens[key] } ?: return false
        // we're in the foreground; lend that to the source app so it may open its screen
        val options = when {
            Build.VERSION.SDK_INT >= 36 -> ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE).toBundle()
            Build.VERSION.SDK_INT >= 34 -> @Suppress("DEPRECATION") ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle()
            else -> null
        }
        return try {
            pi.send(ctx, 0, null, null, null, null, options)
            true
        } catch (_: PendingIntent.CanceledException) {
            synchronized(opens) { opens.remove(key) }
            false
        }
    }

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
