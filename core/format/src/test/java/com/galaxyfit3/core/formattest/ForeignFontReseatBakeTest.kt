package com.galaxyfit3.core.formattest

import com.galaxyfit3.core.format.model.*
import com.galaxyfit3.core.format.parser.BlankFaceBuilder
import com.galaxyfit3.core.format.parser.PlacedWidget
import com.galaxyfit3.core.format.parser.TextBorrow
import com.galaxyfit3.core.format.parser.WatchFaceParser
import com.galaxyfit3.core.format.validator.FaceValidator
import org.junit.Test
import java.io.File

/**
 * Bake a real seed + real donor text widgets exactly like rebuildNow does: seat each
 * borrowed PAIR/COMP font reference on the seed's OWN binding slots by role
 * ([TextBorrow.seatIndex], fonts are never appended when the seed has bindings), carry
 * the donor's locale tables behind the seed's, reparse the output and verify every
 * reseated font index lands inside the seed's proven binding range.
 */
class ForeignFontReseatBakeTest {

    private val facesRoot = File("/Users/halim13/Documents/learn/kotlin/GalaxyFit3/web/backend/faces")

    private fun styleOf(c: WatchFaceContainer): StyleEntry =
        (c.styleEntries.first().payload as ContainerPayload.Style).data

    @Test
    fun reseatSeatsOnSeedSlotsAndCarriesGlyphs() {
        val seedName = "00049"
        // 00112: WF_WEEK/WF_BATT/WF_STEP bindings + 8 localised glyph tables (font_en.bin
        // holds the weekday/unit strings its text widgets index). 00118: WF_DATA/WF_EQ
        // bindings, no glyph tables. Both are the "teks tidak tampil" repro.
        listOf("00118", "00112").forEach { donorName ->
            val seedF = File(facesRoot, "$seedName/original.bin")
            val donorF = File(facesRoot, "$donorName/original.bin")
            if (!seedF.exists() || !donorF.exists()) return@forEach
            val seedC = WatchFaceParser.parse(seedF.readBytes())
            val donorC = WatchFaceParser.parse(donorF.readBytes())
            val donorStyle = styleOf(donorC)
            val carried = TextBorrow.nonCollidingGlyphEntries(seedC, TextBorrow.glyphEntries(donorC))
                .map { it.copy(directory = it.directory.copy(path = "./SM-R390_90001_256x402/${it.name}")) }

            var baked = 0
            var lastReparsed: WatchFaceContainer? = null
            donorStyle.widgets.forEachIndexed { i, donorWidget ->
                if (donorWidget.type != WidgetType.PAIR && donorWidget.type != WidgetType.COMP) return@forEachIndexed
                val d = BlankFaceBuilder.extractDonor(donorStyle, i)
                // Same rule textBorrowPlan applies when the seed has bindings: no appends.
                val seat = { idx: Int -> TextBorrow.seatIndex(donorC.fontBindings, seedC.fontBindings, idx) ?: 0 }
                val reseated = TextBorrow.reseat(d.donor, seat)

                val variants = listOf(BlankFaceBuilder.buildStyle(
                    listOf(PlacedWidget(reseated, d.donorRasters, d.donor.x, d.donor.y)), null
                ))
                val bytes = BlankFaceBuilder.build(
                    seedC, "90001", "SM-R390_90001_256x402", listOf(),
                    null, extraEntries = carried, styles = variants
                )
                val reparsed = WatchFaceParser.parse(bytes)
                val out = styleOf(reparsed).widgets.first()
                val fontIdx = if (out.type == WidgetType.PAIR) out.words.getOrNull(1)?.and(0xFF)
                    else (((out.words.getOrNull(14) ?: 0) ushr 16) and 0xFFFF)
                check(fontIdx != null && fontIdx < seedC.fontBindings.size) {
                    "seed=$seedName donor=$donorName [$i] ${out.type}: fontIdx=$fontIdx must sit in seed range 0..${seedC.fontBindings.size}"
                }
                val result = FaceValidator.validate(bytes)
                check(result.ok) { "validate failed: ${result.errors}" }
                baked++
                lastReparsed = reparsed
            }

            check(baked > 0) { "seed=$seedName donor=$donorName: no PAIR/COMP widgets to bake" }
            // Seek the seed kept its own bindings (nothing appended) and the donor's locale
            // tables made it into the output right behind them.
            val out = lastReparsed!!
            check(out.fontBindings.size == seedC.fontBindings.size) {
                "seed=$seedName donor=$donorName: ${out.fontBindings.size} bindings, seed had ${seedC.fontBindings.size} — donor fonts must NOT be appended when the seed has bindings"
            }
            val donorGlyphNames = carried.map { it.name }.toSet()
            val carriedIntoOut = out.entries.filter { it.payload is ContainerPayload.GlyphTable }.map { it.name }
            check(donorGlyphNames.all { it in carriedIntoOut }) {
                "seed=$seedName donor=$donorName: donor glyph tables $donorGlyphNames missing from baked output"
            }
            println("seed=$seedName donor=$donorName: baked=$baked text widgets, fonts kept=${out.fontBindings.size}, glyphs carried=${carriedIntoOut.size}")
        }
    }
}