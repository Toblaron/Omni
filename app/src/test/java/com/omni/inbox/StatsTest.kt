package com.omni.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsTest {
    private val min = 60_000L
    private fun r(ts: Long, out: Boolean) = Stats.Row("a", Channel.SMS, ts, out)

    @Test fun replyDelaysCountFromFirstUnansweredMessage() {
        val s = Stats.compute(listOf(
            r(0, false), r(2 * min, false), r(10 * min, true),  // I answer 10 min after their first message
            r(11 * min, true), r(16 * min, false),               // they answer 6 min after my first unanswered one
            r(20 * min, false), r(4 * 86_400_000L, true),        // days later: not a reply
        ))["a"]!!
        assertEquals(listOf(10 * min), s.mine)
        assertEquals(listOf(6 * min), s.theirs)
        assertEquals(3, s.sent)
        assertEquals(4, s.received)
    }

    @Test fun vipNeedsManyQuickReplies() {
        assertTrue(Stats.isVip(List(6) { 5 * min }))
        assertFalse(Stats.isVip(List(3) { 1 * min }))
        assertFalse(Stats.isVip(List(8) { 90 * min }))
    }
}
