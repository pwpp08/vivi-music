package com.xlollx.songport.widget

import com.xlollx.songport.providers.*
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.data.Store
import com.xlollx.songport.sync.Scheduler
import com.xlollx.songport.sync.SyncWorker
import java.text.DateFormat
import java.util.Date

/** Widget home: ultima sincronizzazione e un pulsante "Sincronizza tutto". */
class SyncWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, build(context)) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_SYNC_ALL) {
            Scheduler.runNow(context, SyncWorker.ALL)
            refresh(context)
        }
    }

    companion object {
        const val ACTION_SYNC_ALL = "com.xlollx.songport.widget.SYNC_ALL"

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, SyncWidget::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, build(context))
        }

        private fun build(context: Context): RemoteViews {
            val data = Store.get(context).data
            val last = data.reports.firstOrNull()
            val lastText = when {
                last == null -> context.getString(R.string.th_last_run_never)
                !last.ok -> context.getString(R.string.th_result_error, last.error)
                else -> context.getString(R.string.th_widget_last, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(last.startedEpoch)),
                    context.getString(R.string.th_result_added_removed, last.added, last.removed))
            }
            val views = RemoteViews(context.packageName, R.layout.th_widget_sync)
            views.setTextViewText(R.id.widget_title, context.getString(R.string.th_app_name))
            views.setTextViewText(R.id.widget_status, lastText)
            views.setTextViewText(R.id.widget_count, context.resources.getQuantityString(R.plurals.th_widget_jobs, data.jobs.size, data.jobs.size))

            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.widget_root, open)

            val syncAll = PendingIntent.getBroadcast(
                context, 1, Intent(context, SyncWidget::class.java).setAction(ACTION_SYNC_ALL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.widget_sync, syncAll)
            return views
        }
    }
}
