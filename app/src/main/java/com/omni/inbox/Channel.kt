package com.omni.inbox

/** A messaging network, matched by the Android packages that post its notifications. */
enum class Channel(val label: String, val color: Long, val packages: Set<String>) {
    WHATSAPP("WhatsApp", 0xFF25D366, setOf("com.whatsapp", "com.whatsapp.w4b")),
    TELEGRAM("Telegram", 0xFF229ED9, setOf("org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram")),
    SIGNAL("Signal", 0xFF3A76F0, setOf("org.thoughtcrime.securesms")),
    MESSENGER("Messenger", 0xFFA033FF, setOf("com.facebook.orca", "com.facebook.mlite")),
    SNAPCHAT("Snapchat", 0xFFFFFC00, setOf("com.snapchat.android")),
    SMS("SMS", 0xFFF38BA8, setOf("com.google.android.apps.messaging", "com.samsung.android.messaging"));

    companion object {
        fun forPackage(pkg: String): Channel? = entries.firstOrNull { pkg in it.packages }
    }
}
