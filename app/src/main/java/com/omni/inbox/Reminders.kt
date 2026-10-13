package com.omni.inbox

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.runBlocking

/**
 * Timed nudges: snoozed chats coming back and "remind me" on a message. Kept in the [Store]
 * so they survive reboots; AlarmManager wakes [ReminderReceiver] when one is due.
 */
object Reminders {
    private const val CHANNEL = "reminders"
    const val EXTRA_OPEN = "open"
    private const val EXTRA_ID = "reminder_id"

    fun add(ctx: Context, convKey: String, at: Long, text: String, kind: String = Reminder.KIND_REMIND) {
        val id = Store.get(ctx).addReminder(convKey, at, text, kind)
        schedule(ctx, id, at)
        if (kind == Reminder.KIND_SEND) Store.get(ctx).changed() // shows in the chat's queue
    }

    /** Cancels one pending reminder or scheduled message. */
    fun cancel(ctx: Context, id: Long) {
        ctx.getSystemService(AlarmManager::class.java).cancel(alarm(ctx, id))
        Store.get(ctx).deleteReminder(id)
        Store.get(ctx).changed()
    }

    /** Drops pending reminders (not scheduled messages) for [convKey], e.g. the chat was un-snoozed. */
    fun cancelFor(ctx: Context, convKey: String) {
        val store = Store.get(ctx)
        store.pendingReminders().filter { it.convKey == convKey && it.kind == Reminder.KIND_REMIND }.forEach {
            ctx.getSystemService(AlarmManager::class.java).cancel(alarm(ctx, it.id))
            store.deleteReminder(it.id)
        }
    }

    /** After a reboot alarms are gone; set them again from the store. */
    fun rescheduleAll(ctx: Context) = Store.get(ctx).pendingReminders().forEach { schedule(ctx, it.id, it.at) }

    private fun schedule(ctx: Context, id: Long, at: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm(ctx, id))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm(ctx, id))
    }

    private fun alarm(ctx: Context, id: Long) = PendingIntent.getBroadcast(
        ctx, id.toInt(), Intent(ctx, ReminderReceiver::class.java).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    internal fun fire(ctx: Context, id: Long) {
        val store = Store.get(ctx)
        val r = store.reminder(id) ?: return
        store.deleteReminder(id)
        store.changed() // a finished snooze moves the chat back into the inbox
        val conv = store.conversation(r.convKey)
        val title = conv?.title ?: "Omni Inbox"
        if (r.kind == Reminder.KIND_SEND) {
            val sent = conv != null && runBlocking { Actions.send(ctx, store, conv, r.text) }
            // a sent scheduled message is quiet; only a failure needs your attention
            if (sent) return
            notify(ctx, r.convKey, id, title, s(R.string.scheduled_failed_notice, r.text))
            return
        }
        notify(ctx, r.convKey, id, title, r.text)
    }

    private fun notify(ctx: Context, convKey: String, id: Long, title: String, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, s(R.string.reminders_channel), NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            ctx, id.toInt(), Intent(ctx, MainActivity::class.java).putExtra(EXTRA_OPEN, convKey).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat).setColor(0xFF00E5FF.toInt())
            .setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(convKey, id.toInt(), n) } // no-op without POST_NOTIFICATIONS
    }

    class ReminderReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val pending = goAsync()
            Thread {
                try {
                    if (intent.action == Intent.ACTION_BOOT_COMPLETED) rescheduleAll(ctx)
                    else fire(ctx, intent.getLongExtra(EXTRA_ID, -1))
                } finally { pending.finish() }
            }.start()
        }
    }
}
