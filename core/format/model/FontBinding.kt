package com.galaxyfit3.core.format.model

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A font binding entry (font_N.bin) — 92 bytes naming a text role and a firmware font.
 * No glyph data lives in the container; the typeface is in watch ROM.
 */
data class FontBinding(
    val family: Int,
    val opaqueBytes: ByteArray,
    val role: String,
    val pointSize: Int
) {
    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(92).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(family.toInt().and(0xFF).toByte())
        buf.put(opaqueBytes.copyOf(71))
        buf.put(role.toByteArray(Charsets.US_ASCII).copyOf(16))
        buf.putInt(pointSize)
        return buf.array()
    }

    companion object {
        fun decode(bytes: ByteArray): FontBinding {
            require(bytes.size == 92) { "Font binding must be 92 bytes" }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val family = buf.get(0).toInt() and 0xFF
            val opaque = ByteArray(71)
            bytes.copyInto(opaque, 0, 1, 72)
            val role = decodeAscii(bytes, 0x48, 16)
            val size = buf.getInt(0x58)
            return FontBinding(family, opaque, role, size)
        }

        private fun decodeAscii(bytes: ByteArray, offset: Int, max: Int): String {
            var end = offset
            while (end < offset + max && bytes[end] != 0.toByte()) end++
            return String(bytes, offset, end - offset, Charsets.US_ASCII)
        }
    }
}