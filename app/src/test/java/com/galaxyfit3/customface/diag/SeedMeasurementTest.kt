package com.galaxyfit3.customface.diag

import com.galaxyfit3.core.format.model.ContainerPayload
import com.galaxyfit3.core.format.parser.BlankFaceBuilder
import com.galaxyfit3.core.format.parser.WatchFaceParser
import com.galaxyfit3.core.format.parser.WatchFaceSerializer
import com.galaxyfit3.core.format.validator.FaceValidator
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Diagnostic, not a test: it measures what the editor's edit path costs on a real face,
 * so a performance change can be checked against numbers instead of a feeling.
 *
 * It skips unless a seed is placed at `app/build/diag/seed.bin`, which you can pull off a
 * device that has a project:
 *
 * ```
 * adb exec-out run-as com.galaxyfit3.customface \
 *   cat files/projects/<id>/seed.bin > app/build/diag/seed.bin
 * ```
 *
 * Reference figures for a 3.7 MB, 4-style stock face on this machine: parse retains
 * ~12.6 MB in ~54 ms; one container build + validate is ~47 ms and ~18 MB of allocation.
 */
class SeedMeasurementTest {

    private fun used(): Long {
        val rt = Runtime.getRuntime()
        return rt.totalMemory() - rt.freeMemory()
    }

    private fun mb(bytes: Long): String = "%,.1f MB".format(bytes / 1024.0 / 1024.0)

    /** Net heap growth across [block], after a forced collection so the figure is the
     *  allocation that survived — plus how long the block took. */
    private fun measure(label: String, block: () -> Unit): Long {
        System.gc()
        Thread.sleep(150)
        val before = used()
        val nanos = System.nanoTime()
        block()
        val millis = (System.nanoTime() - nanos) / 1_000_000
        val after = used()
        println("$label  net=${mb(after - before)}  time=${millis}ms")
        return after - before
    }

    @Test
    fun measure() {
        val file = File("build/diag/seed.bin")
        assumeTrue("no seed at build/diag/seed.bin", file.exists())
        val seed = file.readBytes()
        println("=== seed: ${seed.size} bytes (${mb(seed.size.toLong())}) ===")

        lateinit var parsed: com.galaxyfit3.core.format.model.WatchFaceContainer
        measure("parse    ") { parsed = WatchFaceParser.parse(seed) }

        println("entries=${parsed.entries.size}")
        parsed.entries.forEach { e ->
            val p = e.payload
            val size = when (p) {
                is ContainerPayload.Style -> p.data.entrySize
                is ContainerPayload.Unknown -> p.bytes.size
                else -> -1
            }
            println("  ${e.name}  ${p::class.simpleName}  " +
                if (size >= 0) "$size (${mb(size.toLong())})" else "-")
        }
        parsed.entries.forEach { e ->
            (e.payload as? ContainerPayload.Style)?.data?.let { s ->
                println("  style ${e.name}: widgets=${s.widgets.size} widgets=${s.widgetBytes}B " +
                    "rasters=${s.rasters.size} rasterBytes=${s.imageBytes} (${mb(s.imageBytes.toLong())})")
            }
        }
        val allStyleBytes = parsed.styleEntries.sumOf { (it.payload as? ContainerPayload.Style)?.data?.entrySize ?: 0 }
        println("  all style entries: ${parsed.styleEntries.size} totalling ${mb(allStyleBytes.toLong())}")
        println("  live heap after parse: ${mb(used())}")

        val faceId = (parsed.settingEntry?.payload as? ContainerPayload.Setting)?.data?.faceId ?: "90001"

        // The 13 donors the saved project actually placed, taken from style0.
        val seedStyles = parsed.styleEntries.mapNotNull { (it.payload as? ContainerPayload.Style)?.data }
        val s0 = seedStyles[0]
        val placedIdx = (0 until s0.widgets.size).toList()

        // Exactly what EditorViewModel.rebuildNow() does: the placed widgets remapped onto
        // every seed style, plus the AOD style.
        fun rebuild(): ByteArray {
            val variants = seedStyles.map { style ->
                val placed = placedIdx.map { i ->
                    val d = BlankFaceBuilder.extractDonor(style, i)
                    com.galaxyfit3.core.format.parser.PlacedWidget(d.donor, d.donorRasters, d.donor.x, d.donor.y)
                }
                BlankFaceBuilder.buildStyle(placed, null)
            }
            return BlankFaceBuilder.build(
                seed = parsed,
                faceId = faceId,
                faceName = "diag",
                placed = emptyList(),
                background = null,
                styles = variants.ifEmpty { null }
            )
        }

        var bytes = ByteArray(0)
        measure("rebuild  ") { bytes = rebuild() }
        println("  built container: ${bytes.size} (${mb(bytes.size.toLong())})")

        measure("validate ") { FaceValidator.validate(bytes) }

        measure("serialize") { WatchFaceSerializer.serialize(parsed) }

        System.gc(); Thread.sleep(150)
        val alloc0 = used()
        val nanos = System.nanoTime()
        repeat(5) { FaceValidator.validate(rebuild()) }
        val millis = (System.nanoTime() - nanos) / 1_000_000
        println("5x rebuild+validate: net=${mb(used() - alloc0)}  time=${millis}ms  (${millis / 5}ms each)")
    }
}
