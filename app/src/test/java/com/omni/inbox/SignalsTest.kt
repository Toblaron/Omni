package com.omni.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class SignalsTest {
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) =
        Calendar.getInstance().apply { clear(); set(y, mo - 1, d, h, mi) }.timeInMillis

    private val tue = at(2026, 10, 13, 14, 30) // Tuesday afternoon

    private fun one(text: String) = Signals.whens(text, tue).single()

    @Test fun questionsAndRequestsAsk() {
        listOf(
            "Hva gjør du?", "hvor er du", "Er du hjemme", "kan du hente meg", "Skal vi ta en kaffe",
            "ring meg når du kan", "gi beskjed", "are you coming", "let me know", "Anna sent you a Snap",
            "Middag i morgen", "vi sees kl 18", "Jeg har tenkt litt på det du sa i går og lurer på noe",
        ).forEach { assertTrue(it, Signals.asks(it)) }
    }

    @Test fun acknowledgementsDontAsk() {
        listOf("ok", "Haha", "takk!", "👍", "Er på vei", "kommer om 5", "ses", "Nice", "📷 Photo", "Ja det stemmer")
            .forEach { assertFalse(it, Signals.asks(it)) }
    }

    @Test fun relativeDays() {
        assertEquals(at(2026, 10, 14, 18), one("i morgen kl 18").start)
        assertEquals(at(2026, 10, 14, 9), one("Ses i morgen!").start.also { assertFalse(one("Ses i morgen!").hasTime) })
        assertEquals(at(2026, 10, 13, 19), one("Middag i kveld?").start)
        assertEquals(at(2026, 10, 13, 19, 30), one("i kveld kl 7.30").start)
        assertEquals(at(2026, 10, 16, 20), one("på fredag kl 20").start)
        assertEquals(at(2026, 10, 20, 9), one("tirsdag").start) // today is Tuesday → next week
        assertEquals(at(2026, 10, 17, 21), one("saturday at 9pm").start)
    }

    @Test fun clockOnly() {
        assertEquals(at(2026, 10, 13, 18, 30), one("møtes 18:30").start)
        assertEquals(at(2026, 10, 14, 10), one("kl 10").start) // already past today
        assertEquals(at(2026, 10, 13, 19), one("kl 7").start) // plans at "7" are evening
        assertEquals(at(2026, 10, 13, 15, 10), one("kl 15.10").start) // time, not 15 October
    }

    @Test fun dates() {
        assertEquals(at(2026, 12, 24, 9), one("Julaften 24.12").start)
        assertEquals(at(2026, 11, 3, 18), one("3. november kl 18").start)
        assertEquals(at(2027, 1, 5, 9), one("5/1").start) // next January
        assertEquals(at(2026, 10, 20, 9), one("20 oct").start)
    }

    @Test fun nothingToFind() {
        listOf("ok", "1 maybe", "haha 2", "den 45.13 er feil", "takk for i går").forEach {
            assertEquals(it, emptyList<Signals.When>(), Signals.whens(it, tue))
        }
    }

    @Test fun places() {
        assertEquals(listOf("Storgata 5"), Signals.places("Vi møtes i Storgata 5 kl 18"))
        assertEquals(listOf("Bogstadveien 12B, 0355 Oslo"), Signals.places("adresse: Bogstadveien 12B, 0355 Oslo"))
        assertEquals(listOf("221B Baker Street"), Signals.places("at 221B Baker Street"))
        assertEquals(emptyList<String>(), Signals.places("jeg går en tur"))
    }
}
