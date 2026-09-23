package com.galaxyfit3.core.format.model

/** A style entry (aod.bin or styleN.bin) — widget section + image section. */
data class StyleEntry(
    val widgetCount: Int,
    val widgetBytes: Int,
    val imageBytes: Int,
    val headerUnknown: Int,
    val widgets: List<WidgetRecord>,
    val rasters: List<Raster>
) {
    val imageSectionOffset: Int get() = 24 + widgetBytes
    val entrySize: Int get() = imageSectionOffset + imageBytes

    fun withWidgets(widgets: List<WidgetRecord>, rasters: List<Raster>): StyleEntry =
        StyleEntry(
            widgetCount = widgets.size,
            widgetBytes = widgets.sumOf { it.recordBytes },
            imageBytes = rasters.sumOf { it.encodedSize() },
            headerUnknown = headerUnknown,
            widgets = widgets,
            rasters = rasters
        )
}