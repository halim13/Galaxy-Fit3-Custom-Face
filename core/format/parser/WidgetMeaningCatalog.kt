package com.galaxyfit3.core.format.parser

import com.galaxyfit3.core.format.WatchFaceFormat
import com.galaxyfit3.core.format.model.*

/**
 * Heuristics that assign a semantic meaning to a widget record, using the
 * derivations documented in the container analysis: known sequence IDs,
 * geometry vs panel centre, glyph-group indexes, anchor modes and position.
 *
 * All meanings are best-effort; the user can always edit them.
 */
object WidgetMeaningCatalog {

    /** Known data-source sequence IDs (from the reference analysis). */
    private val knownSequences = mapOf(
        WatchFaceFormat.SEQ_HOUR_HAND to WidgetMeaning.HOUR_HAND,
        WatchFaceFormat.SEQ_MINUTE_HAND to WidgetMeaning.MINUTE_HAND,
        WatchFaceFormat.SEQ_SECOND_HAND to WidgetMeaning.SECOND_HAND,
        WatchFaceFormat.SEQ_STEPS to WidgetMeaning.STEPS,
        WatchFaceFormat.SEQ_HEART_RATE to WidgetMeaning.HEART_RATE,
        WatchFaceFormat.SEQ_CALORIES to WidgetMeaning.CALORIES,
        WatchFaceFormat.SEQ_ACTIVE_TIME to WidgetMeaning.ACTIVE_TIME,
        WatchFaceFormat.SEQ_AM_PM to WidgetMeaning.AM_PM,
        WatchFaceFormat.SEQ_TEMPERATURE to WidgetMeaning.TEMPERATURE,
        // Digital-time digit sprites (one per digit position; wordA carries no
        // frame count here, so the sequence id is the only tell).
        WatchFaceFormat.SEQ_HOUR_TENS to WidgetMeaning.TIME_DIGITS,
        WatchFaceFormat.SEQ_HOUR_ONES to WidgetMeaning.TIME_DIGITS,
        WatchFaceFormat.SEQ_MINUTE_TENS to WidgetMeaning.TIME_DIGITS,
        WatchFaceFormat.SEQ_MINUTE_ONES to WidgetMeaning.TIME_DIGITS
    )

    fun meaning(widget: WidgetRecord, styleRasters: List<Raster>): WidgetMeaning {
        knownSequences[widget.sequenceId]?.let { return it }

        // Panel-sized rasters opening the style are backgrounds (or raw frames).
        if (widget.type == WidgetType.STATIC && isPanelRaster(widget, styleRasters)) {
            return WidgetMeaning.BACKGROUND
        }

        // Digital time: multi-frame digit sprites with x near panel centre, upper area.
        if (widget.type == WidgetType.SPRITE && widget.wordA.toInt() in 4..12) {
            if (widget.y < WatchFaceFormat.PANEL_HEIGHT / 2) return WidgetMeaning.TIME_DIGITS
            return WidgetMeaning.FRAME
        }
        if (widget.type == WidgetType.SPRITE && widget.wordA.toInt() == 2) {
            return WidgetMeaning.TIME_COLON
        }

        // Hand records near the centre are clock hands if the pivot lands at centre.
        if (widget.type == WidgetType.HAND) {
            val pivot = widget.wordA.toInt()
            val px = pivot and 0xFFFF
            val py = (pivot ushr 16) and 0xFFFF
            if (widget.x + px == WatchFaceFormat.PANEL_CENTER_X &&
                widget.y + py == WatchFaceFormat.PANEL_CENTER_Y
            ) {
                return when (widget.sequenceId) {
                    WatchFaceFormat.SEQ_HOUR_HAND -> WidgetMeaning.HOUR_HAND
                    WatchFaceFormat.SEQ_MINUTE_HAND -> WidgetMeaning.MINUTE_HAND
                    WatchFaceFormat.SEQ_SECOND_HAND -> WidgetMeaning.SECOND_HAND
                    else -> WidgetMeaning.HOUR_HAND
                }
            }
            return WidgetMeaning.HOUR_HAND
        }

        // Pair (value) widgets: anchor + label group.
        if (widget.type == WidgetType.PAIR) {
            return pairMeaning(widget)
        }
        if (widget.type == WidgetType.BADGE) return WidgetMeaning.DIVIDER
        if (widget.type == WidgetType.COMP) return compositeMeaning(widget)
        if (widget.type == WidgetType.STATIC) return WidgetMeaning.STATIC_LABEL

        return WidgetMeaning.UNKNOWN
    }

    private fun isPanelRaster(widget: WidgetRecord, rasters: List<Raster>): Boolean {
        if (widget.type != WidgetType.STATIC) return false
        val off = widget.wordA.toInt()
        // Offset 0 is legitimate: the background is the style's first raster and stock
        // faces point at it with wordA == 0. Only a negative wordA is not an offset.
        if (off < 0) return false
        var running = 0
        for (r in rasters) {
            val size = r.encodedSize()
            if (off == running) {
                return r.width == WatchFaceFormat.PANEL_WIDTH && r.height == WatchFaceFormat.PANEL_HEIGHT
            }
            running += size
        }
        return false
    }

    private fun pairMeaning(widget: WidgetRecord): WidgetMeaning {
        // Third type-word low half is a glyph-group index (label). This is used
        // to distinguish steps/bpm/kcal/... widgets that carry no data-source ID.
        val labelIndex = widget.words.getOrNull(2)?.and(0xFFFF) ?: WatchFaceFormat.GLYPH_NUMERIC

        // Priority to data-source ID if present.
        if (widget.sequenceId in knownSequences) return knownSequences.getValue(widget.sequenceId)

        return when (labelIndex) {
            5 -> WidgetMeaning.STEPS
            6 -> WidgetMeaning.HEART_RATE
            7 -> WidgetMeaning.CALORIES
            8 -> WidgetMeaning.ACTIVE_TIME
            3 -> WidgetMeaning.AM_PM
            else -> WidgetMeaning.UNKNOWN
        }
    }

    private fun compositeMeaning(widget: WidgetRecord): WidgetMeaning {
        // Comp triplets either carry (glyphGroup<<16)|sequenceId (group 2 = '-'
        // => date, group 1 = '°' => temperature) or a firmware composite tag
        // 0xFFFFxxxx whose low half names the data source: 0x25 battery, 0x11
        // weekday/day, 0x08 kcal, 0x3E temp, 0x72 am/pm (see compositeMeaning).
        val group = widget.words.getOrNull(0)
        when (group) {
            null -> return WidgetMeaning.UNKNOWN
            else -> {
                val glyph = (group ushr 16) and 0xFFFF
                if (glyph == 0xFFFF) {
                    return when (group and 0xFFFF) {
                        0x25 -> WidgetMeaning.BATTERY
                        0x3E -> WidgetMeaning.TEMPERATURE
                        0x11, 0x12, 0x13, 0x15, 0x16, 0x18 -> WidgetMeaning.DATE
                        0x08 -> WidgetMeaning.CALORIES
                        0x72 -> WidgetMeaning.AM_PM
                        else -> WidgetMeaning.UNKNOWN
                    }
                }
                return when (glyph) {
                    1 -> WidgetMeaning.TEMPERATURE
                    2 -> WidgetMeaning.DATE
                    else -> WidgetMeaning.UNKNOWN
                }
            }
        }
    }

    /** Human description of a widget for the editor list. */
    fun describe(widget: WidgetRecord): String {
        val meaning = when (widget.type) {
            WidgetType.STATIC -> "Image"
            WidgetType.HAND -> "Hand"
            WidgetType.SPRITE -> "Sprite (${widget.wordA.toInt()} frames)"
            WidgetType.PAIR -> "Value"
            WidgetType.BADGE -> "Divider"
            WidgetType.COMP -> "Composite"
            WidgetType.ARC -> "Arc"
            WidgetType.LINE_BAR -> "Bar"
            else -> "Type ${widget.type}"
        }
        return "$meaning #${widget.sequenceId}"
    }
}