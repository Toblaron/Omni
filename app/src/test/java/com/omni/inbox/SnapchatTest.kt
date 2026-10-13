package com.omni.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapchatTest {
    @Test fun typingIndicators() {
        assertTrue(Snapchat.isTyping("is typing..."))
        assertTrue(Snapchat.isTyping("Anna is typing…"))
        assertTrue(Snapchat.isTyping("typing"))
        assertTrue(Snapchat.isTyping("Anna skriver..."))
        assertFalse(Snapchat.isTyping("stop typing so much and call me"))
        assertFalse(Snapchat.isTyping("sent you a Snap"))
        assertFalse(Snapchat.isTyping("skriver du i morgen?"))
    }

    @Test fun senderFromText() {
        assertEquals("Anna" to "ha det", Snapchat.splitSender("Snapchat", "Anna: ha det"))
        assertEquals("Anna" to "hej", Snapchat.splitSender(null, "Anna: hej"))
        // a real title is the chat; colons in the text stay put
        assertEquals("Anna" to "kl 10: kaffe?", Snapchat.splitSender("Anna", "kl 10: kaffe?"))
        assertEquals("Snapchat" to "no sender here", Snapchat.splitSender("Snapchat", "no sender here"))
    }
}
