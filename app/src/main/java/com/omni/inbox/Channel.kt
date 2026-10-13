package com.omni.inbox

/**
 * A messaging network, matched by the Android packages that post its notifications.
 * [strict] apps also notify about likes, stories, mentions…; only their chat notifications count.
 */
enum class Channel(val label: String, val color: Long, val packages: Set<String>, val strict: Boolean = false) {
    WHATSAPP("WhatsApp", 0xFF25D366, setOf("com.whatsapp", "com.whatsapp.w4b")),
    TELEGRAM("Telegram", 0xFF229ED9, setOf("org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram")),
    SIGNAL("Signal", 0xFF3A76F0, setOf("org.thoughtcrime.securesms")),
    MESSENGER("Messenger", 0xFFA033FF, setOf("com.facebook.orca", "com.facebook.mlite")),
    SNAPCHAT("Snapchat", 0xFFFFFC00, setOf("com.snapchat.android"), strict = true),
    INSTAGRAM("Instagram", 0xFFE1306C, setOf("com.instagram.android", "com.instagram.lite"), strict = true),
    DISCORD("Discord", 0xFF5865F2, setOf("com.discord"), strict = true),
    SLACK("Slack", 0xFFECB22E, setOf("com.Slack"), strict = true),
    TEAMS("Teams", 0xFF7B83EB, setOf("com.microsoft.teams"), strict = true),
    LINKEDIN("LinkedIn", 0xFF70B5F9, setOf("com.linkedin.android"), strict = true),
    SMS("SMS", 0xFFF38BA8, setOf("com.google.android.apps.messaging", "com.samsung.android.messaging"));

    companion object {
        fun forPackage(pkg: String): Channel? = entries.firstOrNull { pkg in it.packages }
    }
}
