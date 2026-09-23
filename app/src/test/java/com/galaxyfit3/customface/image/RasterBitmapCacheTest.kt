package com.galaxyfit3.customface.image

import com.galaxyfit3.core.format.WatchFaceFormat
import com.galaxyfit3.core.format.model.Raster
import com.galaxyfit3.core.format.model.WatchFaceFmt
import com.galaxyfit3.core.image.RasterBitmapCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RasterBitmapCacheTest {

    private fun raster(width: Int, height: Int, fill: Int = 0): Raster {
        val data = ByteArray(width * height * 2 + WatchFaceFmt.RASTER_TRAILER_SIZE)
        data[0] = fill.toByte()
        return Raster(width, height, WatchFaceFmt.FORMAT_RGB565, data)
    }

    @Test
    fun `a widget raster is decoded once and reused`() {
        val cache = RasterBitmapCache()
        val widget = raster(24, 24)
        assertEquals(24, cache.bitmap(widget).width)
        assertSame(cache.bitmap(widget), cache.bitmap(widget))
    }

    @Test
    fun `panel sized rasters are decoded per call`() {
        val cache = RasterBitmapCache()
        val background = raster(WatchFaceFormat.PANEL_WIDTH, WatchFaceFormat.PANEL_HEIGHT)
        // Rebuilt from the user's photo on every edit, so caching it would only grow.
        assertNotSame(cache.bitmap(background), cache.bitmap(background))
    }

    @Test
    fun `the cache does not grow past its bound`() {
        val cache = RasterBitmapCache(maxEntries = 4)
        val first = raster(16, 16, 1)
        val firstDecoded = cache.bitmap(first)
        assertSame(firstDecoded, cache.bitmap(first))

        (2..8).forEach { cache.bitmap(raster(16, 16, it)) }

        // Past the bound the map is dropped, so even a raster decoded earlier is re-decoded
        // rather than kept alive by a cache that grew without limit.
        assertNotSame(firstDecoded, cache.bitmap(first))
    }
}
