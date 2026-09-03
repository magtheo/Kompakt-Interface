package dev.magnor.kompakt.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import dev.magnor.kompakt.MainActivity
import dev.magnor.kompakt.R
import dev.magnor.kompakt.notifications.AlertNotifications
import dev.magnor.kompakt.ui.navigation.Routes
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * T-046 SPIKE — day-timeline home widget.
 *
 * Stub renderer answering three feasibility questions on the Lawnchair
 * beta + e-ink panel:
 *  1. Placement: provider appears in the picker, initial layout renders.
 *  2. Update path: [push] from the app process re-renders (header stamp
 *     changes) — the mechanism the real build rides on sync windows.
 *  3. Per-row taps: each row deep-links MainActivity via the existing
 *     EXTRA_ROUTE pipeline (same as notification taps).
 *
 * No data wiring yet; rows are static text. The real build replaces
 * [views] with the calendar-cache renderer and hooks pushes into the
 * sync-window pipeline.
 */
class DayWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // Placement-time render (system-delivered). Later pushes come
        // from push() below.
        appWidgetManager.updateAppWidget(appWidgetIds, views(context))
    }

    companion object {
        private val stamp = DateTimeFormatter.ofPattern("HH:mm:ss")

        /**
         * Push a re-render from the app process. No-op when no widget is
         * placed (getAppWidgetIds returns empty).
         */
        fun push(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(
                ComponentName(context, DayWidgetProvider::class.java),
            )
            if (ids.isNotEmpty()) mgr.updateAppWidget(ids, views(context))
        }

        private fun views(context: Context): RemoteViews =
            RemoteViews(context.packageName, R.layout.widget_day).apply {
                setTextViewText(
                    R.id.widget_date,
                    "pushed ${LocalDateTime.now().format(stamp)}",
                )
                setOnClickPendingIntent(
                    R.id.widget_header,
                    tapIntent(context, 0, Routes.TODAY),
                )
                setOnClickPendingIntent(
                    R.id.row1,
                    tapIntent(context, 1, Routes.CALENDAR),
                )
                setOnClickPendingIntent(
                    R.id.row2,
                    tapIntent(context, 2, Routes.eventDetail("spike")),
                )
                setOnClickPendingIntent(
                    R.id.row3,
                    tapIntent(context, 3, Routes.TODAY),
                )
            }

        /** Mirrors AlertPoster.tapIntent — identical deep-link contract. */
        private fun tapIntent(context: Context, requestCode: Int, route: String): PendingIntent {
            val tap = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(AlertNotifications.EXTRA_ROUTE, route)
            }
            return PendingIntent.getActivity(
                context,
                requestCode,
                tap,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
