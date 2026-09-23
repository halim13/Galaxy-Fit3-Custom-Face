package com.galaxyfit3.core.formattest

import com.galaxyfit3.core.format.WatchFaceFormat
import com.galaxyfit3.core.format.model.*
import com.galaxyfit3.core.format.parser.FaceIdRemapper
import com.galaxyfit3.core.format.parser.FaceIdentity
import com.galaxyfit3.core.format.parser.WatchFaceParser
import com.galaxyfit3.core.format.parser.WatchFaceSerializer
import com.galaxyfit3.core.format.validator.FaceValidator
import org.junit.Assert.*
import org.junit.Test

/**
 * The install path is fail-closed on face identity since the silent 90001→255 clamp
 * corrupted a watch's face list: the install command's id byte named one face while
 * the container's filename and setting.bin named another. These pin the identity
 * reader, the remapper, and the fail-closed rule the install ViewModel leans on.
 */
class FaceIdRemapperTest {

    private fun backgroundRaster(): Raster {
        val w = WatchFaceFormat.PANEL_WIDTH
        val h = WatchFaceFormat.PANEL_HEIGHT
        val bpp = WatchFaceFmt.BPP_RGB565
        val data = ByteArray(w * h * bpp + 4)
        for (i in data.indices) data[i] = (i * 7 and 0xFF).toByte()
        return Raster(w, h, WatchFaceFmt.FORMAT_RGB565, data)
    }

    private fun buildContainer(faceId: String, styleCount: Int = 2): ByteArray {
        val bg = backgroundRaster()
        val style = StyleEntry(
            widgetCount = 1,
            widgetBytes = 40,
            imageBytes = bg.encodedSize(),
            headerUnknown = 0x400,
            widgets = listOf(
                WidgetRecord(
                    type = WidgetType.STATIC,
                    sequenceId = 0,
                    opaque1 = 0,
                    globalIndex = 0,
                    recordSize = 40,
                    opaque2 = 0,
                    x = 0, y = 0,
                    wOrX2 = WatchFaceFormat.PANEL_WIDTH,
                    hOrY2 = WatchFaceFormat.PANEL_HEIGHT,
                    wordA = 0L,
                    words = listOf(0)
                )
            ),
            rasters = listOf(bg)
        )
        val prefix = "SM-R390_${faceId}_256x402"
        val entries = buildList {
            add(
                ContainerEntry(
                    DirectoryEntry("./$prefix/setting.bin", 0, WatchFaceFormat.SETTING_SIZE, 0),
                    ContainerPayload.Setting(
                        SettingBin(faceId, 40000, styleCount, 0xFFFF, prefix)
                    )
                )
            )
            repeat(styleCount) { i ->
                add(
                    ContainerEntry(
                        DirectoryEntry("./$prefix/style$i.bin", 0, style.entrySize, 0),
                        ContainerPayload.Style(style)
                    )
                )
            }
            add(
                ContainerEntry(
                    DirectoryEntry("./$prefix/aod.bin", 0, style.entrySize, 0),
                    ContainerPayload.Style(style)
                )
            )
        }
        return WatchFaceSerializer.serializeEntries(WatchFaceFormat.VERSION, entries)
    }

    @Test
    fun identityReadsSettingAndDirectory() {
        val bytes = buildContainer("00031")
        val identity = FaceIdentity.of(WatchFaceParser.parse(bytes))
        assertEquals("00031", identity.settingFaceId)
        assertEquals("SM-R390_00031_256x402", identity.directoryPrefix)
        assertEquals("SM-R390_00031_256x402", identity.settingName)
        assertTrue(identity.installable)
    }

    @Test
    fun placeholderIdIsNotInstallable() {
        // The editor's builder default: parseable, valid container, but the id does
        // not fit the install protocol byte — the exact shape that used to be
        // silently clamped to 255 on the wire.
        val bytes = buildContainer("90001")
        val identity = FaceIdentity.of(WatchFaceParser.parse(bytes))
        assertEquals("90001", identity.settingFaceId)
        assertEquals(90001, identity.faceIdNumber)
        assertFalse(identity.installable)
    }

    @Test
    fun remapRewritesSettingAndDirectoryUnderNewId() {
        val bytes = buildContainer("90001")
        val remapped = FaceIdRemapper.remap(
            WatchFaceParser.parse(bytes),
            FaceIdRemapper.Target.of(31),
        )

        // The rewritten container must parse clean and validate — every CRC and
        // tight-packing invariant has to hold after the identity surgery.
        val parsed = WatchFaceParser.parse(remapped)
        val identity = FaceIdentity.of(parsed)
        assertEquals("00031", identity.settingFaceId)
        assertEquals("SM-R390_00031_256x402", identity.directoryPrefix)
        assertEquals("SM-R390_00031_256x402", identity.settingName)
        assertTrue(identity.installable)

        val setting = (parsed.settingEntry?.payload as ContainerPayload.Setting).data
        assertEquals("00031", setting.faceId)
        assertEquals("SM-R390_00031_256x402", setting.name)
        assertTrue(
            parsed.entries.all { it.directory.path.contains("SM-R390_00031_256x402") }
        )
        assertTrue(
            FaceValidator.validate(remapped, retainReparsed = false).ok
        )
    }

    @Test
    fun remapPreservesPayloadBytes() {
        val bytes = buildContainer("00046")
        val before = WatchFaceParser.parse(bytes).styleEntries
            .map { (it.payload as ContainerPayload.Style).data.rasters.first().encodedSize() }
        val remapped = FaceIdRemapper.remap(
            WatchFaceParser.parse(bytes),
            FaceIdRemapper.Target.of(31),
        )
        val after = WatchFaceParser.parse(remapped).styleEntries
            .map { (it.payload as ContainerPayload.Style).data.rasters.first().encodedSize() }
        assertEquals(before, after)
    }

    @Test
    fun remappedContainerRoundTripsByteIdentical() {
        val bytes = buildContainer("90001")
        val remapped = FaceIdRemapper.remap(
            WatchFaceParser.parse(bytes),
            FaceIdRemapper.Target.of(9),
        )
        assertArrayEquals(remapped, WatchFaceSerializer.serialize(WatchFaceParser.parse(remapped)))
    }

    @Test
    fun targetRejectsOutOfRangeIds() {
        try {
            FaceIdRemapper.Target.of(0)
            fail("id 0 must be refused")
        } catch (_: IllegalArgumentException) {
        }
        try {
            FaceIdRemapper.Target.of(256)
            fail("id 256 must be refused")
        } catch (_: IllegalArgumentException) {
        }
        // 1..255 are the protocol byte's legal range.
        FaceIdRemapper.Target.of(1)
        FaceIdRemapper.Target.of(255)
    }
}
