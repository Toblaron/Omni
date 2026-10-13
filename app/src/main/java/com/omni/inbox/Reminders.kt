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

/**
 * Timed nudges: snoozed chats coming back and "remind me" on a message. Kept in the [Store]
 * so they survive reboots; AlarmManager wakes [ReminderReceiver] when one is due.
 */
object Reminders {
    private const val CHANNEL = "reminders"
    const val EXTRA_OPEN = "open"
    private const val EXTRA_ID = "reminder_id"

    fun add(ctx: Context, convKey: String, at: Long, text: String) {
        val id = Store.get(ctx).addReminder(convKey, at, text)
        schedule(ctx, id, at)
    }

    /** Drops pending reminders for [convKey] (e.g. the chat was un-snoozed). */
    fun cancelFor(ctx: Context, convKey: String) {
        val store = Store.get(ctx)
        store.pendingReminders().filter { it.convKey == convKey }.forEach {
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
        val title = store.conversation(r.convKey)?.title ?: "Omni Inbox"
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            ctx, id.toInt(), Intent(ctx, MainActivity::class.java).putExtra(EXTRA_OPEN, r.convKey).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat).setColor(0xFF00E5FF.toInt())
            .setContentTitle(title).setContentText(r.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(r.text))
            .setContentIntent(open).setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(r.convKey, id.toInt(), n) } // no-op without POST_NOTIFICATIONS
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
