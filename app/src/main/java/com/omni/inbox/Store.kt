package com.omni.inbox

import android.content.ContentValues
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteOpenHelper

data class Msg(
    val id: Long,
    val sender: String?,
    val text: String,
    val ts: Long,
    val outgoing: Boolean,
    val convKey: String = "",
    val channel: Channel? = null,
)

data class Conversation(
    val key: String,
    val channel: Channel,
    val pkg: String?,
    val title: String,
    val lastText: String,
    val lastSender: String?,
    val lastTs: Long,
    val address: String?,
    val unread: Boolean = false,
    val needsReply: Boolean = false,
    val isGroup: Boolean = false,
    /** null = automatic (by name), -1 = never merge, >0 = manually linked person. */
    val personId: Long? = null,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val muted: Boolean = false,
    /** Hidden from the inbox until then (0 = not snoozed). */
    val snoozeUntil: Long = 0,
    val draft: String? = null,
) {
    val snoozed get() = snoozeUntil > System.currentTimeMillis()
    val hidden get() = archived || snoozed
    /** Inbox order: a chat whose snooze ran out comes back at the top as if it just arrived. */
    val sortTs get() = if (snoozeUntil in 1..System.currentTimeMillis()) maxOf(lastTs, snoozeUntil) else lastTs

    /** Chats sharing this key are the same human across apps. */
    val personKey: String
        get() = when {
            personId != null && personId > 0 -> "p:$personId"
            personId == -1L || isGroup -> "c:$key"
            else -> "n:" + title.lowercase().filter { it.isLetterOrDigit() || it == ' ' }.trim().replace(Regex("\\s+"), " ")
        }
}

/** One human, possibly on several apps. */
data class Person(val key: String, val name: String, val chats: List<Conversation>) {
    val latest get() = chats.first()
    val unread get() = chats.any { it.unread && !it.muted }
    val needsReply get() = chats.any { it.needsReply && !it.muted }
    val pinned get() = chats.any { it.pinned }
    val muted get() = chats.all { it.muted }
    val hidden get() = chats.all { it.hidden }
    val sortTs get() = chats.maxOf { it.sortTs }
    val draft get() = chats.firstNotNullOfOrNull { it.draft?.takeIf(String::isNotBlank) }
}

data class Reminder(val id: Long, val convKey: String, val at: Long, val text: String)

data class SearchHit(val id: Long, val convKey: String, val channel: Channel, val title: String, val sender: String?, val text: String, val ts: Long)

/**
 * All captured messages in a SQLCipher-encrypted database (passphrase from [Vault]).
 * [changes] bumps on every write so the UI can re-query.
 */
class Store private constructor(private val ctx: Context, passphrase: ByteArray) :
    SQLiteOpenHelper(ctx, DB_NAME, passphrase, null, VERSION, 0, null, null, true) {

    val changes = MutableStateFlow(0L)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE messages(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                channel TEXT NOT NULL, pkg TEXT, conv_key TEXT NOT NULL, conv_title TEXT NOT NULL,
                address TEXT, sender TEXT, body TEXT NOT NULL, ts INTEGER NOT NULL,
                outgoing INTEGER NOT NULL, uniq TEXT NOT NULL UNIQUE)"""
        )
        db.execSQL("CREATE INDEX idx_conv ON messages(conv_key, ts)")
        db.execSQL("CREATE INDEX idx_body ON messages(channel, body)")
        // per-conversation reading state; person_id links chats of the same human across apps
        db.execSQL("CREATE TABLE conv_state(conv_key TEXT PRIMARY KEY, seen_ts INTEGER NOT NULL DEFAULT 0, done_ts INTEGER NOT NULL DEFAULT 0, person_id INTEGER)")
        db.execSQL("CREATE TABLE people(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL)")
        // full-text index over the message table, kept in sync by triggers
        db.execSQL("CREATE VIRTUAL TABLE messages_fts USING fts4(content=\"messages\", body, conv_title, sender)")
        db.execSQL("CREATE TRIGGER messages_ai AFTER INSERT ON messages BEGIN INSERT INTO messages_fts(docid, body, conv_title, sender) VALUES (new.id, new.body, new.conv_title, new.sender); END")
        db.execSQL("CREATE TRIGGER messages_ad AFTER DELETE ON messages BEGIN INSERT INTO messages_fts(messages_fts, docid, body, conv_title, sender) VALUES ('delete', old.id, old.body, old.conv_title, old.sender); END")
        onUpgrade(db, 1, VERSION)
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        // uniq keys of messages the user deleted, so re-syncs (SMS provider, Telegram history) don't bring them back
        if (old < 2) db.execSQL("CREATE TABLE deleted(uniq TEXT PRIMARY KEY)")
        if (old < 3) {
            // archived_ts: hidden until a message newer than it; snooze_until: hidden until then
            listOf("pinned INTEGER NOT NULL DEFAULT 0", "archived_ts INTEGER NOT NULL DEFAULT 0", "muted INTEGER NOT NULL DEFAULT 0",
                "snooze_until INTEGER NOT NULL DEFAULT 0", "draft TEXT").forEach { db.execSQL("ALTER TABLE conv_state ADD COLUMN $it") }
            db.execSQL("CREATE TABLE reminders(id INTEGER PRIMARY KEY AUTOINCREMENT, conv_key TEXT NOT NULL, at INTEGER NOT NULL, text TEXT NOT NULL)")
        }
    }

    /**
     * Inserts a message unless [uniq] was seen before. With [fuzzy], also skips it when the same
     * text already exists in the channel within two minutes (notification re-posts, our own
     * replies echoed back, SMS seen both via the provider and via the Messages notification).
     */
    fun add(
        channel: Channel, pkg: String?, convKey: String, convTitle: String, sender: String?,
        text: String, ts: Long, outgoing: Boolean, uniq: String, fuzzy: Boolean,
        address: String? = null, notify: Boolean = true,
    ): Boolean {
        val db = writableDatabase
        db.rawQuery("SELECT 1 FROM deleted WHERE uniq=?", arrayOf(uniq)).use { if (it.moveToFirst()) return false }
        if (fuzzy) {
            db.rawQuery(
                "SELECT 1 FROM messages WHERE channel=? AND body=? AND ts BETWEEN ? AND ? AND outgoing=? LIMIT 1",
                arrayOf(channel.name, text, (ts - 120_000).toString(), (ts + 120_000).toString(), if (outgoing) "1" else "0"),
            ).use { if (it.moveToFirst()) return false }
        }
        val row = db.insertWithOnConflict("messages", null, ContentValues().apply {
            put("channel", channel.name); put("pkg", pkg); put("conv_key", convKey)
            put("conv_title", convTitle); put("address", address); put("sender", sender)
            put("body", text); put("ts", ts); put("outgoing", if (outgoing) 1 else 0); put("uniq", uniq)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        if (row != -1L && notify) changed()
        return row != -1L
    }

    fun changed() { changes.value = changes.value + 1 }

    fun inTransaction(block: () -> Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try { block(); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }

    private val conversationSelect = """
        SELECT m.conv_key, m.channel, m.pkg, m.conv_title, m.body, m.sender, MAX(m.ts), m.address,
               MAX(CASE WHEN m.outgoing=0 THEN m.ts END), MAX(CASE WHEN m.outgoing=1 THEN m.ts END),
               IFNULL(s.seen_ts, 0), IFNULL(s.done_ts, 0),
               COUNT(DISTINCT CASE WHEN m.outgoing=0 THEN m.sender END), s.person_id,
               IFNULL(s.pinned, 0), IFNULL(s.archived_ts, 0), IFNULL(s.muted, 0), IFNULL(s.snooze_until, 0), s.draft
        FROM messages m LEFT JOIN conv_state s ON s.conv_key = m.conv_key"""

    // SQLite returns the bare columns from the row that holds MAX(ts)
    private fun readConversation(c: android.database.Cursor): Conversation {
        val lastTs = c.getLong(6)
        val lastIn = if (c.isNull(8)) 0L else c.getLong(8)
        val lastOut = if (c.isNull(9)) 0L else c.getLong(9)
        return Conversation(
            c.getString(0), Channel.valueOf(c.getString(1)), c.getString(2), c.getString(3),
            c.getString(4), c.getString(5), lastTs, c.getString(7),
            unread = lastIn > c.getLong(10),
            needsReply = lastIn > lastOut && lastIn > c.getLong(11),
            isGroup = c.getInt(12) > 1,
            personId = if (c.isNull(13)) null else c.getLong(13),
            pinned = c.getInt(14) == 1,
            archived = c.getLong(15) >= lastTs,
            muted = c.getInt(16) == 1,
            snoozeUntil = c.getLong(17),
            draft = c.getString(18),
        )
    }

    fun conversations(filter: Channel?): List<Conversation> {
        val where = if (filter != null) "WHERE m.channel=?" else ""
        val args = if (filter != null) arrayOf(filter.name) else emptyArray()
        return readableDatabase.rawQuery("$conversationSelect $where GROUP BY m.conv_key ORDER BY MAX(m.ts) DESC", args)
            .use { c -> buildList { while (c.moveToNext()) add(readConversation(c)) } }
    }

    /** Inbox order: pinned first, then newest (a finished snooze counts as new). */
    fun inbox(): List<Conversation> = conversations(null).sortedWith(compareByDescending<Conversation> { it.pinned }.thenByDescending { it.sortTs })

    fun conversation(key: String): Conversation? =
        readableDatabase.rawQuery("$conversationSelect WHERE m.conv_key=? GROUP BY m.conv_key", arrayOf(key))
            .use { c -> if (c.moveToFirst()) readConversation(c) else null }

    fun messages(key: String): List<Msg> =
        readableDatabase.rawQuery(
            "SELECT id, sender, body, ts, outgoing FROM messages WHERE conv_key=? ORDER BY ts",
            arrayOf(key),
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(Msg(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getInt(4) == 1))
            }
        }

    /** Conversations grouped into people, most recent first. */
    fun people(filter: Channel?): List<Person> =
        conversations(filter).groupBy { it.personKey }.map { (k, chats) -> Person(k, chats.first().title, chats) }

    fun person(key: String): Person? = people(null).firstOrNull { it.key == key }

    /** Interleaved timeline of several chats, each message tagged with its chat and app. */
    fun messages(keys: List<String>): List<Msg> {
        if (keys.isEmpty()) return emptyList()
        return readableDatabase.rawQuery(
            "SELECT id, sender, body, ts, outgoing, conv_key, channel FROM messages WHERE conv_key IN (${keys.joinToString(",") { "?" }}) ORDER BY ts",
            keys.toTypedArray(),
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(Msg(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getInt(4) == 1, c.getString(5), Channel.valueOf(c.getString(6))))
            }
        }
    }

    /** Puts [other] into the same person as [key]. */
    fun link(key: String, other: String) {
        val db = writableDatabase
        touchState(key); touchState(other)
        fun personOf(k: String) = db.rawQuery("SELECT person_id FROM conv_state WHERE conv_key=?", arrayOf(k))
            .use { if (it.moveToFirst() && !it.isNull(0) && it.getLong(0) > 0) it.getLong(0) else null }
        val id = personOf(key) ?: personOf(other) ?: run {
            val name = conversation(key)?.title ?: "Person"
            db.insert("people", null, ContentValues().apply { put("name", name) })
        }
        db.execSQL("UPDATE conv_state SET person_id=? WHERE conv_key IN (?, ?)", arrayOf<Any>(id, key, other))
        changed()
    }

    /** Stops [key] merging with anyone, including automatic name matches. */
    fun unlink(key: String) {
        touchState(key)
        writableDatabase.execSQL("UPDATE conv_state SET person_id=-1 WHERE conv_key=?", arrayOf<Any>(key))
        changed()
    }

    /** Deletes messages by id and remembers them so they aren't imported again. */
    fun delete(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        purge("id IN (${ids.joinToString(",")})", emptyArray())
    }

    /** Deletes whole chats; a new message later starts the chat afresh. */
    fun deleteConversations(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val marks = keys.joinToString(",") { "?" }
        val args = keys.toTypedArray<Any>()
        purge("conv_key IN ($marks)", args) {
            writableDatabase.execSQL("DELETE FROM conv_state WHERE conv_key IN ($marks)", args)
            writableDatabase.execSQL("DELETE FROM reminders WHERE conv_key IN ($marks)", args)
        }
    }

    private fun purge(where: String, args: Array<Any>, also: () -> Unit = {}) {
        val db = writableDatabase
        inTransaction {
            db.execSQL("INSERT OR IGNORE INTO deleted(uniq) SELECT uniq FROM messages WHERE $where", args)
            db.execSQL("DELETE FROM messages WHERE $where", args)
            also()
        }
        changed()
    }

    // ---------- reading state ----------

    private fun touchState(key: String) =
        writableDatabase.execSQL("INSERT OR IGNORE INTO conv_state(conv_key) VALUES (?)", arrayOf<Any>(key))

    private fun setState(keys: Collection<String>, assignment: String, vararg args: Any) {
        if (keys.isEmpty()) return
        inTransaction {
            keys.forEach { key ->
                touchState(key)
                writableDatabase.execSQL("UPDATE conv_state SET $assignment WHERE conv_key=?", arrayOf(*args, key))
            }
        }
        changed()
    }

    fun setPinned(keys: Collection<String>, on: Boolean) = setState(keys, "pinned=?", if (on) 1 else 0)
    fun setMuted(keys: Collection<String>, on: Boolean) = setState(keys, "muted=?", if (on) 1 else 0)
    /** Archived chats come back on their own when a newer message arrives. */
    fun setArchived(keys: Collection<String>, on: Boolean) = setState(keys, "archived_ts=?", if (on) System.currentTimeMillis() else 0L)
    fun snooze(keys: Collection<String>, until: Long) = setState(keys, "snooze_until=?", until)

    /** Saved quietly: no [changes] bump, so typing doesn't re-query every screen. */
    fun saveDraft(key: String, text: String) {
        touchState(key)
        writableDatabase.execSQL("UPDATE conv_state SET draft=? WHERE conv_key=?", arrayOf<Any?>(text.ifBlank { null }, key))
    }

    fun draft(key: String): String =
        readableDatabase.rawQuery("SELECT draft FROM conv_state WHERE conv_key=?", arrayOf(key))
            .use { if (it.moveToFirst()) it.getString(0).orEmpty() else "" }

    // ---------- reminders ----------

    fun addReminder(convKey: String, at: Long, text: String): Long =
        writableDatabase.insert("reminders", null, ContentValues().apply { put("conv_key", convKey); put("at", at); put("text", text) })

    fun reminder(id: Long): Reminder? = reminders("id=?", arrayOf(id.toString())).firstOrNull()
    fun pendingReminders(): List<Reminder> = reminders("at > ?", arrayOf(System.currentTimeMillis().toString()))
    fun deleteReminder(id: Long) = writableDatabase.execSQL("DELETE FROM reminders WHERE id=?", arrayOf<Any>(id))

    private fun reminders(where: String, args: Array<String>) =
        readableDatabase.rawQuery("SELECT id, conv_key, at, text FROM reminders WHERE $where ORDER BY at", args)
            .use { c -> buildList { while (c.moveToNext()) add(Reminder(c.getLong(0), c.getString(1), c.getLong(2), c.getString(3))) } }

    fun markSeen(key: String) {
        touchState(key)
        writableDatabase.execSQL("UPDATE conv_state SET seen_ts=? WHERE conv_key=?", arrayOf<Any>(System.currentTimeMillis(), key))
        changed()
    }

    /** Clears "needs reply" until the next incoming message. */
    fun markDone(key: String) {
        touchState(key)
        val now = System.currentTimeMillis()
        writableDatabase.execSQL("UPDATE conv_state SET done_ts=?, seen_ts=? WHERE conv_key=?", arrayOf<Any>(now, now, key))
        changed()
    }

    // ---------- search ----------

    /**
     * Full-text search. Supports `from:name` (sender or chat title), `app:whatsapp` and free
     * words, which match as prefixes ("hel" finds "hello").
     */
    fun search(query: String, limit: Int = 200): List<SearchHit> {
        var from: String? = null
        var app: Channel? = null
        val words = mutableListOf<String>()
        for (token in query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }) {
            when {
                token.startsWith("from:", true) -> from = token.substringAfter(':').ifBlank { null }
                token.startsWith("app:", true) -> app = Channel.entries.firstOrNull { it.label.startsWith(token.substringAfter(':'), true) }
                else -> words += token.replace(Regex("[\"*^:()-]"), "")
            }
        }
        val where = mutableListOf<String>()
        val args = mutableListOf<String>()
        words.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { w ->
            where += "m.id IN (SELECT docid FROM messages_fts WHERE messages_fts MATCH ?)"
            args += w.joinToString(" ") { "$it*" }
        }
        from?.let { where += "(m.sender LIKE ? OR m.conv_title LIKE ?)"; args += "%$it%"; args += "%$it%" }
        app?.let { where += "m.channel=?"; args += it.name }
        if (where.isEmpty()) return emptyList()
        return readableDatabase.rawQuery(
            "SELECT m.conv_key, m.channel, m.conv_title, m.sender, m.body, m.ts, m.id FROM messages m WHERE ${where.joinToString(" AND ")} ORDER BY m.ts DESC LIMIT $limit",
            args.toTypedArray(),
        ).use { c ->
            buildList { while (c.moveToNext()) add(SearchHit(c.getLong(6), c.getString(0), Channel.valueOf(c.getString(1)), c.getString(2), c.getString(3), c.getString(4), c.getLong(5))) }
        }
    }

    companion object {
        private const val DB_NAME = "omni-vault.db"
        private const val VERSION = 3
        private const val LEGACY_DB = "omni.db"

        @Volatile private var instance: Store? = null

        fun get(ctx: Context): Store = instance ?: synchronized(this) {
            instance ?: open(ctx.applicationContext).also { instance = it }
        }

        private fun open(app: Context): Store {
            System.loadLibrary("sqlcipher")
            // hex text, so the same passphrase also works in SQL (ATTACH … KEY) if ever needed
            val pass = Vault.secret(app, "db").joinToString("") { "%02x".format(it) }.toByteArray()
            return Store(app, pass).also { migrateLegacy(app, it) }
        }

        /** Moves messages from the old unencrypted database (v0.1/0.2), then deletes it. */
        private fun migrateLegacy(app: Context, store: Store) {
            val legacy = app.getDatabasePath(LEGACY_DB)
            if (!legacy.exists()) return
            val ok = runCatching {
                android.database.sqlite.SQLiteDatabase.openDatabase(legacy.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { old ->
                    old.rawQuery("SELECT channel, pkg, conv_key, conv_title, address, sender, body, ts, outgoing, uniq FROM messages", null).use { c ->
                        store.inTransaction {
                            while (c.moveToNext()) {
                                val channel = runCatching { Channel.valueOf(c.getString(0)) }.getOrNull() ?: continue
                                store.add(channel, c.getString(1), c.getString(2), c.getString(3), c.getString(5), c.getString(6),
                                    c.getLong(7), c.getInt(8) == 1, c.getString(9), fuzzy = false, address = c.getString(4), notify = false)
                            }
                        }
                    }
                }
            }.onFailure { android.util.Log.e("OmniStore", "legacy migration failed; keeping old database", it) }.isSuccess
            if (ok) app.deleteDatabase(LEGACY_DB)
        }
    }
}
