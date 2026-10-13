package com.omni.inbox

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow

data class Msg(
    val id: Long,
    val sender: String?,
    val text: String,
    val ts: Long,
    val outgoing: Boolean,
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
)

/** All captured messages, in one SQLite table. [changes] bumps on every write so the UI can re-query. */
class Store private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx, "omni.db", null, 1) {

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
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}

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

    fun conversations(filter: Channel?): List<Conversation> {
        val where = if (filter != null) "WHERE channel=?" else ""
        val args = if (filter != null) arrayOf(filter.name) else null
        // SQLite returns the other columns from the row that holds MAX(ts)
        return readableDatabase.rawQuery(
            "SELECT conv_key, channel, pkg, conv_title, body, sender, MAX(ts), address FROM messages $where GROUP BY conv_key ORDER BY MAX(ts) DESC",
            args,
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    Conversation(
                        c.getString(0), Channel.valueOf(c.getString(1)), c.getString(2), c.getString(3),
                        c.getString(4), c.getString(5), c.getLong(6), c.getString(7),
                    )
                )
            }
        }
    }

    fun conversation(key: String): Conversation? =
        readableDatabase.rawQuery(
            "SELECT conv_key, channel, pkg, conv_title, body, sender, MAX(ts), address FROM messages WHERE conv_key=?",
            arrayOf(key),
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) Conversation(
                c.getString(0), Channel.valueOf(c.getString(1)), c.getString(2), c.getString(3),
                c.getString(4), c.getString(5), c.getLong(6), c.getString(7),
            ) else null
        }

    fun messages(key: String): List<Msg> =
        readableDatabase.rawQuery(
            "SELECT id, sender, body, ts, outgoing FROM messages WHERE conv_key=? ORDER BY ts",
            arrayOf(key),
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(Msg(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getInt(4) == 1))
            }
        }

    companion object {
        @Volatile private var instance: Store? = null
        fun get(ctx: Context): Store =
            instance ?: synchronized(this) { instance ?: Store(ctx.applicationContext).also { instance = it } }
    }
}
