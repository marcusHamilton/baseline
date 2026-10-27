package dev.baseline.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.util.Log
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import dev.baseline.AppContainer
import dev.baseline.BaselineApp
import dev.baseline.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

open class WidgetUpdater(private val context: Context, private val container: () -> AppContainer) {
    fun hasAnyWidgets(manager: AppWidgetManager = AppWidgetManager.getInstance(context)): Boolean =
        PROVIDERS.any { manager.getAppWidgetIds(ComponentName(context, it)).isNotEmpty() }

    open suspend fun updateAll(forceSyncing: Boolean = false) = withContext(Dispatchers.Default) {
        val manager = AppWidgetManager.getInstance(context)
        if (!hasAnyWidgets(manager)) return@withContext
        val snapshot = loadWidgetSnapshot(container(), DateFormat.is24HourFormat(context), forceSyncing)
        updateReadinessInstances(manager, snapshot)
    }

    private fun updateReadinessInstances(manager: AppWidgetManager, snapshot: WidgetSnapshot) {
        val ids = manager.getAppWidgetIds(ComponentName(context, ReadinessWidgetProvider::class.java))
        for (id in ids) {
            if (snapshot.keyMissing) {
                manager.updateAppWidget(id, buildMissingKeyViews())
                continue
            }
            val options = manager.getAppWidgetOptions(id)
            val views =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val compact = buildReadinessViews(snapshot, compact = true, options = options)
                    val expanded = buildReadinessViews(snapshot, compact = false, options = options)
                    RemoteViews(mapOf(SizeF(140f, 110f) to compact, SizeF(250f, 110f) to expanded))
                } else {
                    val minW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250).let { if (it <= 0) 250 else it }
                    buildReadinessViews(snapshot, compact = minW < 250, options = options)
                }
            manager.updateAppWidget(id, views)
        }
    }

    internal fun buildMissingKeyViews(): RemoteViews = RemoteViews(context.packageName, R.layout.widget_missing_key).apply {
        setOnClickPendingIntent(R.id.widget_missing_root, WidgetRoutes.pendingIntent(context, WidgetTarget.Today))
    }

    internal fun bindSyncPill(views: RemoteViews, pill: SyncPillUi, compact: Boolean = false) {
        when (pill) {
            SyncPillUi.KeyError -> {
                val text = if (compact) "!" else context.getString(R.string.widget_sync_key_error)
                views.setTextViewText(R.id.widget_sync_pill, text)
                views.setTextColor(R.id.widget_sync_pill, KEY_ERR_TEXT_ARGB)
                views.setInt(R.id.widget_sync_pill, "setBackgroundResource", R.drawable.widget_bg_pill_error)
                views.setContentDescription(R.id.widget_sync_hitbox, context.getString(R.string.widget_sync_key_error))
                views.setOnClickPendingIntent(
                    R.id.widget_sync_hitbox,
                    WidgetRoutes.pendingIntent(context, WidgetTarget.Settings),
                )
            }

            SyncPillUi.Syncing -> {
                val text = if (compact) "…" else context.getString(R.string.widget_sync_syncing)
                views.setTextViewText(R.id.widget_sync_pill, text)
                views.setTextColor(R.id.widget_sync_pill, WidgetGraphics.TEXT_BODY)
                views.setInt(R.id.widget_sync_pill, "setBackgroundResource", R.drawable.widget_bg_pill)
                views.setContentDescription(R.id.widget_sync_hitbox, context.getString(R.string.widget_sync_syncing))
                views.setOnClickPendingIntent(R.id.widget_sync_hitbox, WidgetActionReceiver.syncPendingIntent(context))
            }

            is SyncPillUi.Ready -> {
                val text = if (compact) "↻" else pill.label
                views.setTextViewText(R.id.widget_sync_pill, text)
                views.setTextColor(R.id.widget_sync_pill, WidgetGraphics.TEXT_BODY)
                views.setInt(R.id.widget_sync_pill, "setBackgroundResource", R.drawable.widget_bg_pill)
                views.setContentDescription(R.id.widget_sync_hitbox, pill.label)
                views.setOnClickPendingIntent(R.id.widget_sync_hitbox, WidgetActionReceiver.syncPendingIntent(context))
            }
        }
    }

    internal fun buildReadinessViews(snapshot: WidgetSnapshot, compact: Boolean, options: Bundle = Bundle.EMPTY): RemoteViews {
        if (snapshot.keyMissing) return buildMissingKeyViews()
        return if (compact) {
            buildReadinessCompact(snapshot, options)
        } else {
            buildReadinessExpanded(snapshot)
        }
    }

    private fun buildReadinessCompact(snapshot: WidgetSnapshot, options: Bundle): RemoteViews {
        val r = snapshot.readiness
        val views = RemoteViews(context.packageName, R.layout.widget_readiness_compact)
        views.setOnClickPendingIntent(R.id.widget_root, WidgetRoutes.pendingIntent(context, WidgetTarget.Today))
        bindSyncPill(views, snapshot.syncPill, compact = true)

        views.setTextViewText(R.id.compact_recovery_value, r.recoveryPct?.let { "$it%" } ?: "—")
        views.setTextColor(R.id.compact_recovery_value, WidgetGraphics.recoveryArgb(r.recoveryZone))
        views.setTextViewText(R.id.compact_strain_value, r.strainText)

        when {
            r.unreadable -> {
                views.setTextViewText(R.id.compact_strain_target, context.getString(R.string.widget_unreadable_day))
                views.setTextColor(R.id.compact_strain_target, WidgetGraphics.RECOVERY_AMBER)
                views.setViewVisibility(R.id.compact_strain_target, View.VISIBLE)
            }

            r.strainTargetText != null -> {
                views.setTextViewText(R.id.compact_strain_target, "Target ${r.strainTargetText}")
                views.setTextColor(R.id.compact_strain_target, WidgetGraphics.TEXT_MUTED)
                views.setViewVisibility(R.id.compact_strain_target, View.VISIBLE)
            }

            else -> views.setViewVisibility(R.id.compact_strain_target, View.GONE)
        }

        views.setTextViewText(R.id.compact_bb_value, r.bodyBattery?.toString() ?: "—")
        val density = context.resources.displayMetrics.density
        val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 140).let { if (it <= 0) 140 else it }
        val barW = WidgetGraphics.dpToClampedPx((widthDp - 44).coerceAtLeast(60).toFloat(), density)
        val barH = WidgetGraphics.dpToClampedPx(6f, density)
        val bbColor = WidgetGraphics.bodyBatteryArgb(r.bodyBattery)
        views.setImageViewBitmap(
            R.id.compact_bb_bar,
            WidgetGraphics.renderHorizontalFillBar(r.bodyBattery?.let { it / 100f }, bbColor, barW, barH),
        )
        return views
    }

    private fun buildReadinessExpanded(snapshot: WidgetSnapshot): RemoteViews {
        val r = snapshot.readiness
        val views = RemoteViews(context.packageName, R.layout.widget_readiness_expanded)
        views.setOnClickPendingIntent(R.id.widget_root, WidgetRoutes.pendingIntent(context, WidgetTarget.Today))
        bindSyncPill(views, snapshot.syncPill, compact = false)
        views.setTextViewText(R.id.widget_header_title, r.headerDate)

        val density = context.resources.displayMetrics.density
        val tf = WidgetGraphics.loadCondensedBold(context)
        val dialPx = WidgetGraphics.dpToClampedPx(56f, density)
        val strokePx = (5f * density).coerceAtLeast(2f)

        val recColor = WidgetGraphics.recoveryArgb(r.recoveryZone)
        views.setImageViewBitmap(
            R.id.dial_recovery,
            WidgetGraphics.renderDialBitmap(
                fraction = r.recoveryPct?.let { it / 100f },
                value = r.recoveryPct?.toString() ?: "—",
                suffix = if (r.recoveryPct != null) "%" else "",
                note = if (r.recoveryPartial) "Partial" else null,
                accentArgb = recColor,
                sizePx = dialPx,
                strokePx = strokePx,
                typeface = tf,
            ),
        )
        views.setTextColor(R.id.label_recovery, recColor)
        views.setContentDescription(R.id.dial_recovery, r.recoveryPct?.let { "Recovery $it%" } ?: "Recovery unavailable")

        views.setImageViewBitmap(
            R.id.dial_strain,
            WidgetGraphics.renderDialBitmap(
                fraction = r.strain?.let { (it / 21.0).coerceIn(0.0, 1.0).toFloat() },
                value = r.strainText,
                suffix = "",
                note = r.strainTargetText,
                accentArgb = WidgetGraphics.STRAIN_BLUE,
                sizePx = dialPx,
                strokePx = strokePx,
                typeface = tf,
            ),
        )
        views.setContentDescription(R.id.dial_strain, if (r.strain != null) "Strain ${r.strainText}" else "Strain unavailable")

        views.setImageViewBitmap(
            R.id.dial_sleep,
            WidgetGraphics.renderDialBitmap(
                fraction = r.sleepPct?.let { it / 100f },
                value = r.sleepPct?.toString() ?: "—",
                suffix = if (r.sleepPct != null) "%" else "",
                note = null,
                accentArgb = WidgetGraphics.SLEEP_PURPLE,
                sizePx = dialPx,
                strokePx = strokePx,
                typeface = tf,
            ),
        )
        views.setContentDescription(R.id.dial_sleep, r.sleepPct?.let { "Sleep $it%" } ?: "Sleep unavailable")

        when {
            r.unreadable -> {
                views.setTextViewText(R.id.readiness_footer_left, context.getString(R.string.widget_unreadable_day))
                views.setTextColor(R.id.readiness_footer_left, WidgetGraphics.RECOVERY_AMBER)
            }

            r.strainTargetText != null -> {
                views.setTextViewText(R.id.readiness_footer_left, "Target strain ${r.strainTargetText}")
                views.setTextColor(R.id.readiness_footer_left, WidgetGraphics.TEXT_BODY)
            }

            r.recoveryReason != null -> {
                views.setTextViewText(R.id.readiness_footer_left, r.recoveryReason)
                views.setTextColor(R.id.readiness_footer_left, WidgetGraphics.TEXT_MUTED)
            }

            else -> views.setTextViewText(R.id.readiness_footer_left, "")
        }

        views.setTextViewText(R.id.readiness_footer_bb, "BB ${r.bodyBattery ?: "—"}")
        views.setTextColor(R.id.readiness_footer_bb, WidgetGraphics.bodyBatteryArgb(r.bodyBattery))
        return views
    }

    companion object {
        private const val KEY_ERR_TEXT_ARGB: Int = 0xFFFECACA.toInt()
        private const val TAG = "WidgetUpdater"

        private val PROVIDERS: List<Class<out AppWidgetProvider>> =
            listOf(
                ReadinessWidgetProvider::class.java,
            )

        @Volatile
        internal var testContainerOverride: AppContainer? = null

        internal fun resolveContainer(context: Context): AppContainer? = testContainerOverride ?: (context.applicationContext as? BaselineApp)?.container

        internal fun refreshFromProvider(provider: AppWidgetProvider, context: Context) {
            val pending = provider.goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                try {
                    val appContainer = resolveContainer(context) ?: return@launch
                    appContainer.dateTicker.refresh()
                    appContainer.widgetUpdater.updateAll()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "provider update: ${e.javaClass.simpleName}: ${e.message}")
                } finally {
                    pending?.finish()
                }
            }
        }
    }
}
