package com.omni.inbox

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import java.text.DateFormat

/** Sending and jumping out to the source app, shared by the reply bar, forwarding and reminders. */
object Actions {
    private val WA_JID = Regex("""(\d{6,15})@s\.whatsapp\.net""")

    /** Whether we can send into [c] right now (Telegram linked, SMS permission, or a live notification reply). */
    fun canSend(ctx: Context, c: Conversation): Boolean =
        (tgChat(c) != null && Telegram.auth.value == Telegram.Auth.Ready) || (isSms(c) && SmsSync.canSend(ctx)) || Replies.canReply(c.key)

    /** Sends [body] into [c]; false when the channel can't take it right now. */
    suspend fun send(ctx: Context, store: Store, c: Conversation, body: String): Boolean {
        val tg = tgChat(c)
        return when {
            tg != null && Telegram.auth.value == Telegram.Auth.Ready -> Telegram.sendText(tg, body) == null
            isSms(c) -> runCatching { SmsSync.send(ctx, c.address!!, body) }.isSuccess
            Replies.send(ctx, c.key, body) -> {
                val now = System.currentTimeMillis()
                store.add(c.channel, c.pkg, c.key, c.title, null, body, now, true, "${c.key}|me|$now", fuzzy = true)
                true
            }
            else -> false
        }
    }

    /** Opens [c] in its own app: a deep link where one exists, else the notification's tap target, else the app. */
    fun openChat(ctx: Context, c: Conversation) {
        deepLink(c)?.let { link ->
            val withPkg = c.pkg?.let { Intent(link).setPackage(it) }
            if (withPkg != null && runCatching { ctx.startActivity(withPkg) }.isSuccess) return
            if (runCatching { ctx.startActivity(link) }.isSuccess) return
        }
        if (Replies.openFromNotification(ctx, c.key)) return
        c.pkg?.let { openApp(ctx, it) } ?: Toast.makeText(ctx, "App not installed", Toast.LENGTH_SHORT).show()
    }

    private fun deepLink(c: Conversation): Intent? {
        val uri = when {
            isSms(c) -> return Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(c.address)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            tgChat(c) != null -> tgChat(c)!!.let { id ->
                when {
                    id > 0 -> "tg://openmessage?user_id=$id"
                    id < -1_000_000_000_000 -> "tg://openmessage?chat_id=${-id - 1_000_000_000_000}" // supergroup / channel
                    else -> "tg://openmessage?chat_id=${-id}"
                }
            }
            c.channel == Channel.WHATSAPP -> WA_JID.find(c.key)?.let { "https://wa.me/${it.groupValues[1]}" }
            else -> null
        } ?: return null
        return Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun openApp(ctx: Context, pkg: String) {
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) ctx.startActivity(intent)
        else Toast.makeText(ctx, "App not installed", Toast.LENGTH_SHORT).show()
    }

    /** "[13.10 18:02] Anna: hei" lines; time and sender only when copying several. */
    fun asText(msgs: List<Msg>, chatTitle: (Msg) -> String): String =
        if (msgs.size == 1) msgs[0].text
        else msgs.joinToString("\n") { m ->
            val who = if (m.outgoing) "Me" else m.sender ?: chatTitle(m)
            "[${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(m.ts)}] $who: ${m.text}"
        }

    fun copy(ctx: Context, text: String) {
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Messages", text))
    }

    fun share(ctx: Context, text: String) {
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
    }

    /** Opens the calendar's "new event" screen, prefilled; no calendar permission needed. */
    fun addToCalendar(ctx: Context, w: Signals.When, title: String) {
        val intent = Intent(Intent.ACTION_INSERT, android.provider.CalendarContract.Events.CONTENT_URI)
            .putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, w.start)
            .putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, w.start + 3_600_000)
            .putExtra(android.provider.CalendarContract.EXTRA_EVENT_ALL_DAY, !w.hasTime)
            .putExtra(android.provider.CalendarContract.Events.TITLE, title.take(120))
        runCatching { ctx.startActivity(intent) }.onFailure { Toast.makeText(ctx, "No calendar app", Toast.LENGTH_SHORT).show() }
    }

    fun openMap(ctx: Context, place: String) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(place)))) }
            .onFailure { Toast.makeText(ctx, "No maps app", Toast.LENGTH_SHORT).show() }
    }

    private fun tgChat(c: Conversation) = c.key.removePrefix("tg:").toLongOrNull()?.takeIf { c.key.startsWith("tg:") }
    private fun isSms(c: Conversation) = c.channel == Channel.SMS && c.address != null && c.key.startsWith("sms:")
}
