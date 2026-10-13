package com.omni.inbox

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class SnoozeTest {
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) =
        Calendar.getInstance().apply { clear(); set(y, mo - 1, d, h, mi) }.timeInMillis

    @Test fun afternoonOnTuesday() {
        val now = at(2026, 10, 13, 14, 30) // Tuesday
        val o = snoozeOptions(now)
        assertEquals(now + 3_600_000, o[0].second)
        assertEquals(at(2026, 10, 13, 18), o[1].second)
        assertEquals(at(2026, 10, 14, 8), o[2].second)
        assertEquals(at(2026, 10, 19, 8), o[3].second) // next Monday
    }

    @Test fun lateEveningAndMondays() {
        val now = at(2026, 10, 19, 21) // Monday evening
        val o = snoozeOptions(now)
        assertEquals(R.string.snooze_tomorrow_evening, o[1].first)
        assertEquals(at(2026, 10, 20, 18), o[1].second)
        assertEquals(at(2026, 10, 26, 8), o[3].second) // a week later, not today
    }
}
