package com.galaxyfit3.core.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import com.galaxyfit3.core.format.model.Raster
import com.galaxyfit3.core.format.model.WatchFaceFmt

/** Utilities for converting Android bitmaps to and from the watch's raster formats. */
object Rgb565 {

    /** Convert a Bitmap (ARGB_8888) to an RGB565 raster, placing the image per [mode]:
     *  "cover" (fill + centre-crop), "fit" (letterbox), "stretch", "center" (no scale),
     *  or "manual@<scale>:<tx>:<ty>" — the user's pinch-zoom + pan, encoded in the mode
     *  string so it survives project save/load like any other fit value. The manual
     *  transform is normalized to a 1x1 unit space so the same numbers reproduce the
     *  exact framing at any raster size (preview and encoded raster match). */
    fun toRaster565(
        bitmap: Bitmap,
        width: Int,
        height: Int,
        withAlpha: Boolean = false,
        mode: String = "cover"
    ): Raster {
        val scaled = if (mode.startsWith(MANUAL_PREFIX)) {
            manualPlacement(bitmap, width, height, mode)
        } else {
            scaledForMode(bitmap, width, height, mode)
        }
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)

        val format = if (withAlpha) WatchFaceFmt.FORMAT_RGB565_A else WatchFaceFmt.FORMAT_RGB565
        val bpp = if (withAlpha) WatchFaceFmt.BPP_RGB565_A else WatchFaceFmt.BPP_RGB565
        // w*h*bpp pixel bytes + 4-byte trailer.
        val data = ByteArray(width * height * bpp + WatchFaceFmt.RASTER_TRAILER_SIZE)

        for (i in pixels.indices) {
            // getPixels reads back unpremultiplied, so a near-transparent antialiased
            // edge (alpha 1-3) comes out near-white; dropping alpha would then bake a
            // white fringe around the image. Composite over black first.
            val argb = if (withAlpha) pixels[i] else flattenOverBlack(pixels[i])
            val r = (argb ushr 16) and 0xFF
            val g = (argb ushr 8) and 0xFF
            val b = argb and 0xFF
            val alpha = (argb ushr 24) and 0xFF

            val rgb565 = toRgb565(r, g, b)
            val off = i * bpp
            data[off] = (rgb565 and 0xFF).toByte()
            data[off + 1] = ((rgb565 ushr 8) and 0xFF).toByte()
            if (withAlpha) {
                data[off + 2] = alpha.toByte()
            }
        }
        return Raster(width, height, format, data)
    }

    /** Decode raster pixels into an ARGB_8888 bitmap for preview rendering. */
    fun toBitmap(raster: Raster): Bitmap {
        if (raster.format == WatchFaceFmt.FORMAT_INDEXED8) return indexedToBitmap(raster)
        val bitmap = Bitmap.createBitmap(raster.width, raster.height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(raster.width * raster.height)
        val bpp = raster.bpp
        require(raster.pixels.size - WatchFaceFmt.RASTER_TRAILER_SIZE == raster.width * raster.height * bpp) {
            "Raster pixel count mismatch"
        }
        when (raster.format) {
            WatchFaceFmt.FORMAT_RGB565 -> {
                for (i in pixels.indices) {
                    val off = i * 2
                    val half = (raster.pixels[off].toInt() and 0xFF) or
                        ((raster.pixels[off + 1].toInt() and 0xFF) shl 8)
                    pixels[i] = fromRgb565(half, 0xFF)
                }
            }
            WatchFaceFmt.FORMAT_RGB565_A -> {
                for (i in pixels.indices) {
                    val off = i * 3
                    val half = (raster.pixels[off].toInt() and 0xFF) or
                        ((raster.pixels[off + 1].toInt() and 0xFF) shl 8)
                    val alpha = raster.pixels[off + 2].toInt() and 0xFF
                    pixels[i] = fromRgb565(half, alpha)
                }
            }
            else -> throw IllegalArgumentException("Unknown format ${raster.format}")
        }
        bitmap.setPixels(pixels, 0, raster.width, 0, 0, raster.width, raster.height)
        return bitmap
    }

    /** Decode an INDEXED8 raster: 256-entry BGRA palette, then one palette index per
     *  pixel. Rare in the wild but real (stock 00002 style0 background), and a style
     *  carrying one used to fail the whole parse. */
    private fun indexedToBitmap(raster: Raster): Bitmap {
        val bitmap = Bitmap.createBitmap(raster.width, raster.height, Bitmap.Config.ARGB_8888)
        val palette = IntArray(WatchFaceFmt.INDEXED_PALETTE_ENTRIES)
        val pixels = raster.pixels
        for (i in palette.indices) {
            val off = i * 4
            val b = pixels[off].toInt() and 0xFF
            val g = pixels[off + 1].toInt() and 0xFF
            val r = pixels[off + 2].toInt() and 0xFF
            val a = pixels[off + 3].toInt() and 0xFF
            palette[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        val out = IntArray(raster.width * raster.height)
        val base = WatchFaceFmt.INDEXED_PALETTE_BYTES
        for (i in out.indices) {
            out[i] = palette[pixels[base + i].toInt() and 0xFF]
        }
        bitmap.setPixels(out, 0, raster.width, 0, 0, raster.width, raster.height)
        return bitmap
    }

    /** Composite a non-premultiplied ARGB pixel over opaque black, for rasters that have
     *  no alpha channel — transparent and soft-edge pixels become black rather than the
     *  saturated near-white that un-premultiplying a tiny alpha produces. */
    fun flattenOverBlack(argb: Int): Int {
        val a = (argb ushr 24) and 0xFF
        val r = ((argb ushr 16) and 0xFF) * a / 255
        val g = ((argb ushr 8) and 0xFF) * a / 255
        val b = (argb and 0xFF) * a / 255
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun toRgb565(r: Int, g: Int, b: Int): Int =
        ((r shr 3) shl 11) or ((g shr 2) shl 5) or (b shr 3)

    fun fromRgb565(half: Int, alpha: Int): Int {
        val r = ((half ushr 11) and 0x1F) shl 3
        val g = ((half ushr 5) and 0x3F) shl 2
        val b = (half and 0x1F) shl 3
        return (alpha shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * Decode [bytes] at roughly [reqWidth]x[reqHeight] resolution, not the file's own.
     *
     * A camera photo decodes to tens of MB of ARGB_8888 — the editor only ever consumes a
     * 256x402 raster, so the full-resolution decode was tens of MB of large-object churn
     * per pick (visible as the GC storm and multi-second main-thread stalls in logcat).
     * Returns null when the bytes are not a decodable image.
     */
    fun decodeSampled(bytes: ByteArray, reqWidth: Int, reqHeight: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null
        else BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, reqWidth, reqHeight)
            // Backgrounds are opaque and immediately re-encoded as RGB565; decoding in
            // 565 halves the intermediate bitmap for free.
            inPreferredConfig = Bitmap.Config.RGB_565
        })
    } catch (_: Exception) {
        null
    }

    /** [decodeSampled] straight from a file, for the background persisted with a project. */
    fun decodeSampledFile(path: String, reqWidth: Int, reqHeight: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null
        else BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, reqWidth, reqHeight)
            inPreferredConfig = Bitmap.Config.RGB_565
        })
    } catch (_: Exception) {
        null
    }

    /** Largest power of two whose both downsampled dimensions still cover the request. */
    private fun sampleSizeFor(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
        var sample = 1
        if (width <= reqWidth && height <= reqHeight) return sample
        var halfWidth = width / 2
        var halfHeight = height / 2
        while (halfWidth / sample >= reqWidth && halfHeight / sample >= reqHeight) sample *= 2
        return sample
    }

    /** Scale [bitmap] into [width]x[height] using the named mode:
     *  - "cover": fill completely, centre-crop the overflow (photos)
     *  - "fit": fit entirely inside, transparent letterbox (icons/logos)
     *  - "stretch": force the exact size, distorting the aspect
     *  - "center": no scaling — centre-crop when the source is bigger, letterbox otherwise
     *  Unknown values fall back to "cover". */
    fun scaledForMode(bitmap: Bitmap, width: Int, height: Int, mode: String): Bitmap = when (mode) {
        "fit" -> scaleToFit(bitmap, width, height)
        "stretch" -> Bitmap.createScaledBitmap(bitmap, width, height, true)
        "center" ->
            if (bitmap.width >= width && bitmap.height >= height) {
                val cropX = (bitmap.width - width) / 2
                val cropY = (bitmap.height - height) / 2
                Bitmap.createBitmap(bitmap, cropX, cropY, width, height)
            } else {
                scaleToFit(bitmap, width, height)
            }
        else -> scaleToCover(bitmap, width, height)
    }

    /** Scale [bitmap] to cover [width]x[height] and centre-crop it. */
    fun scaleToCover(bitmap: Bitmap, width: Int, height: Int): Bitmap {
        val scale = maxOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val scaledW = (bitmap.width * scale).toInt().coerceAtLeast(width)
        val scaledH = (bitmap.height * scale).toInt().coerceAtLeast(height)
        val scaled = Bitmap.createScaledBitmap(bitmap, scaledW, scaledH, true)
        val cropX = (scaledW - width) / 2
        val cropY = (scaledH - height) / 2
        return Bitmap.createBitmap(scaled, cropX, cropY, width, height)
    }

    /** Prefix marking a manual placement mode: "manual@<scale>;<tx>;<ty>[;<rot>;<flip>]". */
    const val MANUAL_PREFIX = "manual@"

    /** A user's interactive transform: [scale] relative to the cover baseline, pan as
     *  fractions of the frame, [rotation] in degrees, [flip] as H=1/V=2 bitmask. */
    data class ManualTransform(
        val scale: Float,
        val tx: Float,
        val ty: Float,
        val rotation: Float,
        val flip: Int
    )

    /** Build the encoded manual mode string from user zoom/pan/rotation/flip. Pan is a
     *  fraction of the frame size, scale a multiplier on the cover baseline. */
    fun manualModeString(scale: Float, tx: Float, ty: Float, rotation: Float = 0f, flip: Int = 0): String {
        // Locale.US: re-parsed with toFloatOrNull, which expects a dot — a
        // default-locale comma (e.g. id-ID) would silently break the round trip.
        return "$MANUAL_PREFIX" +
            "%.4f;%.4f;%.4f;%.2f;$flip".format(java.util.Locale.US, scale, tx, ty, rotation)
    }

    /** Parse a manual mode string, tolerating the older 3-part form. Unknown/bad values
     *  fall back to the cover baseline (scale 1, no pan, no rotation, no flip). */
    fun parseManual(mode: String): ManualTransform {
        val parts = mode.removePrefix(MANUAL_PREFIX).split(";")
        val scale = parts.getOrNull(0)?.toFloatOrNull()?.takeIf { it > 0f } ?: 1f
        val tx = parts.getOrNull(1)?.toFloatOrNull() ?: 0f
        val ty = parts.getOrNull(2)?.toFloatOrNull() ?: 0f
        val rotation = parts.getOrNull(3)?.toFloatOrNull()?.let {
            ((it % 360f) + 360f) % 360f
        } ?: 0f
        val flip = parts.getOrNull(4)?.toIntOrNull()?.coerceIn(0, 3) ?: 0
        return ManualTransform(scale, tx, ty, rotation, flip)
    }

    /**
     * Place [bitmap] into [width]x[height] following the stored manual transform. The
     * pinch/pan numbers are normalized to the source (unit space): scale is a multiplier
     * on the cover baseline, tx/ty fractions of the frame — the same string therefore
     * reproduces the same framing at preview and at raster size.
     */
    fun manualPlacement(bitmap: Bitmap, width: Int, height: Int, mode: String): Bitmap {
        val t = parseManual(mode)
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(bitmap, manualTransformMatrix(bitmap.width, bitmap.height, width, height, t), paint)
        return out
    }

    /** The placement matrix [manualPlacement] applies, shared with the editor preview so
     *  what you pinch is exactly what gets baked. Rotation (90-degree steps) and flip
     *  (H=1, V=2 bitmask) happen about the source centre, then the frame's cover baseline
     *  and the pan offset are applied. */
    fun manualTransformMatrix(
        sourceW: Int,
        sourceH: Int,
        frameW: Int,
        frameH: Int,
        t: ManualTransform
    ): Matrix {
        val rotated = t.rotation % 180f != 0f
        val effW = if (rotated) sourceH.toFloat() else sourceW.toFloat()
        val effH = if (rotated) sourceW.toFloat() else sourceH.toFloat()
        val absScale = maxOf(frameW / effW, frameH / effH) * t.scale
        val m = Matrix()
        m.postTranslate(-sourceW / 2f, -sourceH / 2f)
        if (t.flip and 1 != 0) m.postScale(-1f, 1f)
        if (t.flip and 2 != 0) m.postScale(1f, -1f)
        m.postRotate(t.rotation)
        m.postScale(absScale, absScale)
        m.postTranslate(frameW / 2f + t.tx * frameW, frameH / 2f + t.ty * frameH)
        return m
    }

    /** The image scaled so its smaller dimension equals the frame's (the cover baseline). */
    fun coverBaselineScale(sourceW: Int, sourceH: Int, frameW: Int, frameH: Int): Float =
        maxOf(frameW.toFloat() / sourceW, frameH.toFloat() / sourceH)

    /**
     * Starting point for the interactive placement editor, in encoder units. A stored
     * fit value maps onto the nearest manual transform so retuning a saved replacement
     * starts where it was ("stretch" falls back to the cover baseline, re-encoded
     * verbatim only while untouched).
     */
    fun manualPresetParams(
        mode: String,
        sourceW: Int,
        sourceH: Int,
        frameW: Int,
        frameH: Int
    ): ManualTransform {
        if (mode.startsWith(MANUAL_PREFIX)) return parseManual(mode)
        if (sourceW <= 0 || sourceH <= 0) return ManualTransform(1f, 0f, 0f, 0f, 0)
        val fw = frameW.toFloat()
        val fh = frameH.toFloat()
        val cover = maxOf(fw / sourceW, fh / sourceH)
        val fit = minOf(fw / sourceW, fh / sourceH)
        val scale = when (mode) {
            "fit" -> fit / cover
            "center" ->
                if (sourceW >= frameW && sourceH >= frameH) 1f / cover else fit / cover
            else -> 1f // cover, stretch, unknown
        }
        return ManualTransform(scale, 0f, 0f, 0f, 0)
    }

    /**
     * Scale [bitmap] to fit entirely inside [width]x[height] and centre it on a
     * transparent letterbox — for icons/logos whose whole shape must stay visible.
     */
    fun scaleToFit(bitmap: Bitmap, width: Int, height: Int): Bitmap {
        val scale = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val scaledW = (bitmap.width * scale).toInt().coerceIn(1, width)
        val scaledH = (bitmap.height * scale).toInt().coerceIn(1, height)
        val scaled = Bitmap.createScaledBitmap(bitmap, scaledW, scaledH, true)
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        canvas.drawBitmap(scaled, ((width - scaledW) / 2).toFloat(), ((height - scaledH) / 2).toFloat(), null)
        return out
    }
}