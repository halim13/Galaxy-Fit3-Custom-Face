package com.galaxyfit3.core.format.model

/**
 * A raster record inside a style entry's image section.
 *
 * On-disk layout:
 *   +0x00 u16 width, +0x02 u16 height, +0x04 u16 format, +0x06 u16 reserved(zero),
 *   +0x08 u32 dataSize (== w*h*bpp + 4),
 *   +0x0C data region  [0 .. w*h*bpp)   = raw row-major pixel data,
 *                      [w*h*bpp .. +4)  = opaque 4-byte trailer.
 *
 * The trailer lives at the END of the declared region; the next raster begins
 * immediately at `offset + 12 + dataSize`.
 *
 * Two storage modes:
 *  - owned (default): [bytes] holds exactly this raster's data region;
 *  - view ([view] factory / [sourceOffset] > 0 or [sharedSource]): [bytes] is a shared
 *    buffer (the parsed container) and the data region starts at [sourceOffset]. The
 *    parser uses the view mode so parsing a real multi-style face does not duplicate
 *    every image section — that copy per parse was tens of MB of large-object churn
 *    and the direct cause of the editor's heap storms.
 */
class Raster(
    val width: Int,
    val height: Int,
    val format: Int,
    internal val bytes: ByteArray,
    /** Offset of this raster's data region within [bytes] when viewed in place. */
    val sourceOffset: Int = 0
) {
    /** Size of this raster's data region in [bytes]. */
    val length: Int = declaredDataSizeFor(width, height, format)

    init {
        require(width > 0 && height > 0) { "Invalid raster dimensions $width x $height" }
        require(sourceOffset >= 0) { "Negative source offset $sourceOffset" }
        require(sourceOffset + length <= bytes.size) {
            "Raster region [$sourceOffset, ${sourceOffset + length}) overruns buffer ${bytes.size}"
        }
    }

    val bpp: Int
        get() = when (format) {
            WatchFaceFmt.FORMAT_RGB565 -> WatchFaceFmt.BPP_RGB565
            WatchFaceFmt.FORMAT_RGB565_A -> WatchFaceFmt.BPP_RGB565_A
            WatchFaceFmt.FORMAT_INDEXED8 -> WatchFaceFmt.BPP_INDEXED8
            else -> WatchFaceFmt.BPP_RGB565
        }

    /** Palette bytes that precede the pixel bytes inside an indexed raster's data region. */
    val paletteSize: Int
        get() = if (format == WatchFaceFmt.FORMAT_INDEXED8) WatchFaceFmt.INDEXED_PALETTE_BYTES else 0

    /** Declared payload per the header — the data region also holds a 4-byte trailer. */
    val declaredDataSize: Int get() = length

    /** The 4 opaque trailer bytes at the end of the data region. */
    val trailer: ByteArray get() = pixels.copyOfRange(pixels.size - 4, pixels.size)

    /** Rows of raw pixel data without the trailer (w*h*bpp bytes). */
    val colorBytes: ByteArray get() = pixels.copyOfRange(0, pixels.size - 4)

    /**
     * The raster's data region (w*h*bpp pixel bytes + trailer). In view mode this copies
     * on first use — [sourceBytes] avoids that copy for byte-level access.
     */
    val pixels: ByteArray by lazy {
        if (sourceOffset == 0 && bytes.size == length) bytes
        else bytes.copyOfRange(sourceOffset, sourceOffset + length)
    }

    /** Read one byte of the data region without copying, in either storage mode. */
    fun sourceBytes(at: Int): Byte {
        require(at in 0 until length) { "Raster byte index $at out of 0..$length" }
        return bytes[sourceOffset + at]
    }

    /** Serialize into [out] at [at] (12-byte header handled by the serializer). */
    fun writePixelsTo(out: ByteArray, at: Int) {
        if (sourceOffset == 0) {
            bytes.copyInto(out, at)
        } else {
            System.arraycopy(bytes, sourceOffset, out, at, length)
        }
    }

    fun withPixels(pixels: ByteArray): Raster =
        Raster(width, height, format, pixels, 0)

    fun encodedSize(): Int = 12 + length

    /** True when this raster aliases a larger buffer instead of owning its bytes. */
    val sharedSource: Boolean get() = sourceOffset != 0 || bytes.size != length

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Raster) return false
        if (width != other.width || height != other.height || format != other.format) return false
        for (i in 0 until length) {
            if (bytes[sourceOffset + i] != other.bytes[other.sourceOffset + i]) return false
        }
        return true
    }

    override fun hashCode(): Int {
        var h = 31 * (31 * (31 * width + height) + format)
        for (i in 0 until length) h = h * 31 + bytes[sourceOffset + i].toInt()
        return h
    }

    companion object {
        /** Construct a raster that owns [pixels] as its complete data region. */
        operator fun invoke(width: Int, height: Int, format: Int, pixels: ByteArray): Raster {
            val r = Raster(width, height, format, pixels, 0)
            require(pixels.size == r.length) {
                "Pixel buffer ${pixels.size} != declared ${r.length} for ${width}x${height} fmt=$format"
            }
            return r
        }

        /** Zero-copy view of [bytes] from [offset], [dataSize] bytes long. */
        fun view(width: Int, height: Int, format: Int, bytes: ByteArray, offset: Int, dataSize: Int): Raster {
            val declared = declaredDataSizeFor(width, height, format)
            require(dataSize == declared) { "Raster dataSize $dataSize != declared $declared" }
            return Raster(width, height, format, bytes, offset)
        }

        private fun declaredDataSizeFor(width: Int, height: Int, format: Int): Int {
            val bpp = when (format) {
                WatchFaceFmt.FORMAT_RGB565 -> WatchFaceFmt.BPP_RGB565
                WatchFaceFmt.FORMAT_RGB565_A -> WatchFaceFmt.BPP_RGB565_A
                else -> WatchFaceFmt.BPP_RGB565
            }
            return width * height * bpp + WatchFaceFmt.RASTER_TRAILER_SIZE
        }
    }
}

/** Aggregated format constants for the pure-Kotlin model layer. */
object WatchFaceFmt {
    const val FORMAT_RGB565 = 0x0082
    const val FORMAT_RGB565_A = 0x0080
    /** 256-entry BGRA palette followed by one index byte per pixel. Rare, but real
     *  stock faces (e.g. 00002 style0) ship a background in it — parseable or nothing. */
    const val FORMAT_INDEXED8 = 0x0088
    const val BPP_RGB565 = 2
    const val BPP_RGB565_A = 3
    const val BPP_INDEXED8 = 1
    /** Indexed rasters carry a 256-entry BGRA palette (1024 bytes) before the pixels. */
    const val INDEXED_PALETTE_BYTES = 256 * 4
    const val INDEXED_PALETTE_ENTRIES = 256
    const val RASTER_TRAILER_SIZE = 4
}
