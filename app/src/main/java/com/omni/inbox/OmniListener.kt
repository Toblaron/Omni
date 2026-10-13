package com.omni.inbox

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.os.BundleCompat

/**
 * Captures incoming messages from the notifications of supported apps. Most messengers post
 * MessagingStyle notifications (sender, text and timestamp per message); others fall back to
 * the notification's title and text.
 */
class OmniListener : NotificationListenerService() {

    override fun onListenerConnected() {
        val current = runCatching { activeNotifications }.getOrNull().orEmpty()
        work.execute { current.forEach(::handleSafely) }
        SmsSync.start(this)
        Telegram.start(this)
    }

    // callbacks arrive on the main thread; database writes and picture decoding happen off it
    override fun onNotificationPosted(sbn: StatusBarNotification) = work.execute { handleSafely(sbn) }

    /** One odd notification must never take the app (which shares this process) down with it. */
    private fun handleSafely(sbn: StatusBarNotification) {
        runCatching { handle(sbn) }.onFailure { android.util.Log.e("OmniListener", "skipped a notification from ${sbn.packageName}", it) }
    }

    private fun handle(sbn: StatusBarNotification) {
        val channel = Channel.forPackage(sbn.packageName) ?: return
        // with TDLib linked, Telegram arrives complete from the API instead
        if (channel == Channel.TELEGRAM && Telegram.isLinked(this)) return
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
                if (channel == Channel.SNAPCHAT && Snapchat.isTyping(text)) continue
                val sender = m.person?.name?.toString()
                val outgoing = m.person == null || (me != null && sender == me)
                val image = m.dataUri?.takeIf { m.dataMimeType?.startsWith("image/") == true }?.let { uri -> { Thumbs.fromUri(this, uri) } }
                store.add(
                    channel, sbn.packageName, key, convTitle, if (outgoing) null else sender, text,
                    m.timestamp, outgoing, "$key|${m.timestamp}|${text.hashCode()}",
                    // incoming messages carry exact timestamps; our own echoed replies don't match ours
                    fuzzy = outgoing || channel == Channel.SMS,
                    // same timestamp, new text: the sender deleted or edited it
                    revisable = !outgoing, image = image,
                )
            }
            Replies.remember(key, n)
            return
        }

        var text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() } ?: return
        if (SUMMARY.matches(text)) return
        var sender = title
        // likes, stories, streaks, friend requests… aren't conversations
        if (channel.strict && !Snapchat.isChat(n)) return
        if (channel == Channel.SNAPCHAT) {
            if (Snapchat.isTyping(text)) return
            Snapchat.splitSender(title, text).let { (s, t) -> sender = s; text = t }
        }
        val convTitle = sender ?: channel.label
        val key = "${sbn.packageName}:${n.shortcutId ?: convTitle}"
        store.add(
            channel, sbn.packageName, key, convTitle, sender, text, sbn.postTime, false,
            "$key|${n.`when`}|${text.hashCode()}", fuzzy = true, image = { bigPicture(n) },
        )
        Replies.remember(key, n)
    }

    private fun bigPicture(n: Notification): ByteArray? {
        BundleCompat.getParcelable(n.extras, Notification.EXTRA_PICTURE, Bitmap::class.java)?.let { return Thumbs.fromBitmap(it) }
        if (Build.VERSION.SDK_INT >= 31) BundleCompat.getParcelable(n.extras, Notification.EXTRA_PICTURE_ICON, Icon::class.java)?.let { return Thumbs.fromIcon(this, it) }
        return null
    }

    companion object {
        private val work = java.util.concurrent.Executors.newSingleThreadExecutor()

        /** "3 new messages", "5 messages from 2 chats", "3 nye meldinger", "5 meldinger fra 2 samtaler" and similar roll-ups. */
        private val SUMMARY = Regex(
            """^\d+ (new )?messages?( from \d+ chats?)?$|^\d+ (nye? )?meldinger?( fra \d+ (samtaler?|chatter?|chater?))?$""",
            RegexOption.IGNORE_CASE,
        )
    }
}
