package com.galaxyfit3.core.formattest

import com.galaxyfit3.core.format.WatchFaceFormat
import com.galaxyfit3.core.format.model.ContainerPayload
import com.galaxyfit3.core.format.model.DirectoryEntry
import com.galaxyfit3.core.format.model.Raster
import com.galaxyfit3.core.format.model.StyleEntry
import com.galaxyfit3.core.format.model.WatchFaceContainer
import com.galaxyfit3.core.format.model.WatchFaceFmt
import com.galaxyfit3.core.format.model.WidgetRecord
import com.galaxyfit3.core.format.model.WidgetType
import com.galaxyfit3.core.format.parser.AssetBundle
import com.galaxyfit3.core.format.parser.Png
import com.galaxyfit3.core.format.parser.WatchFaceParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The asset zip must round-trip: export names every editable frame by its widget
 * role (`assets/<label>/frame_N.png` + `assets/background.png`), an edited zip maps
 * back onto exactly those frames, and frames whose pixel size changed are dropped
 * rather than guessed.
 */
class AssetBundleTest {

    private fun raster(w: Int, h: Int, fill: Int, format: Int = WatchFaceFmt.FORMAT_RGB565): Raster {
        val bpp = WatchFaceFmt.BPP_RGB565
        return Raster(w, h, format, ByteArray(w * h * bpp + WatchFaceFmt.RASTER_TRAILER_SIZE) {
            ((it % (w * h * bpp)) * fill and 0xFF).toByte()
        })
    }

    /** A background (panel-size raster at offset 0) + a 2-frame sprite. */
    private fun seed(): WatchFaceContainer {
        val bg = raster(WatchFaceFormat.PANEL_WIDTH, WatchFaceFormat.PANEL_HEIGHT, 3)
        val f0 = raster(40, 62, 7)
        val f1 = raster(40, 62, 11)
        val digitOff = bg.encodedSize()
        val style = StyleEntry(
            widgetCount = 2,
            widgetBytes = 40 + 44,
            imageBytes = bg.encodedSize() + f0.encodedSize() + f1.encodedSize(),
            headerUnknown = 0x400,
            widgets = listOf(
                WidgetRecord(
                    WidgetType.STATIC, 0, 0, 0, 40, 0,
                    x = 0, y = 0, wOrX2 = WatchFaceFormat.PANEL_WIDTH, hOrY2 = WatchFaceFormat.PANEL_HEIGHT,
                    wordA = 0L, words = listOf(0)
                ),
                WidgetRecord(
                    WidgetType.SPRITE, 3, 0, 1, 44, 0,
                    x = 77, y = 50, wOrX2 = 0, hOrY2 = 10,
                    wordA = 2L, words = listOf(digitOff, digitOff + f0.encodedSize())
                )
            ),
            rasters = listOf(bg, f0, f1)
        )
        val bytes = com.galaxyfit3.core.format.parser.WatchFaceSerializer.serializeEntries(
            WatchFaceFormat.VERSION,
            listOf(
                com.galaxyfit3.core.format.model.ContainerEntry(
                    DirectoryEntry("./SM-R390_90001_256x402/style0.bin", 0, style.entrySize, 0),
                    ContainerPayload.Style(style)
                )
            )
        )
        return WatchFaceParser.parse(bytes)
    }

    private fun zipOf(files: Map<String, ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            files.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun entriesOf(zipBytes: ByteArray): Set<String> {
        val names = HashSet<String>()
        ZipInputStream(zipBytes.inputStream()).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (!e.isDirectory) names += e.name
                e = zip.nextEntry
            }
        }
        return names
    }

    private fun pngOf(w: Int, h: Int, argb: Int): ByteArray =
        Png.encode(w, h, IntArray(w * h) { argb })

    @Test
    fun exportContainsBackgroundAndEveryFrame() {
        val export = AssetBundle.exportZip(seed())
        val names = entriesOf(export.zip)
        assertTrue(names.contains("assets/background.png"))
        // The sprite's sequenceId 3 = SEQ_HOUR_ONES → TIME_DIGITS → "time_digits".
        assertTrue(names.contains("assets/time_digits/frame_0.png"))
        assertTrue(names.contains("assets/time_digits/frame_1.png"))
        assertEquals(2, export.frames.size)
    }

    @Test
    fun importMapsEditedFrameBackToItsSlot() {
        val container = seed()
        val export = AssetBundle.exportZip(container)
        // Simulate an external edit: replace frame_0 with a same-size PNG.
        val edited = HashMap<String, ByteArray>()
        ZipInputStream(export.zip.inputStream()).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (!e.isDirectory) edited[e.name] = z.readBytes()
                e = z.nextEntry
            }
        }
        edited["assets/time_digits/frame_0.png"] = pngOf(40, 62, 0xFF3366CC.toInt())

        val bundle = AssetBundle.importZip(zipOf(edited))
        val mapped = AssetBundle.mapToSeed(bundle, container)
        // Mapping is size-driven: every zip frame matching its seed raster maps back,
        // so the unedited frame_1 rides along and the background stays separate.
        assertEquals(setOf<Pair<Int, Int>>(1 to 0, 1 to 1), mapped.keys)
        val px = mapped[1 to 0]!!
        assertEquals(40, px.width)
        assertEquals(0xFF3366CC.toInt(), px.argb[0])
    }

    @Test
    fun importIgnoresSizeMismatchAndForeignFiles() {
        val container = seed()
        val export = AssetBundle.exportZip(container)
        val edited = HashMap<String, ByteArray>()
        ZipInputStream(export.zip.inputStream()).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (!e.isDirectory) edited[e.name] = z.readBytes()
                e = z.nextEntry
            }
        }
        // Same directory, wrong size — would corrupt the sprite atlas, must be dropped.
        edited["assets/time_digits/frame_0.png"] = pngOf(64, 64, 0xFF112233.toInt())
        // A file the seed has no slot for.
        edited["assets/time_digits/frame_9.png"] = pngOf(40, 62, 0xFF000000.toInt())
        edited["readme.txt"] = "hello".toByteArray()

        val bundle = AssetBundle.importZip(zipOf(edited))
        // The mis-sized frame_0 is dropped by the size check, frame_9 has no seed
        // slot — both silently unmatched. Only readme.txt lands in `ignored` (a name
        // the pattern does not recognize).
        assertEquals(setOf<Pair<Int, Int>>(1 to 1), AssetBundle.mapToSeed(bundle, container).keys)
        assertEquals(1, bundle.ignored.size)
    }

    @Test
    fun zipBackgroundOverridesSeedBackgroundInExport() {
        val container = seed()
        val export = AssetBundle.exportZip(container)
        // The ViewModel layers the effective background on top of the export; here we
        // verify the map/apply side accepts a zip whose background was swapped.
        val edited = HashMap<String, ByteArray>()
        ZipInputStream(export.zip.inputStream()).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (!e.isDirectory) edited[e.name] = z.readBytes()
                e = z.nextEntry
            }
        }
        edited["assets/background.png"] = pngOf(WatchFaceFormat.PANEL_WIDTH, WatchFaceFormat.PANEL_HEIGHT, 0xFF00FF00.toInt())

        val bundle = AssetBundle.importZip(zipOf(edited))
        val bg = AssetBundle.mapBackground(bundle)
        assertNotNull(bg)
        assertEquals(WatchFaceFormat.PANEL_WIDTH, bg!!.width)
        assertEquals(0xFF00FF00.toInt(), bg.argb[0])
        // The frame mapping is unaffected by the background entry.
        assertEquals(
            setOf<Pair<Int, Int>>(1 to 0, 1 to 1),
            AssetBundle.mapToSeed(bundle, container).keys
        )
    }

    @Test
    fun pngCodecRoundTripsRgba() {
        val w = 5; val h = 3
        val px = IntArray(w * h) { ((it * 0x04030201L) or 0x80000000L).toInt() }
        val decoded = Png.decode(Png.encode(w, h, px))
        assertNotNull(decoded)
        assertEquals(w, decoded!!.width)
        assertEquals(h, decoded.height)
        px.forEachIndexed { i, expected -> assertEquals(expected, decoded.argb[i]) }
    }

    @Test
    fun pngDecodeRejectsGarbage() {
        assertNull(Png.decode("not a png".toByteArray()))
        assertNull(Png.decode(ByteArray(0)))
    }

    @Test
    fun duplicatedMeaningWidgetsGetDistinctLabels() {
        // Two widgets with the same meaning label (the second STATIC widget also maps
        // to "background" through its panel-size raster) must not collide in the zip.
        val bg = raster(WatchFaceFormat.PANEL_WIDTH, WatchFaceFormat.PANEL_HEIGHT, 3)
        val style = StyleEntry(
            widgetCount = 2,
            widgetBytes = 80,
            imageBytes = bg.encodedSize(),
            headerUnknown = 0x400,
            widgets = listOf(
                WidgetRecord(
                    WidgetType.STATIC, 0, 0, 0, 40, 0,
                    x = 0, y = 0, wOrX2 = WatchFaceFormat.PANEL_WIDTH, hOrY2 = WatchFaceFormat.PANEL_HEIGHT,
                    wordA = 0L, words = listOf(0)
                ),
                WidgetRecord(
                    WidgetType.STATIC, 0, 0, 0, 40, 0,
                    x = 10, y = 10, wOrX2 = WatchFaceFormat.PANEL_WIDTH, hOrY2 = WatchFaceFormat.PANEL_HEIGHT,
                    wordA = 0L, words = listOf(0)
                )
            ),
            rasters = listOf(bg)
        )
        val bytes = com.galaxyfit3.core.format.parser.WatchFaceSerializer.serializeEntries(
            WatchFaceFormat.VERSION,
            listOf(
                com.galaxyfit3.core.format.model.ContainerEntry(
                    DirectoryEntry("./SM-R390_90001_256x402/style0.bin", 0, style.entrySize, 0),
                    ContainerPayload.Style(style)
                )
            )
        )
        val parsed = WatchFaceParser.parse(bytes)
        val export = AssetBundle.exportZip(parsed)
        val names = entriesOf(export.zip)
        // Both widgets share the label "background", so the dedup suffix kicks in for
        // both — no collision, and no plain "background.png" claiming either slot.
        assertTrue(names.contains("assets/background_w0/frame_0.png"))
        assertTrue(names.contains("assets/background_w1/frame_0.png"))
    }
}
