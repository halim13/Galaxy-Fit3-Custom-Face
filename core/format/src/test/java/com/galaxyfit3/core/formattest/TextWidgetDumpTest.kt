package com.galaxyfit3.core.formattest

import com.galaxyfit3.core.format.model.ContainerPayload
import com.galaxyfit3.core.format.parser.WatchFaceParser
import org.junit.Test

/** Throwaway diagnostic: dump text-widget records of a real seed for styling analysis. */
class TextWidgetDumpTest {
    @Test
    fun dump() {
        // Needs a seed extracted to /tmp; skip cleanly where it is absent, the same
        // guard RealStockFaceTest uses for its corpus file.
        val seed = java.io.File("/tmp/seed.bin")
        org.junit.Assume.assumeTrue("seed not present; extract a face to /tmp/seed.bin", seed.exists())
        val bytes = seed.readBytes()
        val parsed = WatchFaceParser.parse(bytes)
        parsed.styleEntries.forEachIndexed { si, e ->
            val s = (e.payload as? ContainerPayload.Style)?.data ?: return@forEachIndexed
            s.widgets.forEachIndexed { wi, w ->
                if (w.type == 5 || w.type == 13) {
                    println(
                        "style=$si w[$wi] type=${w.type} seq=${w.sequenceId} " +
                            "x=${w.x} y=${w.y} w=${w.wOrX2} h=${w.hOrY2} " +
                            "wordA=0x${w.wordA.toString(16)} words=${w.words.map { "0x" + it.toUInt().toString(16) }}"
                    )
                }
            }
        }
    }
}
