package com.omni.inbox

import org.junit.Assert.assertEquals
import org.junit.Test

class PeopleTest {
    private fun c(key: String, title: String, ch: Channel, phone: String? = null, personId: Long? = null, group: Boolean = false) =
        Conversation(key, ch, null, title, "", null, 0, null, isGroup = group, personId = personId, phone = phone)

    private fun keysOf(people: List<Person>) = people.map { p -> p.chats.map { it.key }.toSet() }.toSet()

    @Test fun mergesByPhoneOrName() {
        val people = People.group(listOf(
            c("sms:1", "+47 912 34 567", Channel.SMS, phone = "+4791234567"),
            c("com.whatsapp:4791234567@s.whatsapp.net", "Anna", Channel.WHATSAPP, phone = "4791234567"),
            c("com.snapchat.android:Anna", "Anna", Channel.SNAPCHAT),
            c("tg:5", "Bob", Channel.TELEGRAM),
        ))
        assertEquals(setOf(setOf("sms:1", "com.whatsapp:4791234567@s.whatsapp.net", "com.snapchat.android:Anna"), setOf("tg:5")), keysOf(people))
    }

    @Test fun groupsAndUnlinkedNeverMerge() {
        val people = People.group(listOf(
            c("a", "Anna", Channel.SMS),
            c("b", "Anna", Channel.WHATSAPP, personId = -1),
            c("g", "Anna", Channel.WHATSAPP, group = true),
        ))
        assertEquals(3, people.size)
    }

    @Test fun manualLinksWin() {
        val people = People.group(listOf(c("a", "Anna", Channel.SMS, personId = 7), c("b", "Bestemor", Channel.WHATSAPP, personId = 7)))
        assertEquals(listOf("p:7"), people.map { it.key })
    }

    @Test fun phoneNormalization() {
        assertEquals("91234567", People.normalizePhone("+47 912 34 567"))
        assertEquals("91234567", People.normalizePhone("0047-91234567"))
        assertEquals(null, People.normalizePhone("1234"))
    }
}
