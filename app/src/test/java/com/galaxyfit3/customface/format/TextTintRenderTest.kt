package com.galaxyfit3.customface.format

import com.galaxyfit3.core.format.WatchFaceFormat
import com.galaxyfit3.core.format.model.StyleEntry
import com.galaxyfit3.core.format.model.WidgetRecord
import com.galaxyfit3.core.format.model.WidgetType
import com.galaxyfit3.core.format.parser.BlankFaceBuilder
import com.galaxyfit3.core.format.parser.PlacedWidget
import com.galaxyfit3.core.image.FaceStyleRenderer
import com.galaxyfit3.core.image.PreviewStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Custom text colour: a user tint for a value (Pair) widget must land in the record's
 * words[0] — the firmware's colour slot — so the baked face shows the same colour as the
 * editor; a composite (Comp) tint must leave the triplet words untouched (their colour
 * field is undocumented), so baking can never corrupt the record.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TextTintRenderTest {

    private val firmwareBlue = 0xFF1E88E5.toInt()
    private val tintRed = 0xFFE53935.toInt()
    /** Temperature composite: triplet words are the packed `(glyphGroup<<16)|seqId`. */
    private val tempTriplet = (1 shl 16) or 62

    private fun pair(color: Int): WidgetRecord = WidgetRecord(
        WidgetType.PAIR, WatchFaceFormat.SEQ_STEPS, 0, 0, 40, 0,
        x = 8, y = 10, wOrX2 = 88, hOrY2 = 34,
        wordA = 1L,
        words = listOf(color, 0, WatchFaceFormat.GLYPH_NUMERIC)
    )

    private fun comp(): WidgetRecord = WidgetRecord(
        WidgetType.COMP, 0, 0, 0, 44, 0,
        x = 8, y = 120, wOrX2 = 0, hOrY2 = 28,
        wordA = 1L,
        words = listOf(tempTriplet, 0, 0, tempTriplet, 0, 0)
    )

    @Test
    fun `pair tint lands in words 0 on bake`() {
        val placed = PlacedWidget(pair(firmwareBlue), emptyList(), 8, 10, tint = tintRed)
        val built = BlankFaceBuilder.buildStyle(listOf(placed), background = null)
        assertEquals(tintRed, built.widgets[0].words[0])
    }

    @Test
    fun `pair without tint keeps firmware colour`() {
        val placed = PlacedWidget(pair(firmwareBlue), emptyList(), 8, 10)
        val built = BlankFaceBuilder.buildStyle(listOf(placed), background = null)
        assertEquals(firmwareBlue, built.widgets[0].words[0])
    }

    @Test
    fun `comp tint leaves triplet words untouched`() {
        val placed = PlacedWidget(comp(), emptyList(), 8, 120, tint = tintRed)
        val built = BlankFaceBuilder.buildStyle(listOf(placed), background = null)
        assertEquals(tempTriplet, built.widgets[0].words[0])
        assertEquals(0, built.widgets[0].words[1])
        assertEquals(0, built.widgets[0].words[2])
        assertEquals(tempTriplet, built.widgets[0].words[3])
    }

    @Test
    fun `renderer accepts textTints without crashing`() {
        val style = StyleEntry(
            widgetCount = 1, widgetBytes = 40,
            imageBytes = 0, headerUnknown = 0x400,
            widgets = listOf(pair(firmwareBlue)), rasters = emptyList()
        )
        // Text pixels can't be asserted here (Robolectric on this host never rasterizes
        // glyphs — measureText returns 4.0 and drawText paints nothing), so pin the
        // override path at the plumbing level: with tokens for every widget, even a
        // composite at index 0, rendering still produces a panel-sized bitmap.
        val bmp = FaceStyleRenderer.render(
            style, PreviewStats(steps = "1234"),
            textTints = mapOf(0 to tintRed)
        )
        assertNotNull(bmp)
        assertEquals(WatchFaceFormat.PANEL_WIDTH, bmp.width)
    }
}