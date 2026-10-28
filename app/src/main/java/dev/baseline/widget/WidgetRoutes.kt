package dev.baseline.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.baseline.MainActivity

sealed interface WidgetTarget {
    data object Today : WidgetTarget

    data object SleepToday : WidgetTarget

    data object Activities : WidgetTarget

    data class Activity(val id: Long) : WidgetTarget

    data object Settings : WidgetTarget
}

object WidgetRoutes {
    private const val SCHEME = "baseline"
    private const val HOST = "widget"

    fun intent(context: Context, target: WidgetTarget): Intent {
        val path =
            when (target) {
                WidgetTarget.Today -> "/today"
                WidgetTarget.SleepToday -> "/sleep/today"
                WidgetTarget.Activities -> "/activities"
                is WidgetTarget.Activity -> "/activity/${target.id}"
                WidgetTarget.Settings -> "/settings"
            }
        val uri = Uri.parse("$SCHEME://$HOST$path")
        return Intent(Intent.ACTION_VIEW, uri, context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
    }

    fun pendingIntent(context: Context, target: WidgetTarget): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        intent(context, target),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun parse(intent: Intent?): WidgetTarget? {
        val uri = intent?.data ?: return null
        if (uri.scheme != SCHEME || uri.host != HOST) return null
        val segments = uri.pathSegments
        return when (segments.size) {
            1 ->
                when (segments[0]) {
                    "today" -> WidgetTarget.Today
                    "activities" -> WidgetTarget.Activities
                    "settings" -> WidgetTarget.Settings
                    else -> null
                }

            2 ->
                when (segments[0]) {
                    "sleep" -> if (segments[1] == "today") WidgetTarget.SleepToday else null
                    "activity" -> segments[1].toLongOrNull()?.let(WidgetTarget::Activity)
                    else -> null
                }

            else -> null
        }
    }
}
