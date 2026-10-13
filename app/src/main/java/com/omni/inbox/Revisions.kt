package com.omni.inbox

/** The placeholder texts apps show after a sender deletes a message, Norwegian and English. */
object Revisions {
    private val DELETED = Regex(
        """^(this message was deleted( by admin)?|you deleted this message|denne meldingen ble slettet( av admin)?|""" +
            """du slettet denne meldingen|meldingen ble slettet|message deleted|melding slettet)\.?$""",
        RegexOption.IGNORE_CASE,
    )

    fun isDeletedNotice(text: String) = DELETED.matches(text.trim().removePrefix("🚫").trim().trimStart('\u200E'))
}
