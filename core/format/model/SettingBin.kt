package com.galaxyfit3.core.format.model

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * `setting.bin` — 256 bytes holding the face's identity metadata.
 */
data class SettingBin(
    val faceId: String,
    val faceVersion: Int,
    val styleCountField: Int,
    val unknownField: Int,
    val name: String
) {
    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("LQ_WF".toByteArray(Charsets.US_ASCII))
        buf.put(ByteArray(7))
        buf.putInt(0x12345678)
        buf.put(faceId.toByteArray(Charsets.US_ASCII).copyOf(16))
        buf.put(ByteArray(16))
        buf.putInt(faceVersion)
        buf.putShort(styleCountField.toShort())
        buf.putShort(unknownField.toShort())
        buf.put(byteArrayOf(0))
        buf.put(name.toByteArray(Charsets.US_ASCII).copyOf(63))
        buf.put(byteArrayOf(0))
        buf.put(name.toByteArray(Charsets.US_ASCII).copyOf(63))
        buf.put(ByteArray(256 - buf.position()))
        return buf.array()
    }

    companion object {
        fun decode(bytes: ByteArray): SettingBin {
            require(bytes.size == 256) { "setting.bin must be 256 bytes" }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(readAscii(buf, 0, 6) == "LQ_WF") { "Bad setting.bin marker" }
            require(buf.getInt(0x0C) == 0x12345678) { "Bad struct magic" }
            val faceId = readAscii(buf, 0x10, 16)
            val faceVersion = buf.getInt(0x30)
            val styleCount = buf.getShort(0x34).toInt() and 0xFFFF
            val unknown = buf.getShort(0x36).toInt() and 0xFFFF
            val nameA = readAsciiAt(buf, 0x39, 63)
            val name = nameA.ifEmpty { faceId }
            return SettingBin(faceId.trim(), faceVersion, styleCount, unknown, name)
        }

        private fun readAscii(buf: ByteBuffer, offset: Int, max: Int): String =
            readAsciiAt(buf, offset, max).trim()

        private fun readAsciiAt(buf: ByteBuffer, offset: Int, max: Int): String {
            val end = buf.array()
            var i = offset
            val start = offset
            while (i < offset + max && end[i] != 0.toByte()) i++
            return String(end, start, i - start, Charsets.US_ASCII)
        }
    }
}