package com.galaxyfit3.customface.format

import com.galaxyfit3.core.format.WatchFaceFormat
import com.galaxyfit3.core.format.model.Raster
import com.galaxyfit3.core.format.model.WatchFaceFmt
import com.galaxyfit3.core.format.model.WidgetRecord
import com.galaxyfit3.core.format.model.WidgetType
import com.galaxyfit3.core.format.parser.BlankFaceBuilder
import com.galaxyfit3.core.format.parser.PlacedWidget
import com.galaxyfit3.core.format.model.StyleEntry
import com.galaxyfit3.core.image.FaceStyleRenderer
import com.galaxyfit3.core.image.PreviewStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Arc (16), LineBar (17) and Badge (7) records were invisible in previews and — worse
 * for Arc/LineBar — their image-section pointers were never remapped on bake, so any
 * edit that shifted the image section left them drawing nothing on the watch. These
 * tests pin the pointer schema fitface-studio's corpus established:
 * Arc words[4], LineBar words[2], Badge thickness in words[3] low byte.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NonSpriteWidgetRenderTest {

    private fun raster(w: Int, h: Int): Raster = Raster(
        w, h, WatchFaceFmt.FORMAT_RGB565,
        ByteArray(w * h * WatchFaceFmt.BPP_RGB565 + 4)
    )

    private fun stripRaster(): Raster = raster(138, 14)

    /** A LineBar pointing at the strip raster through words[2], geometry in words[0..1]. */
    private fun lineBar(stripOffset: Int): WidgetRecord = WidgetRecord(
        WidgetType.LINE_BAR, 44, 0, 20, 48, 0,
        x = 10, y = 300, wOrX2 = 148, hOrY2 = 314,
        wordA = 0L,
        words = listOf(0x0064, 0x000A, stripOffset, 0x0000003C)
    )

    @Test
    fun `extractDonor resolves linebar raster`() {
        val strip = stripRaster()
        val style = StyleEntry(
            widgetCount = 1, widgetBytes = 48,
            imageBytes = strip.encodedSize(), headerUnknown = 0x400,
            widgets = listOf(lineBar(0)), rasters = listOf(strip)
        )
        val donor = BlankFaceBuilder.extractDonor(style, 0)
        assertEquals(1, donor.donorRasters.size)
        assertEquals(strip, donor.donorRasters[0])
        assertTrue(donor.needsRasterCopy)
    }

    @Test
    fun `linebar pointer is remapped when the image section shifts`() {
        val strip = stripRaster()
        val padding = raster(50, 30)
        val style = StyleEntry(
            widgetCount = 1, widgetBytes = 48,
            imageBytes = padding.encodedSize() + strip.encodedSize(), headerUnknown = 0x400,
            widgets = listOf(lineBar(padding.encodedSize())),
            rasters = listOf(padding, strip)
        )
        val donor = BlankFaceBuilder.extractDonor(style, 0)
        val placed = PlacedWidget(donor.donor, donor.donorRasters, x = 12, y = 298)
        val built = BlankFaceBuilder.buildStyle(listOf(placed), background = null)

        // The strip now sits after the widget's own copy of nothing else — one raster only,
        // so its new offset is 0 and words[2] must say 0, not the seed's padded offset.
        assertEquals(0, built.widgets[0].words[2])
        assertEquals(12, built.widgets[0].x)
        assertEquals(298, built.widgets[0].y)
    }

    @Test
    fun `arc pointer is remapped and geometry words survive`() {
        val fill = raster(310, 310)
        val padding = raster(50, 30)
        val arc = WidgetRecord(
            WidgetType.ARC, 30, 0, 15, 52, 0,
            x = -27, y = 46, wOrX2 = 310, hOrY2 = 310,
            wordA = 0L,
            words = listOf(0x0100, 0x0002, 0x003C, 0x005A, padding.encodedSize())
        )
        val style = StyleEntry(
            widgetCount = 1, widgetBytes = 52,
            imageBytes = padding.encodedSize() + fill.encodedSize(), headerUnknown = 0x400,
            widgets = listOf(arc), rasters = listOf(padding, fill)
        )
        val donor = BlankFaceBuilder.extractDonor(style, 0)
        assertEquals(1, donor.donorRasters.size)
        assertEquals(fill, donor.donorRasters[0])

        val placed = PlacedWidget(donor.donor, donor.donorRasters, x = -27, y = 46)
        val built = BlankFaceBuilder.buildStyle(listOf(placed), background = null)
        // words[4] now points at the arc's own fill raster at offset 0; the geometry
        // words 0..3 ride along untouched.
        assertEquals(0, built.widgets[0].words[4])
        assertEquals(0x0100, built.widgets[0].words[0])
        assertEquals(0x005A, built.widgets[0].words[3])
    }

    @Test
    fun `renderer draws arc and linebar rasters`() {
        val strip = stripRaster()
        val fill = raster(120, 120)
        val bar = lineBar(0)
        val arc = WidgetRecord(
            WidgetType.ARC, 30, 0, 15, 52, 0,
            x = 60, y = 100, wOrX2 = 120, hOrY2 = 120,
            wordA = 0L, words = listOf(0x0100, 0x0002, 0x003C, 0x005A, strip.encodedSize())
        )
        val style = StyleEntry(
            widgetCount = 2, widgetBytes = 48 + 52,
            imageBytes = strip.encodedSize() + fill.encodedSize(), headerUnknown = 0x400,
            widgets = listOf(bar, arc),
            rasters = listOf(strip, fill)
        )
        val bmp = FaceStyleRenderer.render(style, PreviewStats())
        // Rasters start zero-filled; paint opaque pixels in each and verify they land
        // at the widget's x/y on the canvas.
        strip.setOpaque(0xFF112233.toInt())
        fill.setOpaque(0xFF445566.toInt())
        val rendered = FaceStyleRenderer.render(style, PreviewStats())
        // RGB565 quantizes each channel, so compare with 5-bit tolerance.
        fun assertNear(actual: Int, expected: Int, what: String) {
            assertEquals("$what alpha", (expected ushr 24) and 0xFF, (actual ushr 24) and 0xFF)
            assertEquals("$what red", (expected shr 16) and 0xF8, (actual shr 16) and 0xF8)
            assertEquals("$what green", (expected shr 8) and 0xFC, (actual shr 8) and 0xFC)
            assertEquals("$what blue", expected and 0xF8, actual and 0xF8)
        }
        assertNear(rendered.getPixel(10, 300), 0xFF112233.toInt(), "bar") // bar x/y
        assertNear(rendered.getPixel(60, 100), 0xFF445566.toInt(), "arc") // arc x/y
    }

    private fun Raster.setOpaque(argb: Int) {
        // A 1x1-scale fill is enough: paint the first row of pixels via the 565 encoding
        // of a solid color, overwriting only pixel bytes (trailer untouched).
        val half = com.galaxyfit3.core.image.Rgb565.toRgb565(
            (argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF
        )
        for (i in 0 until width) {
            val off = i * bpp
            pixels[off] = (half and 0xFF).toByte()
            pixels[off + 1] = ((half shr 8) and 0xFF).toByte()
        }
    }
}
