package com.galaxyfit3.core.formattest

import com.galaxyfit3.core.format.WatchFaceFormat
import com.galaxyfit3.core.format.model.*
import com.galaxyfit3.core.format.parser.BlankFaceBuilder
import com.galaxyfit3.core.format.parser.PlacedWidget
import com.galaxyfit3.core.format.parser.WatchFaceParser
import com.galaxyfit3.core.format.parser.WatchFaceSerializer
import org.junit.Assert.*
import org.junit.Test

/**
 * Regression for per-frame image replacement: the editor swaps a sprite frame's raster
 * (same dimensions and format) before rebuilding, and BlankFaceBuilder must carry the
 * replacement into the image section, remap the widget's offsets, and leave every other
 * frame byte-for-byte stock.
 */
class FrameOverrideRoundTripTest {

    private fun solidRaster(w: Int, h: Int, format: Int, fill: Byte): Raster {
        val bpp = if (format == WatchFaceFmt.FORMAT_RGB565_A) WatchFaceFmt.BPP_RGB565_A else WatchFaceFmt.BPP_RGB565
        val data = ByteArray(w * h * bpp + WatchFaceFmt.RASTER_TRAILER_SIZE)
        data.fill(fill, 0, data.size - WatchFaceFmt.RASTER_TRAILER_SIZE)
        return Raster(w, h, format, data)
    }

    /** 3-frame sprite whose words are the (sequential) raster offsets. */
    private fun spriteWidget(offsets: List<Int>): WidgetRecord {
        val base = offsets.first()
        return WidgetRecord(
            type = WidgetType.SPRITE,
            sequenceId = WatchFaceFormat.SEQ_HOUR_ONES,
            opaque1 = 0,
            globalIndex = 7,
            recordSize = 36 + offsets.size * 4,
            opaque2 = 0,
            x = 60, y = 100,
            wOrX2 = 24, hOrY2 = 40,
            wordA = offsets.size.toLong(),
            words = offsets
        )
    }

    @Test
    fun replacedFrameSurvivesRebuildAndRoundTrip() {
        val frames = listOf(
            solidRaster(24, 40, WatchFaceFmt.FORMAT_RGB565_A, 0x11),
            solidRaster(24, 40, WatchFaceFmt.FORMAT_RGB565_A, 0x22),
            solidRaster(24, 40, WatchFaceFmt.FORMAT_RGB565_A, 0x33)
        )
        // Style rasters: background first (offset 0), then the sprite frames.
        val bg = solidRaster(64, 64, WatchFaceFmt.FORMAT_RGB565, 0)
        val style = StyleEntry(
            widgetCount = 2,
            widgetBytes = 40 + (36 + 3 * 4),
            imageBytes = (listOf(bg) + frames).sumOf { it.encodedSize() },
            headerUnknown = 0x400,
            widgets = listOf(
                WidgetRecord(
                    WidgetType.STATIC, 0, 0, 1, 40, 0, 0, 0, 64, 64,
                    0L, listOf(0)
                ),
                spriteWidget(listOf(bg.encodedSize(), bg.encodedSize() + frames[0].encodedSize(),
                    bg.encodedSize() + frames[0].encodedSize() * 2))
            ),
            rasters = listOf(bg) + frames
        )

        // --- the editor's override: swap frame 1 for a same-shape replacement ---
        val replacedPixels = ByteArray(frames[1].length)
        java.util.Arrays.fill(replacedPixels, 0, replacedPixels.size - WatchFaceFmt.RASTER_TRAILER_SIZE, 0x77.toByte())
        replacedPixels[replacedPixels.size - 4] = frames[1].trailer[0] // keep stock trailer
        replacedPixels[replacedPixels.size - 3] = frames[1].trailer[1]
        replacedPixels[replacedPixels.size - 2] = frames[1].trailer[2]
        replacedPixels[replacedPixels.size - 1] = frames[1].trailer[3]
        val replacement = frames[1].withPixels(replacedPixels)
        val overridden = listOf(frames[0], replacement, frames[2])

        val donor = BlankFaceBuilder.extractDonor(style, 1)
        assertEquals(frames.size, donor.donorRasters.size)

        val built = BlankFaceBuilder.buildStyle(
            listOf(BlankFaceBuilder.extractDonor(style, 0), PlacedWidget(donor.donor, overridden, donor.donor.x, donor.donor.y)),
            null
        )

        // Image section must now contain bg + frame0 + replacement + frame2.
        assertEquals(4, built.rasters.size)
        assertArrayEquals(replacement.pixels, built.rasters[2].pixels) // replacement embedded
        assertEquals(frames[1].width, built.rasters[2].width)
        assertEquals(frames[1].format, built.rasters[2].format)

        // The sprite's offset words must point at the replaced raster.
        val sprite = built.widgets[1]
        assertEquals(3, sprite.words.size)
        val replacementOffset = sprite.words[1].toInt()
        val offsetToIndex = HashMap<Int, Int>()
        var running = 0
        built.rasters.forEachIndexed { i, r ->
            offsetToIndex[running] = i
            running += r.encodedSize()
        }
        assertEquals(2, offsetToIndex[replacementOffset]) // index of the replacement raster

        // Full container round trip: replacement pixels survive, others stay stock.
        val bytes = WatchFaceSerializer.serializeEntries(
            WatchFaceFormat.VERSION,
            listOf(
                ContainerEntry(
                    DirectoryEntry("./SM-R390_90001_256x402/style0.bin", 0, built.entrySize, 0),
                    ContainerPayload.Style(built)
                )
            )
        )
        val reparsed = WatchFaceParser.parse(bytes)
        val s = (reparsed.entries[0].payload as ContainerPayload.Style).data
        assertArrayEquals(frames[0].pixels, s.rasters[1].pixels)
        assertArrayEquals(replacement.pixels, s.rasters[2].pixels)
        assertArrayEquals(frames[2].pixels, s.rasters[3].pixels)
    }
}
