package com.galaxyfit3.core.format.model

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A glyph table entry (font_cn0.bin, font_en.bin, ...) holding locale-aware strings
 * (weekday names, metric labels, digits) that the watch draws.
 */
data class GlyphTable(
    val localeId: Int,
    val groups: List<GlyphGroup>
) {
    data class GlyphGroup(val index: Int, val text: String)

    fun encode(): ByteArray {
        val textBlob = groups.joinToString("") { it.text }.toByteArray(Charsets.UTF_8)
        val body = 0x18 + 8 * groups.size
        val buf = ByteBuffer.allocate(body + textBlob.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0x12345678)
        buf.putInt(localeId)
        buf.putInt(groups.size)
        buf.put(ByteArray(12))
        var offset = 0
        groups.forEach { g ->
            val bytes = g.text.toByteArray(Charsets.UTF_8)
            buf.putInt(bytes.size)
            buf.putInt(body + offset)
            offset += bytes.size
        }
        buf.position(body)
        buf.put(textBlob)
        return buf.array()
    }

    companion object {
        fun decode(bytes: ByteArray): GlyphTable {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(buf.getInt(0) == 0x12345678) { "Bad glyph table magic" }
            val locale = buf.getInt(4)
            val count = buf.getInt(8)
            val groups = ArrayList<GlyphGroup>(count)
            for (i in 0 until count) {
                val len = buf.getInt(0x18 + i * 8)
                val off = buf.getInt(0x1C + i * 8)
                require(off + len <= bytes.size) { "Glyph group $i text out of range" }
                val text = String(bytes, off, len, Charsets.UTF_8)
                groups.add(GlyphGroup(i, text))
            }
            return GlyphTable(locale, groups)
        }
    }
}