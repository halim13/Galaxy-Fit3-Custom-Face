package com.galaxyfit3.core.formattest

import com.galaxyfit3.core.format.WatchFaceFormat
import com.galaxyfit3.core.format.model.*
import com.galaxyfit3.core.format.parser.Crc16
import com.galaxyfit3.core.format.parser.WatchFaceParser
import com.galaxyfit3.core.format.parser.WatchFaceSerializer
import com.galaxyfit3.core.format.validator.FaceValidator
import org.junit.Assert.*
import org.junit.Test

class ContainerRoundTripTest {

    private fun backgroundRaster(): Raster {
        val w = WatchFaceFormat.PANEL_WIDTH
        val h = WatchFaceFormat.PANEL_HEIGHT
        val bpp = WatchFaceFmt.BPP_RGB565
        val data = ByteArray(w * h * bpp + 4)
        // Fill with a simple gradient so re-encoding is observable.
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = (y * w + x) * bpp
                val rb = (x * 255 / w) and 0xFF
                val gb = (y * 255 / h) and 0xFF
                data[i] = rb.toByte()
                data[i + 1] = ((gb and 0xF8)).toByte()
            }
        }
        return Raster(w, h, WatchFaceFmt.FORMAT_RGB565, data)
    }

    private fun pairWidget(): WidgetRecord {
        // A minimal Pair (value) record: 36-byte head + 4 type words (52 bytes).
        val words = listOf(0xFFFFFFFF.toInt(), 0x00000000, 0x00010005, 0x00000000)
        return WidgetRecord(
            type = WidgetType.PAIR,
            sequenceId = 29, // steps
            opaque1 = 0,
            globalIndex = 0,
            recordSize = 52,
            opaque2 = 0,
            x = 16, y = 20,
            wOrX2 = 0, hOrY2 = 0,
            wordA = 1L, // anchor mode left
            words = words
        )
    }

    private fun staticWidget(offset: Int): WidgetRecord {
        // Image widget pointing at a raster (the background at offset 0).
        return WidgetRecord(
            type = WidgetType.STATIC,
            sequenceId = 0,
            opaque1 = 0,
            globalIndex = 1,
            recordSize = 40,
            opaque2 = 0,
            x = 0, y = 0,
            wOrX2 = WatchFaceFormat.PANEL_WIDTH,
            hOrY2 = WatchFaceFormat.PANEL_HEIGHT,
            wordA = offset.toLong(),
            words = listOf(0)
        )
    }

    private fun buildMinimalContainer(): ByteArray {
        val bg = backgroundRaster()
        val style = StyleEntry(
            widgetCount = 2,
            widgetBytes = 40 + 52,
            imageBytes = bg.encodedSize(),
            headerUnknown = 0x400,
            widgets = listOf(staticWidget(0), pairWidget()),
            rasters = listOf(bg)
        )

        val entries = listOf(
            ContainerEntry(
                DirectoryEntry("./SM-R390_90001_256x402/setting.bin", 0, WatchFaceFormat.SETTING_SIZE, 0),
                ContainerPayload.Setting(SettingBin("90001", 40000, 1, 0xFFFF, "SM-R390_90001_256x402"))
            ),
            ContainerEntry(
                DirectoryEntry("./SM-R390_90001_256x402/style0.bin", 0, style.entrySize, 0),
                ContainerPayload.Style(style)
            ),
            ContainerEntry(
                DirectoryEntry("./SM-R390_90001_256x402/style1.bin", 0, style.entrySize, 0),
                ContainerPayload.Style(style)
            )
        )
        return WatchFaceSerializer.serializeEntries(WatchFaceFormat.VERSION, entries)
    }

    @Test
    fun crc16MatchesReferenceValue() {
        // binascii.crc_hqx(b"123456789", 0xFFFF) = 0x29B1
        assertEquals(0x29B1, Crc16.compute("123456789".toByteArray()))
    }

    @Test
    fun serializeThenParseMatches() {
        val bytes = buildMinimalContainer()
        val parsed = WatchFaceParser.parse(bytes)

        assertEquals("oppo", parsed.version.let { WatchFaceFormat.MAGIC })
        assertEquals(3, parsed.entries.size)
        assertEquals("style0.bin", parsed.entries[1].name)
        assertEquals(2, parsed.styleEntries.size)

        val style = parsed.entries[1].payload
        assertTrue(style is ContainerPayload.Style)
        val s = (style as ContainerPayload.Style).data
        assertEquals(2, s.widgets.size)
        assertEquals(1, s.rasters.size)
        assertTrue(s == WatchFaceParser.parse(bytes).entries[1].payload.let { (it as ContainerPayload.Style).data })
    }

    @Test
    fun reparseByteIdentical() {
        val bytes = buildMinimalContainer()
        val reencoded = WatchFaceSerializer.serialize(WatchFaceParser.parse(bytes))
        assertArrayEquals(bytes, reencoded)
    }

    @Test
    fun validationPasses() {
        val bytes = buildMinimalContainer()
        val result = FaceValidator.validate(bytes)
        assertTrue(result.errors.joinToString(), result.ok)
        assertEquals(bytes.size, result.sizeBytes)
    }

    @Test
    fun corruptedPayloadFailsValidation() {
        val bytes = buildMinimalContainer()
        // Flip a byte inside the style payload — the per-entry CRC must fail.
        bytes[bytes.size - 5] = (bytes[bytes.size - 5].toInt() xor 0xFF).toByte()
        val result = FaceValidator.validate(bytes)
        assertFalse(result.ok)
        assertTrue(result.errors.isNotEmpty())
    }

    @Test
    fun encodeGlyphTableFromRealFilename() {
        // Real faces ship the glyph table as "font_en.bin" (suffix included). The
        // parser's old regex forgot the suffix, so the entry fell through to Unknown
        // and each bake silently dropped it — value/composite text widgets lost their
        // strings on the watch. Regress the exact on-disk name.
        val table = GlyphTable(1, listOf(GlyphTable.GlyphGroup(0, "°"), GlyphTable.GlyphGroup(1, "一月")))
        val entries = listOf(
            ContainerEntry(
                DirectoryEntry("./SM-R390_90001_256x402/font_en.bin", 0, table.encode().size, 0),
                ContainerPayload.GlyphTable(table)
            )
        )
        val bytes = WatchFaceSerializer.serializeEntries(WatchFaceFormat.VERSION, entries)
        val parsed = WatchFaceParser.parse(bytes)
        assertTrue(parsed.entries[0].payload is ContainerPayload.GlyphTable)
        assertEquals(table, (parsed.entries[0].payload as ContainerPayload.GlyphTable).data)
    }

    /**
     * Regression: serializing a style with SEVERAL rasters used to write each
     * raster's pixels without advancing the ByteBuffer position, so every header
     * after the first landed inside the previous raster's pixels and the trailing
     * rasters' own slots stayed zero — reparsing then failed with
     * "Unknown raster format 0x0". Single-raster fixtures never caught it; real
     * faces (e.g. the stock "Wish" face, 19 rasters per style) did.
     */
    @Test
    fun multiRasterRoundTripIsByteIdentical() {
        val bg = backgroundRaster()
        val smallA = Raster(64, 84, WatchFaceFmt.FORMAT_RGB565_A,
            ByteArray(64 * 84 * WatchFaceFmt.BPP_RGB565_A + 4) { (it * 31 and 0xFF).toByte() })
        val smallB = Raster(30, 84, WatchFaceFmt.FORMAT_RGB565,
            ByteArray(30 * 84 * WatchFaceFmt.BPP_RGB565 + 4) { (it * 7 and 0xFF).toByte() })
        val rasters = listOf(bg, smallA, smallB)
        val style = StyleEntry(
            widgetCount = 3,
            widgetBytes = 40 + 40 + 52,
            imageBytes = rasters.sumOf { it.encodedSize() },
            headerUnknown = 0x100,
            widgets = listOf(staticWidget(0), staticWidget(bg.encodedSize()), pairWidget()),
            rasters = rasters
        )
        val bytes = WatchFaceSerializer.serializeEntries(
            WatchFaceFormat.VERSION,
            listOf(
                ContainerEntry(
                    DirectoryEntry("./SM-R390_90002_256x402/style0.bin", 0, style.entrySize, 0),
                    ContainerPayload.Style(style)
                )
            )
        )

        // Byte-identical re-encode, including after a parse (which switches rasters
        // to shared-source view mode — the path writePixelsTo must handle).
        assertArrayEquals(bytes, WatchFaceSerializer.serialize(WatchFaceParser.parse(bytes)))

        // Every raster must come back with its own format and pixel bytes.
        val parsed = WatchFaceParser.parse(bytes)
        val s = (parsed.entries[0].payload as ContainerPayload.Style).data
        assertEquals(listOf(0x0082, 0x0080, 0x0082).map { it }, s.rasters.map { it.format })
        assertArrayEquals(bg.pixels, s.rasters[0].pixels)
        assertArrayEquals(smallA.pixels, s.rasters[1].pixels)
        assertArrayEquals(smallB.pixels, s.rasters[2].pixels)
    }
}