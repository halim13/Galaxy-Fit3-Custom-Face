package com.galaxyfit3.core.format

/**
 * Constants for the SM-R390 (Galaxy Fit 3) OPPO watch-face container format.
 * Derived from public analysis of the container layout (see docs).
 */
object WatchFaceFormat {
    const val MAGIC = "oppo"
    const val VERSION = 4
    const val HEADER_SIZE = 32
    const val DIR_ENTRY_SIZE = 74
    const val STYLE_HEADER_SIZE = 24
    const val RASTER_HEADER_SIZE = 12
    const val RASTER_TRAILER_SIZE = 4
    const val SETTING_SIZE = 256
    const val STRUCT_MAGIC = 0x12345678

    const val PANEL_WIDTH = 256
    const val PANEL_HEIGHT = 402
    const val PANEL_CENTER_X = 128
    const val PANEL_CENTER_Y = 201

    const val FORMAT_RGB565 = 0x0082
    const val FORMAT_RGB565_A = 0x0080
    const val BPP_RGB565 = 2
    const val BPP_RGB565_A = 3

    const val WATCH_CONTAINER_BYTE_CEILING = 16 * 1024 * 1024

    const val GLYPH_NUMERIC = 0xFFFF

    const val SEQ_HOUR_HAND = 1
    const val SEQ_MINUTE_HAND = 9
    const val SEQ_SECOND_HAND = 13
    // Digital-time digit positions (one sprite per digit, 0..9 frames).
    const val SEQ_HOUR_TENS = 2
    const val SEQ_HOUR_ONES = 3
    const val SEQ_MINUTE_TENS = 10
    const val SEQ_MINUTE_ONES = 11
    const val SEQ_STEPS = 29
    const val SEQ_HEART_RATE = 41
    const val SEQ_CALORIES = 48
    const val SEQ_ACTIVE_TIME = 71
    const val SEQ_AM_PM = 5
    const val SEQ_TEMPERATURE = 62
}