package com.galaxyfit3.core.format.model

/** Parsed payload of a directory entry. */
sealed interface ContainerPayload {
    data class Setting(val data: com.galaxyfit3.core.format.model.SettingBin) : ContainerPayload
    data class FontBinding(val data: com.galaxyfit3.core.format.model.FontBinding) : ContainerPayload
    data class GlyphTable(val data: com.galaxyfit3.core.format.model.GlyphTable) : ContainerPayload
    data class Style(val data: StyleEntry) : ContainerPayload
    /** Vendor preview rasters (178x280 RGB565, one per style) from preview.bin. */
    data class Preview(val rasters: List<Raster>) : ContainerPayload
    data class Unknown(val bytes: ByteArray) : ContainerPayload {
        override fun equals(other: Any?): Boolean =
            other is Unknown && other.bytes.contentEquals(bytes)
        override fun hashCode(): Int = bytes.contentHashCode()
    }
}

/** One directory entry plus its parsed payload. */
data class ContainerEntry(
    val directory: DirectoryEntry,
    val payload: ContainerPayload
) {
    val name: String get() = directory.name
}

/** The full parsed OPPO watch-face container. */
data class WatchFaceContainer(
    val version: Int,
    val entries: List<ContainerEntry>,
    val rawBytes: ByteArray?
) {
    val styleNames: List<String> get() = entries.map { it.name }.filter { it.startsWith("style") }
    val isStyle: (String) -> Boolean = { name -> name in styleNames }
    val styleEntries: List<ContainerEntry> get() = entries.filter { isStyle(it.name) }
    val aodEntry: ContainerEntry? get() = entries.find { it.name == "aod.bin" }
    val settingEntry: ContainerEntry? get() = entries.find { it.name == "setting.bin" }
    /** Vendor preview rasters from preview.bin, or empty when the container has none. */
    val previewRasters: List<Raster>
        get() = (entries.find { it.name == "preview.bin" }
            ?.payload as? ContainerPayload.Preview)?.rasters.orEmpty()

    /** Firmware font bindings (font_N.bin), ordered by N in the filename. Used to size
     *  value/composite text exactly like the watch does (the binding's point size). */
    val fontBindings: List<FontBinding>
        get() = entries.mapNotNull { e ->
            val data = (e.payload as? ContainerPayload.FontBinding)?.data ?: return@mapNotNull null
            val n = Regex("font_(\\d+)\\.bin").matchEntire(e.name)
                ?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            n to data
        }.sortedBy { it.first }.map { it.second }

    /** Glyph strings from the 'en' glyph table (font_en.bin, falling back to any glyph
     *  table), in group-index order. These hold weekday names, units, separators. */
    val glyphGroups: List<String>
        get() {
            val en = (entries.find { it.name == "font_en.bin" }
                ?.payload as? ContainerPayload.GlyphTable)?.data
            val table = en ?: entries
                .mapNotNull { (it.payload as? ContainerPayload.GlyphTable)?.data }
                .firstOrNull()
            return table?.groups?.sortedBy { it.index }?.map { it.text }.orEmpty()
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WatchFaceContainer) return false
        return version == other.version && entries == other.entries
    }

    override fun hashCode(): Int = 31 * version + entries.hashCode()
}