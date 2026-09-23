package com.galaxyfit3.core.format.model

/**
 * A widget record inside a style entry.
 *
 * Layout (36-byte fixed head followed by N 32-bit type-specific words):
 *   +0x00 type, +0x04 sequence/data-source id, +0x08 opaque, +0x0C (globalIndex<<16|recordSize),
 *   +0x10..+0x18 opaque 8 bytes, +0x18 x, +0x1A y, +0x1C width/x2, +0x1E height/y2,
 *   +0x20 type-dependent word A, +0x24... type-specific words.
 */
data class WidgetRecord(
    val type: Int,
    val sequenceId: Int,
    val opaque1: Int,
    val globalIndex: Int,
    val recordSize: Int,
    val opaque2: Long,
    val x: Int,
    val y: Int,
    val wOrX2: Int,
    val hOrY2: Int,
    val wordA: Long,
    val words: List<Int>
) {
    val recordBytes: Int get() = 36 + words.size * 4
    val isHand: Boolean get() = type == WidgetType.HAND
    val isPointerHolder: Boolean
        get() = type == WidgetType.STATIC || type == WidgetType.SPRITE || type == WidgetType.HAND
}

/** Known widget types observed in the container format. */
object WidgetType {
    const val STATIC = 1
    const val HAND = 2
    const val SPRITE = 3
    const val PAIR = 5
    const val BADGE = 7
    const val COMP = 13
    const val ARC = 16
    const val LINE_BAR = 17
}

/** Semantic interpretation of a widget, populated during cataloguing. */
enum class WidgetMeaning(val label: String) {
    UNKNOWN("Unknown"),
    BACKGROUND("Background"),
    HOUR_HAND("Hour hand"),
    MINUTE_HAND("Minute hand"),
    SECOND_HAND("Second hand"),
    TIME_DIGITS("Time (digital)"),
    TIME_COLON("Colon"),
    DATE("Date"),
    STEPS("Steps"),
    HEART_RATE("Heart rate"),
    CALORIES("Calories"),
    ACTIVE_TIME("Active time"),
    AM_PM("AM/PM"),
    TEMPERATURE("Temperature"),
    WEATHER("Weather"),
    BATTERY("Battery"),
    DIVIDER("Divider"),
    STATIC_LABEL("Static image"),
    FRAME("Frames (sprite)")
}