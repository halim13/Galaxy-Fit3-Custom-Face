package com.galaxyfit3.customface.viewmodel

import com.galaxyfit3.core.format.WatchFaceFormat
import com.galaxyfit3.core.format.model.*
import com.galaxyfit3.core.format.parser.WatchFaceParser
import com.galaxyfit3.core.format.parser.WatchFaceSerializer
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorViewModelTest {

    private lateinit var store: ProjectStore
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("editor-vm-test").toFile()
        store = ProjectStore(File(root, "projects"))
    }

    // A seed with one value widget (steps, seq 29) at a known position.
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

    private fun awaitLoaded(vm: EditorViewModel): EditorUiState {
        // load() parses on Dispatchers.Default; poll until seed is ready.
        await("seed load") { vm.ui.value.seedLoaded }
        // load() ends with a refresh(); wait for that first build too, so a later
        // "the bytes changed" check cannot be satisfied by the initial one.
        await("initial rebuild") { vm.ui.value.generatedBytes != null }
        return vm.ui.value
    }

    /** Preview renders, container builds and saves are queued on the editor's background
     * engine, so their results are polled for instead of assumed on return. */
    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for $what")
    }

    private fun loadedVm(): EditorViewModel {
        val project = store.create(buildSeed(), "smr390", "Test")
        val vm = EditorViewModel(store)
        vm.load(project.id)
        awaitLoaded(vm)
        return vm
    }

    @Test
    fun `text colour override survives reopen`() = runTest {
        val project = store.create(buildSeed(), "smr390", "Test")
        val vm = EditorViewModel(store)
        vm.load(project.id)
        awaitLoaded(vm)
        await("widgets placed") { vm.ui.value.placed.isNotEmpty() }
        val id = vm.ui.value.placed[0].id
        vm.select(id)
        vm.setTextColor(0xFFE53935.toInt())
        await("tint saved") { store.get(project.id)?.placed?.firstOrNull()?.color == "#E53935" }
        val fresh = EditorViewModel(store)
        fresh.load(project.id)
        awaitLoaded(fresh)
        assertEquals(0xFFE53935.toInt(), fresh.ui.value.placed[0].tint)
        // Clearing returns to the firmware colour (null tint).
        fresh.select(fresh.ui.value.placed[0].id)
        fresh.clearTextColor()
        await("tint cleared") { store.get(project.id)?.placed?.firstOrNull()?.color == null }
        assertEquals(null, fresh.ui.value.placed[0].tint)
    }

    @Test
    fun `load seed exposes donor widgets and auto-fills default layout`() = runTest {
        val vm = loadedVm()
        val state = vm.ui.value
        assertEquals(1, state.donors.size)
        assertEquals(WidgetMeaning.STEPS, state.donors[0].meaning)
        assertEquals(40, state.donors[0].x)
        assertEquals(50, state.donors[0].y)
        // A freshly imported face opens showing the stock layout, not an empty canvas.
        assertEquals(1, state.placed.size)
        assertEquals(40, state.placed[0].x)
        assertEquals(50, state.placed[0].y)
        assertNull(state.error)
    }

    @Test
    fun `addFromDonor places widget and selects it`() = runTest {
        val vm = loadedVm()
        val before = vm.ui.value.generatedBytes
        vm.addFromDonor(0)
        await("container rebuild") { vm.ui.value.generatedBytes !== before }
        val state = vm.ui.value
        assertEquals(2, state.placed.size)
        val placed = state.placed[1]
        assertEquals(40, placed.x)
        assertEquals(50, placed.y)
        assertEquals(placed.id, state.selectedId)
        assertNotNull(state.generatedBytes)
    }

    @Test
    fun `reloading the same project keeps in-memory edits`() = runTest {
        val project = store.create(buildSeed(), "smr390", "Test")
        val vm = EditorViewModel(store)
        vm.load(project.id)
        awaitLoaded(vm)
        vm.addFromDonor(0)
        assertEquals(2, vm.ui.value.placed.size)
        // Re-entering the editor after Preview must not reset the session's edits.
        vm.load(project.id)
        assertEquals(2, vm.ui.value.placed.size)
    }

    @Test
    fun `reopening a project restores the saved style`() = runTest {
        val project = store.create(buildTwoStyleSeed(), "smr390", "Test")
        val vm = EditorViewModel(store)
        vm.load(project.id)
        awaitLoaded(vm)
        vm.selectStyle(1)
        await("style saved") { store.get(project.id)?.styleIndex == 1 }
        // A fresh editor session (reopened from the project list) shows style 2 again.
        val fresh = EditorViewModel(store)
        fresh.load(project.id)
        awaitLoaded(fresh)
        assertEquals(1, fresh.ui.value.selectedStyleIndex)
    }

    @Test
    fun `selectStyle persists the chosen style index`() = runTest {
        val project = store.create(buildTwoStyleSeed(), "smr390", "Test")
        val vm = EditorViewModel(store)
        vm.load(project.id)
        awaitLoaded(vm)
        assertEquals(0, store.get(project.id)!!.styleIndex)
        vm.selectStyle(1)
        // Preview reads this index to open on the style the user was editing.
        await("style saved") { store.get(project.id)?.styleIndex == 1 }
    }

    @Test
    fun `moveSelectedTo updates position and clamps to panel`() = runTest {
        val vm = loadedVm()
        vm.addFromDonor(0)
        val id = vm.ui.value.placed[0].id

        vm.moveSelectedTo(id, 100, 120)
        val moved = vm.ui.value.placed[0]
        assertEquals(100, moved.x)
        assertEquals(120, moved.y)

        vm.moveSelectedTo(id, -9999, 99999)
        val clamped = vm.ui.value.placed[0]
        assertEquals(0, clamped.x)
        assertEquals(WatchFaceFormat.PANEL_HEIGHT, clamped.y)
    }

    @Test
    fun `resetPositions moves widgets back to stock but keeps tint`() = runTest {
        val vm = loadedVm()
        vm.addFromDonor(0)
        val id = vm.ui.value.placed[0].id
        vm.moveSelectedTo(id, 100, 120)
        vm.select(id)
        vm.setTextColor(0xFFE53935.toInt())
        await("tint saved") { store.get(vm.ui.value.projectId)?.placed?.firstOrNull()?.color == "#E53935" }
        vm.resetPositions()
        await("positions reset") {
            val p = vm.ui.value.placed.firstOrNull() ?: return@await false
            p.x == 40 && p.y == 50
        }
        val p = vm.ui.value.placed[0]
        assertEquals(40, p.x)
        assertEquals(50, p.y)
        assertEquals(0xFFE53935.toInt(), p.tint)
    }

    @Test
    fun `finishDrag persists placement back to store`() = runTest {
        val project = store.create(buildSeed(), "smr390", "Test")
        val vm = EditorViewModel(store)
        vm.load(project.id)
        awaitLoaded(vm)
        vm.addFromDonor(0)
        val id = vm.ui.value.placed[0].id
        vm.moveSelectedTo(id, 77, 66)
        vm.finishDrag()

        await("placement saved") { store.get(project.id)?.placed?.firstOrNull()?.x == 77 }
        val saved = store.get(project.id)!!
        assertEquals(2, saved.placed.size)
        assertEquals(77, saved.placed[0].x)
        assertEquals(66, saved.placed[0].y)
    }

    @Test
    fun `removeSelected removes widget and deselects`() = runTest {
        val vm = loadedVm()
        vm.addFromDonor(0)
        vm.removeSelected()
        val state = vm.ui.value
        // The auto-filled default widget stays; only the added copy is removed.
        assertEquals(1, state.placed.size)
        assertNull(state.selectedId)
    }

    @Test
    fun `duplicateSelected adds a copy at offset and selects it`() = runTest {
        val vm = loadedVm()
        vm.addFromDonor(0)
        vm.duplicateSelected()
        val state = vm.ui.value
        assertEquals(3, state.placed.size)
        val dup = state.placed[2]
        assertEquals(40 + 12, dup.x)
        assertEquals(50 + 12, dup.y)
        assertEquals(dup.id, state.selectedId)
    }

    @Test
    fun `nudge moveSelected persists by design`() = runTest {
        val project = store.create(buildSeed(), "smr390", "Test")
        val vm = EditorViewModel(store)
        vm.load(project.id)
        awaitLoaded(vm)
        vm.addFromDonor(0)
        vm.moveSelected(5, -5)
        await("nudge saved") { store.get(project.id)?.placed?.any { it.x == 45 && it.y == 45 } == true }
        // The nudge is the added widget, among the auto-filled default + copy.
        assertTrue(store.get(project.id)!!.placed.any { it.x == 45 && it.y == 45 })
    }

    @Test
    fun `missing project surfaces error state`() = runTest {
        val vm = EditorViewModel(store)
        vm.load("nope")
        assertEquals("Project not found", vm.ui.value.error)
    }

    // A seed with two distinct colorways: each style's static widget points at its own raster.
    private fun buildTwoStyleSeed(): ByteArray {
        fun style(r: Raster) = StyleEntry(
            widgetCount = 1,
            widgetBytes = 40,
            imageBytes = r.encodedSize(),
            headerUnknown = 0x400,
            widgets = listOf(spriteWidget()),
            rasters = listOf(r)
        )
        val entries = listOf(
            ContainerEntry(
                DirectoryEntry("./SM-R390_90001_256x402/setting.bin", 0, WatchFaceFormat.SETTING_SIZE, 0),
                ContainerPayload.Setting(SettingBin("90001", 40000, 2, 0xFFFF, "SM-R390_90001_256x402"))
            ),
            ContainerEntry(
                DirectoryEntry("./SM-R390_90001_256x402/style0.bin", 0, style(coloredRaster(0)).entrySize, 0),
                ContainerPayload.Style(style(coloredRaster(0)))
            ),
            ContainerEntry(
                DirectoryEntry("./SM-R390_90001_256x402/style1.bin", 0, style(coloredRaster(1)).entrySize, 0),
                ContainerPayload.Style(style(coloredRaster(1)))
            )
        )
        return WatchFaceSerializer.serializeEntries(WatchFaceFormat.VERSION, entries)
    }

    private fun coloredRaster(firstPixel: Int): Raster {
        val data = ByteArray(WatchFaceFormat.PANEL_WIDTH * WatchFaceFormat.PANEL_HEIGHT * 2 + 4)
        data[0] = firstPixel.toByte()
        data[1] = 0x10
        return Raster(
            WatchFaceFormat.PANEL_WIDTH,
            WatchFaceFormat.PANEL_HEIGHT,
            WatchFaceFormat.FORMAT_RGB565,
            data
        )
    }

    // A sprite frame referencing raster offset 0 (patchWidget rewrites the offset).
    private fun spriteWidget() = WidgetRecord(
        type = WidgetType.SPRITE,
        sequenceId = 0,
        opaque1 = 0,
        globalIndex = 1,
        recordSize = 40,
        opaque2 = 0,
        x = 0, y = 0,
        wOrX2 = WatchFaceFormat.PANEL_WIDTH,
        hOrY2 = WatchFaceFormat.PANEL_HEIGHT,
        wordA = 1L,
        words = listOf(0)
    )

    @Test
    fun `load seed exposes every style`() = runTest {
        val project = store.create(buildTwoStyleSeed(), "smr390", "Test")
        val vm = EditorViewModel(store)
        vm.load(project.id)
        val state = awaitLoaded(vm)
        assertEquals(2, state.styleCount)
        assertEquals(2, state.stylePreviews.size)
        assertEquals(0, state.selectedStyleIndex)
    }

    @Test
    fun `resetToDefault restores only the active style and keeps the others`() = runTest {
        val project = store.create(buildTwoStyleSeed(), "smr390", "Test")
        val vm = EditorViewModel(store)
        vm.load(project.id)
        awaitLoaded(vm)
        vm.addFromDonor(0)
        await("style0 has its duplicate") { vm.ui.value.placed.size == 2 }
        vm.selectStyle(1)
        await("style1 active") { vm.ui.value.selectedStyleIndex == 1 }
        vm.addFromDonor(0)
        await("style1 has its duplicate") { vm.ui.value.placed.size == 2 }
        await("both styles marked modified") { vm.ui.value.modifiedStyles == setOf(0, 1) }
        vm.selectStyle(0)
        await("style0 active after switch back") { vm.ui.value.selectedStyleIndex == 0 }
        vm.resetToDefault()
        await("style0 reset, style1 untouched") {
            vm.ui.value.modifiedStyles == setOf(1) && vm.ui.value.placed.size == 1
        }
        val fresh = EditorViewModel(store)
        fresh.load(project.id)
        awaitLoaded(fresh)
        fresh.selectStyle(1)
        await("style1 edit survived reopen") { fresh.ui.value.placed.size == 2 }
        fresh.selectStyle(0)
        await("style0 stayed stock after reopen") { fresh.ui.value.placed.size == 1 }
    }

    @Test
    fun `rebuild keeps each style colorway instead of duplicating style0`() = runTest {
        val project = store.create(buildTwoStyleSeed(), "smr390", "Test")
        val vm = EditorViewModel(store)
        vm.load(project.id)
        awaitLoaded(vm)
        val before = vm.ui.value.generatedBytes
        vm.addFromDonor(0)
        await("container rebuild") { vm.ui.value.generatedBytes !== before }
        val bytes = vm.ui.value.generatedBytes ?: throw AssertionError("no generated bytes")
        val reparsed = WatchFaceParser.parse(bytes)
        assertEquals(2, reparsed.styleEntries.size)
        val s0 = (reparsed.styleEntries[0].payload as ContainerPayload.Style).data
        val s1 = (reparsed.styleEntries[1].payload as ContainerPayload.Style).data
        assertEquals(1, s0.rasters.size)
        assertEquals(1, s1.rasters.size)
        assertTrue("style rasters must stay distinct", s0.rasters[0].pixels[0] != s1.rasters[0].pixels[0])
    }

    @Test
    fun `edited rebuild keeps the seed faceId so the watch slot stays visible`() = runTest {
        val project = store.create(buildTwoStyleSeed(), "smr390", "Test")
        val vm = EditorViewModel(store)
        vm.load(project.id)
        awaitLoaded(vm)
        val before = vm.ui.value.generatedBytes
        vm.addFromDonor(0)
        await("container rebuild") { vm.ui.value.generatedBytes !== before }
        // The watch only shows faces registered in the Wearable list, so the edited face
        // must keep the stock id to overwrite a slot that exists instead of vanishing.
        val parsed = WatchFaceParser.parse(vm.ui.value.generatedBytes ?: throw AssertionError("no bytes"))
        val id = (parsed.settingEntry?.payload as ContainerPayload.Setting).data.faceId
        assertEquals("90001", id)
        assertEquals(
            "./SM-R390_90001_256x402/setting.bin",
            parsed.settingEntry?.directory?.path
        )
    }

    @Test
    fun `baked output embeds a regenerated preview raster per style`() = runTest {
        val project = store.create(buildTwoStyleSeed(), "smr390", "Test")
        val vm = EditorViewModel(store)
        vm.load(project.id)
        awaitLoaded(vm)
        val file = vm.bakeOutput() ?: throw AssertionError("bakeOutput returned null")
        val parsed = WatchFaceParser.parse(file.readBytes())
        val preview = parsed.entries.find { it.name == "preview.bin" }
            ?: throw AssertionError("output.bin has no preview.bin entry")
        val rasters = (preview.payload as ContainerPayload.Preview).rasters

        assertEquals(2, rasters.size)
        rasters.forEach { r ->
            assertEquals(178, r.width)
            assertEquals(280, r.height)
            assertEquals(WatchFaceFormat.FORMAT_RGB565, r.format)
        }
    }

    @Test
    fun `picked background is persisted and clearBackground removes it`() = runTest {
        val app = org.robolectric.RuntimeEnvironment.getApplication() as android.content.Context
        val ctxStore = ProjectStore(File(root, "projects-ctx"), app)
        val project = ctxStore.create(buildSeed(), "smr390", "Test")
        val vm = EditorViewModel(ctxStore)
        vm.load(project.id)
        awaitLoaded(vm)

        // A tiny decodable PNG standing in for the picked photo.
        val png = java.io.ByteArrayOutputStream().use { out ->
            android.graphics.Bitmap.createBitmap(64, 96, android.graphics.Bitmap.Config.ARGB_8888)
                .apply { eraseColor(0xFF3366AA.toInt()) }
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 90, out)
            out.toByteArray()
        }
        val uri = android.net.Uri.parse("content://test/photo.png")
        org.robolectric.Shadows.shadowOf(app.contentResolver).registerInputStream(uri, png.inputStream())

        vm.setBackgroundFromUri(uri)
        await("background applied") { vm.ui.value.background != null }
        await("backgroundUri persisted") { ctxStore.get(project.id)?.backgroundUri != null }
        assertTrue("background file must exist", ctxStore.backgroundFile(project.id, 0).exists())

        vm.clearBackground()
        await("background cleared") { vm.ui.value.background == null }
        await("backgroundUri removed from design.json") {
            ctxStore.get(project.id)?.backgroundUri == null
        }
        assertTrue("background file must be deleted", !ctxStore.backgroundFile(project.id, 0).exists())
    }

    // A DIFFERENT face (heart-rate widget at a fresh position) standing in for whatever
    // file the user imports as a widget library.
    private fun buildDonorFace(): ByteArray {
        val raster = Raster(
            WatchFaceFormat.PANEL_WIDTH,
            WatchFaceFormat.PANEL_HEIGHT,
            WatchFaceFormat.FORMAT_RGB565,
            ByteArray(WatchFaceFormat.PANEL_WIDTH * WatchFaceFormat.PANEL_HEIGHT * 2 + 4)
        )
        val widget = WidgetRecord(
            type = WidgetType.PAIR,
            sequenceId = WatchFaceFormat.SEQ_HEART_RATE,
            opaque1 = 0,
            globalIndex = 0,
            recordSize = 52,
            opaque2 = 0,
            x = 60, y = 70,
            wOrX2 = 0, hOrY2 = 0,
            wordA = 1L,
            words = listOf(0xFFFFFFFF.toInt(), 0, 5, 0)
        )
        val style = StyleEntry(
            widgetCount = 1,
            widgetBytes = 52,
            imageBytes = raster.encodedSize(),
            headerUnknown = 0x400,
            widgets = listOf(widget),
            rasters = listOf(raster)
        )
        val entries = listOf(
            ContainerEntry(
                DirectoryEntry("./SM-R390_99999_256x402/setting.bin", 0, WatchFaceFormat.SETTING_SIZE, 0),
                ContainerPayload.Setting(SettingBin("99999", 40000, 1, 0xFFFF, "SM-R390_99999_256x402"))
            ),
            ContainerEntry(
                DirectoryEntry("./SM-R390_99999_256x402/style0.bin", 0, style.entrySize, 0),
                ContainerPayload.Style(style)
            )
        )
        return WatchFaceSerializer.serializeEntries(WatchFaceFormat.VERSION, entries)
    }

    // A face from a different panel size with a data source the Fit3 firmware won't
    // know — exactly what the import warning is for.
    private fun buildOffPanelFace(): ByteArray {
        val raster = Raster(
            300, 300,
            WatchFaceFormat.FORMAT_RGB565,
            ByteArray(300 * 300 * 2 + 4)
        )
        val widget = WidgetRecord(
            type = WidgetType.PAIR,
            sequenceId = 999,
            opaque1 = 0,
            globalIndex = 0,
            recordSize = 52,
            opaque2 = 0,
            x = 10, y = 10,
            wOrX2 = 0, hOrY2 = 0,
            wordA = 1L,
            words = listOf(0xFFFFFFFF.toInt(), 0, 0, 0)
        )
        val style = StyleEntry(
            widgetCount = 1,
            widgetBytes = 52,
            imageBytes = raster.encodedSize(),
            headerUnknown = 0x400,
            widgets = listOf(widget),
            rasters = listOf(raster)
        )
        val entries = listOf(
            ContainerEntry(
                DirectoryEntry("./SM-R390_88888_256x402/setting.bin", 0, WatchFaceFormat.SETTING_SIZE, 0),
                ContainerPayload.Setting(SettingBin("88888", 40000, 1, 0xFFFF, "SM-R390_88888_256x402"))
            ),
            ContainerEntry(
                DirectoryEntry("./SM-R390_88888_256x402/style0.bin", 0, style.entrySize, 0),
                ContainerPayload.Style(style)
            )
        )
        return WatchFaceSerializer.serializeEntries(WatchFaceFormat.VERSION, entries)
    }

    @Test
    fun `import warns when the donor face is incompatible`() = runTest {
        val project = store.create(buildSeed(), "smr390", "Test")
        val vm = EditorViewModel(store)
        vm.load(project.id)
        awaitLoaded(vm)

        val notice = vm.importFaceBytes(project.id, buildOffPanelFace())
        assertTrue("warn on panel size: $notice", notice.contains("300×300"))
        assertTrue("warn on unknown data source: $notice", notice.contains("tak dikenali"))
        // A usable widget is still exposed so the user can decide.
        assertEquals(1, vm.ui.value.foreignDonors.size)
    }

    private fun bakedWidgets(bytes: ByteArray): List<WidgetRecord> =
        runCatching {
            val parsed = WatchFaceParser.parse(bytes)
            (parsed.styleEntries.first().payload as? ContainerPayload.Style)?.data?.widgets ?: emptyList()
        }.getOrDefault(emptyList())

    @Test
    fun `borrowed widget from an imported face bakes into the container`() = runTest {
        val project = store.create(buildSeed(), "smr390", "Test")
        val vm = EditorViewModel(store)
        vm.load(project.id)
        awaitLoaded(vm)

        val notice = vm.importFaceBytes(project.id, buildDonorFace())
        assertEquals("1 widget diimpor dari face lain.", notice)
        assertEquals(1, vm.ui.value.foreignDonors.size)

        vm.addForeignDonor(0)
        await("foreign widget on board") { vm.ui.value.placed.any { it.donorIndex < 0 } }
        // The donor's record is frozen: the baked style must carry its heart-rate
        // widget at the donor position, proven by x/y — only the foreign widget is (60,70).
        await("foreign widget baked") {
            bakedWidgets(vm.ui.value.generatedBytes ?: return@await false)
                .any { it.sequenceId == WatchFaceFormat.SEQ_HEART_RATE && it.x == 60 && it.y == 70 }
        }
        // The negative donorIndex survives through design.json so reopen can rebuild it.
        await("negative donorIndex persisted") {
            store.get(project.id)?.styleEdits?.get(0)?.placed?.any { it.donorIndex < 0 } == true
        }
    }

    @Test
    fun `borrowed widget survives reopen from the stored donor face`() = runTest {
        val project = store.create(buildSeed(), "smr390", "Test")
        val vm = EditorViewModel(store)
        vm.load(project.id)
        awaitLoaded(vm)
        vm.importFaceBytes(project.id, buildDonorFace())
        vm.addForeignDonor(0)
        await("persisted") {
            store.get(project.id)?.styleEdits?.get(0)?.placed?.any { it.donorIndex < 0 } == true
        }

        // No re-import: the donor face was stored with the project, so a fresh view
        // recovers the library and reconstructs the widget from it.
        val fresh = EditorViewModel(store)
        fresh.load(project.id)
        awaitLoaded(fresh)
        await("foreign library restored") { fresh.ui.value.foreignDonors.isNotEmpty() }
        val foreign = fresh.ui.value.placed.firstOrNull { it.donorIndex < 0 }
        assertNotNull("foreign widget reconstructed on reopen", foreign)
        assertEquals(-1, foreign!!.donorIndex)
        assertEquals(60, foreign.x)
        assertEquals(70, foreign.y)
        assertEquals(WatchFaceFormat.SEQ_HEART_RATE, foreign.donor.sequenceId)
    }

    @Test
    fun `donor face can come from an already-downloaded project`() = runTest {
        val project = store.create(buildSeed(), "smr390", "Test")
        // The donor face lives as another project in the same store.
        val donorProject = store.create(buildDonorFace(), "smr390", "Donor")
        val vm = EditorViewModel(store)
        vm.load(project.id)
        awaitLoaded(vm)
        assertEquals(listOf(donorProject.id), vm.otherFaces().map { it.projectId })

        vm.importDonorFromProject(donorProject.id)
        await("donor library from project") { vm.ui.value.foreignDonors.size == 1 }
        vm.addForeignDonor(0)
        await("foreign widget baked") {
            bakedWidgets(vm.ui.value.generatedBytes ?: return@await false)
                .any { it.sequenceId == WatchFaceFormat.SEQ_HEART_RATE }
        }
    }

    @Test
    fun `foreign and native bakes produce identical records for the same donor`() = runTest {
        val project = store.create(buildSeed(), "smr390", "Test")
        val vm = EditorViewModel(store)
        vm.load(project.id)
        awaitLoaded(vm)

        // The seed's steps widget is already on the board (native placement). Import
        // the SAME face as a foreign donor and drop its steps widget too, then slide
        // the foreign copy sideways so both exist at different positions.
        vm.importFaceBytes(project.id, buildSeed())
        vm.addForeignDonor(0)
        await("foreign on board") { vm.ui.value.placed.any { it.donorIndex < 0 } }
        val foreignId = vm.ui.value.placed.first { it.donorIndex < 0 }.id
        vm.select(foreignId)
        vm.moveSelected(20, 0)
        await("two steps widgets baked") {
            bakedWidgets(vm.ui.value.generatedBytes ?: return@await false)
                .count { it.sequenceId == WatchFaceFormat.SEQ_STEPS } == 2
        }

        val steps = bakedWidgets(vm.ui.value.generatedBytes!!)
            .filter { it.sequenceId == WatchFaceFormat.SEQ_STEPS }
        val native = steps.first { it.x == 40 }
        val foreign = steps.first { it.x == 60 }
        // A foreign widget must bake byte-for-byte like a native one; only position
        // differs. Same type/record/sequence words = the watch can't tell them apart.
        assertEquals(native.type, foreign.type)
        assertEquals(native.recordSize, foreign.recordSize)
        assertEquals(native.wordA, foreign.wordA)
        assertEquals(native.words, foreign.words)
        assertEquals(native.opaque1, foreign.opaque1)
        assertEquals(native.opaque2, foreign.opaque2)
        assertEquals(native.globalIndex, foreign.globalIndex)
    }

    // A donor whose value widget references font binding 3 and whose container carries
    // four of its own bindings — the real "borrowed text widget" shape.
    private fun buildTextDonorFace(): ByteArray {
        val raster = Raster(
            WatchFaceFormat.PANEL_WIDTH,
            WatchFaceFormat.PANEL_HEIGHT,
            WatchFaceFormat.FORMAT_RGB565,
            ByteArray(WatchFaceFormat.PANEL_WIDTH * WatchFaceFormat.PANEL_HEIGHT * 2 + 4)
        )
        val widget = WidgetRecord(
            type = WidgetType.PAIR,
            sequenceId = WatchFaceFormat.SEQ_HEART_RATE,
            opaque1 = 0,
            globalIndex = 0,
            recordSize = 52,
            opaque2 = 0,
            x = 60, y = 70,
            wOrX2 = 0, hOrY2 = 0,
            wordA = 1L,
            words = listOf(0xFFFFFFFF.toInt(), 3, 5, 0)
        )
        val style = StyleEntry(
            widgetCount = 1,
            widgetBytes = 52,
            imageBytes = raster.encodedSize(),
            headerUnknown = 0x400,
            widgets = listOf(widget),
            rasters = listOf(raster)
        )
        fun binding(family: Int, size: Int, role: String) =
            FontBinding(family, ByteArray(71), role, size)
        val bindings = listOf(
            binding(0, 24, "WF_ONE"),
            binding(1, 20, "WF_TWO"),
            binding(7, 18, "WF_THREE"),
            binding(9, 36, "WF_DATA")
        )
        val entries = buildList {
            add(
                ContainerEntry(
                    DirectoryEntry("./SM-R390_99999_256x402/setting.bin", 0, WatchFaceFormat.SETTING_SIZE, 0),
                    ContainerPayload.Setting(SettingBin("99999", 40000, 1, 0xFFFF, "SM-R390_99999_256x402"))
                )
            )
            bindings.forEachIndexed { i, b ->
                add(
                    ContainerEntry(
                        DirectoryEntry("./SM-R390_99999_256x402/font_$i.bin", 0, b.encode().size, 0),
                        ContainerPayload.FontBinding(b)
                    )
                )
            }
            add(
                ContainerEntry(
                    DirectoryEntry("./SM-R390_99999_256x402/style0.bin", 0, style.entrySize, 0),
                    ContainerPayload.Style(style)
                )
            )
        }
        return WatchFaceSerializer.serializeEntries(WatchFaceFormat.VERSION, entries)
    }

    @Test
    fun `borrowed text widget gets its donor font binding and reseated reference`() = runTest {
        val project = store.create(buildSeed(), "smr390", "Test")
        val vm = EditorViewModel(store)
        vm.load(project.id)
        awaitLoaded(vm)
        val notice = vm.importFaceBytes(project.id, buildTextDonorFace())
        assertTrue(notice, notice.contains("1 widget"))
        vm.addForeignDonor(0)
        await("bake appends the donor font bindings") {
            WatchFaceParser.parse(vm.ui.value.generatedBytes ?: return@await false).fontBindings.size == 4
        }
        val parsed = WatchFaceParser.parse(vm.ui.value.generatedBytes!!)
        val widgets = parsed.styleEntries.flatMap { (it.payload as ContainerPayload.Style).data.widgets }
        assertEquals("donor fonts appended after the seed's", 4, parsed.fontBindings.size)
        val foreign = widgets.first { it.sequenceId == WatchFaceFormat.SEQ_HEART_RATE }
        assertEquals("foreign Pair font re-pointed to its donor binding", 3, foreign.words[1] and 0xFF)
        val native = widgets.first { it.sequenceId == WatchFaceFormat.SEQ_STEPS }
        assertEquals("native widget font untouched", 0, native.words[1] and 0xFF)
    }
}