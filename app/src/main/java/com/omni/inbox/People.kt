package com.omni.inbox

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract

/**
 * Decides which chats are the same human. Chats merge when they share a manual link, a phone
 * number, or a name; any one shared trait is enough, so an SMS thread titled "+47 912 34 567"
 * joins WhatsApp "Anna" through the number, and Snapchat "Anna" joins through the name.
 */
object People {
    /** Last 8 digits: Norwegian numbers are 8 long, and it ignores +47 / 0047 / spacing. */
    fun normalizePhone(raw: String?): String? = raw?.filter(Char::isDigit)?.takeIf { it.length >= 8 }?.takeLast(8)

    fun normalizeName(title: String): String =
        title.lowercase().filter { it.isLetterOrDigit() || it == ' ' }.trim().replace(Regex("\\s+"), " ")

    /** Groups [convs] into people, keeping their order (the first chat of each person leads). */
    fun group(convs: List<Conversation>): List<Person> {
        val parent = IntArray(convs.size) { it }
        fun find(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        fun union(a: Int, b: Int) { val ra = find(a); val rb = find(b); if (ra != rb) parent[maxOf(ra, rb)] = minOf(ra, rb) }

        val owner = HashMap<String, Int>()
        convs.forEachIndexed { i, c ->
            for (trait in traits(c)) owner.putIfAbsent(trait, i)?.let { union(it, i) }
        }
        return convs.indices.groupBy(::find).values.map { idx ->
            val chats = idx.map { convs[it] }
            Person(personKey(chats), chats.first().title, chats)
        }
    }

    /** Stable while the group's members stay the same: a manual link id, else the smallest chat key. */
    private fun personKey(chats: List<Conversation>): String =
        chats.firstNotNullOfOrNull { c -> c.personId?.takeIf { it > 0 }?.let { "p:$it" } } ?: ("c:" + chats.minOf { it.key })

    private fun traits(c: Conversation): List<String> = when {
        c.personId != null && c.personId > 0 -> listOf("p:${c.personId}")
        c.personId == -1L || c.isGroup -> emptyList() // never merges
        else -> listOfNotNull(
            normalizePhone(c.phone)?.let { "t:$it" },
            normalizeName(c.title).takeIf { it.isNotBlank() && !it.all(Char::isDigit) }?.let { "n:$it" },
        )
    }

    // ---------- address book ----------

    @Volatile private var byName: Map<String, String> = emptyMap()
    @Volatile private var loadedAt = 0L

    /** Phone number of the contact named [name], from the address book (cached for 10 minutes). */
    fun contactPhone(ctx: Context, name: String): String? {
        val now = System.currentTimeMillis()
        if (now - loadedAt > 600_000) {
            loadedAt = now
            byName = loadContacts(ctx)
        }
        return byName[normalizeName(name)]
    }

    private fun loadContacts(ctx: Context): Map<String, String> {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return emptyMap()
        val map = HashMap<String, String>()
        runCatching {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null,
            )?.use { c -> while (c.moveToNext()) { val n = c.getString(0) ?: continue; map.putIfAbsent(normalizeName(n), c.getString(1) ?: continue) } }
        }
        return map
    }
}
