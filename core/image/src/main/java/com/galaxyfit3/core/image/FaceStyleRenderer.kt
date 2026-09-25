package com.galaxyfit3.core.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import com.galaxyfit3.core.format.WatchFaceFormat
import com.galaxyfit3.core.format.model.FontBinding
import com.galaxyfit3.core.format.model.Raster
import com.galaxyfit3.core.format.model.StyleEntry
import com.galaxyfit3.core.format.model.WatchFaceContainer
import com.galaxyfit3.core.format.model.WidgetRecord
import com.galaxyfit3.core.format.model.WidgetType
import kotlin.math.abs
import java.util.concurrent.ConcurrentHashMap

/**
 * Live data shown in the editor preview, sampled locally so the user can see
 * how value widgets will render on the watch.
 */
data class PreviewStats(
    val hour: Int = java.time.LocalTime.now().hour,
    val minute: Int = java.time.LocalTime.now().minute,
    val second: Int = java.time.LocalTime.now().second,
    val dayOfMonth: Int = java.time.LocalDate.now().dayOfMonth,
    val month: Int = java.time.LocalDate.now().monthValue,
    val year: Int = java.time.LocalDate.now().year,
    val steps: String = "3457",
    val heartRate: String = "89",
    val calories: String = "350",
    val activeMinutes: String = "53",
    val temperature: String = "25",
    val battery: String = "82",
    val amPm: String = if (java.time.LocalTime.now().hour < 12) "a.m." else "p.m.",
    /** 1..7, Monday = 1. */
    val weekday: Int = java.time.LocalDate.now().dayOfWeek.value
)

/**
 * Firmware fonts and glyph strings pulled from the container (font_N.bin bindings and
 * font_en.bin groups). Used to size and place value/composite text exactly like the watch
 * firmware does, mirroring the fitface reference parser.
 */
data class TextResources(
    /** Font bindings in index order (font_0.bin, font_1.bin, ...). */
    val bindings: List<FontBinding> = emptyList(),
    /** 'en' glyph strings, in group-index order (weekday names, units, separators). */
    val glyphs: List<String> = emptyList()
)

/** Draws a style entry as a preview bitmap using a sampled live data set. */
object FaceStyleRenderer {

    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.LTGRAY
    }

    /**
     * @param cache optional decoded-raster cache. Pass one when rendering the same style
     *              repeatedly (the editor does, on every edit); leave it null for a
     *              one-shot render.
     * @param vendorPreview optional vendor preview raster (178x280 from preview.bin),
     *                      scaled to panel size and used as base when no panel-sized
     *                      background raster exists.
     */
    fun render(
        style: StyleEntry,
        stats: PreviewStats = PreviewStats(),
        cache: RasterBitmapCache? = null,
        vendorPreview: Bitmap? = null,
        /** Per-widget text colour overrides keyed by widget index within the style.
         *  Overrides the firmware colour for value (Pair) and composite (Comp) text. */
        textTints: Map<Int, Int> = emptyMap(),
        /** Firmware font bindings + glyph groups (from the containing container). */
        text: TextResources = TextResources()
    ): Bitmap {
        val width = WatchFaceFormat.PANEL_WIDTH
        val height = WatchFaceFormat.PANEL_HEIGHT
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)

        // Draw the base layer: panel-sized background raster if present, else vendor preview.
        val hasBackground = style.rasters.any { it.width == width && it.height == height }
        if (hasBackground) {
            style.rasters.firstOrNull { r -> r.width == width && r.height == height }
                ?.let { canvas.drawBitmap(decode(it, cache), 0f, 0f, null) }
        } else if (vendorPreview != null) {
            // Scale the 178x280 vendor preview to fill the 256x402 panel.
            val scaled = Bitmap.createScaledBitmap(vendorPreview, width, height, true)
            canvas.drawBitmap(scaled, 0f, 0f, null)
        }

        val offsetToIndex = imageOffsets(style)

        style.widgets.forEachIndexed { i, w ->
            drawWidget(canvas, w, style, offsetToIndex, stats, cache, textTints[i], text)
        }
        return bitmap
    }

    private fun decode(raster: Raster, cache: RasterBitmapCache?): Bitmap =
        cache?.bitmap(raster) ?: Rgb565.toBitmap(raster)

    internal fun imageOffsets(style: StyleEntry): Map<Int, Int> {
        val map = HashMap<Int, Int>()
        var running = 0
        style.rasters.forEachIndexed { i, r ->
            map[running] = i
            running += r.encodedSize()
        }
        return map
    }

    private fun drawWidget(
        canvas: Canvas,
        w: WidgetRecord,
        style: StyleEntry,
        offsetToIndex: Map<Int, Int>,
        stats: PreviewStats,
        cache: RasterBitmapCache?,
        tint: Int?,
        text: TextResources
    ) {
        when (w.type) {
            WidgetType.STATIC -> {
                val idx = offsetToIndex[w.wordA.toInt()] ?: return
                drawRaster(canvas, style.rasters[idx], w.x, w.y, cache)
            }
            WidgetType.SPRITE -> {
                val frame = currentFrame(w, stats)
                val off = w.words.getOrNull(frame) ?: return
                val idx = offsetToIndex[off] ?: return
                drawRaster(canvas, style.rasters[idx], w.x, w.y, cache)
            }
            WidgetType.HAND -> drawHand(canvas, w, style.rasters, offsetToIndex, stats, cache)
            WidgetType.PAIR -> drawPair(canvas, w, stats, tint, text)
            WidgetType.BADGE -> drawBadge(canvas, w)
            WidgetType.COMP -> drawComp(canvas, w, style, stats, tint, text)
            WidgetType.ARC -> drawRasterBacked(canvas, w, style, offsetToIndex, rasterWord = 4, cache)
            WidgetType.LINE_BAR -> drawRasterBacked(canvas, w, style, offsetToIndex, rasterWord = 2, cache)
        }
    }

    private fun drawRaster(canvas: Canvas, raster: Raster, x: Int, y: Int, cache: RasterBitmapCache?) {
        canvas.drawBitmap(decode(raster, cache), x.toFloat(), y.toFloat(), null)
    }

    /** Arc (16) and LineBar (17) blit their own raster at the record's x/y — Arc's fill
     *  ring in words[4], LineBar's strip in words[2]. Both went unrendered before, so
     *  progress rings and bars silently vanished from every preview. */
    private fun drawRasterBacked(
        canvas: Canvas,
        w: WidgetRecord,
        style: StyleEntry,
        offsets: Map<Int, Int>,
        rasterWord: Int,
        cache: RasterBitmapCache?
    ) {
        val off = w.words.getOrNull(rasterWord) ?: return
        val idx = offsets[off] ?: return
        val raster = style.rasters.getOrNull(idx) ?: return
        drawRaster(canvas, raster, w.x, w.y, cache)
    }

    /** For a sprite, choose the frame that makes the preview look sensible. */
    private fun currentFrame(w: WidgetRecord, stats: PreviewStats): Int {
        val count = w.words.size
        return when {
            count == 2 -> if (stats.second % 2 == 0) 0 else 1 // colon blink
            // Digit sprites (hour tens/ones, minute tens/ones) carry one raster per
            // digit value; seq tells which position the widget renders.
            w.sequenceId == WatchFaceFormat.SEQ_HOUR_TENS -> stats.hour / 10
            w.sequenceId == WatchFaceFormat.SEQ_HOUR_ONES -> stats.hour % 10
            w.sequenceId == WatchFaceFormat.SEQ_MINUTE_TENS -> stats.minute / 10
            w.sequenceId == WatchFaceFormat.SEQ_MINUTE_ONES -> stats.minute % 10
            count >= 10 -> stats.minute % 10 // unrecognized digit sprite: ones digit
            else -> 0
        }.coerceIn(0, (count - 1).coerceAtLeast(0))
    }

    private fun drawHand(
        canvas: Canvas,
        w: WidgetRecord,
        rasters: List<Raster>,
        offsets: Map<Int, Int>,
        stats: PreviewStats,
        cache: RasterBitmapCache?
    ) {
        val off = w.words.getOrNull(1) ?: return
        val idx = offsets[off] ?: return
        val bmp = decode(rasters[idx], cache)
        val pivot = w.wordA.toInt()
        val pivotX = pivot and 0xFFFF
        val pivotY = (pivot ushr 16) and 0xFFFF
        val cx = w.x + pivotX
        val cy = w.y + pivotY

        val angleDeg = when {
            w.sequenceId == WatchFaceFormat.SEQ_HOUR_HAND ->
                (stats.hour % 12) * 30f + stats.minute * 0.5f
            w.sequenceId == WatchFaceFormat.SEQ_MINUTE_HAND ->
                stats.minute * 6f + stats.second * 0.1f
            w.sequenceId == WatchFaceFormat.SEQ_SECOND_HAND -> stats.second * 6f
            else -> 0f
        }
        val matrix = Matrix()
        matrix.postTranslate(-pivotX.toFloat(), -pivotY.toFloat())
        matrix.postRotate(angleDeg)
        matrix.postTranslate(cx.toFloat(), cy.toFloat())
        canvas.drawBitmap(bmp, matrix, null)
    }

    /**
     * Value widget (Pair, type 5), placed like the firmware does (ported from the fitface
     * reference parser): words[0] BGRA colour, font binding gives the point size, words[1]
     * carries align (0=left, 1=center, 2=right) and layout (0=suffix inline, 1=plain,
     * 2=suffix stacked below), words[2] is an optional suffix glyph. The anchor x honours
     * negative x (right-anchored) and centres on the record's width; y is the vertical mid
     * of the text cell, not its baseline — the watch centres text by nominal height.
     */
    private fun drawPair(canvas: Canvas, w: WidgetRecord, stats: PreviewStats, tint: Int?, r: TextResources) {
        val words = w.words
        val color = tint ?: words.getOrNull(0)?.let { bgraAllowBlack(it) } ?: Color.WHITE
        val fontIdx = words.getOrNull(1)?.and(0xFF) ?: 0
        val pt = fontPt(fontIdx, w.wordA.toInt(), r)
        val family = r.bindings.getOrNull(fontIdx)?.family ?: 0
        val cfg = words.getOrNull(1) ?: 0
        val align = (cfg ushr 8) and 0xFF
        val layout = (cfg ushr 16) and 0xFF
        val ax = pairAnchorX(w, align)
        val cellY = displayCoordinate(w.y, w.hOrY2, WatchFaceFormat.PANEL_HEIGHT)
        val ay = cellY + w.hOrY2 / 2
        val suffixIdx = words.getOrNull(2)?.and(0xFF) ?: 0xFF
        val suffix = glyph(suffixIdx, r)

        // seq_id 0 + suffix = static label ("steps", "BPM", ...) rendered on its own.
        if (w.sequenceId == 0 && suffix.isNotEmpty()) {
            drawTextF(canvas, suffix, ax, ay, pt, color, align = align, family = family)
            return
        }
        val value = pairValue(w, stats)
        if (value == null) return

        val useGlyphSuffix = suffix.isNotEmpty() && isUnitSuffix(suffix) && layout != 1
        when {
            layout == 1 || !useGlyphSuffix ->
                drawTextF(canvas, value, ax, ay, pt, color, align = align, family = family)
            layout == 2 -> {
                // Stacked rows: value on top, suffix below.
                val pt2 = maxOf(pt - 4, 8)
                drawTextF(canvas, value, ax, cellY + pt / 2, pt, color, align = align, family = family)
                drawTextF(canvas, suffix.trim(), ax, cellY + pt + pt2 / 2, pt2, color, align = align, family = family)
            }
            else -> {
                // Inline: value then a smaller suffix ("3457 steps").
                val suf = suffix.trim()
                if (suf.isEmpty()) {
                    drawTextF(canvas, value, ax, ay, pt, color, align = align, family = family)
                } else {
                    val pt2 = maxOf(pt - 3, 8)
                    val vp = paintFor(pt, family)
                    val sp = paintFor(pt2, family)
                    val gap =
                        if (suf.startsWith("°") || suf.startsWith("%") || suf.startsWith("/") || suf.startsWith(":")) 0 else 2
                    val total = vp.measureText(value) + gap + sp.measureText(suf)
                    val sx = when (align) {
                        1 -> ax - total / 2f
                        2 -> ax - total
                        else -> ax.toFloat()
                    }
                    val vw = drawTextF(canvas, value, sx.toInt(), ay, pt, color, align = 0, family = family)
                    drawTextF(canvas, suf, (sx + vw + gap).toInt(), ay, pt2, color, align = 0, family = family)
                }
            }
        }
    }

    private fun pairValue(w: WidgetRecord, stats: PreviewStats): String? = when {
        w.sequenceId == WatchFaceFormat.SEQ_HEART_RATE -> stats.heartRate
        w.sequenceId == WatchFaceFormat.SEQ_STEPS -> stats.steps
        w.sequenceId == WatchFaceFormat.SEQ_CALORIES -> stats.calories
        w.sequenceId == WatchFaceFormat.SEQ_ACTIVE_TIME -> stats.activeMinutes
        w.sequenceId == WatchFaceFormat.SEQ_TEMPERATURE -> stats.temperature
        (w.words.getOrNull(2)?.and(0xFFFF) ?: 0) == 3 -> stats.amPm
        // Mirror the web renderer: unknown data bindings still render a "0000"
        // placeholder, so value widgets never vanish from the editor preview. The
        // watch feeds these seqs real data, so they must render here too.
        else -> "0000"
    }

    private fun drawBadge(canvas: Canvas, w: WidgetRecord) {
        // Badge stores two endpoints: (x, y) and (wOrX2, hOrY2). Negative values
        // anchor from the right/bottom edge of the panel (same as Pair anchor mode 3).
        val thickness = w.words.getOrNull(3)?.and(0xFF)?.takeIf { it >= 2 } ?: 8
        dividerPaint.strokeWidth = thickness.toFloat()
        val x1 = displayCoordinate(w.x, 0, WatchFaceFormat.PANEL_WIDTH)
        val y1 = displayCoordinate(w.y, 0, WatchFaceFormat.PANEL_HEIGHT)
        val x2 = displayCoordinate(w.wOrX2, 0, WatchFaceFormat.PANEL_WIDTH)
        val y2 = displayCoordinate(w.hOrY2, 0, WatchFaceFormat.PANEL_HEIGHT)
        canvas.drawLine(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), dividerPaint)
    }

    /**
     * Composite widget (type 13), rendered the way the firmware does (ported from the
     * fitface reference parser). The record's words hold repeating triplets — each a data
     * source encoded by COMP_TYPE, with optional prefix/suffix glyphs and spacing. Colour
     * comes from an explicit BGRA word, else is inherited from a Pair sharing this widget's
     * binding index, else white; alignment is inherited from a nearby Pair when every sub
     * is a date field. y is the vertical mid of the text cell.
     */
    private fun drawComp(canvas: Canvas, w: WidgetRecord, style: StyleEntry, stats: PreviewStats, tint: Int?, r: TextResources) {
        // The cell right/bottom-anchors when x/y are negative (same convention as Pair
        // modes and Badge endpoints). Negatives must render, not bail — anchored
        // composites were invisible before.
        val boxX = displayCoordinate(w.x, w.wOrX2, WatchFaceFormat.PANEL_WIDTH)
        val boxY = displayCoordinate(w.y, w.hOrY2, WatchFaceFormat.PANEL_HEIGHT)
        val subs = parseCompSubs(w.words)
        if (subs.isEmpty()) return

        var color = tint
        if (color == null) {
            for (p in w.words) {
                if (p == 0xFFFFFFFF.toInt() || (p ushr 16) == 0xFFFF) continue
                color = bgraAllowBlack(p)
                if (color != null) break
            }
        }
        if (color == null) {
            val bi = compBindingIndex(w)
            if (bi < r.bindings.size) {
                for (pw in style.widgets) {
                    if (pw.type != WidgetType.PAIR || pw.sequenceId == 0) continue
                    if ((pw.words.getOrNull(1)?.and(0xFF) ?: 0) == bi) {
                        color = pw.words.getOrNull(0)?.let { bgraAllowBlack(it) }
                        if (color != null) break
                    }
                }
            }
        }
        if (color == null) color = Color.WHITE
        val family = r.bindings.getOrNull(compBindingIndex(w))?.family ?: 0

        data class Seg(val sub: CompSub, val key: String, val text: String)
        val segs = mutableListOf<Seg>()
        for (sub in subs) {
            val key = compKey(sub, r, subs)
            if (key == "none") continue
            val text = compText(key, stats, r)
            if (text.isEmpty()) continue
            segs.add(Seg(sub, key, text))
        }
        if (segs.isEmpty()) return

        // Battery cells render top-left instead of center, matching how the
        // watch lays the battery out.
        val batteryCell = segs.all { it.key == "battery" }

        val pt = compFontPt(w, r)
        val paint = paintFor(pt, family)

        // Interleave prefix/suffix glyphs and segment spacing into one drawable line.
        val pieces = mutableListOf<Pair<Float, String>>() // (spacing before, text)
        var totalW = 0f
        var prevSp = 0
        for (i in segs.indices) {
            val sub = segs[i].sub
            val prefix = if (sub.prefixIdx in 1 until r.glyphs.size) glyph(sub.prefixIdx, r) else ""
            var suf = if (sub.glyphIdx in 0 until r.glyphs.size && sub.glyphIdx != 0xFFFF) glyph(sub.glyphIdx, r) else ""
            if (suf.isNotEmpty()) suf = suf.replace("\u0000", "")
            if (i == segs.size - 1) {
                val s2 = suf.trim()
                suf = when {
                    s2.isEmpty() -> ""
                    segs.size == 1 && s2.length > 1 && s2.none { it.isLetter() } -> s2.take(1)
                    else -> s2
                }
            } else {
                suf = suf.trim()
            }
            val sp = if (pieces.isEmpty()) 0f else maxOf((sub.spacing - prevSp).toFloat(), 0f)
            prevSp = sub.spacing
            pieces.add(sp to prefix)
            pieces.add(0f to segs[i].text)
            pieces.add(0f to suf)
            totalW += sp + paint.measureText(prefix) + paint.measureText(segs[i].text) + paint.measureText(suf)
        }
        if (totalW <= 0f) return

        val ay = boxY + w.hOrY2 / 2
        // y is the vertical mid of the cell; the glyph box top sits pt/2 above it.
        // Battery cells pin the glyph box to the cell TOP instead.
        val topY = if (batteryCell) boxY else ay - pt / 2
        var compAlign = if (batteryCell) 0 else 1
        if (!batteryCell && segs.all { it.key in dateKeys }) {
            for (ow in style.widgets) {
                if (ow.type != WidgetType.PAIR || ow.sequenceId == 0) continue
                if (abs(ow.x - w.x) > 4 || abs(ow.wOrX2 - w.wOrX2) > 12) continue
                if (abs(ow.y + ow.hOrY2 / 2 - ay) > (w.hOrY2 + ow.hOrY2 + 12)) continue
                compAlign = ow.words.getOrNull(1)?.let { (it ushr 8) and 0xFF } ?: 0
                break
            }
        }
        val px = when (compAlign) {
            0 -> boxX
            2 -> boxX + maxOf(w.wOrX2 - totalW, 0f).toInt()
            else -> boxX + maxOf((w.wOrX2 - totalW) / 2f, 0f).toInt()
        }

        var cursor = px.toFloat()
        for ((space, pieceText) in pieces) {
            cursor += space
            if (pieceText.isNotEmpty()) cursor += drawTextPiece(canvas, pieceText, cursor.toInt(), topY, paint, color)
        }
    }

    // ── fitface COMP tables ────────────────────────────────────────────────────

    private val compTypeNames: Map<Int, String> = mapOf(
        0x00 to "none", 0x08 to "kcal", 0x09 to "date_day",
        0x11 to "weekday_name", 0x12 to "date_day", 0x13 to "month_name",
        0x15 to "date_month_num", 0x16 to "date_year", 0x18 to "date_year",
        0x25 to "battery", 0x3E to "temp", 0x72 to "ampm"
    )
    private val compTypeAliases = mapOf(0x7A to 0x15, 0x7B to 0x11, 0x7C to 0x12)
    private val dateKeys = setOf("weekday_name", "month_name", "date_day", "date_month_num", "date_year")
    private val weekdayPrefixes = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
    private val monthPrefixes = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val weekdayNames = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
    private val monthNames = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val unitSuffixWhitelist = setOf(
        "°", "%", "°c", "°f", "\u2103", "\u2109",
        "km", "mi", "lb", "kg", "g", "m", "h", "hr", "min", "sec", "s",
        "btu", "w", "kw"
    )

    /** One parsed composite sub: a data source plus its prefix/suffix/spacing words. */
    private data class CompSub(
        val dataType: Int, val prefixIdx: Int, val glyphIdx: Int, val spacing: Int, val word2: Int
    )

    private fun normalizeCompType(t: Int): Int = compTypeAliases[t] ?: t

    /** Parse the repeating triplets (and optional auxiliary sextuple) in a Comp's words. */
    private fun parseCompSubs(ptrs: List<Int>): List<CompSub> {
        val subs = mutableListOf<CompSub>()
        var i = 0
        while (i < ptrs.size) {
            val p = ptrs[i]
            val st = p and 0xFFFF
            val norm = normalizeCompType(st)
            val hi16 = (p ushr 16) and 0xFFFF
            if (norm in compTypeNames && norm != 0 && norm != 0xFFFF && (hi16 == 0xFFFF || hi16 < 0x100)) {
                val word1 = ptrs.getOrElse(i + 1) { 0 }
                val word2 = ptrs.getOrElse(i + 2) { 0 }
                val prefixIdx = if (hi16 < 0x100) hi16 else 0xFFFF
                var glyphIdx = word1 and 0xFFFF
                val spacing = (word1 ushr 16) and 0xFFFF
                var step = 3
                if (i + 5 < ptrs.size && ptrs[i + 3] == 0xFFFF0000.toInt()) {
                    val aux = ptrs[i + 4]
                    val auxHi = (aux ushr 16) and 0xFFFF
                    val auxLo = aux and 0xFFFF
                    if (glyphIdx == 0xFFFF && auxLo == 0xFFFF && auxHi < 0x100) {
                        glyphIdx = auxHi
                        step = 6
                    }
                }
                subs.add(CompSub(st, prefixIdx, glyphIdx, spacing, word2))
                i += step
                continue
            }
            i += 1
        }
        return subs
    }

    private fun compBindingIndex(w: WidgetRecord): Int =
        ((w.words.getOrNull(14) ?: 0) ushr 16) and 0xFFFF

    /** Resolve a sub's COMP_TYPE code to a data key, aliasing world codes and telling
     *  month-number from month-name via the separator glyph (fitface's disambiguation). */
    private fun compKey(sub: CompSub, r: TextResources, subs: List<CompSub>): String {
        val st = normalizeCompType(sub.dataType)
        if (st == 0x15) {
            val hasYear = subs.any { normalizeCompType(it.dataType) in intArrayOf(0x16, 0x18) }
            if (hasYear) return "date_month_num"
            val sep = glyph(sub.glyphIdx, r).trim()
            val numericSep = sep.isNotEmpty() && sep.all { !it.isLetter() && !it.isWhitespace() }
            return if (sep.contains('/') || sep.contains('-') || numericSep) "date_month_num" else "month_name"
        }
        return compTypeNames[st] ?: ""
    }

    private fun compText(key: String, stats: PreviewStats, r: TextResources): String = when (key) {
        "weekday_name" -> glyphWeekday(stats.weekday - 1, r)
        "month_name" -> glyphMonth(stats.month - 1, r)
        "date_day" -> stats.dayOfMonth.toString()
        "date_month_num" -> two(stats.month)
        "date_year" -> stats.year.toString()
        "battery" -> stats.battery
        "temp" -> stats.temperature
        "kcal" -> stats.calories
        "ampm" -> stats.amPm
        else -> ""
    }

    private fun glyphWeekday(idx: Int, r: TextResources): String {
        val base = findGlyphBase(weekdayPrefixes, r.glyphs)
        val gi = if (base >= 0) base + idx % 7 else -1
        return if (gi in r.glyphs.indices) r.glyphs[gi] else weekdayNames[idx % 7]
    }

    private fun glyphMonth(idx: Int, r: TextResources): String {
        val base = findGlyphBase(monthPrefixes, r.glyphs)
        val gi = if (base >= 0) base + idx % 12 else -1
        return if (gi in r.glyphs.indices) r.glyphs[gi] else monthNames[idx % 12]
    }

    /** Find the glyph-group index whose consecutive entries match the given name prefixes. */
    private fun findGlyphBase(prefixes: List<String>, groups: List<String>): Int {
        for (gi in groups.indices) {
            if (!groups[gi].lowercase().startsWith(prefixes[0])) continue
            if (gi + prefixes.size > groups.size) continue
            if ((1 until prefixes.size).all { groups[gi + it].lowercase().startsWith(prefixes[it]) }) return gi
        }
        return -1
    }

    private fun glyph(idx: Int, r: TextResources): String =
        if (idx == 0xFF || idx !in r.glyphs.indices) "" else r.glyphs[idx]

    private fun isUnitSuffix(s: String): Boolean {
        val stripped = s.trim()
        if (stripped.isEmpty()) return false
        if (stripped.all { it in "°%+-/··\u00b0\u2103\u2109" || !it.isLetter() }) return true
        return stripped.lowercase() in unitSuffixWhitelist
    }

    // ── text placement helpers ─────────────────────────────────────────────────

    /** Point size for a Pair: font binding, then the record's byte0, then the index, else 16. */
    private fun fontPt(fontIdx: Int, unk20: Int, r: TextResources): Int {
        r.bindings.getOrNull(fontIdx)?.let { if (it.pointSize in 8..120) return it.pointSize }
        r.bindings.getOrNull(unk20 and 0xFF)?.let { if (it.pointSize in 8..120) return it.pointSize }
        if (fontIdx in 12..80) return fontIdx
        return 16
    }

    /** Point size for a Comp: binding indexes in words[14]/[15] and wordA, else a literal. */
    private fun compFontPt(w: WidgetRecord, r: TextResources): Int {
        val cands = mutableListOf<Int>()
        for (pi in intArrayOf(14, 15)) {
            w.words.getOrNull(pi)?.let { p ->
                cands.add((p ushr 16) and 0xFFFF)
                cands.add(p and 0xFFFF)
            }
        }
        val u = w.wordA.toInt()
        cands.add((u ushr 16) and 0xFF)
        cands.add(u and 0xFF)
        for (c in cands) {
            r.bindings.getOrNull(c)?.let { if (it.pointSize in 8..120) return it.pointSize }
        }
        val literal = (u ushr 16) and 0xFFFF
        if (literal in 8..120) return literal
        return 20
    }

    private fun pairAnchorX(w: WidgetRecord, align: Int): Int {
        val bx = displayCoordinate(w.x, w.wOrX2, WatchFaceFormat.PANEL_WIDTH)
        return when (align) {
            1 -> bx + w.wOrX2 / 2
            2 -> bx + w.wOrX2
            else -> bx
        }
    }

    private fun bgraAllowBlack(v: Int): Int? {
        if ((v ushr 24) and 0xFF != 0xFF) return null
        return Color.rgb((v ushr 16) and 0xFF, (v ushr 8) and 0xFF, v and 0xFF)
    }

    private fun paintFor(pt: Int, family: Int = 0): Paint {
        val bold = family >= 2
        // ponytail: Robolectric's shadow Paint/Typeface throw on background threads;
        // fall back to a bare paint rather than crashing the render pipeline.
        return runCatching {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = pt.toFloat()
                this.typeface = Typeface.create(
                    "sans-serif-condensed", if (bold) Typeface.BOLD else Typeface.NORMAL
                )
            }
        }.getOrElse { Paint() }
    }

    /** Draw text centred on the cell: y is the vertical mid, never the baseline. */
    private fun drawTextF(
        canvas: Canvas, text: String, x: Int, y: Int, pt: Int, color: Int,
        align: Int = 0, family: Int = 0
    ): Float {
        if (text.isEmpty()) return 0f
        val paint = paintFor(pt, family)
        paint.color = color
        val w = paint.measureText(text)
        var dx = x.toFloat()
        if (align == 1) dx -= w / 2f
        else if (align == 2) dx -= w
        canvas.drawText(text, dx, (y - pt / 2f) - paint.fontMetrics.ascent, paint)
        return w
    }

    /** Draw a text piece whose top edge sits at [topY]; returns its advance width. */
    private fun drawTextPiece(canvas: Canvas, text: String, x: Int, topY: Int, paint: Paint, color: Int): Float {
        if (text.isEmpty()) return 0f
        paint.color = color
        canvas.drawText(text, x.toFloat(), topY - paint.fontMetrics.ascent, paint)
        return paint.measureText(text)
    }

    /**
     * Convert a stored coordinate to display space. Negative values anchor from
     * the opposite edge of the panel (e.g. x = -17 with canvas width 256 → 256 - 17 = 239).
     */
    private fun displayCoordinate(value: Int, extent: Int, canvasExtent: Int): Int =
        if (value < 0) canvasExtent + value - extent else value

    private fun two(v: Int): String = v.toString().padStart(2, '0')
}

/**
 * Decoded raster pixels, reused across renders.
 *
 * Decoding a raster allocates an ARGB_8888 bitmap plus an IntArray of the same pixel
 * count. The editor re-renders the whole face on every nudge, drop and drag start, and
 * every widget used to re-decode its own raster each time — on a 13-widget face that was
 * a steady stream of ~0.4 MB allocations feeding the collector (visible as the GC storm
 * and 5-second frame stalls in logcat). Rasters are immutable here and a style holds the
 * same instances for as long as its project is open, so the bitmaps are worth keeping.
 *
 * Panel-sized rasters are deliberately left out: the background raster is rebuilt from
 * the user's photo on every edit, so caching it would only ever grow.
 *
 * Thread-safe — renders run on the editor's engine and, during a project load, on the
 * default dispatcher.
 */
class RasterBitmapCache(private val maxEntries: Int = 64) {

    private val bitmaps = ConcurrentHashMap<Raster, Bitmap>()

    fun bitmap(raster: Raster): Bitmap {
        if (raster.width == WatchFaceFormat.PANEL_WIDTH &&
            raster.height == WatchFaceFormat.PANEL_HEIGHT
        ) {
            return Rgb565.toBitmap(raster)
        }
        bitmaps[raster]?.let { return it }
        val decoded = Rgb565.toBitmap(raster)
        // Bounded by dropping the map rather than evicting a single entry: nothing here is
        // recycled, because a bitmap may still be used by a render that is in flight.
        if (bitmaps.size >= maxEntries) bitmaps.clear()
        bitmaps[raster] = decoded
        return decoded
    }
}

/** Firmware text resources (font bindings + glyph groups) from a parsed container. */
fun WatchFaceContainer.textResources(): TextResources =
    TextResources(fontBindings, glyphGroups)