package com.galaxyfit3.core.format.parser

import com.galaxyfit3.core.format.model.ContainerEntry
import com.galaxyfit3.core.format.model.ContainerPayload
import com.galaxyfit3.core.format.model.FontBinding
import com.galaxyfit3.core.format.model.WatchFaceContainer
import com.galaxyfit3.core.format.model.WidgetRecord
import com.galaxyfit3.core.format.model.WidgetType

/**
 * Reseats a borrowed text widget (Pair value or Comp composite) onto the recipient
 * face's own font slots and glyph tables. Every index a face's text records carry is
 * face-local, so a record left untouched after a cross-face bake points at the wrong
 * font and wrong strings on the watch:
 *
 *  - The font binding a Pair/Comp names by index lives in THAT face's font_N.bin list.
 *    Kept at the donor index, the baked face either resolves a different binding or none
 *    at all (a firmware that has fewer font slots than the donor reads out of range).
 *    Borrowed text instead seats on the seed's OWN bindings, matched by role
 *    (WF_BATT -> WF_BATTARY), falling back to the seed's WF_VALUE slot, then slot 0 —
 *    a slot the seed's own text already proves the watch renders.
 *
 *  - The glyph indexes (weekday/month/unit strings) a Comp/Pair names index into THAT
 *    face's locale tables (font_en.bin, font_cn0.bin, …). The seed's tables hold
 *    different strings at the same index, so the donor's tables must travel with the
 *    borrowed widget (unless a same-named table already exists in the seed).
 *
 * A face whose container has NO font bindings keeps the append path: the donor's own
 * bindings are copied in and records re-pointed at the appended slots, since there is
 * no seed font to seat them on.
 */
object TextBorrow {

    /** All glyph-table entries of [container] (font_en.bin, font_cn0.bin, …). */
    fun glyphEntries(container: WatchFaceContainer): List<ContainerEntry> =
        container.entries.filter { it.payload is ContainerPayload.GlyphTable }

    /** Donor glyph entries the baked face can carry without shadowing the seed's:
     *  same-named locale tables (font_en.bin …) already in the seed are left out. */
    fun nonCollidingGlyphEntries(seed: WatchFaceContainer, donor: List<ContainerEntry>): List<ContainerEntry> {
        val seedNames = seed.entries.mapTo(HashSet()) { it.name }
        return donor.filter { it.name !in seedNames }
    }

    /** The seed binding slot a donor font index should reference, or null when the seed
     *  has no bindings (the caller should append the donor's binding instead). The result
     *  is always within the seed's binding list, so the baked record uses a font the
     *  watch already renders. */
    fun seatIndex(
        donorBindings: List<FontBinding>,
        seedBindings: List<FontBinding>,
        donorFontIndex: Int
    ): Int? {
        if (seedBindings.isEmpty()) return null
        val donor = donorBindings.getOrNull(donorFontIndex) ?: return 0
        fun slot(role: String): Int? = seedBindings.indexOfFirst { it.role == role }.takeIf { it >= 0 }
        return slot(donor.role)
            ?: if (donor.role == "WF_BATT") slot("WF_BATTARY") else null
            ?: slot("WF_VALUE")
            ?: 0
    }

    /** Re-point a borrowed text record's font reference through [seat]. Pair stores its
     *  font in words[1]'s low byte, Comp in words[14]'s high half (compBindingIndex);
     *  everything else keeps its words. [seat] maps a donor-local font index to the
     *  index the baked face should use. */
    fun reseat(record: WidgetRecord, seat: (Int) -> Int): WidgetRecord {
        val words = when (record.type) {
            WidgetType.PAIR -> {
                if (record.words.size <= 1) return record
                val m = record.words.toMutableList()
                m[1] = (m[1] and 0xFFFFFF00.toInt()) or (seat(m[1] and 0xFF) and 0xFF)
                m
            }
            WidgetType.COMP -> {
                if (record.words.size <= 14) return record
                val m = record.words.toMutableList()
                val donorIndex = (m[14] ushr 16) and 0xFFFF
                m[14] = (m[14] and 0x0000FFFF) or ((seat(donorIndex) and 0xFFFF) shl 16)
                m
            }
            else -> return record
        }
        return record.copy(words = words)
    }
}