package com.galaxyfit3.core.formattest

import com.galaxyfit3.core.format.WatchFaceFormat
import com.galaxyfit3.core.format.model.ContainerPayload
import com.galaxyfit3.core.format.model.DirectoryEntry
import com.galaxyfit3.core.format.model.Raster
import com.galaxyfit3.core.format.model.StyleEntry
import com.galaxyfit3.core.format.model.WatchFaceFmt
import com.galaxyfit3.core.format.model.WidgetMeaning
import com.galaxyfit3.core.format.model.WidgetRecord
import com.galaxyfit3.core.format.model.WidgetType
import com.galaxyfit3.core.format.parser.BlankFaceBuilder
import com.galaxyfit3.core.format.parser.PlacedWidget
import com.galaxyfit3.core.format.parser.WatchFaceParser
import com.galaxyfit3.core.format.parser.WidgetMeaningCatalog
import com.galaxyfit3.core.format.parser.WatchFaceSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression: stock faces point their background widget at raster offset 0
 * (wordA == 0). Both the meaning catalog and extractDonor treated offset <= 0
 * as "no raster", so the stock background raster was dropped on every rebuild:
 * after any edit the preview lost the face's own background image, and donors
 * whose rasters started at offset 0 rendered without their image.
 */
class DonorBackgroundRoundTripTest {

    private fun bgRaster(): Raster = Raster(
        WatchFaceFormat.PANEL_WIDTH, WatchFaceFormat.PANEL_HEIGHT,
        WatchFaceFmt.FORMAT_RGB565,
        ByteArray(WatchFaceFormat.PANEL_WIDTH * WatchFaceFormat.PANEL_HEIGHT * WatchFaceFmt.BPP_RGB565 + 4) {
            (it * 13 and 0xFF).toByte()
        }
    )

    private fun digitRaster(): Raster = Raster(
        40, 62, WatchFaceFmt.FORMAT_RGB565,
        ByteArray(40 * 62 * WatchFaceFmt.BPP_RGB565 + 4) { (it * 7 and 0xFF).toByte() }
    )

    /** Background widget at offset 0 (wordA == 0) + a two-frame digit sprite. */
    private fun stockStyle(): StyleEntry {
        val bg = bgRaster()
        val digit = digitRaster()
        val digitOff = bg.encodedSize()
        return StyleEntry(
            widgetCount = 2,
            widgetBytes = 40 + 44, // STATIC 40 B (1 word), SPRITE 44 B (2 words)
            imageBytes = bg.encodedSize() + digit.encodedSize(),
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
                    wordA = 2L, words = listOf(digitOff, digitOff)
                )
            ),
            rasters = listOf(bg, digit)
        )
    }

    @Test
    fun catalogDetectsOffsetZeroBackground() {
        val style = stockStyle()
        assertEquals(WidgetMeaning.BACKGROUND, WidgetMeaningCatalog.meaning(style.widgets[0], style.rasters))
    }

    @Test
    fun extractDonorKeepsBackgroundRaster() {
        val style = stockStyle()
        val donor = BlankFaceBuilder.extractDonor(style, 0)
        assertEquals(1, donor.donorRasters.size)
        assertEquals(style.rasters[0], donor.donorRasters[0])
    }

    @Test
    fun buildStylePreservesStockBackgroundWithoutPhoto() {
        val style = stockStyle()
        val placed = listOf(
            BlankFaceBuilder.extractDonor(style, 0),
            BlankFaceBuilder.extractDonor(style, 1)
        )
        val built = BlankFaceBuilder.buildStyle(placed, background = null)

        // Both rasters ride along, background first at offset 0.
        assertEquals(2, built.rasters.size)
        assertEquals(style.rasters[0], built.rasters[0])
        assertEquals(style.rasters[1], built.rasters[1])

        val builtBg = built.widgets[0]
        assertEquals(0, builtBg.wordA.toInt())

        val digitOff = built.rasters[0].encodedSize()
        val builtSprite = built.widgets[1]
        assertEquals(listOf(digitOff, digitOff), builtSprite.words)
    }

    @Test
    fun buildStyleWithCustomBackgroundEmitsReferencingWidget() {
        val style = stockStyle()
        val donor = BlankFaceBuilder.extractDonor(style, 1)
        val customBg = bgRaster().withPixels(ByteArray(bgRaster().pixels.size) { 0x7F })
        val built = BlankFaceBuilder.buildStyle(
            listOf(PlacedWidget(donor.donor, donor.donorRasters, donor.donor.x, donor.donor.y)),
            customBg
        )

        // Custom background raster opens the image section at offset 0, and widget 0
        // (STATIC at 0,0) must reference it, mirroring stock faces.
        assertEquals(0, built.widgets[0].wordA.toInt())
        assertEquals(WidgetType.STATIC, built.widgets[0].type)
        assertEquals(0, built.widgets[0].x)
        assertEquals(0, built.widgets[0].y)
        assertEquals(2, built.rasters.size)
        assertEquals(customBg.encodedSize(), built.rasters[0].encodedSize())

        assertEquals(listOf(digitRaster().encodedSize()), built.rasters.drop(1).map { it.encodedSize() })
    }

    @Test
    fun rebuiltStyleReparsesWithIntactRasters() {
        val style = stockStyle()
        val placed = listOf(
            BlankFaceBuilder.extractDonor(style, 0),
            BlankFaceBuilder.extractDonor(style, 1)
        )
        val built = BlankFaceBuilder.buildStyle(placed, background = null)
        val bytes = WatchFaceSerializer.serializeEntries(
            WatchFaceFormat.VERSION,
            listOf(
                com.galaxyfit3.core.format.model.ContainerEntry(
                    DirectoryEntry("./SM-R390_90001_256x402/style0.bin", 0, built.entrySize, 0),
                    ContainerPayload.Style(built)
                )
            )
        )
        val parsed = WatchFaceParser.parse(bytes)
        val s = (parsed.entries[0].payload as ContainerPayload.Style).data
        assertTrue(s.widgets[0].wordA.toInt() == 0)
        assertEquals(listOf(WatchFaceFmt.FORMAT_RGB565, WatchFaceFmt.FORMAT_RGB565), s.rasters.map { it.format })
        assertEquals(style.rasters[0].pixels.toList(), s.rasters[0].pixels.toList())
    }
}
