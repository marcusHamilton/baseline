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
import dev.baseline.ui.components.sportIcon
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
        updateVitalsInstances(manager, snapshot)
        updateBodyBatteryInstances(manager, snapshot)
        updateSleepInstances(manager, snapshot)
        updateWeeklyActivityInstances(manager, snapshot)
    }

    private fun updateSleepInstances(manager: AppWidgetManager, snapshot: WidgetSnapshot) {
        val ids = manager.getAppWidgetIds(ComponentName(context, SleepWidgetProvider::class.java))
        for (id in ids) {
            val options = manager.getAppWidgetOptions(id)
            manager.updateAppWidget(id, buildSleepViews(snapshot, options))
        }
    }

    private fun updateWeeklyActivityInstances(manager: AppWidgetManager, snapshot: WidgetSnapshot) {
        val ids = manager.getAppWidgetIds(ComponentName(context, WeeklyActivityWidgetProvider::class.java))
        for (id in ids) {
            val options = manager.getAppWidgetOptions(id)
            manager.updateAppWidget(id, buildWeeklyActivityViews(snapshot, options))
        }
    }

    private fun updateVitalsInstances(manager: AppWidgetManager, snapshot: WidgetSnapshot) {
        val ids = manager.getAppWidgetIds(ComponentName(context, VitalsWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val views = buildVitalsViews(snapshot)
        for (id in ids) {
            manager.updateAppWidget(id, views)
        }
    }

    private fun updateBodyBatteryInstances(manager: AppWidgetManager, snapshot: WidgetSnapshot) {
        val ids = manager.getAppWidgetIds(ComponentName(context, BodyBatteryWidgetProvider::class.java))
        for (id in ids) {
            val options = manager.getAppWidgetOptions(id)
            manager.updateAppWidget(id, buildBodyBatteryViews(snapshot, options))
        }
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

    internal fun buildVitalsViews(snapshot: WidgetSnapshot): RemoteViews {
        if (snapshot.keyMissing) return buildMissingKeyViews()
        val v = snapshot.vitals
        val views = RemoteViews(context.packageName, R.layout.widget_vitals)
        views.setOnClickPendingIntent(R.id.widget_root, WidgetRoutes.pendingIntent(context, WidgetTarget.Today))
        bindSyncPill(views, snapshot.syncPill, compact = false)

        if (v.statusSummary != null) {
            views.setTextViewText(R.id.vitals_status_summary, v.statusSummary)
            views.setTextColor(
                R.id.vitals_status_summary,
                if (v.statusWarn) WidgetGraphics.RECOVERY_AMBER else WidgetGraphics.TEXT_MUTED,
            )
            views.setViewVisibility(R.id.vitals_status_summary, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.vitals_status_summary, View.GONE)
        }

        views.setViewVisibility(R.id.vitals_unreadable, if (v.unreadable) View.VISIBLE else View.GONE)

        if (v.allHidden) {
            views.setViewVisibility(R.id.vitals_all_hidden, View.VISIBLE)
            views.setViewVisibility(R.id.vitals_grid, View.GONE)
            return views
        }

        views.setViewVisibility(R.id.vitals_all_hidden, View.GONE)
        views.setViewVisibility(R.id.vitals_grid, View.VISIBLE)
        views.setViewVisibility(R.id.vitals_row_1, if (v.tiles.isNotEmpty()) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.vitals_row_2, if (v.tiles.size > 2) View.VISIBLE else View.GONE)

        VITAL_SLOTS.forEachIndexed { idx, slot ->
            val tile = v.tiles.getOrNull(idx)
            if (tile == null) {
                views.setViewVisibility(slot.tileId, View.GONE)
            } else {
                views.setViewVisibility(slot.tileId, View.VISIBLE)
                views.setTextViewText(slot.labelId, tile.shortLabel)
                val formattedValue =
                    if (tile.valueText == "—" || tile.unit.isEmpty()) {
                        tile.valueText
                    } else {
                        "${tile.valueText} ${tile.unit}"
                    }
                views.setTextViewText(slot.valueId, formattedValue)
                if (tile.deltaText != null) {
                    views.setTextViewText(slot.deltaId, tile.deltaText)
                    val deltaColor =
                        when (tile.better) {
                            true -> WidgetGraphics.BETTER_GREEN
                            false -> WidgetGraphics.RECOVERY_RED
                            null -> WidgetGraphics.TEXT_MUTED
                        }
                    views.setTextColor(slot.deltaId, deltaColor)
                    views.setViewVisibility(slot.deltaId, View.VISIBLE)
                } else {
                    views.setViewVisibility(slot.deltaId, View.GONE)
                }
            }
        }
        return views
    }

    internal fun buildBodyBatteryViews(snapshot: WidgetSnapshot, options: Bundle = Bundle.EMPTY): RemoteViews {
        if (snapshot.keyMissing) return buildMissingKeyViews()
        val bb = snapshot.bodyBattery
        val views = RemoteViews(context.packageName, R.layout.widget_body_battery)
        views.setOnClickPendingIntent(R.id.widget_root, WidgetRoutes.pendingIntent(context, WidgetTarget.Today))
        bindSyncPill(views, snapshot.syncPill, compact = false)

        val accent = WidgetGraphics.bodyBatteryArgb(bb.current)
        views.setTextViewText(R.id.bb_current_value, bb.current?.toString() ?: "—")
        views.setTextColor(R.id.bb_current_value, accent)

        if (bb.unreadable && bb.curve.size >= 2) {
            views.setTextViewText(R.id.bb_meta_line, context.getString(R.string.widget_unreadable_day))
            views.setTextColor(R.id.bb_meta_line, WidgetGraphics.RECOVERY_AMBER)
        } else {
            views.setTextViewText(R.id.bb_meta_line, bb.metaLine)
            views.setTextColor(R.id.bb_meta_line, WidgetGraphics.TEXT_BODY)
        }

        val axisIds = listOf(R.id.bb_axis_1, R.id.bb_axis_2, R.id.bb_axis_3, R.id.bb_axis_4)
        axisIds.forEachIndexed { i, id ->
            views.setTextViewText(id, bb.axisLabels.getOrElse(i) { "" })
        }

        if (bb.curve.size >= 2) {
            val density = context.resources.displayMetrics.density
            val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250).let { if (it <= 0) 250 else it }
            val wPx = WidgetGraphics.dpToClampedPx((widthDp - 36).coerceAtLeast(120).toFloat(), density)
            val hPx = WidgetGraphics.dpToClampedPx(44f, density)
            views.setImageViewBitmap(
                R.id.bb_sparkline,
                WidgetGraphics.renderBodyBatterySparkline(bb.curve, accent, snapshot.today, snapshot.zone, wPx, hPx),
            )
            views.setContentDescription(R.id.bb_sparkline, "Body Battery today, now ${bb.current ?: "unavailable"}")
            views.setViewVisibility(R.id.bb_sparkline, View.VISIBLE)
            views.setViewVisibility(R.id.bb_empty_note, View.GONE)
        } else {
            views.setViewVisibility(R.id.bb_sparkline, View.GONE)
            views.setViewVisibility(R.id.bb_empty_note, View.VISIBLE)
            if (bb.unreadable) {
                views.setTextViewText(R.id.bb_empty_note, context.getString(R.string.widget_unreadable_day))
                views.setTextColor(R.id.bb_empty_note, WidgetGraphics.RECOVERY_AMBER)
            } else {
                views.setTextViewText(R.id.bb_empty_note, context.getString(R.string.widget_bb_empty_curve))
                views.setTextColor(R.id.bb_empty_note, WidgetGraphics.TEXT_MUTED)
            }
        }
        return views
    }

    internal fun buildSleepViews(snapshot: WidgetSnapshot, options: Bundle = Bundle.EMPTY): RemoteViews {
        if (snapshot.keyMissing) return buildMissingKeyViews()
        val s = snapshot.sleep
        val views = RemoteViews(context.packageName, R.layout.widget_sleep)
        views.setOnClickPendingIntent(R.id.widget_root, WidgetRoutes.pendingIntent(context, WidgetTarget.SleepToday))
        bindSyncPill(views, snapshot.syncPill, compact = false)

        views.setTextViewText(R.id.sleep_asleep_value, s.asleepText)
        if (s.needText != null) {
            views.setTextViewText(R.id.sleep_need_value, s.needText)
            views.setViewVisibility(R.id.sleep_need_value, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.sleep_need_value, View.GONE)
        }
        if (s.sleepPctText != null) {
            views.setTextViewText(R.id.sleep_pct_badge, s.sleepPctText)
            views.setViewVisibility(R.id.sleep_pct_badge, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.sleep_pct_badge, View.GONE)
        }
        if (s.garminScoreText != null) {
            views.setTextViewText(R.id.sleep_garmin_score, s.garminScoreText)
            views.setViewVisibility(R.id.sleep_garmin_score, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.sleep_garmin_score, View.GONE)
        }

        val density = context.resources.displayMetrics.density
        val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250).let { if (it <= 0) 250 else it }
        val barW = WidgetGraphics.dpToClampedPx((widthDp - 28).coerceAtLeast(120).toFloat(), density)
        val barH = WidgetGraphics.dpToClampedPx(10f, density)
        views.setImageViewBitmap(
            R.id.sleep_stage_bar,
            WidgetGraphics.renderSleepStageBar(s.deepSec, s.remSec, s.lightSec, s.awakeSec, barW, barH),
        )
        views.setContentDescription(R.id.sleep_stage_bar, "Last night sleep ${s.asleepText}")

        if (s.unreadable) {
            views.setTextViewText(R.id.sleep_tonight_need, context.getString(R.string.widget_unreadable_day))
            views.setTextColor(R.id.sleep_tonight_need, WidgetGraphics.RECOVERY_AMBER)
        } else {
            views.setTextViewText(R.id.sleep_tonight_need, s.tonightNeedText)
            views.setTextColor(R.id.sleep_tonight_need, WidgetGraphics.TEXT_BODY)
        }
        if (s.bedtimeText != null) {
            views.setTextViewText(R.id.sleep_bedtime, s.bedtimeText)
            views.setViewVisibility(R.id.sleep_bedtime, View.VISIBLE)
        } else {
            views.setTextViewText(R.id.sleep_bedtime, "")
            views.setViewVisibility(R.id.sleep_bedtime, View.GONE)
        }
        return views
    }

    internal fun buildWeeklyActivityViews(snapshot: WidgetSnapshot, options: Bundle = Bundle.EMPTY): RemoteViews {
        if (snapshot.keyMissing) return buildMissingKeyViews()
        val w = snapshot.weekly
        val views = RemoteViews(context.packageName, R.layout.widget_weekly_activity)
        val activitiesIntent = WidgetRoutes.pendingIntent(context, WidgetTarget.Activities)
        views.setOnClickPendingIntent(R.id.widget_root, activitiesIntent)
        views.setOnClickPendingIntent(R.id.weekly_strip, activitiesIntent)
        bindSyncPill(views, snapshot.syncPill, compact = false)

        views.setTextViewText(R.id.weekly_header_title, w.headerTitle)
        views.setTextViewText(R.id.weekly_totals_text, w.weekTotalsText)

        val latest = w.latest
        if (latest != null) {
            views.setViewVisibility(R.id.weekly_empty_text, View.GONE)
            views.setViewVisibility(R.id.weekly_sport_icon, View.VISIBLE)
            views.setViewVisibility(R.id.weekly_activity_text_col, View.VISIBLE)
            views.setViewVisibility(R.id.weekly_activity_strain, View.VISIBLE)
            views.setImageViewResource(R.id.weekly_sport_icon, sportIcon(latest.family))
            views.setInt(R.id.weekly_sport_icon, "setColorFilter", SPORT_ICON_TINT_ARGB)
            views.setTextViewText(R.id.weekly_activity_title, latest.title)
            views.setTextViewText(R.id.weekly_activity_meta, latest.metaLine)
            views.setTextViewText(R.id.weekly_activity_strain, latest.strainText)
            views.setOnClickPendingIntent(
                R.id.weekly_workout_card,
                WidgetRoutes.pendingIntent(context, WidgetTarget.Activity(latest.id)),
            )
        } else {
            views.setViewVisibility(R.id.weekly_empty_text, View.VISIBLE)
            views.setViewVisibility(R.id.weekly_sport_icon, View.GONE)
            views.setViewVisibility(R.id.weekly_activity_text_col, View.GONE)
            views.setViewVisibility(R.id.weekly_activity_strain, View.GONE)
            views.setOnClickPendingIntent(R.id.weekly_workout_card, activitiesIntent)
        }

        val density = context.resources.displayMetrics.density
        val tf = WidgetGraphics.loadCondensedBold(context)
        val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250).let { if (it <= 0) 250 else it }
        val stripW = WidgetGraphics.dpToClampedPx((widthDp - 28).coerceAtLeast(140).toFloat(), density)
        val stripH = WidgetGraphics.dpToClampedPx(26f, density)
        views.setImageViewBitmap(
            R.id.weekly_strip,
            WidgetGraphics.renderWeekStripBitmap(w.days, stripW, stripH, tf),
        )
        views.setContentDescription(R.id.weekly_strip, "${w.headerTitle}, ${w.weekTotalsText}")
        return views
    }

    private data class VitalSlotIds(val tileId: Int, val labelId: Int, val valueId: Int, val deltaId: Int)

    companion object {
        private const val KEY_ERR_TEXT_ARGB: Int = 0xFFFECACA.toInt()
        private const val SPORT_ICON_TINT_ARGB: Int = 0xFF93C5FD.toInt()
        private const val TAG = "WidgetUpdater"

        private val VITAL_SLOTS =
            listOf(
                VitalSlotIds(R.id.vital_tile_1, R.id.vital_label_1, R.id.vital_value_1, R.id.vital_delta_1),
                VitalSlotIds(R.id.vital_tile_2, R.id.vital_label_2, R.id.vital_value_2, R.id.vital_delta_2),
                VitalSlotIds(R.id.vital_tile_3, R.id.vital_label_3, R.id.vital_value_3, R.id.vital_delta_3),
                VitalSlotIds(R.id.vital_tile_4, R.id.vital_label_4, R.id.vital_value_4, R.id.vital_delta_4),
            )

        private val PROVIDERS: List<Class<out AppWidgetProvider>> =
            listOf(
                ReadinessWidgetProvider::class.java,
                VitalsWidgetProvider::class.java,
                BodyBatteryWidgetProvider::class.java,
                SleepWidgetProvider::class.java,
                WeeklyActivityWidgetProvider::class.java,
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
