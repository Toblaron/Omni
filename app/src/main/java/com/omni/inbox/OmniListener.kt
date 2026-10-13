package com.omni.inbox

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat

/**
 * Captures incoming messages from the notifications of supported apps. Most messengers post
 * MessagingStyle notifications (sender, text and timestamp per message); others fall back to
 * the notification's title and text.
 */
class OmniListener : NotificationListenerService() {

    override fun onListenerConnected() {
        runCatching { activeNotifications }.getOrNull()?.forEach(::handle)
        SmsSync.start(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = handle(sbn)

    private fun handle(sbn: StatusBarNotification) {
        val channel = Channel.forPackage(sbn.packageName) ?: return
        val n = sbn.notification
        if (n.flags and (Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_ONGOING_EVENT) != 0) return
        val store = Store.get(this)
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)

        if (style != null && style.messages.isNotEmpty()) {
            val convTitle = style.conversationTitle?.toString()
                ?: title
                ?: style.messages.last().person?.name?.toString()
                ?: channel.label
            val key = "${sbn.packageName}:${n.shortcutId ?: convTitle}"
            val me = style.user.name?.toString()
            for (m in style.messages) {
                val text = m.text?.toString()?.takeIf { it.isNotBlank() } ?: continue
                val sender = m.person?.name?.toString()
                val outgoing = m.person == null || (me != null && sender == me)
                store.add(
                    channel, sbn.packageName, key, convTitle, if (outgoing) null else sender, text,
                    m.timestamp, outgoing, "$key|${m.timestamp}|${text.hashCode()}",
                    // incoming messages carry exact timestamps; our own echoed replies don't match ours
                    fuzzy = outgoing || channel == Channel.SMS,
                )
            }
            Replies.remember(key, n)
            return
        }

        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() } ?: return
        if (SUMMARY.matches(text)) return
        val convTitle = title ?: channel.label
        val key = "${sbn.packageName}:${n.shortcutId ?: convTitle}"
        store.add(
            channel, sbn.packageName, key, convTitle, title, text, sbn.postTime, false,
            "$key|${n.`when`}|${text.hashCode()}", fuzzy = true,
        )
        Replies.remember(key, n)
    }

    companion object {
        /** "3 new messages", "5 messages from 2 chats" and similar roll-ups. */
        private val SUMMARY = Regex("""^\d+ (new )?messages?( from \d+ chats?)?$""", RegexOption.IGNORE_CASE)
    }
}
