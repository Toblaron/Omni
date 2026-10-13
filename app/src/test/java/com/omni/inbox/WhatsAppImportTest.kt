package com.omni.inbox

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class WhatsAppImportTest {
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0) =
        Calendar.getInstance().apply { clear(); set(y, mo - 1, d, h, mi, s) }.timeInMillis

    @Test fun androidDayFirst() {
        val l = WhatsAppImport.parse(
            """
            13/10/2026, 10:31 - Messages and calls are end-to-end encrypted. Tap to learn more.
            13/10/2026, 10:31 - Anna: hej!
            13/10/2026, 10:32 - Tobbe: hello
            second line
            14/10/2026, 09:05 - Anna: <Media omitted>
            """.trimIndent()
        )
        assertEquals(3, l.size)
        assertEquals(WhatsAppImport.Line(at(2026, 10, 13, 10, 31), "Anna", "hej!"), l[0])
        assertEquals("hello\nsecond line", l[1].text)
        assertEquals(at(2026, 10, 14, 9, 5), l[2].ts)
    }

    @Test fun swedishIso() {
        val l = WhatsAppImport.parse("2026-10-13 10:31 - Anna Svensson: Ses: ikväll?")
        assertEquals(WhatsAppImport.Line(at(2026, 10, 13, 10, 31), "Anna Svensson", "Ses: ikväll?"), l.single())
    }

    @Test fun usMonthFirst12h() {
        val l = WhatsAppImport.parse("10/13/26, 10:31 PM - Bob: hi\n10/14/26, 12:05 AM - Bob: late")
        assertEquals(at(2026, 10, 13, 22, 31), l[0].ts)
        assertEquals(at(2026, 10, 14, 0, 5), l[1].ts)
    }

    @Test fun iphoneBrackets() {
        val l = WhatsAppImport.parse("‎[13.10.26, 10:31:05] Anna: hi\n[13.10.26, 10:32:00] Tobbe: ‎image omitted")
        assertEquals(WhatsAppImport.Line(at(2026, 10, 13, 10, 31, 5), "Anna", "hi"), l[0])
        assertEquals("image omitted", l[1].text)
    }

    @Test fun titles() {
        assertEquals("Anna", WhatsAppImport.titleFrom("WhatsApp Chat with Anna.txt"))
        assertEquals("Anna Svensson", WhatsAppImport.titleFrom("WhatsApp-chatt med Anna Svensson.zip"))
        assertEquals("Family", WhatsAppImport.titleFrom("WhatsApp Chat - Family.zip"))
    }
}
