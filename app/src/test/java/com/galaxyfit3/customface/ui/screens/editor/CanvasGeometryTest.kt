package com.galaxyfit3.customface.ui.screens.editor

import com.galaxyfit3.core.format.WatchFaceFormat
import com.galaxyfit3.core.format.model.WidgetMeaning
import com.galaxyfit3.core.format.model.WidgetRecord
import com.galaxyfit3.core.format.model.WidgetType
import com.galaxyfit3.customface.viewmodel.PlacedEditorWidget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The canvas geometry ported from fitface-studio: which widget a touch lands on, and how
 * a drag step clamps the widget's rectangle while keeping the finger's running total.
 */
class CanvasGeometryTest {

    private fun widget(
        type: Int,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        globalIndex: Int = 0
    ) = WidgetRecord(
        type = type,
        sequenceId = 0,
        opaque1 = 0,
        globalIndex = globalIndex,
        recordSize = 36,
        opaque2 = 0,
        x = x,
        y = y,
        wOrX2 = w,
        hOrY2 = h,
        wordA = 1L,
        words = emptyList()
    )

    private fun placed(
        id: Long,
        type: Int,
        x: Int,
        y: Int,
        w: Int,
        h: Int
    ) = PlacedEditorWidget(
        id = id,
        donorIndex = 0,
        x = x,
        y = y,
        meaning = WidgetMeaning.STATIC_LABEL,
        donor = widget(type, x, y, w, h)
    )

    private fun static(id: Long, x: Int, y: Int, w: Int, h: Int) =
        placed(id, WidgetType.STATIC, x, y, w, h)

    @Test
    fun `statics and sprites bind their stored position and size`() {
        val r = placedRect(static(1, 40, 50, 30, 20))!!
        assertEquals(40, r.left)
        assertEquals(50, r.top)
        assertEquals(70, r.right)
        assertEquals(70, r.bottom)
        assertEquals(30, r.width)
        assertEquals(20, r.height)
    }

    @Test
    fun `badge bounds both endpoints`() {
        val r = placedRect(placed(1, WidgetType.BADGE, 200, 50, 50, 80))!!
        assertEquals(50, r.left)
        assertEquals(50, r.top)
        assertEquals(200, r.right)
        assertEquals(80, r.bottom)
    }

    @Test
    fun `widgets without a stored size get a nominal touch box`() {
        // Clock hands, zero-extent statics and live-value widgets paint no stored
        // rectangle, but they must still be tappable and draggable: they fall back to
        // their raster box (via preview on device) or a 40x40 nominal box.
        val hand = placedRect(placed(1, WidgetType.HAND, 100, 100, 10, 10))!!
        assertEquals(100, hand.left)
        assertEquals(100, hand.top)
        assertEquals(140, hand.right)
        assertEquals(140, hand.bottom)

        val zeroExtent = placedRect(static(2, 10, 10, 0, 20))!!
        assertEquals(50, zeroExtent.right)
        assertEquals(50, zeroExtent.bottom)

        val comp = placedRect(placed(3, WidgetType.COMP, 10, 10, 0, 0))!!
        assertEquals(50, comp.right)
        assertEquals(50, comp.bottom)
    }

    @Test
    fun `text widgets bind their anchored cell`() {
        // Negative x/y anchor from the right/bottom edge (pair/composite convention);
        // the drag box must overlay the cell the renderer paints into.
        val pair = placedRect(placed(1, WidgetType.PAIR, -50, -20, 80, 24))!!
        assertEquals(WatchFaceFormat.PANEL_WIDTH - 130, pair.left)
        assertEquals(WatchFaceFormat.PANEL_HEIGHT - 44, pair.top)
        assertEquals(WatchFaceFormat.PANEL_WIDTH - 50, pair.right)
        assertEquals(WatchFaceFormat.PANEL_HEIGHT - 20, pair.bottom)

        val comp = placedRect(placed(2, WidgetType.COMP, -50, 120, 64, 22))!!
        assertEquals(WatchFaceFormat.PANEL_WIDTH - 114, comp.left)
        assertEquals(120, comp.top)
        assertEquals(WatchFaceFormat.PANEL_WIDTH - 50, comp.right)
        assertEquals(142, comp.bottom)
    }

    @Test
    fun `every widget has a rectangle so all are tappable`() {
        // Regression: value/composite widgets used to return null and were invisible
        // to both the tap hit-test and the selection marker.
        for (type in listOf(WidgetType.HAND, WidgetType.COMP, WidgetType.PAIR, WidgetType.ARC, WidgetType.LINE_BAR)) {
            assertNotNull(placedRect(placed(1, type, 50, 60, 0, 0)))
        }
    }

    @Test
    fun `hit test uses half-open bounds so abutting widgets stay distinct`() {
        val a = static(1, 10, 10, 5, 5)
        val b = static(2, 15, 10, 5, 5)
        val list = listOf(a, b)

        assertSame(a, hitPlaced(list, xF = 10f, yF = 10f, preferredId = null))
        assertSame(a, hitPlaced(list, xF = 14.9f, yF = 14.9f, preferredId = null))
        assertSame(b, hitPlaced(list, xF = 15f, yF = 10f, preferredId = null))
        assertNull(hitPlaced(list, xF = 20f, yF = 10f, preferredId = null))
    }

    @Test
    fun `smallest overlapping widget wins`() {
        val big = static(1, 0, 0, 20, 20)
        val small = static(2, 5, 5, 5, 5)
        val hit = hitPlaced(listOf(big, small), xF = 7f, yF = 7f, preferredId = null)
        assertSame(small, hit)
    }

    @Test
    fun `preferred selection wins only while it is not much larger`() {
        val bigger = static(1, 0, 0, 5, 4)
        val smaller = static(2, 1, 1, 4, 4)
        // 20 <= 16 * 5 / 4, so the current selection is kept.
        assertSame(bigger, hitPlaced(listOf(bigger, smaller), xF = 2f, yF = 2f, preferredId = 1L))

        val huge = static(3, 0, 0, 40, 40)
        val tiny = static(4, 5, 5, 2, 2)
        // 1600 > 4 * 5 / 4, so the smaller widget under the finger wins instead.
        assertSame(tiny, hitPlaced(listOf(huge, tiny), xF = 5f, yF = 5f, preferredId = 3L))
        assertSame(huge, hitPlaced(listOf(huge, tiny), xF = 20f, yF = 20f, preferredId = 3L))
    }

    @Test
    fun `drag step clamps the rectangle inside the face but never the finger total`() {
        // A 50-wide widget starting at x=100: it may travel from -100 to 106.
        assertEquals(13f to 113f, stepDragAxis(13f, start = 100f, low = 100, high = 150, faceExtent = 256))
        // Pushed far past the right edge the position stops at 206, while the total that
        // is handed back keeps accumulating — that is what stops the widget sticking.
        assertEquals(500f to 206f, stepDragAxis(500f, start = 100f, low = 100, high = 150, faceExtent = 256))
        // Coming back from an overshoot resumes from under the finger, not from the edge.
        assertEquals(10f to 110f, stepDragAxis(10f, start = 100f, low = 100, high = 150, faceExtent = 256))
    }

    @Test
    fun `a widget wider than the face pins to its left edge`() {
        assertEquals(50f to 0f, stepDragAxis(50f, start = 0f, low = 0, high = 300, faceExtent = 256))
    }
}
