package com.galaxyfit3.core.image

import org.junit.Assert.assertEquals
import org.junit.Test

class Rgb565Test {

    @Test
    fun manualRoundTrip_keepsExactNumbers() {
        val s = Rgb565.manualModeString(0.5f, -0.25f, 1.5f, rotation = 90f, flip = 3)
        val t = Rgb565.parseManual(s)
        assertEquals(0.5f, t.scale, 0f)
        assertEquals(-0.25f, t.tx, 0f)
        assertEquals(1.5f, t.ty, 0f)
        assertEquals(90f, t.rotation, 0f)
        assertEquals(3, t.flip)
    }

    @Test
    fun fractionalRotation_survivesRoundTrip() {
        val t = Rgb565.parseManual(Rgb565.manualModeString(1f, 0f, 0f, rotation = 33.7f))
        assertEquals(33.7f, t.rotation, 0.001f)
    }

    @Test
    fun oldThreePartStrings_parseWithNoRotationFlip() {
        val t = Rgb565.parseManual("manual@1.0000;0.0000;0.0000")
        assertEquals(1f, t.scale, 0f)
        assertEquals(0f, t.rotation, 0f)
        assertEquals(0, t.flip)
    }

    @Test
    fun presets_mapToNearestManualTransform() {
        // Same aspect: cover and fit baselines coincide -> the fit chip keeps scale 1.
        assertEquals(
            Rgb565.ManualTransform(1f, 0f, 0f, 0f, 0),
            Rgb565.manualPresetParams("cover", 4, 2, 8, 4)
        )
        assertEquals(
            Rgb565.ManualTransform(1f, 0f, 0f, 0f, 0),
            Rgb565.manualPresetParams("fit", 4, 2, 8, 4)
        )
        // Source wider than the frame: cover base = 2x, fit base = 1x -> fit scales to half.
        assertEquals(
            Rgb565.ManualTransform(0.5f, 0f, 0f, 0f, 0),
            Rgb565.manualPresetParams("fit", 4, 4, 8, 4)
        )
        // Center with the source already larger than the frame = no scaling (abs 1x).
        assertEquals(
            Rgb565.ManualTransform(2f, 0f, 0f, 0f, 0),
            Rgb565.manualPresetParams("center", 16, 8, 8, 4)
        )
        // Center with a smaller source letterboxes like fit.
        assertEquals(
            Rgb565.ManualTransform(0.5f, 0f, 0f, 0f, 0),
            Rgb565.manualPresetParams("center", 4, 4, 8, 4)
        )
        // Stretch has no uniform-manual equivalent: show cover baseline and round-trip.
        assertEquals(
            Rgb565.ManualTransform(1f, 0f, 0f, 0f, 0),
            Rgb565.manualPresetParams("stretch", 4, 4, 8, 4)
        )
        assertEquals(
            Rgb565.ManualTransform(1f, 0f, 0f, 0f, 0),
            Rgb565.manualPresetParams("bogus", 4, 4, 8, 4)
        )
        assertEquals(
            Rgb565.ManualTransform(1f, 0f, 0f, 0f, 0),
            Rgb565.manualPresetParams("fit", 0, 0, 8, 4)
        )
    }

    @Test
    fun droppingAlpha_compositesSoftEdgeOverBlack() {
        // getPixels returns unpremultiplied ARGB: a premultiplied near-transparent white
        // edge (alpha 1) reads back as white, which used to bake a white fringe.
        assertEquals(0x010101, Rgb565.flattenOverBlack((1 shl 24) or 0xFFFFFF) and 0xFFFFFF)
        assertEquals(0x000000, Rgb565.flattenOverBlack(0) and 0xFFFFFF)
        // Opaque pixels are unchanged.
        assertEquals(0x123456, Rgb565.flattenOverBlack((0xFF shl 24) or 0x123456) and 0xFFFFFF)
    }

    @Test
    fun presetParams_matchesEncoderBaseline() {
        // A stored "fit" preset must re-encode to the same framing as the legacy fit mode:
        // the manual scale relative to cover equals min/max of the fit/cover base ratio.
        val t = Rgb565.manualPresetParams("fit", 5, 3, 8, 4)
        assertEquals(0.8333334f, t.scale, 0.0001f)
        assertEquals(0f, t.tx, 0f)
        assertEquals(0f, t.ty, 0f)
    }
}