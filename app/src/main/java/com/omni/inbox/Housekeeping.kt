package com.omni.inbox

import android.content.Context
import android.net.Uri
import java.io.File

/** Automatic cleanup of old messages, and password-protected backup files. */
object Housekeeping {
    private const val DAY = 86_400_000L
    /** Keep-for choices in days; 0 = forever. */
    val KEEP_CHOICES = listOf(0 to "Forever", 365 to "1 year", 90 to "90 days", 30 to "30 days")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun keepDays(ctx: Context) = prefs(ctx).getInt("keep_days", 0)

    fun setKeepDays(ctx: Context, days: Int) {
        prefs(ctx).edit().putInt("keep_days", days).putLong("purged_at", 0).apply()
        cleanIfDue(ctx)
    }

    /** At most once a day; pinned chats are kept whole. */
    fun cleanIfDue(ctx: Context) {
        val days = keepDays(ctx)
        val now = System.currentTimeMillis()
        if (days == 0 || now - prefs(ctx).getLong("purged_at", 0) < DAY) return
        Store.get(ctx).purgeOlderThan(now - days * DAY)
        prefs(ctx).edit().putLong("purged_at", now).apply()
    }

    fun export(ctx: Context, to: Uri, password: String) {
        val tmp = File(ctx.cacheDir, "backup.tmp")
        try {
            Store.get(ctx).exportTo(tmp, password)
            ctx.contentResolver.openOutputStream(to, "wt")!!.use { out -> tmp.inputStream().use { it.copyTo(out) } }
        } finally { tmp.delete() }
    }

    /** Messages added; throws when the password is wrong or the file isn't a backup. */
    fun restore(ctx: Context, from: Uri, password: String): Int {
        val tmp = File(ctx.cacheDir, "restore.tmp")
        try {
            ctx.contentResolver.openInputStream(from)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            return Store.get(ctx).importFrom(tmp, password)
        } finally { tmp.delete() }
    }
}
