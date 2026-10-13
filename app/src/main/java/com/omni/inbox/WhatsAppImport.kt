package com.omni.inbox

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.util.Calendar
import java.util.zip.ZipInputStream

/**
 * Parses WhatsApp's own "Export chat" file (.txt, or .zip when media was included) — the
 * official way to get WhatsApp history out. Handles Android and iPhone layouts, day/month or
 * month/day or ISO dates, 12/24h clocks and multi-line messages.
 */
object WhatsAppImport {
    data class Line(val ts: Long, val sender: String, val text: String)
    data class Parsed(val title: String, val lines: List<Line>) {
        /** Participants by message count, so "you" is easy to spot. */
        val senders: List<String> get() = lines.groupingBy { it.sender }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
    }

    // [13/10/2026, 10:31:05] Anna: hi   |   13/10/2026, 10:31 - Anna: hi   |   2026-10-13 10:31 - Anna: hi   |   10/13/26, 10:31 PM - Anna: hi
    private val head = Regex(
        """^\[?(\d{1,4})[./-](\d{1,2})[./-](\d{1,4}),?\s+(\d{1,2})[:.](\d{2})(?:[:.](\d{2}))?\s*([AaPp])?\.?\s*[Mm]?\.?\]?\s*(?:[-–]\s*)?(.*)$"""
    )
    private val invisible = Regex("[‎‏‪-‮⁦-⁩]")

    fun read(ctx: Context, uri: Uri): Parsed {
        val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "WhatsApp chat"
        // streamed: an export "with media" can be hundreds of MB, but only the chat text is read
        val text = ctx.contentResolver.openInputStream(uri)!!.buffered().use { input ->
            input.mark(2)
            val zipped = input.read() == 'P'.code && input.read() == 'K'.code
            input.reset()
            if (zipped) {
                ZipInputStream(input).let { zip ->
                    generateSequence { zip.nextEntry }.firstOrNull { it.name.endsWith(".txt", true) }
                        ?.let { zip.readBytes().toString(Charsets.UTF_8) }
                        ?: error("No chat text in this zip")
                }
            } else input.readBytes().toString(Charsets.UTF_8)
        }
        return Parsed(titleFrom(name), parse(text))
    }

    /** "WhatsApp Chat with Anna.txt", "WhatsApp-chatt med Anna.zip", "WhatsApp Chat - Anna.zip" → "Anna". */
    fun titleFrom(file: String): String {
        val base = file.substringBeforeLast('.').trim()
        val m = Regex("""^WhatsApp[\s-]*(?:Chat|chatt|Unterhaltung|conversa|chat)\s*(?:with|med|mit|com|con|avec|met|-)\s*(.+)$""", RegexOption.IGNORE_CASE).find(base)
        return (m?.groupValues?.get(1) ?: base).trim().ifBlank { "WhatsApp chat" }
    }

    fun parse(raw: String): List<Line> {
        data class Pending(val a: Int, val b: Int, val c: Int, val h: Int, val min: Int, val s: Int, val ampm: Char?, val sender: String, val text: StringBuilder)
        val items = mutableListOf<Pending>()
        for (rawLine in raw.lines()) {
            val line = invisible.replace(rawLine, "").replace(' ', ' ').replace(' ', ' ')
            val m = head.matchEntire(line)
            if (m == null) { items.lastOrNull()?.text?.append('\n')?.append(line); continue }
            val g = m.groupValues
            val rest = g[8]
            val colon = rest.indexOf(": ")
            if (colon <= 0) { items.add(Pending(0, 0, 0, 0, 0, 0, null, "", StringBuilder())); continue } // system line placeholder
            items.add(Pending(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toIntOrNull() ?: 0,
                g[7].firstOrNull()?.lowercaseChar(), rest.substring(0, colon).trim(), StringBuilder(rest.substring(colon + 2))))
        }
        val real = items.filter { it.sender.isNotEmpty() }
        // decide field order once per file: ISO (yyyy first), day-first or month-first
        val iso = real.any { it.a > 31 }
        val monthFirst = !iso && real.none { it.a > 12 } && real.any { it.b > 12 }
        val cal = Calendar.getInstance()
        return real.map { p ->
            val (y, mo, d) = when {
                iso -> Triple(p.a, p.b, p.c)
                monthFirst -> Triple(p.c, p.a, p.b)
                else -> Triple(p.c, p.b, p.a)
            }
            var hour = p.h
            if (p.ampm == 'p' && hour < 12) hour += 12
            if (p.ampm == 'a' && hour == 12) hour = 0
            cal.clear()
            cal.set(if (y < 100) 2000 + y else y, mo - 1, d, hour, p.min, p.s)
            Line(cal.timeInMillis, p.sender, p.text.toString().trimEnd())
        }
    }

    /**
     * Stores the chat. Lands in the same conversation as notification-captured WhatsApp
     * messages when the title matches, so history and live messages form one timeline.
     */
    fun save(ctx: Context, parsed: Parsed, title: String, me: String?): Int {
        val store = Store.get(ctx)
        val key = store.conversations(Channel.WHATSAPP).firstOrNull { it.title.equals(title, true) }?.key ?: "wa-import:$title"
        var added = 0
        store.inTransaction {
            parsed.lines.forEachIndexed { i, l ->
                val mine = l.sender == me
                if (store.add(Channel.WHATSAPP, "com.whatsapp", key, title, if (mine) null else l.sender, l.text, l.ts, mine,
                        "wai:$title:${l.ts}:$i:${l.text.hashCode()}", fuzzy = false, notify = false)) added++
            }
        }
        store.changed()
        return added
    }
}
