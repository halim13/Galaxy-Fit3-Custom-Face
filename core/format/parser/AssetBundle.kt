package com.galaxyfit3.core.format.parser

import com.galaxyfit3.core.format.WatchFaceFormat
import com.galaxyfit3.core.format.model.ContainerPayload
import com.galaxyfit3.core.format.model.Raster
import com.galaxyfit3.core.format.model.StyleEntry
import com.galaxyfit3.core.format.model.WidgetMeaning
import com.galaxyfit3.core.format.model.WatchFaceContainer
import com.galaxyfit3.core.format.model.WatchFaceFmt
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The editable assets of one face, named so a zip of them maps back without a manifest.
 *
 * Every raster is written as a PNG under `assets/` at a path derived from the widget's
 * role in style 0 — the seed's donor library, the same indexes the editor shows:
 *
 * - `assets/background.png` — the background widget's raster, when the style has one
 * - `assets/wIDGETNAME/frame_N.png` — frame N of the donor widget (meaning lowercase)
 *
 * [importZip] accepts the exported zip or any zip whose asset names still match the
 * pattern (e.g. one where only some PNGs were edited). Entries that decode cleanly and
 * match the seed raster's pixel size are mapped back as replacement rasters; unknown
 * files inside the zip are ignored.
 */
object AssetBundle {

    private const val DIR = "assets/"

    /** Why a frame has no PNG of its own in an export. */
    enum class SkipReason { NON_IMAGE_FORMAT, EMPTY_PIXELS, INVALID_SIZE }

    /** One editable frame of one donor widget, as the export offers it. */
    data class Frame(
        val donorIndex: Int,
        val frameIndex: Int,
        /** Zip path the frame's PNG was written to. */
        val path: String,
        /** Human label of the widget, e.g. "steps" — also its directory name. */
        val label: String,
        /** Encoded pixel size the replacement raster must keep, width to height. */
        val width: Int,
        val height: Int,
        /** True when the raster carries per-pixel alpha (RGB565_A). */
        val withAlpha: Boolean
    )

    /** Everything the export wrote, plus what it could not. */
    data class Export(
        val zip: ByteArray,
        val frames: List<Frame>,
        val hasBackground: Boolean,
        /** Frames left out of the zip, with the reason. */
        val skipped: List<Pair<Frame, SkipReason>>
    )

    /** The result of mapping an edited zip back onto the seed. */
    data class Import(
        /** donor label -> (frameIndex -> replacement pixels). */
        val frames: Map<String, Map<Int, Png.Decoded>>,
        /** The zip's background PNG, when it carried one. */
        val background: Png.Decoded?,
        /** Zip paths that matched no seed asset or failed to decode. */
        val ignored: List<String>
    )

    // ------------------------------------------------------------------ export

    /** Export the seed's editable assets as a self-describing zip. */
    fun exportZip(container: WatchFaceContainer): Export {
        val style = style0(container)
            ?: throw IllegalArgumentException("Face has no editable style")
        val frames = ArrayList<Frame>()
        val skipped = ArrayList<Pair<Frame, SkipReason>>()
        val files = LinkedHashMap<String, ByteArray>()
        var hasBackground = false

        style.widgets.forEachIndexed { wi, w ->
            val rasters = widgetRasters(style, wi)
            if (rasters.isEmpty()) return@forEachIndexed
            val label = labelOf(style, wi)
            if (label == "background") {
                hasBackground = true
                val r = rasters.first()
                files[DIR + "background.png"] = Png.encode(r.width, r.height, argbOf(r))
                return@forEachIndexed
            }
            rasters.forEachIndexed { fi, r ->
                val reason = skipReason(r)
                val frame = Frame(
                    wi, fi, DIR + label + "/frame_$fi.png", label,
                    r.width, r.height, r.format == WatchFaceFmt.FORMAT_RGB565_A
                )
                if (reason != null) {
                    skipped += frame to reason
                    return@forEachIndexed
                }
                frames += frame
                files[frame.path] = Png.encode(r.width, r.height, argbOf(r))
            }
        }

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            files.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return Export(out.toByteArray(), frames, hasBackground, skipped)
    }

    // ------------------------------------------------------------------ import

    /** Decode an edited zip into per-frame and background pixels, validated by name. */
    fun importZip(zipBytes: ByteArray): Import {
        val frames = HashMap<String, HashMap<Int, Png.Decoded>>()
        var background: Png.Decoded? = null
        val ignored = ArrayList<String>()

        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val name = entry.name.trimStart('.', '/')
                    when {
                        name == DIR + "background.png" -> {
                            background = Png.decode(zip.readBytes()) ?: run {
                                ignored += entry.name
                                null
                            }
                        }
                        name.startsWith(DIR) -> {
                            val rest = name.removePrefix(DIR)
                            val slash = rest.indexOf('/')
                            if (slash > 0) {
                                val label = rest.substring(0, slash)
                                val file = rest.substring(slash + 1)
                                val m = Regex("""frame_(\d+)\.png""").matchEntire(file)
                                if (m != null) {
                                    val frameIndex = m.groupValues[1].toInt()
                                    val decoded = Png.decode(zip.readBytes())
                                    if (decoded != null) {
                                        frames.getOrPut(labelKey(label)) { HashMap() }[frameIndex] = decoded
                                    } else {
                                        ignored += entry.name
                                    }
                                } else {
                                    ignored += entry.name
                                }
                            } else {
                                ignored += entry.name
                            }
                        }
                        else -> ignored += entry.name
                    }
                }
                entry = zip.nextEntry
            }
        }
        return Import(frames, background, ignored)
    }

    /** Lowercased label — the same key the exporter derived from the widget meaning. */
    private fun labelKey(label: String): String = label.lowercase()

    // -------------------------------------------------------------- map & apply

    /**
     * Map decoded zip pixels onto the seed's rasters. Returns (donorIndex, frameIndex)
     * -> the replacement pixels, keeping only frames whose pixel size matches the seed
     * raster — size mismatches would corrupt the sprite atlas, so they are dropped.
     * The background is validated separately through [mapBackground].
     */
    fun mapToSeed(bundle: Import, container: WatchFaceContainer): Map<Pair<Int, Int>, Png.Decoded> {
        val style = style0(container) ?: return emptyMap()
        val mapped = HashMap<Pair<Int, Int>, Png.Decoded>()
        style.widgets.forEachIndexed { wi, w ->
            val rasters = widgetRasters(style, wi)
            if (rasters.isEmpty()) return@forEachIndexed
            val label = labelOf(style, wi)
            if (label == "background") {
                return@forEachIndexed // handled through Import.background
            }
            val replacements = bundle.frames[labelKey(label)] ?: return@forEachIndexed
            rasters.forEachIndexed { fi, r ->
                val pixels = replacements[fi] ?: return@forEachIndexed
                if (pixels.width != r.width || pixels.height != r.height) return@forEachIndexed
                if (pixels.argb.size != r.width * r.height) return@forEachIndexed
                mapped[wi to fi] = pixels
            }
        }
        return mapped
    }

    /** The zip's background pixels as they are — the caller scales them to the panel
     *  size if an editor changed the canvas, the same leniency the image picker has. */
    fun mapBackground(bundle: Import): Png.Decoded? = bundle.background

    // ------------------------------------------------------------------ helpers

    private fun style0(container: WatchFaceContainer): StyleEntry? =
        container.styleEntries.mapNotNull { (it.payload as? ContainerPayload.Style)?.data }.firstOrNull()

    /** The donor's own rasters — sprite widgets carry one per frame, others none. */
    private fun widgetRasters(style: StyleEntry, widgetIndex: Int): List<Raster> =
        try {
            BlankFaceBuilder.extractDonor(style, widgetIndex).donorRasters
        } catch (_: Exception) {
            emptyList()
        }

    /** Directory label for a donor widget: lowercase meaning. When several widgets
     *  share a meaning (two battery icons, say), each gets a unique
     *  `<meaning>_w<index>` directory so their art does not collide in the zip. */
    private fun labelOf(style: StyleEntry, widgetIndex: Int): String {
        val rasters = widgetRasters(style, widgetIndex)
        val meaning = WidgetMeaningCatalog.meaning(style.widgets[widgetIndex], rasters)
        val base = if (meaning == WidgetMeaning.UNKNOWN) "widget" else meaning.name.lowercase()
        val duplicated = style.widgets.indices.any { other ->
            other != widgetIndex && widgetRasters(style, other).isNotEmpty() &&
                baseOf(style, other) == base
        }
        return if (duplicated) "${base}_w$widgetIndex" else base
    }

    /** The label base of a widget — meaning lowercase, "widget" when unknown. */
    private fun baseOf(style: StyleEntry, widgetIndex: Int): String {
        val meaning = WidgetMeaningCatalog.meaning(style.widgets[widgetIndex], widgetRasters(style, widgetIndex))
        return if (meaning == WidgetMeaning.UNKNOWN) "widget" else meaning.name.lowercase()
    }

    /** A raster the exporter cannot round-trip: skip it rather than ship broken art. */
    private fun skipReason(r: Raster): SkipReason? = when {
        r.format != WatchFaceFmt.FORMAT_RGB565 && r.format != WatchFaceFmt.FORMAT_RGB565_A ->
            SkipReason.NON_IMAGE_FORMAT
        r.width <= 0 || r.height <= 0 ->
            SkipReason.INVALID_SIZE
        r.colorBytes.size != r.width * r.height * r.bpp ->
            SkipReason.INVALID_SIZE
        else -> null
    }

    /** A raster's pixels as ARGB_8888. RGB565 and RGB565_A decode inline — the format
     *  module is pure JVM, so the image module's bitmap decoder is not reachable here. */
    private fun argbOf(r: Raster): IntArray {
        val bpp = r.bpp
        val out = IntArray(r.width * r.height)
        for (i in out.indices) {
            val off = i * bpp
            val half = (r.pixels[off].toInt() and 0xFF) or ((r.pixels[off + 1].toInt() and 0xFF) shl 8)
            val rr = ((half ushr 11) and 0x1F) shl 3
            val gg = ((half ushr 5) and 0x3F) shl 2
            val bb = (half and 0x1F) shl 3
            out[i] = if (bpp == 3) {
                val a = r.pixels[off + 2].toInt() and 0xFF
                (a shl 24) or (rr shl 16) or (gg shl 8) or bb
            } else {
                0xFF000000.toInt() or (rr shl 16) or (gg shl 8) or bb
            }
        }
        return out
    }
}
