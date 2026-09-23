package com.galaxyfit3.core.format.parser

import com.galaxyfit3.core.format.WatchFaceFormat
import com.galaxyfit3.core.format.model.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parses the OPPO watch-face container, accounting for every byte.
 *
 * Layout:
 *   32-byte header ("oppo", version, payload size, entry count, CRC-16 over 0x20..EOF)
 *   N × 74-byte directory records   (tight-packed: first offset == 0x20 + 74*N)
 *   payloads, each bytes beginning at its declared absolute offset.
 */
object WatchFaceParser {

    /** Parse the whole container and return the typed model. */
    fun parse(bytes: ByteArray): WatchFaceContainer {
        require(bytes.size >= WatchFaceFormat.HEADER_SIZE) { "File too small for container header" }
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val magic = readAscii(bytes, 0, 4)
        require(magic == WatchFaceFormat.MAGIC) { "Not an OPPO container (magic '$magic')" }
        val version = header.getInt(0x04)
        require(version == WatchFaceFormat.VERSION) { "Unsupported container version $version" }

        val declaredPayload = header.getInt(0x08)
        require(declaredPayload == bytes.size - WatchFaceFormat.HEADER_SIZE) {
            "Header payload size $declaredPayload != file_size - 32 (${bytes.size - 32})"
        }

        val entryCount = header.getInt(0x0C)
        require(entryCount > 0) { "Zero directory entries" }

        val headerCrcStored = header.getShort(0x10).toInt() and 0xFFFF
        val headerCrcActual = Crc16.computeRange(bytes, WatchFaceFormat.HEADER_SIZE, bytes.size)
        require(headerCrcStored == headerCrcActual) {
            "Header CRC mismatch: stored 0x${headerCrcStored.toString(16)}, actual 0x${headerCrcActual.toString(16)}"
        }

        val dirStart = WatchFaceFormat.HEADER_SIZE
        val directory = ArrayList<DirectoryEntry>(entryCount)
        for (i in 0 until entryCount) {
            directory.add(parseDirectoryEntry(bytes, dirStart + i * WatchFaceFormat.DIR_ENTRY_SIZE))
        }

        var cursor = dirStart + entryCount * WatchFaceFormat.DIR_ENTRY_SIZE
        for (entry in directory) {
            require(entry.offset == cursor) {
                "Directory ${entry.name}: offset ${entry.offset} != tight-packed $cursor"
            }
            require(entry.offset + entry.size <= bytes.size) {
                "Directory ${entry.name}: payload end ${entry.offset + entry.size} > file ${bytes.size}"
            }
            val payloadCrcActual = Crc16.computeRange(bytes, entry.offset, entry.offset + entry.size)
            require(payloadCrcActual == entry.payloadCrc) {
                "Payload CRC mismatch for ${entry.name}: stored 0x${entry.payloadCrc.toString(16)}, actual 0x${payloadCrcActual.toString(16)}"
            }
            cursor = entry.offset + entry.size
        }
        require(cursor == bytes.size) { "Trailing bytes after last payload: $cursor -> ${bytes.size}" }

        // Style payloads are parsed in place (rasters reference the source bytes); only
        // the small setting/font/glyph payloads are copied. Copying a whole style payload
        // first — as this used to do — doubled the image section on every parse: on a
        // real 4-style face that was tens of MB of large-object churn per parse, and the
        // editor parses on load, on every rebuild and on every validation.
        val entries = directory.map { de ->
            ContainerEntry(de, parsePayload(de.name, bytes, de.offset, de.size))
        }

        // Not copied: `rawBytes` is never read back, and duplicating the whole container on
        // every parse is megabytes of heap for nothing on a real 3.7 MB face.
        return WatchFaceContainer(version, entries, bytes)
    }

    private fun parseDirectoryEntry(bytes: ByteArray, offset: Int): DirectoryEntry {
        val path = readAscii(bytes, offset, 64)
        require(path.isNotBlank()) { "Directory entry has blank path at $offset" }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val payloadOffset = buf.getInt(offset + 64)
        val size = buf.getInt(offset + 68)
        val crc = buf.getShort(offset + 72).toInt() and 0xFFFF
        return DirectoryEntry(path, payloadOffset, size, crc)
    }

    private fun parsePayload(name: String, bytes: ByteArray, offset: Int, size: Int): ContainerPayload = when {
        name == "setting.bin" -> ContainerPayload.Setting(
            SettingBin.decode(bytes.copyOfRange(offset, offset + size))
        )
        name.matches(Regex("font_\\d+\\.bin")) -> ContainerPayload.FontBinding(
            FontBinding.decode(bytes.copyOfRange(offset, offset + size))
        )
        name.matches(Regex("font_[a-zA-Z0-9_]+\\.bin")) -> ContainerPayload.GlyphTable(
            GlyphTable.decode(bytes.copyOfRange(offset, offset + size))
        )
        name == "aod.bin" || name.matches(Regex("style\\d+\\.bin")) ->
            ContainerPayload.Style(parseStyleEntry(bytes, offset, size))
        name == "preview.bin" ->
            ContainerPayload.Preview(parsePreviewSection(bytes, offset, size))
        else ->
            ContainerPayload.Unknown(bytes.copyOfRange(offset, offset + size))
    }

    /** Parse a style entry starting at absolute [base], [size] bytes long, in place. */
    private fun parseStyleEntry(bytes: ByteArray, base: Int, size: Int): StyleEntry {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(buf.getInt(base) == WatchFaceFormat.STRUCT_MAGIC) { "Bad style magic" }
        val widgetCount = buf.getInt(base + 0x04)
        val widgetBytes = buf.getInt(base + 0x08)
        val imageBytes = buf.getInt(base + 0x0C)
        val unknown = buf.getInt(base + 0x10)
        val imageOffset = buf.getInt(base + 0x14)

        require(imageOffset == 0x18 + widgetBytes) {
            "Image section offset $imageOffset != 24+$widgetBytes"
        }
        require(imageOffset + imageBytes <= size) { "Image section overruns entry" }
        require(imageOffset + imageBytes == size) { "Trailing bytes in style entry" }

        val widgets = ArrayList<WidgetRecord>(widgetCount)
        var wc = 0x18
        repeat(widgetCount) { index ->
            val record = parseWidgetRecord(bytes, base + wc)
            widgets.add(record)
            wc += record.recordBytes
        }
        require(wc == imageOffset) { "Widget section end $wc != image offset $imageOffset" }

        val rasters = parseImageSection(bytes, base + imageOffset, imageBytes)
        return StyleEntry(widgetCount, widgetBytes, imageBytes, unknown, widgets, rasters)
    }

    private fun parseWidgetRecord(bytes: ByteArray, offset: Int): WidgetRecord {
        val buf = ByteBuffer.wrap(bytes, offset, bytes.size - offset).order(ByteOrder.LITTLE_ENDIAN)
        val type = buf.getInt()
        val seq = buf.getInt()
        val opaque1 = buf.getInt()
        val giAndSize = buf.getInt()
        val globalIndex = giAndSize ushr 16
        val recordSize = giAndSize and 0xFFFF
        val opaque2 = buf.getLong()
        val x = buf.getShort().toInt()
        val y = buf.getShort().toInt()
        val w = buf.getShort().toInt()
        val h = buf.getShort().toInt()
        val wordA = buf.getInt().toLong() and 0xFFFFFFFFL

        require(recordSize % 4 == 0 && recordSize >= 40) { "Odd record size $recordSize" }
        val extraWords = recordSize / 4 - 9
        require(extraWords >= 0) { "Record smaller than its fixed head" }
        val words = ArrayList<Int>(extraWords)
        repeat(extraWords) { words.add(buf.getInt()) }

        return WidgetRecord(type, seq, opaque1, globalIndex, recordSize, opaque2, x, y, w, h, wordA, words)
    }

    private fun parseImageSection(bytes: ByteArray, start: Int, size: Int): List<Raster> {
        val rasters = ArrayList<Raster>()
        var pos = start
        val end = start + size
        while (pos < end) {
            val rast = parseRaster(bytes, pos)
            rasters.add(rast)
            pos += rast.encodedSize()
        }
        require(pos == end) { "Image section end $pos != $end" }
        return rasters
    }

    private fun parseRaster(bytes: ByteArray, offset: Int): Raster {
        val buf = ByteBuffer.wrap(bytes, offset, 12).order(ByteOrder.LITTLE_ENDIAN)
        val width = buf.getShort().toInt() and 0xFFFF
        val height = buf.getShort().toInt() and 0xFFFF
        val format = buf.getShort().toInt() and 0xFFFF
        buf.getShort() // reserved
        val dataSize = buf.getInt()

        val bpp = when (format) {
            WatchFaceFmt.FORMAT_RGB565 -> WatchFaceFmt.BPP_RGB565
            WatchFaceFmt.FORMAT_RGB565_A -> WatchFaceFmt.BPP_RGB565_A
            // Indexed-8: a 256-entry BGRA palette precedes one byte per pixel.
            WatchFaceFmt.FORMAT_INDEXED8 -> WatchFaceFmt.BPP_INDEXED8
            else -> throw IllegalArgumentException("Unknown raster format 0x${format.toString(16)}")
        }
        val palette = if (format == WatchFaceFmt.FORMAT_INDEXED8) WatchFaceFmt.INDEXED_PALETTE_BYTES else 0
        val declared = palette + width * height * bpp + WatchFaceFmt.RASTER_TRAILER_SIZE
        require(dataSize == declared) { "Raster dataSize $dataSize != declared $declared" }

        // Zero copy: pixels stay inside the container bytes. This used to copyOfRange
        // every raster — duplicating each image section on parse, i.e. tens of MB of
        // large-object churn per parse on a real multi-style face.
        return Raster.view(width, height, format, bytes, offset + 12, dataSize)
    }

    /** Parse preview.bin: a sequence of 178x280 RGB565 rasters, one per style. */
    private fun parsePreviewSection(bytes: ByteArray, start: Int, size: Int): List<Raster> {
        val rasters = ArrayList<Raster>()
        var pos = start
        val end = start + size
        while (pos < end) {
            val rast = parseRaster(bytes, pos)
            rasters.add(rast)
            pos += rast.encodedSize()
        }
        return rasters
    }

    private fun readAscii(bytes: ByteArray, offset: Int, max: Int): String {
        var end = offset
        while (end < offset + max && end < bytes.size && bytes[end] != 0.toByte()) end++
        return String(bytes, offset, end - offset, Charsets.UTF_8)
    }
}