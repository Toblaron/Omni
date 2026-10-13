package com.omni.inbox

import android.app.Notification

/**
 * Snapchat posts many notifications that aren't messages: typing indicators, streak reminders,
 * stories, Spotlight, Memories, friend requests. Chats and Snaps are told apart by structure
 * (message category, conversation shortcut, inline reply) rather than wording, which follows
 * the phone's language. Only the typing indicator needs a text check, since it looks like a chat.
 */
object Snapchat {
    /** "Anna is typing...", "typing…", Norwegian "skriver…". */
    private val TYPING = Regex("""(^|\s)(is typing|typing|skriver)\s*(\.\.\.|…)?\s*$""", RegexOption.IGNORE_CASE)

    /** "Anna: hey" when Snapchat puts the app name in the title (groups, hidden previews). */
    private val SENDER_PREFIX = Regex("""^([^:\n]{1,40}):\s+(.+)$""", RegexOption.DOT_MATCHES_ALL)

    fun isTyping(text: String) = TYPING.containsMatchIn(text)

    /** A plain (non-MessagingStyle) notification that belongs to a conversation. */
    fun isChat(n: Notification): Boolean =
        n.category == Notification.CATEGORY_MESSAGE ||
            n.shortcutId != null ||
            n.actions?.any { !it.remoteInputs.isNullOrEmpty() } == true

    /** Splits "Anna: hey" into sender and text when the title is just "Snapchat". */
    fun splitSender(title: String?, text: String): Pair<String?, String> {
        if (title != null && !title.equals(Channel.SNAPCHAT.label, true)) return title to text
        val m = SENDER_PREFIX.matchEntire(text) ?: return title to text
        return m.groupValues[1].trim() to m.groupValues[2]
    }
}
