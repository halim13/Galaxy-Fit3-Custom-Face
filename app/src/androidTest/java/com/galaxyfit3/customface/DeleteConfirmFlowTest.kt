package com.galaxyfit3.customface

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Rule
import org.junit.Test

/**
 * Drives every delete confirmation end to end on device:
 *   widget (inspector panel) -> cancel, then confirm
 *   background (top-bar menu) -> cancel, then confirm
 *   project (home card)       -> cancel, then confirm
 *
 * Requires the "ZZ-Test-Hapus" project to exist with a pre-seeded background
 * (the shell sets files/projects/zztest01/{seed.bin,design.json,background.png}).
 */
class DeleteConfirmFlowTest {

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

    private fun awaitGone(substr: String, label: String, timeoutMs: Long = 10_000) {
        compose.waitUntil(timeoutMillis = timeoutMs) {
            compose.onAllNodesWithText(substr, substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty()
        }
        println("gone: $label")
    }

    private fun tapFirstText(substr: String, label: String) {
        val m = compose.onAllNodesWithText(substr, substring = true, useUnmergedTree = true)
        check(m.fetchSemanticsNodes().isNotEmpty()) { "no text node: $label" }
        m[0].performClick()
        println("tapped: $label")
    }

    private fun tapFirstDesc(desc: String, label: String) {
        val m = compose.onAllNodes(hasContentDescription(desc, substring = false), useUnmergedTree = true)
        val nodes = m.fetchSemanticsNodes()
        check(nodes.isNotEmpty()) { "no content-desc node: $label" }
        m[0].performClick()
        println("tapped: $label")
    }

    private fun tapDialogConfirm() {
        // The confirm button is exact-text "Delete" (widget/background dialogs) or
        // "Delete Project" (project dialog, which spells out the stronger action).
        val labels = listOf("Delete", "Delete Project")
        for (label in labels) {
            val nodes = compose.onAllNodes(hasText(label), useUnmergedTree = true).fetchSemanticsNodes()
            if (nodes.isNotEmpty()) {
                compose.onAllNodes(hasText(label), useUnmergedTree = true)[nodes.size - 1].performClick()
                println("tapped: dialog confirm ($label)")
                return
            }
        }
        error("no dialog confirm button for $labels")
    }

    private fun tapDialogCancel() {
        val m = compose.onAllNodes(hasText("Cancel"), useUnmergedTree = true)
        val nodes = m.fetchSemanticsNodes()
        check(nodes.isNotEmpty()) { "no dialog cancel button" }
        m[nodes.size - 1].performClick()
        println("tapped: dialog cancel")
    }

    /** Taps the first donor card matching any of [labels], scrolling the grid if needed. */
    private fun tapAnyDonor(labels: List<String>) {
        repeat(4) { attempt ->
            for (label in labels) {
                val m = compose.onAllNodesWithText(label, substring = true, useUnmergedTree = true)
                if (m.fetchSemanticsNodes().isNotEmpty()) {
                    m[0].performClick()
                    println("tapped donor: $label (attempt $attempt)")
                    return
                }
            }
            device.swipe(640, 1600, 640, 700, 24) // scroll the LazyVerticalGrid
            Thread.sleep(800)
        }
        val visible = compose.onAllNodes(hasText(" ", substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .mapNotNull { n ->
                n.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }
                    .joinToString("") { it.text }
            }
        println("### library visible texts: ${visible.joinToString(" | ")}")
        error("no donor card found for $labels")
    }

    @Test
    fun deleteFlowsShowConfirmation() {
        awaitText("Galaxy Fit 3", "home", 20_000)
        Thread.sleep(1_500)

        awaitText("ZZ-Test-Hapus", "test project card", 20_000)
        tapFirstText("ZZ-Test-Hapus", "project card")
        awaitText("Preview", "editor top bar")
        Thread.sleep(3_000)

        // ---- 1. Widget delete via the inspector panel. ----
        tapFirstDesc("Add widget", "add-widget button")
        awaitText("Widget library", "widget library sheet")
        Thread.sleep(1_500) // thumbnails settle
        device.executeShellCommand("screencap -p /data/local/tmp/library_thumbs.png")
        tapAnyDonor(listOf("Static image", "Steps", "Heart rate", "Calories", "Value", "Time (digital)"))
        Thread.sleep(2_000) // addFromDonor -> inspector panel with delete button
        tapFirstDesc("Delete", "inspector delete button")
        awaitText("Delete Selected Widget?", "widget confirm dialog")
        tapDialogCancel()
        awaitGone("Delete Selected Widget?", "widget dialog after cancel")
        tapFirstDesc("Delete", "inspector delete button (2nd)")
        awaitText("Delete Selected Widget?", "widget confirm dialog again")
        tapDialogConfirm()
        awaitGone("Delete Selected Widget?", "widget dialog after confirm")
        Thread.sleep(2_000)
        println("=== widget delete confirmed ===")

        // ---- 2. Background delete via the top-bar menu. ----
        tapFirstDesc("Background", "top bar image icon")
        awaitText("Delete Background", "menu item")
        tapFirstText("Delete Background", "menu item")
        awaitText("Delete Background?", "background confirm dialog")
        tapDialogCancel()
        awaitGone("Delete Background?", "background dialog after cancel")
        tapFirstDesc("Background", "top bar image icon (2nd)")
        awaitText("Delete Background", "menu item (2nd)")
        tapFirstText("Delete Background", "menu item (2nd)")
        awaitText("Delete Background?", "background confirm dialog again")
        tapDialogConfirm()
        awaitGone("Delete Background?", "background dialog after confirm")
        Thread.sleep(2_500) // clearBackground: file delete + persist + re-render
        println("=== background delete confirmed ===")

        // Back to home via the editor's own back button: UiDevice.pressBack injects a
        // real key event, which HyperOS blocks (same policy that blocks `adb input`),
        // while performClick bypasses the input pipeline entirely.
        tapFirstText("←", "editor back button")
        // The editor now stops at a "Leave editor?" confirm before exiting.
        tapFirstText("Yes", "leave-editor confirm")
        awaitText("Download Face", "home top bar", 20_000)
        println("awaited: back on home")
        Thread.sleep(1_500)

        // ---- 3. Project delete via the home card. ----
        awaitText("ZZ-Test-Hapus", "project card again")
        tapFirstText("✕", "home card delete")
        awaitText("Delete Project?", "project confirm dialog")
        tapDialogCancel()
        awaitGone("Delete Project?", "project dialog after cancel")
        awaitText("ZZ-Test-Hapus", "project still listed after cancel")
        tapFirstText("✕", "home card delete (2nd)")
        awaitText("Delete Project?", "project confirm dialog again")
        tapDialogConfirm()
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText("ZZ-Test-Hapus", substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty()
        }
        println("=== project delete confirmed, card gone ===")
        println("=== ALL DELETE CONFIRMATION FLOWS OK ===")
    }
}
