package com.galaxyfit3.core.formattest

import com.galaxyfit3.core.format.parser.WatchFaceParser
import com.galaxyfit3.core.format.parser.WatchFaceSerializer
import com.galaxyfit3.core.format.validator.FaceValidator
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RealStockFaceTest {

    private val seedFile = File("/tmp/facex/assets/SM-R390_00031_256x402.bin")

    private fun stockFaceBytes(): ByteArray {
        org.junit.Assume.assumeTrue("stock face not present; extract from Samsung store to /tmp/facex", seedFile.exists())
        return seedFile.readBytes()
    }

    @Test
    fun parsesRealStockFace() {
        val bytes = stockFaceBytes()
        val container = WatchFaceParser.parse(bytes)
        val setting = (container.settingEntry?.payload as com.galaxyfit3.core.format.model.ContainerPayload.Setting).data
        assertEquals("SM-R390_00031_256x402", setting.name)
        assertEquals("00031", setting.faceId)
        assertEquals(6, setting.styleCountField)
        assertEquals(6, container.styleEntries.size)
        val s0 = (container.styleEntries[0].payload as com.galaxyfit3.core.format.model.ContainerPayload.Style).data
        assertTrue("style0 must donate widgets", s0.widgets.isNotEmpty())
        assertTrue("style0 must carry rasters", s0.rasters.isNotEmpty())
        println("face=${container.settingEntry?.name} styles=${container.styleEntries.size} " +
            "widgets=${s0.widgets.size} rasters=${s0.rasters.size}")
    }

    @Test
    fun realStockFaceRoundTripsByteIdentical() {
        val bytes = stockFaceBytes()
        val reencoded = WatchFaceSerializer.serialize(WatchFaceParser.parse(bytes))
        for (i in bytes.indices) {
            if (i in 16..17) continue
            if (bytes[i] != reencoded[i]) {
                println("FIRST DIFF at byte $i (0x${i.toString(16)}): " +
                    "orig=${bytes[i].toInt() and 0xFF} (0x${(bytes[i].toInt() and 0xFF).toString(16)}) " +
                    "new=${reencoded[i].toInt() and 0xFF} (0x${(reencoded[i].toInt() and 0xFF).toString(16)})")
                break
            }
        }
        assertArrayEquals(bytes, reencoded)
    }

    @Test
    fun realStockFaceValidates() {
        val bytes = stockFaceBytes()
        val result = FaceValidator.validate(bytes)
        println("errors=${result.errors} warnings=${result.warnings} size=${result.sizeBytes}")
        assertTrue(result.errors.joinToString(), result.ok)
    }
}