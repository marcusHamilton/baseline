package dev.baseline.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import dev.baseline.R
import dev.baseline.scores.RecoveryZone
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.min
import kotlin.math.roundToInt

object WidgetGraphics {
    const val BG: Int = 0xFF0E1013.toInt()
    const val SURFACE_2: Int = 0xFF262A31.toInt()
    const val TEXT_PRIMARY: Int = 0xFFF2F4F7.toInt()
    const val TEXT_MUTED: Int = 0xFF9AA3AE.toInt()
    const val TEXT_BODY: Int = 0xFFC9CFD6.toInt()
    const val RECOVERY_GREEN: Int = 0xFF34D399.toInt()
    const val RECOVERY_AMBER: Int = 0xFFFBBF24.toInt()
    const val RECOVERY_RED: Int = 0xFFF87171.toInt()
    const val STRAIN_BLUE: Int = 0xFF60A5FA.toInt()
    const val SLEEP_PURPLE: Int = 0xFFA78BFA.toInt()
    const val SLEEP_PURPLE_LIGHT: Int = 0xFFC4B5FD.toInt()
    const val BETTER_GREEN: Int = 0xFF6EE7B7.toInt()
    const val STAGE_DEEP: Int = 0xFF5B48C2.toInt()
    const val STAGE_REM: Int = 0xFF7DD3FC.toInt()
    const val STAGE_LIGHT: Int = 0xFFA78BFA.toInt()
    const val STAGE_AWAKE: Int = 0xFFFBBF24.toInt()
    const val MAX_BITMAP_PX: Int = 900

    private const val BB_HIGH_MID: Int = 0xFFA3D94C.toInt()

    fun recoveryArgb(zone: RecoveryZone?): Int = when (zone) {
        RecoveryZone.GREEN -> RECOVERY_GREEN
        RecoveryZone.AMBER -> RECOVERY_AMBER
        RecoveryZone.RED -> RECOVERY_RED
        null -> TEXT_MUTED
    }

    fun bodyBatteryArgb(level: Int?): Int = when {
        level == null -> TEXT_MUTED
        level >= 76 -> RECOVERY_GREEN
        level >= 51 -> BB_HIGH_MID
        level >= 26 -> RECOVERY_AMBER
        else -> RECOVERY_RED
    }

    fun dpToClampedPx(dp: Float, density: Float, maxPx: Int = MAX_BITMAP_PX): Int {
        val cap = maxPx.coerceIn(1, MAX_BITMAP_PX)
        if (!dp.isFinite() || !density.isFinite() || density <= 0f) return 1
        return (dp * density).roundToInt().coerceIn(1, cap)
    }

    fun loadCondensedBold(context: Context): Typeface =
        runCatching { context.resources.getFont(R.font.barlow_condensed_bold) }.getOrDefault(Typeface.DEFAULT_BOLD)

    @Suppress("LongParameterList")
    fun renderDialBitmap(
        fraction: Float?,
        value: String,
        suffix: String,
        note: String?,
        accentArgb: Int,
        sizePx: Int,
        strokePx: Float,
        typeface: Typeface = Typeface.DEFAULT_BOLD,
    ): Bitmap {
        val size = sizePx.coerceIn(1, MAX_BITMAP_PX)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        if (size < 4) return bmp
        val canvas = Canvas(bmp)
        val stroke = (if (strokePx.isFinite() && strokePx > 0f) strokePx else 1f).coerceIn(1f, size / 3f)
        val inset = stroke / 2f + 1f
        val oval = RectF(inset, inset, size - inset, size - inset)

        val ringPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = stroke
                strokeCap = Paint.Cap.ROUND
                color = SURFACE_2
            }
        canvas.drawArc(oval, 0f, 360f, false, ringPaint)

        val safeFrac = fraction?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        if (safeFrac > 0f) {
            ringPaint.color = accentArgb
            canvas.drawArc(oval, -90f, safeFrac * 360f, false, ringPaint)
        }

        val valuePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.typeface = typeface
                color = TEXT_PRIMARY
                textSize = size * if (note != null) 0.30f else 0.34f
            }
        val suffixPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.typeface = typeface
                color = TEXT_PRIMARY
                textSize = valuePaint.textSize * 0.52f
            }
        val valueW = valuePaint.measureText(value)
        val suffixW = if (suffix.isNotEmpty()) suffixPaint.measureText(suffix) else 0f
        val totalW = valueW + suffixW
        val centerX = size / 2f
        val fm = valuePaint.fontMetrics
        val textCenterOffset = -(fm.ascent + fm.descent) / 2f
        val baselineY = (size / 2f) + textCenterOffset - if (note != null) size * 0.07f else 0f
        val startX = centerX - totalW / 2f
        canvas.drawText(value, startX, baselineY, valuePaint)
        if (suffix.isNotEmpty()) {
            canvas.drawText(suffix, startX + valueW, baselineY, suffixPaint)
        }

        if (!note.isNullOrEmpty()) {
            val notePaint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = accentArgb
                    textAlign = Paint.Align.CENTER
                    textSize = (size * 0.13f).coerceAtLeast(8f)
                    isFakeBoldText = true
                }
            canvas.drawText(note, centerX, baselineY + size * 0.19f, notePaint)
        }
        return bmp
    }

    fun renderHorizontalFillBar(fraction: Float?, accentArgb: Int, widthPx: Int, heightPx: Int): Bitmap {
        val w = widthPx.coerceIn(1, MAX_BITMAP_PX)
        val h = heightPx.coerceIn(1, MAX_BITMAP_PX)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val radius = h / 2f
        val outer = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SURFACE_2 }
        canvas.drawRoundRect(outer, radius, radius, paint)

        val safeFrac = fraction?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        if (safeFrac > 0f) {
            val clipPath = Path().apply { addRoundRect(outer, radius, radius, Path.Direction.CW) }
            canvas.save()
            canvas.clipPath(clipPath)
            paint.color = accentArgb
            val fillRight = (w * safeFrac).coerceAtLeast(1f)
            canvas.drawRoundRect(RectF(0f, 0f, fillRight, h.toFloat()), radius, radius, paint)
            canvas.restore()
        }
        return bmp
    }

    @Suppress("LongParameterList")
    fun renderSleepStageBar(deepSec: Int?, remSec: Int?, lightSec: Int?, awakeSec: Int?, widthPx: Int, heightPx: Int): Bitmap {
        val w = widthPx.coerceIn(1, MAX_BITMAP_PX)
        val h = heightPx.coerceIn(1, MAX_BITMAP_PX)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val radius = h / 2f
        val outer = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SURFACE_2 }
        canvas.drawRoundRect(outer, radius, radius, trackPaint)

        val segments =
            listOf(
                (deepSec?.coerceAtLeast(0) ?: 0) to STAGE_DEEP,
                (remSec?.coerceAtLeast(0) ?: 0) to STAGE_REM,
                (lightSec?.coerceAtLeast(0) ?: 0) to STAGE_LIGHT,
                (awakeSec?.coerceAtLeast(0) ?: 0) to STAGE_AWAKE,
            ).filter { it.first > 0 }
        val total = segments.sumOf { it.first }
        if (total <= 0) return bmp

        val clipPath = Path().apply { addRoundRect(outer, radius, radius, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(clipPath)
        val segPaint = Paint()
        var cursor = 0f
        segments.forEachIndexed { index, (sec, color) ->
            val segW = if (index == segments.lastIndex) w - cursor else (sec.toFloat() / total.toFloat()) * w
            segPaint.color = color
            canvas.drawRect(cursor, 0f, cursor + segW, h.toFloat(), segPaint)
            cursor += segW
        }
        canvas.restore()
        return bmp
    }

    @Suppress("LongParameterList")
    fun renderBodyBatterySparkline(curve: List<Pair<Instant, Int>>, accentArgb: Int, today: LocalDate, zone: ZoneId, widthPx: Int, heightPx: Int): Bitmap {
        val w = widthPx.coerceIn(1, MAX_BITMAP_PX)
        val h = heightPx.coerceIn(1, MAX_BITMAP_PX)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (w < 4 || h < 4) return bmp
        val canvas = Canvas(bmp)

        val dayStart = today.atStartOfDay(zone).toInstant().epochSecond
        val dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant().epochSecond
        val spanSec = (dayEnd - dayStart).coerceAtLeast(1L).toFloat()

        val padX = min(w * 0.02f, 6f)
        val padY = min(h * 0.14f, 8f)
        val plotW = (w - 2f * padX).coerceAtLeast(1f)
        val plotH = (h - 2f * padY).coerceAtLeast(1f)

        val inDay =
            curve
                .filter { (t, _) -> t.epochSecond in dayStart..dayEnd }
                .sortedBy { it.first }
                .map { (t, v) ->
                    val x = padX + ((t.epochSecond - dayStart).toFloat() / spanSec) * plotW
                    val y = padY + (1f - (v.coerceIn(0, 100) / 100f)) * plotH
                    x to y
                }

        val guidePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = (h * 0.025f).coerceIn(1f, 3f)
                color = SURFACE_2
                pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
            }

        if (inDay.size < 2) {
            val midY = h / 2f
            canvas.drawLine(padX, midY, w - padX, midY, guidePaint)
            if (inDay.size == 1) {
                val (px, py) = inDay.first()
                val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentArgb }
                canvas.drawCircle(px, py, (h * 0.07f).coerceAtLeast(3.5f), dotPaint)
            }
            return bmp
        }

        val linePath = Path()
        val fillPath = Path()
        val (firstX, firstY) = inDay.first()
        val (lastX, lastY) = inDay.last()
        linePath.moveTo(firstX, firstY)
        fillPath.moveTo(firstX, h - padY)
        fillPath.lineTo(firstX, firstY)
        for (i in 1 until inDay.size) {
            val (x, y) = inDay[i]
            linePath.lineTo(x, y)
            fillPath.lineTo(x, y)
        }
        fillPath.lineTo(lastX, h - padY)
        fillPath.close()

        val rgbMask = accentArgb and 0x00FFFFFF
        val fillPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                shader =
                    LinearGradient(
                        0f,
                        padY,
                        0f,
                        h - padY,
                        rgbMask or 0x59000000,
                        rgbMask,
                        Shader.TileMode.CLAMP,
                    )
            }
        canvas.drawPath(fillPath, fillPaint)

        if (lastX < w - padX - 4f) {
            canvas.drawLine(lastX, lastY, w - padX, lastY, guidePaint)
        }

        val strokePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = (h * 0.04f).coerceIn(2f, 5f)
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                color = accentArgb
            }
        canvas.drawPath(linePath, strokePaint)

        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentArgb }
        canvas.drawCircle(lastX, lastY, (h * 0.07f).coerceAtLeast(3.5f), dotPaint)
        return bmp
    }

    fun renderWeekStripBitmap(days: List<WeekDayCell>, widthPx: Int, heightPx: Int, typeface: Typeface = Typeface.DEFAULT_BOLD): Bitmap {
        val w = widthPx.coerceIn(1, MAX_BITMAP_PX)
        val h = heightPx.coerceIn(1, MAX_BITMAP_PX)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (days.isEmpty() || w < days.size * 2 || h < 6) return bmp
        val canvas = Canvas(bmp)

        val count = days.size
        val gap = (w * 0.016f).coerceIn(1f, (w.toFloat() / (count * 3)).coerceAtLeast(1f))
        val cellW = (w - gap * (count - 1)) / count
        val cornerR = min(cellW, h.toFloat()) * 0.22f

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BG }
        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = STRAIN_BLUE }
        val textPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.typeface = typeface
                color = TEXT_BODY
                textAlign = Paint.Align.CENTER
                textSize = min(cellW * 0.42f, h * 0.38f).coerceAtLeast(7f)
            }

        days.forEachIndexed { idx, cell ->
            val left = idx * (cellW + gap)
            val right = left + cellW
            canvas.drawRoundRect(RectF(left, 0f, right, h.toFloat()), cornerR, cornerR, bgPaint)

            val cx = (left + right) / 2f
            val fm = textPaint.fontMetrics
            val textY = h * 0.44f - (fm.ascent + fm.descent) / 2f
            canvas.drawText(cell.letter, cx, textY, textPaint)

            if (cell.hasActivity) {
                val dotR = min(cellW, h.toFloat()) * 0.07f
                canvas.drawCircle(right - cornerR * 0.85f, cornerR * 0.85f, dotR.coerceAtLeast(1.5f), dotPaint)
            }

            val barH = (h * 0.13f).coerceAtLeast(2f)
            val barInset = cellW * 0.18f
            barPaint.color = cell.recoveryZone?.let(::recoveryArgb) ?: SURFACE_2
            val barTop = h - barH - (h * 0.10f)
            canvas.drawRoundRect(
                RectF(left + barInset, barTop, right - barInset, barTop + barH),
                barH / 2f,
                barH / 2f,
                barPaint,
            )
        }
        return bmp
    }
}
