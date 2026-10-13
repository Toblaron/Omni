package com.omni.inbox

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import android.widget.RemoteViewsService

/** Home-screen list of the people waiting for your reply; tap one to open the chat. */
class Widget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
        Thread { update(ctx.applicationContext) }.start()
    }

    companion object {
        private const val MAX_ROWS = 15
        private val main = Handler(Looper.getMainLooper())
        private var pending: Runnable? = null

        /** Coalesces bursts of store changes into one redraw a second later. */
        fun refresh(ctx: Context) {
            val app = ctx.applicationContext
            val ids = AppWidgetManager.getInstance(app).getAppWidgetIds(ComponentName(app, Widget::class.java))
            if (ids.isEmpty()) return
            pending?.let(main::removeCallbacks)
            pending = Runnable { Thread { update(app) }.start() }.also { main.postDelayed(it, 1_000) }
        }

        internal fun waiting(ctx: Context): List<Person> =
            People.group(Store.get(ctx).inbox()).filter { !it.hidden && it.needsReply }

        private fun update(ctx: Context) {
            val manager = AppWidgetManager.getInstance(ctx)
            val ids = manager.getAppWidgetIds(ComponentName(ctx, Widget::class.java))
            if (ids.isEmpty()) return
            // the vault may still be locked right after boot; show the last state until it opens
            val count = runCatching { waiting(ctx).size }.getOrNull() ?: return
            for (id in ids) {
                val views = RemoteViews(ctx.packageName, R.layout.widget).apply {
                    setTextViewText(R.id.count, if (count > 0) s(R.string.widget_count, count) else "")
                    setRemoteAdapter(R.id.list, Intent(ctx, Rows::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                    setEmptyView(R.id.list, R.id.empty)
                    setOnClickPendingIntent(R.id.header, PendingIntent.getActivity(
                        ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
                    ))
                    // each row fills in which chat to open
                    setPendingIntentTemplate(R.id.list, PendingIntent.getActivity(
                        ctx, 1, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    ))
                }
                manager.updateAppWidget(id, views)
            }
            @Suppress("DEPRECATION") manager.notifyAppWidgetViewDataChanged(ids, R.id.list)
        }
    }

    class Rows : RemoteViewsService() {
        override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = object : RemoteViewsFactory {
            private var people = emptyList<Person>()
            override fun onCreate() {}
            override fun onDataSetChanged() { people = runCatching { waiting(applicationContext).take(MAX_ROWS) }.getOrDefault(emptyList()) }
            override fun onDestroy() {}
            override fun getCount() = people.size
            override fun getViewTypeCount() = 1
            override fun hasStableIds() = true
            override fun getItemId(position: Int) = people.getOrNull(position)?.key?.hashCode()?.toLong() ?: position.toLong()
            override fun getLoadingView(): RemoteViews? = null

            override fun getViewAt(position: Int): RemoteViews {
                val p = people.getOrNull(position) ?: return RemoteViews(packageName, R.layout.widget_row)
                val c = p.latest
                val open = if (p.chats.size == 1) c.key else "person:${p.key}"
                return RemoteViews(packageName, R.layout.widget_row).apply {
                    setTextViewText(R.id.name, p.name)
                    setTextViewText(R.id.meta, "${c.channel.label.uppercase()} · ${shortTime(c.lastTs)}")
                    setTextColor(R.id.meta, c.channel.color.toInt())
                    setTextViewText(R.id.preview, c.lastText)
                    setOnClickFillInIntent(R.id.row, Intent().putExtra(Reminders.EXTRA_OPEN, open))
                }
            }
        }
    }
}
