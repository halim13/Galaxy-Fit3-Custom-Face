package com.galaxyfit3.customface

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Verifies the arrow-button nudge controls: chevrons point in the actual movement
 * direction, single chevron = 1px, double = 10px. Reads the inspector's "(x, y)"
 * label before/after and checks the deltas land exactly.
 *
 * Requires the "ZZ-Test-Hapus" project fixture (Color Pie seed).
 */
class NudgeArrowTest {

    @get:Rule
    val compose = createAndroidComposeRule(MainActivity::class.java)

    private val device: UiDevice by lazy {
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    }

    private fun awaitText(substr: String, label: String, timeoutMs: Long = 30_000) {
        compose.waitUntil(timeoutMillis = timeoutMs) {
            compose.onAllNodesWithText(substr, substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        println("awaited: $label")
    }

    private fun tapDesc(desc: String, label: String) {
        val m = compose.onAllNodes(hasContentDescription(desc, substring = false), useUnmergedTree = true)
        check(m.fetchSemanticsNodes().isNotEmpty()) { "no node: $desc ($label)" }
        m[0].performClick()
        println("tapped: $label")
    }

    private fun tapText(substr: String, label: String) {
        val m = compose.onAllNodesWithText(substr, substring = true, useUnmergedTree = true)
        check(m.fetchSemanticsNodes().isNotEmpty()) { "no text: $label" }
        m[0].performClick()
        println("tapped: $label")
    }

    /** The inspector's "(x, y)" label, or null while the panel is missing. */
    private fun positionLabel(): Pair<Int, Int>? {
        val nodes = compose.onAllNodes(hasText(" ", substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes()
        for (n in nodes) {
            val text = n.config
                .getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }
                .joinToString("") { it.text }
            Regex("\\((\\d+), (\\d+)\\)").find(text)?.let {
                return it.groupValues[1].toInt() to it.groupValues[2].toInt()
            }
        }
        return null
    }

    private fun awaitPosition(label: String): Pair<Int, Int> {
        compose.waitUntil(timeoutMillis = 10_000) { positionLabel() != null }
        return positionLabel() ?: error("no position label: $label")
    }

    @Test
    fun nudgeArrowsMoveWidgetExactly() {
        awaitText("Galaxy Fit 3", "home", 20_000)
        Thread.sleep(1_500)
        awaitText("ZZ-Test-Hapus", "fixture project", 20_000)
        tapText("ZZ-Test-Hapus", "project card")
        awaitText("Preview", "editor")
        Thread.sleep(3_000)

        // Add one widget so the inspector panel with the arrows shows up.
        tapDesc("Add widget", "add-widget")
        awaitText("Widget library", "library")
        val donorLabels = listOf("Static image", "Steps", "Heart rate", "Calories", "Value", "Time (digital)")
        var added = false
        for (attempt in 0 until 4) {
            for (label in donorLabels) {
                val m = compose.onAllNodesWithText(label, substring = true, useUnmergedTree = true)
                if (m.fetchSemanticsNodes().isNotEmpty()) {
                    m[0].performClick()
                    added = true
                    break
                }
            }
            if (added) break
            device.swipe(640, 1600, 640, 700, 24)
            Thread.sleep(800)
        }
        check(added) { "no donor card found" }
        Thread.sleep(2_000)

        val start = awaitPosition("inspector visible")
        println("start position: $start")

        // Double chevron right: +10, twice.
        tapDesc("X ke kanan 10", "double chevron right #1")
        tapDesc("X ke kanan 10", "double chevron right #2")
        compose.waitUntil(10_000) { positionLabel()?.first == start.first + 20 }
        // Single chevron right: +1.
        tapDesc("X ke kanan 1", "single chevron right")
        compose.waitUntil(10_000) { positionLabel()?.first == start.first + 21 }

        // Single chevron down: +1, and double chevron down: +10.
        tapDesc("Y ke bawah 1", "single chevron down")
        tapDesc("Y ke bawah 10", "double chevron down")
        compose.waitUntil(10_000) { positionLabel()?.second == start.second + 11 }

        val moved = awaitPosition("after moves")
        assertEquals(start.first + 21, moved.first)
        assertEquals(start.second + 11, moved.second)

        // Arrows must also move left/up: back to the start exactly
        // (undo 21x / 11y).
        repeat(21) { tapDesc("X ke kiri 1", "single chevron left") }
        tapDesc("Y ke atas 10", "double chevron up")
        tapDesc("Y ke atas 1", "single chevron up")
        compose.waitUntil(15_000) {
            positionLabel()?.let { it.first == start.first && it.second == start.second } == true
        }
        assertEquals(start, awaitPosition("returned to start"))

        device.executeShellCommand("screencap -p /data/local/tmp/nudge_inspector.png")
        println("=== NUDGE ARROWS OK: +21x/+11y then back to $start ===")
    }
}
