package com.galaxyfit3.customface.viewmodel

import android.graphics.Bitmap
import android.graphics.Color
import com.galaxyfit3.core.format.WatchFaceFormat
import com.galaxyfit3.core.format.model.ContainerEntry
import com.galaxyfit3.core.format.model.ContainerPayload
import com.galaxyfit3.core.format.model.DirectoryEntry
import com.galaxyfit3.core.format.model.Raster
import com.galaxyfit3.core.format.model.SettingBin
import com.galaxyfit3.core.format.model.StyleEntry
import com.galaxyfit3.core.format.model.WidgetRecord
import com.galaxyfit3.core.format.model.WidgetType
import com.galaxyfit3.core.format.parser.WatchFaceSerializer
import com.galaxyfit3.core.image.Rgb565
import com.galaxyfit3.customface.data.ProjectStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * The background is a project-level asset with three states — photo (with a placement
 * mode), solid color, or stock (neither) — and the state must survive save/load and
 * reach the baked raster exactly as the editor preview showed it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackgroundOptionsTest {

    private lateinit var store: ProjectStore
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("bg-test").toFile()
        store = ProjectStore(File(root, "projects"))
    }

    private fun await(what: String, vm: EditorViewModel, condition: (EditorViewModel) -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            if (condition(vm)) return
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for $what")
    }

    private fun loadedVm(): Pair<EditorViewModel, String> {
        val project = store.create(buildSeed(), "smr390", "Test")
        val vm = EditorViewModel(store)
        vm.load(project.id)
        await("seed load", vm) { it.ui.value.seedLoaded }
        return vm to project.id
    }

    @Test
    fun `solid color replaces photo and persists across reload`() = runTest {
        val (vm, id) = loadedVm()
        // A photo first: the color must replace it, not stack on it.
        val photo = Bitmap.createBitmap(256, 402, Bitmap.Config.ARGB_8888)
        photo.eraseColor(Color.WHITE)
        vm.setBackground(photo)
        await("photo set", vm) { it.ui.value.background != null }

        vm.setBackgroundColor("#FF8800")
        await("color set", vm) { it.ui.value.backgroundColor != null }
        assertNull("photo must be dropped when a color is applied", vm.ui.value.background)
        assertEquals("#FF8800", vm.ui.value.backgroundColor)

        // Survives a fresh editor session.
        val fresh = EditorViewModel(store)
        fresh.load(id)
        await("reload color", fresh) { it.ui.value.backgroundColor != null }
        assertEquals("#FF8800", fresh.ui.value.backgroundColor)
        assertNull(fresh.ui.value.background)
    }

    @Test
    fun `backgroundFit manual placement persists and is used by the raster encoder`() = runTest {
        val app = org.robolectric.RuntimeEnvironment.getApplication() as android.content.Context
        val ctxStore = ProjectStore(File(root, "projects-ctx"), app)
        val project = ctxStore.create(buildSeed(), "smr390", "Test")
        val vm = EditorViewModel(ctxStore)
        vm.load(project.id)
        await("load", vm) { it.ui.value.seedLoaded }

        val photo = Bitmap.createBitmap(256, 402, Bitmap.Config.ARGB_8888)
        photo.eraseColor(Color.WHITE)
        val png = java.io.ByteArrayOutputStream().apply { photo.compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
        val uri = android.net.Uri.parse("content://test/photo.png")
        org.robolectric.Shadows.shadowOf(app.contentResolver).registerInputStream(uri, png.inputStream())

        // Off-centre manual placement: only the manual string round-trips precisely.
        val mode = Rgb565.manualModeString(scale = 1.25f, tx = 0.1f, ty = -0.05f)
        vm.setBackgroundFromUri(uri, mode)
        await("fit saved", vm) { ctxStore.get(project.id)?.styleEdits[0]?.backgroundFit == mode }

        val fresh = EditorViewModel(ctxStore)
        fresh.load(project.id)
        await("reload fit", fresh) { it.ui.value.backgroundFit == mode }
        assertEquals(mode, fresh.ui.value.backgroundFit)
        // The stored photo round-trips too.
        assertNotNull(fresh.ui.value.background)
    }

    @Test
    fun `clearBackground drops both photo and color`() = runTest {
        val (vm, _) = loadedVm()
        vm.setBackgroundColor("#112233")
        await("color set", vm) { it.ui.value.backgroundColor != null }

        vm.clearBackground()
        await("cleared", vm) { it.ui.value.backgroundColor == null && it.ui.value.background == null }
    }

    @Test
    fun `baked output carries the solid color background`() = runTest {
        val (vm, _) = loadedVm()
        vm.setBackgroundColor("#204060")
        await("color set", vm) { it.ui.value.backgroundColor != null }
        await("rebuild", vm) { it.ui.value.generatedBytes != null }

        val out = kotlinx.coroutines.runBlocking { vm.bakeOutput() }
        assertNotNull(out)
        // The reparse guard inside bakeOutput's rebuild validates structure; here we
        // verify the color reached the raster: scan the output for the RGB565 pattern
        // of #204060. RGB565 of (0x20,0x40,0x60) = 0x2823, little-endian bytes 23 28.
        val rgb565 = Rgb565.toRgb565(0x20, 0x40, 0x60)
        assertEquals(0x220C, rgb565)
        val pattern = byteArrayOf(rgb565.toByte(), (rgb565 shr 8).toByte())
        val bytes = out!!.readBytes()
        var matches = 0
        var i = 0
        while (i <= bytes.size - pattern.size) {
            if (bytes[i] == pattern[0] && bytes[i + 1] == pattern[1]) matches++
            i++
        }
        assertTrue(
            "baked face should contain the solid color raster (found $matches matches)",
            matches >= WatchFaceFormat.PANEL_WIDTH * WatchFaceFormat.PANEL_HEIGHT / 2
        )
    }
}

// ------------------------------------------------------------------- seed fixture

private fun buildSeed(): ByteArray {
    val raster = Raster(
        WatchFaceFormat.PANEL_WIDTH,
        WatchFaceFormat.PANEL_HEIGHT,
        WatchFaceFormat.FORMAT_RGB565,
        ByteArray(WatchFaceFormat.PANEL_WIDTH * WatchFaceFormat.PANEL_HEIGHT * 2 + 4)
    )
    val valueWidget = WidgetRecord(
        type = WidgetType.PAIR,
        sequenceId = WatchFaceFormat.SEQ_STEPS,
        opaque1 = 0,
        globalIndex = 0,
        recordSize = 52,
        opaque2 = 0,
        x = 40, y = 50,
        wOrX2 = 0, hOrY2 = 0,
        wordA = 1L,
        words = listOf(0xFFFFFFFF.toInt(), 0, 5, 0)
    )
    val style = StyleEntry(
        widgetCount = 1,
        widgetBytes = 52,
        imageBytes = raster.encodedSize(),
        headerUnknown = 0x400,
        widgets = listOf(valueWidget),
        rasters = listOf(raster)
    )
    val entries = listOf(
        ContainerEntry(
            DirectoryEntry("./SM-R390_90001_256x402/setting.bin", 0, WatchFaceFormat.SETTING_SIZE, 0),
            ContainerPayload.Setting(SettingBin("90001", 40000, 1, 0xFFFF, "SM-R390_90001_256x402"))
        ),
        ContainerEntry(
            DirectoryEntry("./SM-R390_90001_256x402/style0.bin", 0, style.entrySize, 0),
            ContainerPayload.Style(style)
        )
    )
    return WatchFaceSerializer.serializeEntries(WatchFaceFormat.VERSION, entries)
}
