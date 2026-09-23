package com.galaxyfit3.core.format.model

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Container directory entry — 74 bytes on disk.
 * Each path is NUL-padded UTF-8 (64 bytes), offset is absolute from file start.
 */
data class DirectoryEntry(
    val path: String,
    val offset: Int,
    val size: Int,
    val payloadCrc: Int
) {
    /** Tail component of the path (e.g. "style0.bin"). */
    val name: String get() = path.substringAfterLast('/').ifEmpty { path }

    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(74).order(ByteOrder.LITTLE_ENDIAN)
        val pathBytes = path.toByteArray(Charsets.UTF_8)
        require(pathBytes.size <= 64)
        buf.put(pathBytes)
        buf.position(64)
        buf.putInt(offset)
        buf.putInt(size)
        buf.putShort((payloadCrc and 0xFFFF).toShort())
        return buf.array()
    }
}