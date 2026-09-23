package com.galaxyfit3.core.format.parser

import com.galaxyfit3.core.format.WatchFaceFormat
import com.galaxyfit3.core.format.model.*

/**
 * A widget dropped onto the blank canvas.
 *
 * @param donor        the source widget (provides firmware data-source IDs and
 *                     type-specific words that must survive editing)
 * @param donorRasters the rasters the donor references, in offset order
 * @param x            canvas x (top-left, anchor-aware for Pair widgets)
 * @param y            canvas y
 */
data class PlacedWidget(
    val donor: WidgetRecord,
    val donorRasters: List<Raster>,
    val x: Int,
    val y: Int,
    /** Custom text ARGB override. Pair records store it as words[0] (the firmware's
     *  colour slot), so the override survives into the baked output. Composite records
     *  keep their colour in a yet-undocumented place, so their override is preview-only
     *  and patchWidget leaves those words untouched. */
    val tint: Int? = null
) {
    val sequenceId: Int get() = donor.sequenceId
    val meaning: WidgetMeaning get() = WidgetMeaningCatalog.meaning(donor, donorRasters)
    /** True when the widget references image-section rasters that must be copied. */
    val needsRasterCopy: Boolean
        get() = donor.type == WidgetType.STATIC ||
            donor.type == WidgetType.SPRITE ||
            donor.type == WidgetType.HAND ||
            donor.type == WidgetType.ARC ||
            donor.type == WidgetType.LINE_BAR
}

/**
 * Builds a fresh watch-face container from a seed. The seed supplies the
 * firmware metadata (setting, font bindings, glyph tables) and the data-source
 * sequence IDs; the user's placed widgets and background are assembled into a
 * brand-new style set. Unrelated seed entries are preserved byte-for-byte.
 */
object BlankFaceBuilder {

    /**
     * Build a new container.
     *
     * @param seed         parsed seed face (imported stock face)
     * @param faceId       e.g. "90001"
     * @param faceName     display name, e.g. "SM-R390_90001_256x402"
     * @param placed       widgets on the canvas, in draw order
     * @param background   optional full-panel background raster (256x402). When
     *                     null the watch paints onto its black panel.
     * @param extraEntries additional entries the caller wants before styles
     *                     (rarely used)
     * @param styles       prebuilt style variants to emit as styleN.bin. When
     *                     empty/null, one style is built from [placed] (default).
     */
    fun build(
        seed: WatchFaceContainer,
        faceId: String,
        faceName: String,
        placed: List<PlacedWidget>,
        background: Raster?,
        extraEntries: List<ContainerEntry> = emptyList(),
        styles: List<StyleEntry>? = null
    ): ByteArray {
        val variants = if (styles.isNullOrEmpty()) listOf(buildStyle(placed, background)) else styles

        val entries = ArrayList<ContainerEntry>()
        entries += buildSettingEntry(seed, faceId, faceName, variants.size)

        // Keep font bindings and glyph tables from the seed — they carry the
        // text roles and locale strings the firmware needs for label widgets.
        seed.entries.forEach { entry ->
            val p = entry.payload
            if (p is ContainerPayload.FontBinding || p is ContainerPayload.GlyphTable) {
                entries += entry
            }
        }

        extraEntries.forEach { entries += it }

        for ((i, style) in variants.withIndex()) {
            val name = "style$i.bin"
            entries += ContainerEntry(
                directory = DirectoryEntry("./SM-R390_${faceId}_256x402/$name", 0, style.entrySize, 0),
                payload = ContainerPayload.Style(style)
            )
        }

        // AOD: same widgets over no background raster (watch paints black).
        val aod = buildStyle(placed, null)
        entries += ContainerEntry(
            directory = DirectoryEntry("./SM-R390_${faceId}_256x402/aod.bin", 0, aod.entrySize, 0),
            payload = ContainerPayload.Style(aod)
        )

        return WatchFaceSerializer.serializeEntries(WatchFaceFormat.VERSION, entries)
    }

    private fun buildSettingEntry(seed: WatchFaceContainer, faceId: String, name: String, styleCount: Int): ContainerEntry {
        val old = (seed.settingEntry?.payload as? ContainerPayload.Setting)?.data
        val faceVersion = old?.faceVersion ?: 40000
        val setting = SettingBin(faceId, faceVersion, styleCount, 0xFFFF, name)
        return ContainerEntry(
            directory = DirectoryEntry("./SM-R390_${faceId}_256x402/setting.bin", 0, WatchFaceFormat.SETTING_SIZE, 0),
            payload = ContainerPayload.Setting(setting)
        )
    }

    /** For a hand, the placed position the editor tracks is the rotation centre,
     *  not the sprite's top-left: the record stores top-left (+0x18) and the anchor
     *  lives in wordA as (pivotY<<16 | pivotX). Seeding at top-left shifts every hand
     *  up-left (the stock layout centres them on the panel). */
    fun handCenter(donor: WidgetRecord): Pair<Int, Int> {
        val pivot = donor.wordA.toInt()
        return (donor.x + (pivot and 0xFFFF)) to (donor.y + (pivot ushr 16 and 0xFFFF))
    }

    /**
     * Extract a donor [PlacedWidget] from a seed style's widget at [index],
     * carrying the exact rasters the widget references.
     */
    fun extractDonor(style: StyleEntry, index: Int): PlacedWidget {
        val donor = style.widgets[index]
        val rasters = ArrayList<Raster>()
        val offsetToRaster = HashMap<Int, Raster>()
        var running = 0
        style.rasters.forEach { r ->
            offsetToRaster[running] = r
            running += r.encodedSize()
        }
        val refs = when (donor.type) {
            // STATIC wordA is the raster offset — 0 is valid (stock background widgets
            // point at the first raster with wordA == 0); only a negative word is not.
            WidgetType.STATIC -> listOfNotNull(donor.wordA.toInt().takeIf { it >= 0 })
            WidgetType.SPRITE -> donor.words
            WidgetType.HAND -> donor.words.drop(1).take(1)
            // Arc and LineBar address rasters too: Arc keeps its fill raster in
            // words[4], LineBar its strip in words[2] (fitface-studio corpus: every
            // one of the 30 Arc / 16 LineBar records resolves, none zero). Without
            // copying and remapping these, any bake that shifts the image section
            // leaves the pointer dangling and the widget draws nothing on the watch.
            WidgetType.ARC -> donor.words.drop(4).take(1)
            WidgetType.LINE_BAR -> donor.words.drop(2).take(1)
            else -> emptyList()
        }
        refs.forEach { off ->
            offsetToRaster[off]?.let { rasters += it }
        }
        return PlacedWidget(donor, rasters, donor.x, donor.y)
    }

    /**
     * Assemble widget records + an image section from placed widgets.
     * The background, when present, is raster 0; every widget's rasters are
     * appended after it and the widget's offset words remapped.
     */
    fun buildStyle(placed: List<PlacedWidget>, background: Raster?): StyleEntry {
        val rasters = ArrayList<Raster>()
        background?.let { rasters += it }
        val rasterOffsetByRaster = HashMap<Raster, Int>()

        // Stock faces draw the background as widget 0: STATIC at (0,0) referencing
        // raster offset 0 (the prepended background above). The watch blits only
        // what a widget references, so a background raster with no widget until
        // now baked orphaned and never reached the watch.
        val builtWidgets = ArrayList<WidgetRecord>(placed.size + 1)
        if (background != null) {
            builtWidgets += WidgetRecord(
                WidgetType.STATIC, 0, 0, 0, 40, 0,
                x = 0, y = 0, wOrX2 = WatchFaceFormat.PANEL_WIDTH, hOrY2 = WatchFaceFormat.PANEL_HEIGHT,
                wordA = 0L, words = listOf(0)
            )
        }
        placed.forEach { p ->
            val remapped = ArrayList<Int>()
            if (p.needsRasterCopy) {
                p.donorRasters.forEach { r ->
                    val off = rasterOffsetByRaster.getOrPut(r) {
                        val at = rasters.sumOf { it.encodedSize() }
                        rasters += r
                        at
                    }
                    remapped += off
                }
            }

            val widget = patchWidget(p.donor, remapped, p.x, p.y, p.tint)
            builtWidgets += widget
        }

        val widgetBytes = builtWidgets.sumOf { it.recordBytes }
        val imageBytes = rasters.sumOf { it.encodedSize() }
        return StyleEntry(
            widgetCount = builtWidgets.size,
            widgetBytes = widgetBytes,
            imageBytes = imageBytes,
            headerUnknown = 0x400,
            widgets = builtWidgets,
            rasters = rasters
        )
    }

    /**
     * Copy the donor widget, patch x/y and rewrite its raster reference words to
     * point at the remapped image-section offsets.
     */
    private fun patchWidget(donor: WidgetRecord, remapped: List<Int>, x: Int, y: Int, tint: Int?): WidgetRecord {
        return when (donor.type) {
            WidgetType.PAIR -> {
                // words[0] is the firmware text ARGB (see FaceStyleRenderer.textStyle):
                // a user tint slots in exactly there, so both preview and bake agree.
                val words = donor.words.toMutableList()
                if (tint != null) {
                    if (words.isEmpty()) words += 0 else words[0] = tint
                }
                WidgetRecord(
                    donor.type, donor.sequenceId, donor.opaque1, donor.globalIndex, donor.recordSize,
                    donor.opaque2, x, y, donor.wOrX2, donor.hOrY2,
                    donor.wordA, words
                )
            }
            WidgetType.STATIC -> {
                val headerOffset = remapped.getOrNull(0)
                // Static image pointer lives in +0x20; words stay as-is (usually 0).
                WidgetRecord(
                    donor.type, donor.sequenceId, donor.opaque1, donor.globalIndex, donor.recordSize,
                    donor.opaque2, x, y, donor.wOrX2, donor.hOrY2,
                    (headerOffset ?: donor.wordA.toInt()).toLong(),
                    donor.words
                )
            }
            WidgetType.SPRITE -> {
                // wordA is the frame count; every word is a per-frame offset.
                require(remapped.size == donor.words.size) {
                    "Sprite frame rasters ${remapped.size} != donor frames ${donor.words.size}"
                }
                WidgetRecord(
                    donor.type, donor.sequenceId, donor.opaque1, donor.globalIndex, donor.recordSize,
                    donor.opaque2, x, y, donor.wOrX2, donor.hOrY2,
                    donor.wordA,
                    remapped
                )
            }
            WidgetType.HAND -> {
                // words[0] = 360° sweep, words[1] = sprite offset. Keep sweep,
                // remap the sprite. x/y are placed as the pivot position minus pivot.
                require(remapped.size == 1)
                val head = donor.words.firstOrNull() ?: 0x01680000
                val pivot = donor.wordA.toInt()
                val pivotX = pivot and 0xFFFF
                val pivotY = (pivot ushr 16) and 0xFFFF
                WidgetRecord(
                    donor.type, donor.sequenceId, donor.opaque1, donor.globalIndex, donor.recordSize,
                    donor.opaque2,
                    x - pivotX, y - pivotY,
                    donor.wOrX2, donor.hOrY2,
                    donor.wordA,
                    listOf(head, remapped[0])
                )
            }
            WidgetType.ARC -> {
                // words[4] is the fill raster; words[0..3] carry arc geometry/flags
                // that must survive untouched.
                require(remapped.size == 1) { "Arc raster refs ${remapped.size} != 1" }
                val words = donor.words.toMutableList()
                if (words.size > 4) words[4] = remapped[0]
                WidgetRecord(
                    donor.type, donor.sequenceId, donor.opaque1, donor.globalIndex, donor.recordSize,
                    donor.opaque2, x, y, donor.wOrX2, donor.hOrY2,
                    donor.wordA, words
                )
            }
            WidgetType.LINE_BAR -> {
                // words[2] is the strip raster; words[0..1] carry bar geometry.
                require(remapped.size == 1) { "LineBar raster refs ${remapped.size} != 1" }
                val words = donor.words.toMutableList()
                if (words.size > 2) words[2] = remapped[0]
                WidgetRecord(
                    donor.type, donor.sequenceId, donor.opaque1, donor.globalIndex, donor.recordSize,
                    donor.opaque2, x, y, donor.wOrX2, donor.hOrY2,
                    donor.wordA, words
                )
            }
            WidgetType.COMP -> {
                // Composite text (battery %, day name, kcal, …) keeps its colour
                // in the same opaque word FaceStyleRenderer.drawComp reads — the
                // first non-0xFFFFFF/0xFFFFxxxx word whose alpha byte is 0xFF. Baking
                // the tint into exactly that slot makes baked == preview (the watch
                // firmware renders that very word), so comps recolor too.
                val words = donor.words.toMutableList()
                if (tint != null) {
                    for (i in words.indices) {
                        val p = words[i]
                        if (p == 0xFFFFFFFF.toInt() || (p ushr 16) == 0xFFFF) continue
                        if (((p ushr 24) and 0xFF) == 0xFF) { words[i] = tint; break }
                    }
                }
                WidgetRecord(
                    donor.type, donor.sequenceId, donor.opaque1, donor.globalIndex, donor.recordSize,
                    donor.opaque2, x, y, donor.wOrX2, donor.hOrY2,
                    donor.wordA, words
                )
            }
            else -> {
                // Pair/Badge — no raster refs; just relocate.
                WidgetRecord(
                    donor.type, donor.sequenceId, donor.opaque1, donor.globalIndex, donor.recordSize,
                    donor.opaque2, x, y, donor.wOrX2, donor.hOrY2,
                    donor.wordA, donor.words
                )
            }
        }
    }
}