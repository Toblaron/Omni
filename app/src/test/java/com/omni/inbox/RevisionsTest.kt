package com.omni.inbox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RevisionsTest {
    @Test fun deletedNotices() {
        listOf("This message was deleted", "🚫 This message was deleted.", "Denne meldingen ble slettet", "Du slettet denne meldingen",
            "This message was deleted by admin").forEach { assertTrue(it, Revisions.isDeletedNotice(it)) }
    }

    @Test fun ordinaryTextIsNot() {
        listOf("I deleted the photo", "meldingen ble slettet ved en feil, sender på nytt", "ok").forEach { assertFalse(it, Revisions.isDeletedNotice(it)) }
    }
}
