package com.galaxyfit3.core.format.parser

import com.galaxyfit3.core.format.WatchFaceFormat
import com.galaxyfit3.core.format.model.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Serializes a parsed container back to bytes with correct tight packing,
 * per-entry CRCs and the header CRC that covers the whole directory + payloads.
 */
object WatchFaceSerializer {

    /**
     * Re-serialize an existing container. Entry order and payload contents are
     * preserved from the model (payloads were parsed for edits).
     */
    fun serialize(container: WatchFaceContainer): ByteArray =
        serializeEntries(container.version, container.entries)

    /**
     * Build a container from scratch. Payloads are encoded from the typed models.
     */
    fun serializeEntries(version: Int, entries: List<ContainerEntry>): ByteArray {
        // Size every payload, then encode straight into the output buffer. Materializing
        // each payload as its own array first (the old approach) allocated ~1x container
        // size in intermediate large objects on top of the output — and the editor
        // serializes on every rebuild, so that was a ~15 MB churn per edit cycle.
        var totalPayload = 0
        val sizes = IntArray(entries.size)
        entries.forEachIndexed { i, entry ->
            sizes[i] = payloadSize(entry.payload)
            totalPayload += sizes[i]
        }
        require(totalPayload + 32 <= Long.MAX_VALUE) { "Container too large" }

        val entryCount = entries.size
        val headerSize = WatchFaceFormat.HEADER_SIZE
        val dirSize = entryCount * WatchFaceFormat.DIR_ENTRY_SIZE

        // Absolute offsets, tight-packed after the directory.
        var cursor = headerSize + dirSize
        val offsets = IntArray(entryCount)
        for (i in entries.indices) {
            offsets[i] = cursor
            cursor += sizes[i]
        }

        val totalSize = cursor
        val out = ByteArray(totalSize)
        val buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)

        // Header with placeholder CRC.
        buf.put(WatchFaceFormat.MAGIC.toByteArray(Charsets.US_ASCII))
        buf.putInt(WatchFaceFormat.VERSION)
        buf.putInt(totalSize - headerSize)
        buf.putInt(entryCount)
        buf.putShort(0) // CRC placeholder
        buf.putShort(0) // unknown
        buf.put(ByteArray(12)) // reserved

        // Payloads, encoded in place; each payload's CRC is taken over the freshly
        // written region (the directory records carry it).
        val payloadCrcs = IntArray(entryCount)
        entries.forEachIndexed { i, entry ->
            encodePayloadInto(entry.payload, out, offsets[i])
            payloadCrcs[i] = Crc16.computeRange(out, offsets[i], offsets[i] + sizes[i])
        }

        // Directory records.
        entries.forEachIndexed { i, entry ->
            val de = entry.directory.copy(offset = offsets[i], payloadCrc = payloadCrcs[i])
            out.write(de.encode(), headerSize + i * WatchFaceFormat.DIR_ENTRY_SIZE)
        }

        // Header CRC over bytes 0x20..EOF, stored little-endian (u16).
        val headerCrc = Crc16.computeRange(out, headerSize, totalSize)
        out[0x10] = (headerCrc and 0xFF).toByte()
        out[0x11] = ((headerCrc ushr 8) and 0xFF).toByte()

        return out
    }

    /** Write one directory entry at an absolute position. */
    private fun ByteArray.write(bytes: ByteArray, at: Int) {
        bytes.copyInto(this, at)
    }

    /** Exact encoded size of [payload] without materializing the big ones. */
    private fun payloadSize(p: ContainerPayload): Int = when (p) {
        is ContainerPayload.Setting -> p.data.encode().size
        is ContainerPayload.FontBinding -> p.data.encode().size
        is ContainerPayload.GlyphTable -> p.data.encode().size
        is ContainerPayload.Style -> styleEntrySize(p.data)
        is ContainerPayload.Preview -> p.rasters.sumOf { it.encodedSize() }
        is ContainerPayload.Unknown -> p.bytes.size
    }

    private fun styleEntrySize(style: StyleEntry): Int {
        val widgetBytes = style.widgets.sumOf { it.recordBytes }
        return 24 + widgetBytes + style.rasters.sumOf { it.encodedSize() }
    }

    /** Encode [p] into [out] at absolute offset [at]. */
    private fun encodePayloadInto(p: ContainerPayload, out: ByteArray, at: Int) {
        when (p) {
            is ContainerPayload.Setting -> p.data.encode().copyInto(out, at)
            is ContainerPayload.FontBinding -> p.data.encode().copyInto(out, at)
            is ContainerPayload.GlyphTable -> p.data.encode().copyInto(out, at)
            is ContainerPayload.Style -> encodeStyleEntry(p.data, out, at)
            is ContainerPayload.Preview -> {
                var pos = at
                p.rasters.forEach { r ->
                    encodeRaster(r, out, pos)
                    pos += r.encodedSize()
                }
            }
            is ContainerPayload.Unknown -> p.bytes.copyInto(out, at)
        }
    }

    /** Encode a single raster into [out] at [at]. */
    private fun encodeRaster(r: Raster, out: ByteArray, at: Int) {
        val buf = java.nio.ByteBuffer.wrap(out, at, r.encodedSize()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.putShort(r.width.toShort())
        buf.putShort(r.height.toShort())
        buf.putShort(r.format.toShort())
        buf.putShort(0)
        buf.putInt(r.length)
        r.writePixelsTo(out, at + 12)
    }

    /** Encode a style entry directly into [out] at absolute offset [at]. */
    private fun encodeStyleEntry(style: StyleEntry, out: ByteArray, at: Int) {
        val widgetBytes = style.widgets.sumOf { it.recordBytes }
        require(widgetBytes == style.widgetBytes) {
            "Widget bytes $widgetBytes != header ${style.widgetBytes}"
        }
        val imageBytes = style.rasters.sumOf { it.encodedSize() }
        require(imageBytes == style.imageBytes) {
            "Image bytes $imageBytes != header ${style.imageBytes}"
        }
        require(style.widgets.size == style.widgetCount) {
            "Widget count ${style.widgets.size} != header ${style.widgetCount}"
        }

        val buf = ByteBuffer.wrap(out, at, 24 + widgetBytes + imageBytes)
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(WatchFaceFormat.STRUCT_MAGIC)
        buf.putInt(style.widgetCount)
        buf.putInt(widgetBytes)
        buf.putInt(imageBytes)
        buf.putInt(style.headerUnknown)
        buf.putInt(24 + widgetBytes)

        style.widgets.forEach { w ->
            buf.putInt(w.type)
            buf.putInt(w.sequenceId)
            buf.putInt(w.opaque1)
            buf.putInt((w.globalIndex shl 16) or (w.recordSize and 0xFFFF))
            buf.putLong(w.opaque2)
            buf.putShort(w.x.toShort())
            buf.putShort(w.y.toShort())
            buf.putShort(w.wOrX2.toShort())
            buf.putShort(w.hOrY2.toShort())
            buf.putInt(w.wordA.toInt())
            w.words.forEach { buf.putInt(it) }
        }

        style.rasters.forEach { r ->
            buf.putShort(r.width.toShort())
            buf.putShort(r.height.toShort())
            buf.putShort(r.format.toShort())
            buf.putShort(0)
            buf.putInt(r.declaredDataSize)
            // Pixel bytes may live inside a shared container buffer (parsed view);
            // copy them straight into the output. writePixelsTo bypasses this
            // ByteBuffer, so the position must be advanced past the data region
            // manually — otherwise the next raster's header lands inside the
            // previous raster's pixels and the trailing rasters' own slots stay
            // zero, which then fails to reparse with "Unknown raster format 0x0".
            // (Single-raster fixtures never caught this; real faces carry dozens.)
            r.writePixelsTo(out, buf.position())
            buf.position(buf.position() + r.declaredDataSize)
        }
    }
}
