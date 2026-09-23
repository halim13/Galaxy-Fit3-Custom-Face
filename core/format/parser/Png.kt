package com.galaxyfit3.core.format.parser

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.InflaterInputStream

/**
 * Minimal PNG codec for [AssetBundle], pure JVM — core/format has no Android graphics.
 *
 * Encoding always writes 8-bit RGBA, non-interlaced, filter 0 — what every external
 * editor opens and what keeps the writer small. Decoding accepts the 8-bit non-interlaced
 * subset editors actually save (gray, RGB, gray+alpha, RGBA; filters 0–4); anything
 * else (palette, 16-bit, interlaced) is rejected and the asset is reported ignored.
 */
object Png {

    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** Decoded pixels: 8-bit ARGB_8888, row-major, w*h entries. */
    class Decoded(val width: Int, val height: Int, val argb: IntArray)

    /** Encode [argb] (w*h ARGB_8888) as an 8-bit RGBA PNG. */
    fun encode(width: Int, height: Int, argb: IntArray): ByteArray {
        val raw = ByteArray(height * (1 + width * 4))
        var o = 0
        for (y in 0 until height) {
            raw[o++] = 0 // filter: none
            for (x in 0 until width) {
                val p = argb[y * width + x]
                raw[o++] = ((p ushr 16) and 0xFF).toByte()
                raw[o++] = ((p ushr 8) and 0xFF).toByte()
                raw[o++] = (p and 0xFF).toByte()
                raw[o++] = ((p ushr 24) and 0xFF).toByte()
            }
        }
        val deflated = ByteArrayOutputStream()
        val d = Deflater(Deflater.BEST_COMPRESSION)
        try {
            d.setInput(raw)
            d.finish()
            val buf = ByteArray(8192)
            while (!d.finished()) {
                val n = d.deflate(buf)
                deflated.write(buf, 0, n)
            }
        } finally {
            d.end()
        }
        val out = ByteArrayOutputStream()
        out.write(SIGNATURE)
        val ihdr = ByteArrayOutputStream(13)
        ihdr.write(u32(width)); ihdr.write(u32(height))
        ihdr.write(8); ihdr.write(6); ihdr.write(0); ihdr.write(0); ihdr.write(0)
        writeChunk(out, "IHDR", ihdr.toByteArray())
        writeChunk(out, "IDAT", deflated.toByteArray())
        writeChunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    /** Decode an 8-bit non-interlaced PNG into ARGB, or null when unsupported. */
    fun decode(bytes: ByteArray): Decoded? {
        return try {
        val inStream = ByteArrayInputStream(bytes)
        val sig = ByteArray(8)
        if (inStream.read(sig) != 8 || !sig.contentEquals(SIGNATURE)) return null

        var width = 0; var height = 0; var colorType = -1; var interlace = -1
        val idat = ByteArrayOutputStream()
        while (true) {
            val len = readU32(inStream) ?: break
            val type = ByteArray(4)
            if (inStream.read(type) != 4) break
            val data = ByteArray(len)
            if (inStream.read(data) != len) break
            // The CRC is not verified: the exporter and editors write valid files, and
            // the decode result is size-checked against the seed raster anyway.
            readU32(inStream) ?: break
            when (String(type, Charsets.US_ASCII)) {
                "IHDR" -> {
                    if (len < 13) return null
                    width = i32(data, 0); height = i32(data, 4)
                    val bitDepth = data[8].toInt() and 0xFF
                    colorType = data[9].toInt() and 0xFF
                    interlace = data[12].toInt() and 0xFF
                    if (bitDepth != 8) return null
                }
                "IDAT" -> idat.write(data)
                "IEND" -> break
            }
        }
        if (width <= 0 || height <= 0 || width > 4096 || height > 4096) return null
        if (interlace != 0) return null
        val channels = when (colorType) {
            0 -> 1; 2 -> 3; 4 -> 2; 6 -> 4
            else -> return null
        }
        val stride = width * channels
        val raw = InflaterInputStream(ByteArrayInputStream(idat.toByteArray())).use { it.readBytes() }
        if (raw.size < height * (stride + 1)) return null

        // Unfilter in place, one scanline at a time.
        val img = ByteArray(height * stride)
        var src = 0
        for (y in 0 until height) {
            val filter = raw[src++].toInt() and 0xFF
            val row = y * stride
            when (filter) {
                0 -> System.arraycopy(raw, src, img, row, stride)
                1 -> for (x in 0 until stride) {
                    val left = if (x >= channels) img[row + x - channels].toInt() and 0xFF else 0
                    img[row + x] = ((raw[src + x].toInt() and 0xFF) + left).toByte()
                }
                2 -> for (x in 0 until stride) {
                    val up = if (y > 0) img[row - stride + x].toInt() and 0xFF else 0
                    img[row + x] = ((raw[src + x].toInt() and 0xFF) + up).toByte()
                }
                3 -> for (x in 0 until stride) {
                    val left = if (x >= channels) img[row + x - channels].toInt() and 0xFF else 0
                    val up = if (y > 0) img[row - stride + x].toInt() and 0xFF else 0
                    img[row + x] = ((raw[src + x].toInt() and 0xFF) + (left + up) / 2).toByte()
                }
                4 -> for (x in 0 until stride) {
                    val a = if (x >= channels) img[row + x - channels].toInt() and 0xFF else 0
                    val b = if (y > 0) img[row - stride + x].toInt() and 0xFF else 0
                    val c = if (x >= channels && y > 0) img[row - stride + x - channels].toInt() and 0xFF else 0
                    img[row + x] = ((raw[src + x].toInt() and 0xFF) + paeth(a, b, c)).toByte()
                }
                else -> return null
            }
            src += stride
        }

        val argb = IntArray(width * height)
        for (i in 0 until width * height) {
            val off = i * channels
            when (colorType) {
                0 -> argb[i] = 0xFF000000.toInt() or (g(img[off]) shl 16) or (g(img[off]) shl 8) or g(img[off])
                2 -> argb[i] = 0xFF000000.toInt() or (g(img[off]) shl 16) or (g(img[off + 1]) shl 8) or g(img[off + 2])
                4 -> {
                    val gray = g(img[off]); val a = g(img[off + 1])
                    argb[i] = (a shl 24) or (gray shl 16) or (gray shl 8) or gray
                }
                6 -> argb[i] = (g(img[off + 3]) shl 24) or (g(img[off]) shl 16) or (g(img[off + 1]) shl 8) or g(img[off + 2])
            }
        }
        Decoded(width, height, argb)
        } catch (_: Exception) {
            null
        }
    }

    // -------------------------------------------------------------- internals

    private fun g(b: Byte): Int = b.toInt() and 0xFF

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = Math.abs(p - a); val pb = Math.abs(p - b); val pc = Math.abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    private fun u32(v: Int): ByteArray = byteArrayOf(
        ((v ushr 24) and 0xFF).toByte(), ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte()
    )

    private fun readU32(inStream: ByteArrayInputStream): Int? {
        val b = ByteArray(4)
        if (inStream.read(b) != 4) return null
        return ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
    }

    private fun i32(data: ByteArray, off: Int): Int =
        ((data[off].toInt() and 0xFF) shl 24) or ((data[off + 1].toInt() and 0xFF) shl 16) or
            ((data[off + 2].toInt() and 0xFF) shl 8) or (data[off + 3].toInt() and 0xFF)

    private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        out.write(u32(data.size))
        val body = type.toByteArray(Charsets.US_ASCII) + data
        out.write(body)
        val crc = CRC32().apply { update(body) }
        out.write(u32(crc.value.toInt()))
    }
}
