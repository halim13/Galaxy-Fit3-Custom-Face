package com.galaxyfit3.customface.viewmodel

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.galaxyfit3.core.format.WatchFaceFormat
import com.galaxyfit3.core.format.model.Raster
import com.galaxyfit3.core.format.model.FontBinding
import com.galaxyfit3.core.format.model.WatchFaceFmt
import com.galaxyfit3.core.format.model.ContainerEntry
import com.galaxyfit3.core.format.model.ContainerPayload
import com.galaxyfit3.core.format.model.DirectoryEntry
import com.galaxyfit3.core.format.model.StyleEntry
import com.galaxyfit3.core.format.model.WidgetMeaning
import com.galaxyfit3.core.format.model.WidgetRecord
import com.galaxyfit3.core.format.model.WatchFaceContainer
import com.galaxyfit3.core.format.parser.AssetBundle
import com.galaxyfit3.core.format.parser.BlankFaceBuilder
import com.galaxyfit3.core.format.parser.PlacedWidget
import com.galaxyfit3.core.format.parser.TextBorrow
import com.galaxyfit3.core.format.parser.WatchFaceParser
import com.galaxyfit3.core.format.parser.WidgetMeaningCatalog
import com.galaxyfit3.core.format.validator.FaceValidator
import com.galaxyfit3.core.format.validator.ValidationResult
import com.galaxyfit3.core.image.FaceStyleRenderer
import com.galaxyfit3.core.image.TextResources
import com.galaxyfit3.core.image.textResources
import com.galaxyfit3.core.image.PreviewStats
import com.galaxyfit3.core.image.RasterBitmapCache
import com.galaxyfit3.core.image.Rgb565
import com.galaxyfit3.customface.data.FrameOverride
import com.galaxyfit3.customface.data.PlacedDesign
import com.galaxyfit3.customface.data.Project
import com.galaxyfit3.customface.data.ProjectStore
import com.galaxyfit3.customface.data.StyleEdit
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import javax.inject.Inject

data class PlacedEditorWidget(
    val id: Long,
    val donorIndex: Int,
    val x: Int,
    val y: Int,
    val meaning: WidgetMeaning,
    val donor: WidgetRecord,
    /** Custom text ARGB override; null = the firmware colour from the record. */
    val tint: Int? = null,
    /** Decoded first raster, for thumbnails in the placed-widget list. */
    val preview: Bitmap? = null,
    /** Non-null when the widget was borrowed from ANOTHER face: it carries its own
     *  record + rasters, so bakes never re-derive them from the current face's styles.
     *  Its [donorIndex] is negative (-(libraryIndex + 1)) to stay off the face's range. */
    val donorRasters: List<Raster>? = null
)

/** A sprite/ static frame whose image the user replaced, exposed to the UI. */
data class FrameOverrideEntry(
    val donorIndex: Int,
    val frameIndex: Int,
    val fit: String
)

/** A library entry — a source widget the user can drop onto the canvas. */
data class DonorEntry(
    val donorIndex: Int,
    val meaning: WidgetMeaning,
    val description: String,
    val x: Int,
    val y: Int,
    /** Decoded first raster, for thumbnails in the widget library. */
    val preview: Bitmap? = null
)

/** Another downloaded project usable as a donor-face source. */
data class FaceSource(
    val projectId: String,
    val name: String
)

/** The whole edited state of ONE face style. Missing entries ship stock. */
private data class StyleState(
    val placed: List<PlacedEditorWidget> = emptyList(),
    /** Source path of the custom background image (null = none). */
    val backgroundUri: String? = null,
    val background: Bitmap? = null,
    val backgroundFit: String = "cover",
    val backgroundColor: String? = null,
    val frameOverrides: Map<Pair<Int, Int>, String> = emptyMap(),
    val modified: Boolean = false
)

data class EditorUiState(
    val projectId: String = "",
    val projectName: String = "Untitled",
    val seedLoaded: Boolean = false,
    val error: String? = null,
    val donors: List<DonorEntry> = emptyList(),
    val placed: List<PlacedEditorWidget> = emptyList(),
    val selectedId: Long? = null,
    val background: Bitmap? = null,
    /** How the background image fills the panel — preset or "manual@…" placement. */
    val backgroundFit: String = "cover",
    /** Solid background color (#RRGGBB) when no image is used; null otherwise. */
    val backgroundColor: String? = null,
    val preview: Bitmap? = null,
    val validation: ValidationResult? = null,
    val generatedBytes: ByteArray? = null,
    /** The dragged widget's own raster, blitted live on the canvas while dragging. */
    val dragWidgetPreview: Bitmap? = null,
    val styleCount: Int = 0,
    val selectedStyleIndex: Int = 0,
    val stylePreviews: List<Bitmap> = emptyList(),
    /** (donorIndex, frameIndex) of frames the user replaced with their own image. */
    val frameOverrides: Set<Pair<Int, Int>> = emptySet(),
    /** Styles whose data was changed; the style picker badges exactly these. */
    val modifiedStyles: Set<Int> = emptySet(),
    /** Widgets imported from a different face, offered in the widget library.
     *  Placed via addForeignDonor; their donorIndex on the canvas is negative. */
    val foreignDonors: List<DonorEntry> = emptyList(),
    /** One-shot import feedback ("X widget dari <file>", or failure reason). */
    val libraryNotice: String? = null
)

@HiltViewModel
class EditorViewModel @Inject constructor(
    private val store: ProjectStore
) : ViewModel() {

    private val _ui = MutableStateFlow(EditorUiState())
    val ui: StateFlow<EditorUiState> = _ui.asStateFlow()

    // Written by load() off the main thread and read from the UI, so they have to be
    // volatile rather than plain fields.
    @Volatile private var container: WatchFaceContainer? = null
    @Volatile private var styles: List<StyleEntry> = emptyList()
    @Volatile private var selectedStyleIndex = 0
    @Volatile private var project: Project? = null
    private var nextId: Long = 1

    /** Per-style editor state; _ui mirrors the [selectedStyleIndex] entry. Reads cross
     *  threads (UI switches style, engine bakes), hence concurrent. */
    private val styleStates = java.util.concurrent.ConcurrentHashMap<Int, StyleState>()

    /** Widgets borrowed from an imported donor face. Keyed by library index (0..n);
     *  a placed widget referencing it stores donorIndex = -(index + 1). */
    private val foreignDonorCache = java.util.concurrent.ConcurrentHashMap<Int, DonorCacheItem>()

    private data class DonorCacheItem(
        val donor: WidgetRecord,
        val donorRasters: List<Raster>,
        /** The donor face's own font bindings, for reseating borrowed text widgets. */
        val bindings: List<FontBinding> = emptyList(),
        /** The donor face's locale tables (font_en.bin …), carried with borrowed text. */
        val glyphEntries: List<ContainerEntry> = emptyList()
    )

    /** The stock layout signature of every style — what "unedited" looks like, so only
     *  styles that really differ from it count as modified. */
    private val pristineKeys = java.util.concurrent.ConcurrentHashMap<Int, List<PlacedKey>>()

    /** (donorIndex, frameIndex) -> (x, y, tintIsSet) of the style's stock layout. */
    private data class PlacedKey(
        val donor: Int,
        val x: Int,
        val y: Int,
        val tint: String? = null
    )

    /** Decoded widget rasters, reused by every preview render of the open project. */
    private val rasterCache = RasterBitmapCache()

    /** (donorIndex, frameIndex) -> the replacement image's fit mode, persisted in the project. */
    @Volatile private var frameOverrides: Map<Pair<Int, Int>, String> = emptyMap()

    /** Decoded replacement-frame bitmaps keyed by file path; engine-confined except
     *  during [load], hence concurrent. Replacement PNGs are tiny (widget-sized). */
    private val frameBitmapCache = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()

    /**
     * Every render, container build and disk write runs here, one at a time.
     *
     * Editing a widget used to do all of it inline on the UI thread: re-render the face
     * into a fresh 256x402 bitmap per widget, serialise the container, CRC-check every
     * byte and write the design — on each nudge and at each end of a drag. On a 13-widget
     * face that produced the multi-second main-thread stalls and GC storms. Serialising on
     * one thread also keeps the results in edit order, since each task reads the latest
     * state when it runs.
     */
    private val engineExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "face-editor-engine")
    }
    private val engine: CoroutineDispatcher = engineExecutor.asCoroutineDispatcher()

    /** The pending coalesced container build, if an edit is still settling. */
    private var rebuildJob: Job? = null

    override fun onCleared() {
        engineExecutor.shutdown()
    }

    fun load(projectId: String) {
        // Returning from Preview/Install recomposes this screen and re-runs load().
        // Skipping the reload when the same project is already open keeps every edit
        // the user made this session (positions, replaced images) exactly as they are.
        if (_ui.value.projectId == projectId && _ui.value.seedLoaded) return
        val p = store.get(projectId) ?: run {
            _ui.update { it.copy(error = "Project not found") }
            return
        }
        project = p
        val seed = store.seedBytes(projectId)
        if (seed == null) {
            _ui.update { it.copy(projectId = projectId, projectName = p.name, error = "Seed face missing") }
            return
        }
        viewModelScope.launch(Dispatchers.Default) {
            val parsed = try {
                WatchFaceParser.parse(seed)
            } catch (e: Exception) {
                _ui.update {
                    it.copy(projectId = projectId, projectName = p.name, error = "Seed parse failed: ${e.message}")
                }
                return@launch
            }
            val seedStyles = parsed.styleEntries.mapNotNull { (it.payload as? ContainerPayload.Style)?.data }
            if (seedStyles.isEmpty()) {
                _ui.update { it.copy(projectId = projectId, projectName = p.name, error = "Seed has no editable style") }
                return@launch
            }
            val s0 = seedStyles[0]
            val donors = s0.widgets.indices.mapNotNull { donorEntry(s0, it) }

            // Widgets borrowed from an imported donor face ship with the project; rebuild
            // the library + donor cache so placed references reconstruct and stay editable.
            foreignDonorCache.clear()
            var foreignDonors: List<DonorEntry> = emptyList()
            store.donorFaceBytes(projectId)?.let { donorBytes ->
                try {
                    val donorParsed = WatchFaceParser.parse(donorBytes)
                    val donorStyle = donorParsed.styleEntries
                        .mapNotNull { (it.payload as? ContainerPayload.Style)?.data }
                        .firstOrNull() ?: return@let
                    foreignDonors = donorStyle.widgets.indices.mapNotNull { donorEntry(donorStyle, it) }
                    foreignDonors.forEach { entry ->
                        val d = BlankFaceBuilder.extractDonor(donorStyle, entry.donorIndex)
foreignDonorCache[entry.donorIndex] = DonorCacheItem(d.donor, d.donorRasters, donorParsed.fontBindings, TextBorrow.glyphEntries(donorParsed))
                    }
                } catch (_: Exception) {
                    // Corrupt donor face: dropped. Placed widgets referencing it are skipped
                    // during reconstruction below rather than crashing the editor.
                }
            }

            container = parsed
            styles = seedStyles
            val restoredStyle = p.styleIndex.coerceIn(0, (seedStyles.size - 1).coerceAtLeast(0))
            selectedStyleIndex = restoredStyle

            frameBitmapCache.clear()
            styleStates.clear()
            pristineKeys.clear()
            seedStyles.indices.forEach { i ->
                val stock = seedStyles[i]
                val stockLayout = defaultPlaced(stock)
                pristineKeys[i] = placedKeys(stockLayout)
                val persisted = p.styleEdits[i]
                styleStates[i] = if (persisted == null) {
                    StyleState(placed = stockLayout)
                } else {
                    val placed = ensureBackground(
                        stock,
                        persisted.placed.mapNotNull { d ->
                            if (d.donorIndex < 0) {
                                val item = foreignDonorCache[-d.donorIndex - 1] ?: return@mapNotNull null
                                PlacedEditorWidget(
                                    id = nextId++,
                                    donorIndex = d.donorIndex,
                                    x = d.x,
                                    y = d.y,
                                    meaning = stringToMeaning(d.meaning),
                                    tint = d.color?.let { runCatching { android.graphics.Color.parseColor(it) }.getOrNull() },
                                    donor = item.donor,
                                    donorRasters = item.donorRasters,
                                    preview = donorCacheDecode(item)
                                )
                            } else {
                                val donor = styleDonor(stock, d.donorIndex) ?: return@mapNotNull null
                                PlacedEditorWidget(
                                    id = nextId++,
                                    donorIndex = d.donorIndex,
                                    x = d.x,
                                    y = d.y,
                                    meaning = stringToMeaning(d.meaning),
                                    tint = d.color?.let { runCatching { android.graphics.Color.parseColor(it) }.getOrNull() },
                                    donor = donor,
                                    preview = donorPreview(stock, d.donorIndex)
                                )
                            }
                        }
                    )
                    StyleState(
                        placed = placed,
                        backgroundUri = persisted.backgroundUri,
                        background = loadBackgroundFile(persisted.backgroundUri),
                        backgroundFit = persisted.backgroundFit,
                        backgroundColor = persisted.backgroundColor,
                        frameOverrides = persisted.frameOverrides
                            .filter { resolveFrameFile(projectId, i, it.donorIndex, it.frameIndex) != null }
                            .associate { it.donorIndex to it.frameIndex to it.fit },
                        modified = true
                    )
                }
            }
            val active = styleStates[restoredStyle] ?: StyleState()
            frameOverrides = active.frameOverrides
            val previews = seedStyles.mapIndexed { i, s ->
                FaceStyleRenderer.render(
                    s, PreviewStats(), rasterCache, null,
                    text = container?.textResources() ?: TextResources()
                )
            }
            _ui.update {
                it.copy(
                    projectId = projectId,
                    projectName = p.name,
                    seedLoaded = true,
                    error = null,
                    styleCount = seedStyles.size,
                    stylePreviews = previews,
                    donors = donors,
                    placed = active.placed,
                    background = active.background,
                    backgroundFit = active.backgroundFit,
                    backgroundColor = active.backgroundColor,
                    frameOverrides = active.frameOverrides.keys,
                    modifiedStyles = styleStates.filterValues { it.modified }.keys,
                    selectedStyleIndex = restoredStyle,
                    foreignDonors = foreignDonors,
                    libraryNotice = null
                )
            }
            refresh()
        }
    }

    private fun stringToMeaning(s: String): WidgetMeaning =
        WidgetMeaning.entries.firstOrNull { it.name == s } ?: WidgetMeaning.UNKNOWN

    /** The seed style's background widget as a placed entry, or null when it has none. */
    private fun backgroundWidget(style: StyleEntry): PlacedEditorWidget? {
        val index = style.widgets.indexOfFirst {
            WidgetMeaningCatalog.meaning(it, style.rasters) == WidgetMeaning.BACKGROUND
        }
        if (index < 0) return null
        val donor = BlankFaceBuilder.extractDonor(style, index)
        return PlacedEditorWidget(
            id = nextId++,
            donorIndex = index,
            x = donor.donor.x,
            y = donor.donor.y,
            meaning = WidgetMeaning.BACKGROUND,
            donor = donor.donor,
            preview = donorPreview(style, index)
        )
    }

    private fun currentStyle(): StyleEntry? =
        styles.getOrNull(selectedStyleIndex.coerceIn(0, (styles.size - 1).coerceAtLeast(0)))

    /** Donor widget [index] of [style]; null when the style lacks that widget. */
    private fun styleDonor(style: StyleEntry, index: Int): WidgetRecord? =
        if (index in 0 until style.widgets.size) BlankFaceBuilder.extractDonor(style, index).donor else null

    /** The widget's own raster decoded for list thumbnails; null for text-only widgets.
     * Goes through [rasterCache] so every list re-render reuses the same bitmaps. */
    private fun donorPreview(style: StyleEntry, index: Int): Bitmap? = try {
        BlankFaceBuilder.extractDonor(style, index).donorRasters.firstOrNull()?.let { rasterCache.bitmap(it) }
    } catch (_: Exception) {
        null
    }

    /** Widget [i] of a donor style as a library entry; backgrounds are managed separately. */
    private fun donorEntry(style: StyleEntry, i: Int): DonorEntry? {
        val w = style.widgets.getOrNull(i) ?: return null
        val meaning = WidgetMeaningCatalog.meaning(w, style.rasters)
        if (meaning == WidgetMeaning.BACKGROUND) return null
        return DonorEntry(
            donorIndex = i,
            meaning = meaning,
            description = WidgetMeaningCatalog.describe(w),
            x = w.x,
            y = w.y,
            preview = donorPreview(style, i)
        )
    }

    /** A foreign donor's first raster decoded for thumbnails. */
    private fun donorCacheDecode(item: DonorCacheItem): Bitmap? = try {
        item.donorRasters.firstOrNull()?.let { rasterCache.bitmap(it) }
    } catch (_: Exception) {
        null
    }

    /** The placed widgets remapped onto [style]'s rasters (position preserved; frame
     *  replacements come from [styleIndex]'s overrides). The placed list is passed in so
     *  a background render can't straddle two edits' snapshots.
     *  [dropBackground]: with a custom photo set, stock background widgets are left out —
     *  the photo replaces them instead of of being drawn underneath (and then covered by) the
     *  face's own background raster. Without a photo the stock background rides along with
     *  its raster (extractDonor keeps offset-0 references), so clearing a photo restores
     *  the face's own background.
     *  Widgets borrowed from another face (negative [PlacedEditorWidget.donorIndex]) carry
     *  their own record + rasters and are baked as-is. [fontSeats] (see [textBorrowPlan])
     *  re-points a borrowed text widget's font reference at the container slot that
     *  resolves on the watch (the seed's own role-matched binding, or an appended donor
     *  font when the seed has none).
     */
    private fun placedInStyle(
        style: StyleEntry,
        styleIndex: Int,
        placed: List<PlacedEditorWidget>,
        dropBackground: Boolean = false,
        fontSeats: Map<Int, (Int) -> Int> = emptyMap()
    ): List<PlacedWidget> =
        placed.asSequence()
            .filter { pw -> pw.donorIndex < 0 || pw.donorIndex in 0 until style.widgets.size }
            .filterNot { pw ->
                dropBackground && meaningOf(pw, style) == WidgetMeaning.BACKGROUND
            }
            .map { pw ->
                if (pw.donorIndex < 0) {
                    val reseated = fontSeats[-pw.donorIndex - 1]
                        ?.let { TextBorrow.reseat(pw.donor, it) } ?: pw.donor
                    PlacedWidget(
                        reseated,
                        overrideRasters(styleIndex, pw.donorIndex, pw.donorRasters ?: emptyList()),
                        pw.x, pw.y, pw.tint
                    )
                } else {
                    val donor = BlankFaceBuilder.extractDonor(style, pw.donorIndex)
                    PlacedWidget(
                        donor.donor,
                        overrideRasters(styleIndex, pw.donorIndex, donor.donorRasters),
                        pw.x, pw.y, pw.tint
                    )
                }
            }
            .toList()

    /** Meaning of a placed widget: foreign widgets carry theirs (baked from their donor
     *  face's rasters); current-face widgets are re-derived from the style. */
    private fun meaningOf(pw: PlacedEditorWidget, style: StyleEntry): WidgetMeaning =
        if (pw.donorIndex < 0) pw.meaning
        else WidgetMeaningCatalog.meaning(style.widgets[pw.donorIndex], style.rasters)

    /** The style's own frame overrides: the active style reads the live board, the
     *  others their stashed [StyleState]. */
    private fun styleOverrides(styleIndex: Int): Map<Pair<Int, Int>, String> =
        if (styleIndex == selectedStyleIndex) frameOverrides
        else styleStates[styleIndex]?.frameOverrides ?: emptyMap()

    /** Live view of a style's edits — the active style from the editor board, the rest
     *  from their stashed state (needed when a bake touches every style). */
    private fun styleView(styleIndex: Int): StyleState =
        if (styleIndex == selectedStyleIndex) StyleState(
            placed = _ui.value.placed,
            background = _ui.value.background,
            backgroundFit = _ui.value.backgroundFit,
            backgroundColor = _ui.value.backgroundColor
        ) else styleStates[styleIndex] ?: StyleState()

    /** The style's background raster (solid color or framed photo), null = none. */
    private fun bgRasterOf(v: StyleState): Raster? =
        v.backgroundColor?.let { hex ->
            val argb = runCatching { android.graphics.Color.parseColor(hex) }.getOrNull()
                ?: return@let null
            solidRaster(argb)
        } ?: v.background?.let {
            val manual = v.backgroundFit.startsWith(Rgb565.MANUAL_PREFIX)
            Rgb565.toRaster565(
                it, WatchFaceFormat.PANEL_WIDTH, WatchFaceFormat.PANEL_HEIGHT,
                withAlpha = manual, mode = v.backgroundFit
            )
        }

    /** Sorting key of a placed list, used to tell "edited" from "still stock". */
    private fun placedKeys(placed: List<PlacedEditorWidget>): List<PlacedKey> =
        placed.map { PlacedKey(it.donorIndex, it.x, it.y, it.tint?.let(::toHex)) }
            .sortedWith(compareBy({ it.donor }, { it.x }, { it.y }, { it.tint }))

    fun selectStyle(index: Int) {
        if (index !in styles.indices || index == selectedStyleIndex) return
        // Stash the outgoing style's edits so it keeps them; persist only if it was
        // actually modified (switching alone must not mark a style as edited).
        if (styleStates[selectedStyleIndex]?.modified == true) {
            syncActiveIntoMap()
            saveStyleEdit(selectedStyleIndex)
        }
        selectedStyleIndex = index
        viewModelScope.launch(engine) { persistStyleIndex(index) }
        loadStyleIntoUi(index)
        refresh()
    }

    /** Persist only the active style index — Preview/Install open on the style the
     *  user was editing, even when it has no edits yet. */
    private fun persistStyleIndex(index: Int) {
        val current = project ?: return
        if (current.styleIndex == index && _ui.value.projectId.isNotBlank()) return
        val updated = current.copy(styleIndex = index)
        project = updated
        store.save(updated)
    }

    /** Point _ui at [styleIndex]'s stashed state (default layout when never edited). */
    private fun loadStyleIntoUi(index: Int) {
        val st = styleStates[index] ?: StyleState(
            placed = defaultPlaced(styles.getOrNull(index) ?: return)
        )
        styleStates[index] = st
        frameOverrides = st.frameOverrides
        _ui.update {
            it.copy(
                placed = st.placed,
                background = st.background,
                backgroundFit = st.backgroundFit,
                backgroundColor = st.backgroundColor,
                frameOverrides = st.frameOverrides.keys,
                selectedId = null,
                selectedStyleIndex = index
            )
        }
    }

    /** Fold the live editor board into the active style's stashed state. */
    private fun syncActiveIntoMap() {
        val current = styleStates[selectedStyleIndex] ?: StyleState()
        styleStates[selectedStyleIndex] = current.copy(
            placed = _ui.value.placed,
            background = _ui.value.background,
            backgroundFit = _ui.value.backgroundFit,
            backgroundColor = _ui.value.backgroundColor,
            frameOverrides = frameOverrides,
            backgroundUri = current.backgroundUri
        )
    }

    /** Persist one style's edit. A style whose data equals its stock layout is dropped,
     *  so unedited styles bake exactly as shipped. [syncBoard]: fold the live editor
     *  board in first (false when the caller already wrote the map via [saveActive]). */
    private fun saveStyleEdit(styleIndex: Int, syncBoard: Boolean = true) {
        val current = project ?: return
        if (_ui.value.projectId.isBlank()) return
        if (syncBoard && styleIndex == selectedStyleIndex) syncActiveIntoMap()
        val st = styleStates[styleIndex] ?: return
        val placed = st.placed.map { pw ->
            PlacedDesign(pw.donorIndex, pw.x, pw.y, System.currentTimeMillis(), pw.meaning.name, pw.tint?.let(::toHex))
        }
        val frames = st.frameOverrides.map { (k, v) -> FrameOverride(k.first, k.second, v) }
        val built = StyleEdit(styleIndex, placed, frames, st.backgroundUri, st.backgroundFit, st.backgroundColor)
        val edited = built.frameOverrides.isNotEmpty() || built.backgroundUri != null || built.backgroundColor != null ||
            (pristineKeys[styleIndex]?.let { it != placedKeys(st.placed) } ?: st.placed.isNotEmpty())
        val updated = if (edited) {
            markStyleModified(styleIndex)
            current.copy(styleIndex = selectedStyleIndex, styleEdits = current.styleEdits + (styleIndex to built))
        } else {
            styleStates[styleIndex]?.let { styleStates[styleIndex] = it.copy(modified = false) }
            _ui.update { it.copy(modifiedStyles = it.modifiedStyles - styleIndex) }
            current.copy(styleIndex = selectedStyleIndex, styleEdits = current.styleEdits - styleIndex)
        }
        project = updated
        store.save(updated)
    }

    /** Flag [styleIndex] as changed so the picker can badge it. */
    private fun markStyleModified(styleIndex: Int) {
        styleStates[styleIndex]?.let { styleStates[styleIndex] = it.copy(modified = true) }
        _ui.update { it.copy(modifiedStyles = it.modifiedStyles + styleIndex) }
    }

    private fun loadBackgroundFile(path: String?): Bitmap? {
        if (path == null) return null
        val f = java.io.File(path)
        if (!f.exists()) return null
        return try {
            Rgb565.decodeSampledFile(f.absolutePath, WatchFaceFormat.PANEL_WIDTH * 2, WatchFaceFormat.PANEL_HEIGHT * 2)
        } catch (_: Exception) {
            null
        }
    }

    /** Append the stock background widget when the face has one and [placed] lacks it —
     *  the stock layout rides along with its raster on every bake. */
    private fun ensureBackground(style: StyleEntry, placed: List<PlacedEditorWidget>): List<PlacedEditorWidget> =
        if (placed.any { it.meaning == WidgetMeaning.BACKGROUND }) placed
        else placed + listOfNotNull(backgroundWidget(style))

    fun addFromDonor(index: Int) {
        val s0 = currentStyle() ?: return
        if (index !in 0 until s0.widgets.size) return
        val donor = BlankFaceBuilder.extractDonor(s0, index)
        val (px, py) = if (donor.donor.isHand) {
            BlankFaceBuilder.handCenter(donor.donor)
        } else donor.donor.x to donor.donor.y
        val placed = PlacedEditorWidget(
            id = nextId++,
            donorIndex = index,
            x = px,
            y = py,
            meaning = WidgetMeaningCatalog.meaning(donor.donor, s0.rasters),
            donor = donor.donor,
            preview = donorPreview(s0, index)
        )
        _ui.update { it.copy(placed = it.placed + placed, selectedId = placed.id) }
        persist()
        refresh()
    }

    /** Import another face's container as a widget library. [ui] is called on the main
     *  thread; parsing + raster decode run on the engine executor. */
    fun importDonorFace(uri: android.net.Uri) {
        val projectId = _ui.value.projectId
        if (projectId.isBlank()) return
        val bytes = store.readUriBytes(uri)
        if (bytes == null) {
            _ui.update { it.copy(libraryNotice = "Cannot read the file.") }
            return
        }
        viewModelScope.launch(engine) {
            val notice = importFaceBytes(projectId, bytes)
            _ui.update { it.copy(libraryNotice = notice) }
        }
    }

    /** Store a donor face and expose its style-0 widgets as library entries. Injectable
     *  so tests can import without a content resolver. Returns a user-facing notice. */
    fun importFaceBytes(projectId: String, bytes: ByteArray): String {
        val parsed = try {
            WatchFaceParser.parse(bytes)
        } catch (e: Exception) {
            return "File cannot be read as a face (${e.message ?: "bad format?"})."
        }
        val donorStyle = parsed.styleEntries
            .mapNotNull { (it.payload as? ContainerPayload.Style)?.data }
            .firstOrNull()
            ?: return "The file contains no face style."
        val entries = donorStyle.widgets.indices.mapNotNull { donorEntry(donorStyle, it) }
        if (entries.isEmpty()) {
            // Still store the container so already-placed foreign widgets reconstruct;
            // nothing new is offered in the library.
            if (store.donorFaceBytes(projectId) == null) store.saveDonorFace(projectId, bytes)
            return "That face has no usable widgets."
        }
        store.saveDonorFace(projectId, bytes)
        entries.forEach { entry ->
            val d = BlankFaceBuilder.extractDonor(donorStyle, entry.donorIndex)
            foreignDonorCache[entry.donorIndex] = DonorCacheItem(d.donor, d.donorRasters, parsed.fontBindings, TextBorrow.glyphEntries(parsed))
        }
        _ui.update { it.copy(foreignDonors = entries) }
        return "${entries.size} widgets imported from another face.${donorWarnings(donorStyle, entries)}"
    }

    /** Warnings about a donor face the user should know before borrowing from it:
     *  a different panel size or data sources the Fit3 firmware won't draw. */
    private fun donorWarnings(donorStyle: StyleEntry, entries: List<DonorEntry>): String {
        val panel = donorStyle.rasters.firstOrNull()
        val warnings = buildList {
            if (panel != null &&
                (panel.width != WatchFaceFormat.PANEL_WIDTH || panel.height != WatchFaceFormat.PANEL_HEIGHT)
            ) {
                add("This face's panel size is ${panel.width}×${panel.height} (seed 256×402) — widgets may not appear on the watch.")
            }
            val unknown = entries.count { it.meaning == WidgetMeaning.UNKNOWN }
            if (unknown > 0) add("$unknown unrecognized widgets — may not appear on the watch.")
        }
        return if (warnings.isEmpty()) "" else " " + warnings.joinToString(" ")
    }

    /** Place a foreign-library widget on the board. Its donorIndex on the canvas is
     *  negative (-(libraryIndex + 1)) so it never collides with the face's own widgets. */
    fun addForeignDonor(libraryIndex: Int) {
        val entry = _ui.value.foreignDonors.getOrNull(libraryIndex) ?: return
        val item = foreignDonorCache[libraryIndex] ?: return
        val (px, py) = if (item.donor.isHand) {
            BlankFaceBuilder.handCenter(item.donor)
        } else item.donor.x to item.donor.y
        val placed = PlacedEditorWidget(
            id = nextId++,
            donorIndex = -(libraryIndex + 1),
            x = px,
            y = py,
            meaning = entry.meaning,
            donor = item.donor,
            donorRasters = item.donorRasters,
            preview = donorCacheDecode(item)
        )
        _ui.update { it.copy(placed = it.placed + placed, selectedId = placed.id) }
        persist()
        refresh()
    }

    /** Another way in: import a face that is already downloaded in the app (a project's
     *  seed container is the same file a picker would return). */
    fun importDonorFromProject(sourceProjectId: String) {
        val projectId = _ui.value.projectId
        if (projectId.isBlank() || sourceProjectId == projectId) return
        val bytes = store.seedBytes(sourceProjectId) ?: run {
            _ui.update { it.copy(libraryNotice = "That project's seed face was not found.") }
            return
        }
        viewModelScope.launch(engine) {
            _ui.update { it.copy(libraryNotice = importFaceBytes(projectId, bytes)) }
        }
    }

    /** Downloaded faces (excluding the one being edited) the user can borrow widgets from. */
    fun otherFaces(): List<FaceSource> =
        store.projects()
            .filter { it.id != _ui.value.projectId }
            .map { FaceSource(it.id, it.name.ifBlank { "Untitled" }) }

    fun consumeLibraryNotice() {
        _ui.update { it.copy(libraryNotice = null) }
    }

    fun moveSelected(dx: Int, dy: Int) {
        val id = _ui.value.selectedId ?: return
        _ui.update { state ->
            state.copy(
                placed = state.placed.map {
                    if (it.id == id) it.copy(
                        x = (it.x + dx).coerceIn(0, WatchFaceFormat.PANEL_WIDTH),
                        y = (it.y + dy).coerceIn(0, WatchFaceFormat.PANEL_HEIGHT)
                    ) else it
                }
            )
        }
        persist()
        refresh()
    }

    /** Absolute placement used by canvas drag. During a drag the preview base was already
     * rendered without the widget by [beginDrag], so nothing re-renders per frame; the
     * widget's own bitmap follows the finger on the canvas until [finishDrag] rebuilds. */
    fun moveSelectedTo(id: Long, x: Int, y: Int) {
        _ui.update { state ->
            state.copy(
                placed = state.placed.map {
                    if (it.id == id) it.copy(
                        x = x.coerceIn(0, WatchFaceFormat.PANEL_WIDTH),
                        y = y.coerceIn(0, WatchFaceFormat.PANEL_HEIGHT)
                    ) else it
                }
            )
        }
    }

    /** Start a canvas drag: render the preview once without the dragged widget (clean base
     * so the finger doesn't drag a ghost), and extract the widget's own raster for the blit.
     * Called from a gesture, so both run on the engine rather than blocking the UI. */
    fun beginDrag(id: Long) {
        val style = currentStyle() ?: return
        viewModelScope.launch(engine) {
            val pw = _ui.value.placed.find { it.id == id } ?: return@launch
            val bitmap = if (pw.donor.isHand) {
                null
            } else {
                runCatching {
                    BlankFaceBuilder.extractDonor(style, pw.donorIndex)
                        .donorRasters.firstOrNull()?.let { Rgb565.toBitmap(it) }
                }.getOrNull()
            }
            // Base first, then the widget's raster: the other order would draw a ghost of
            // the widget on top of a base that still contains it.
            renderPreview(style, excludeId = id)
            _ui.update { it.copy(dragWidgetPreview = bitmap) }
        }
    }

    /** Persist + full rebuild+validate after a drag gesture ends. */
    fun finishDrag() {
        _ui.update { it.copy(dragWidgetPreview = null) }
        viewModelScope.launch(engine) { saveStyleEdit(selectedStyleIndex) }
        scheduleRebuild()
    }

    fun select(id: Long?) {
        _ui.update { it.copy(selectedId = id) }
    }

    fun removeSelected() {
        val id = _ui.value.selectedId ?: return
        removePlaced(id)
    }

    /** Remove a specific widget by id (used by the placed-widget list). */
    fun removePlaced(id: Long) {
        _ui.update { state ->
            state.copy(
                placed = state.placed.filterNot { it.id == id },
                selectedId = state.selectedId?.takeIf { it != id }
            )
        }
        persist()
        refresh()
    }

    fun duplicateSelected() {
        val id = _ui.value.selectedId ?: return
        val w = _ui.value.placed.find { it.id == id } ?: return
        val copy = w.copy(id = nextId++, x = w.x + 12, y = w.y + 12)
        _ui.update { state -> state.copy(placed = state.placed + copy, selectedId = copy.id) }
        persist()
        refresh()
    }

    /** Custom text colour (ARGB) for the selected widget — value (Pair) and composite
     *  (Comp) text. Baked for Pair (words[0] is the firmware colour slot); Comp words
     *  are left untouched (their colour field is undocumented), so a Comp tint reaches
     *  the editor/install preview but the baked face keeps its firmware colour. */
    fun setTextColor(argb: Int) {
        val id = _ui.value.selectedId ?: return
        _ui.update { state ->
            state.copy(
                placed = state.placed.map { if (it.id == id) it.copy(tint = argb) else it }
            )
        }
        persist()
        refresh()
    }

    /** Drop the custom text colour; the record's firmware colour renders again. */
    fun clearTextColor() {
        val id = _ui.value.selectedId ?: return
        _ui.update { state ->
            state.copy(
                placed = state.placed.map { if (it.id == id) it.copy(tint = null) else it }
            )
        }
        persist()
        refresh()
    }

    /** The stock layout: every style-0 widget placed at its original position. */
    private fun defaultPlaced(s0: StyleEntry): List<PlacedEditorWidget> =
        s0.widgets.mapIndexedNotNull { i, w ->
            val donor = BlankFaceBuilder.extractDonor(s0, i)
            val (px, py) = if (donor.donor.isHand) {
                BlankFaceBuilder.handCenter(donor.donor)
            } else donor.donor.x to donor.donor.y
            PlacedEditorWidget(
                id = nextId++,
                donorIndex = i,
                x = px,
                y = py,
                meaning = WidgetMeaningCatalog.meaning(donor.donor, s0.rasters),
                donor = donor.donor,
                preview = donorPreview(s0, i)
            )
        }

    /**
     * Reset only the CURRENT style to its stock look: every widget back at its donor
     * position, custom background dropped, replaced frames removed, its persisted edit
     * record forgotten. Other styles and the imported foreign face are untouched.
     */
    fun resetToDefault() {
        val styleIndex = selectedStyleIndex
        val s0 = styles.getOrNull(styleIndex) ?: return
        viewModelScope.launch(engine) {
            val projectId = _ui.value.projectId
            if (projectId.isNotBlank()) {
                store.removeBackground(projectId, styleIndex)
                (styleStates[styleIndex]?.frameOverrides ?: emptyMap()).keys.forEach { (donor, frame) ->
                    store.deleteFrameFile(projectId, styleIndex, donor, frame)
                }
                val current = project ?: return@launch
                project = current.copy(styleEdits = current.styleEdits - styleIndex)
                store.save(project!!)
            }
            styleStates[styleIndex] = StyleState(placed = defaultPlaced(s0))
            frameOverrides = emptyMap()
            frameBitmapCache.clear()
            loadStyleIntoUi(styleIndex)
            refresh()
            _ui.update { it.copy(modifiedStyles = it.modifiedStyles - styleIndex) }
        }
    }

    /**
     * Reset only the CURRENT style's widget POSITIONS to their stock donor positions.
     * The custom background, replaced frames and text colours all stay; only x/y move.
     * Widgets borrowed from another face keep theirs. When positions were the style's
     * only edit, the style itself reverts to stock (no longer counts as modified).
     */
    fun resetPositions() {
        val styleIndex = selectedStyleIndex
        val s0 = styles.getOrNull(styleIndex) ?: return
        viewModelScope.launch(engine) {
            syncActiveIntoMap()
            val st = styleStates[styleIndex] ?: return@launch
            val stockPos = HashMap<Int, Pair<Int, Int>>()
            st.placed.forEach { pw ->
                if (pw.donorIndex >= 0) {
                    val w = s0.widgets.getOrNull(pw.donorIndex) ?: return@forEach
                    stockPos[pw.donorIndex] = if (w.isHand) BlankFaceBuilder.handCenter(w) else w.x to w.y
                }
            }
            styleStates[styleIndex] = st.copy(
                placed = st.placed.map { pw ->
                    stockPos[pw.donorIndex]?.let { pw.copy(x = it.first, y = it.second) } ?: pw
                }
            )
            saveStyleEdit(styleIndex, syncBoard = false)
            loadStyleIntoUi(styleIndex)
            refresh()
        }
    }

    fun reorder(from: Int, to: Int) {
        _ui.update { state ->
            val list = state.placed.toMutableList()
            if (from in list.indices && to in list.indices) {
                val item = list.removeAt(from)
                list.add(to, item)
            }
            state.copy(placed = list)
        }
        persist()
        refresh()
    }

    /** Apply a background bitmap with its placement mode (cropped to 256x402 and
     *  re-encoded on rebuild). A photo replaces any solid color. The fit is persisted
     *  here too — the caller may have retuned it without going through a picker. */
    fun setBackground(bitmap: Bitmap, fit: String = _ui.value.backgroundFit) {
        _ui.update { it.copy(background = bitmap, backgroundFit = fit, backgroundColor = null) }
        viewModelScope.launch(engine) {
            if (_ui.value.projectId.isNotBlank()) {
                persistBackgroundUri(styleStates[selectedStyleIndex]?.backgroundUri, fit)
            }
            refresh()
        }
    }

    /**
     * Decode a picked background image off the main thread and downsample it to roughly
     * the panel size.
     *
     * The picker callback runs on the main thread, and decoding a camera photo there
     * allocates tens of MB of ARGB_8888 — the logcat hang behind the editor's 5-second
     * frames: full-heap GC pauses while the heap sat at 110/110 MB with 42 MB of large
     * objects. Sampling to ~512x804 first makes every later step cheap as well.
     *
     * The bytes are also copied into the project directory and the path persisted: photo
     * picker grants are temporary, so without the copy the background silently vanished
     * the next time the app opened the project. [fit] is the placement the user tuned in
     * the picker dialog (preset or "manual@…"), applied at every re-encode.
     */
    fun setBackgroundFromUri(uri: android.net.Uri, fit: String = "cover") {
        viewModelScope.launch(engine) {
            val bytes = store.readUriBytes(uri) ?: return@launch
            val sampled = Rgb565.decodeSampled(bytes, WatchFaceFormat.PANEL_WIDTH * 2, WatchFaceFormat.PANEL_HEIGHT * 2)
                ?: return@launch
            val path = store.saveBackground(_ui.value.projectId, selectedStyleIndex, bytes)
            persistBackgroundUri(path, fit)
            setBackground(sampled, fit)
        }
    }

    /**
     * Retune the placement of the already-chosen background image — no re-pick; the
     * stored photo is re-framed with the new preset or "manual@…" transform, the same
     * way [setFrameMode] re-frames a replaced widget frame.
     */
    fun setBackgroundFit(fit: String) {
        viewModelScope.launch(engine) {
            if (_ui.value.projectId.isBlank()) return@launch
            persistBackgroundUri(styleStates[selectedStyleIndex]?.backgroundUri, fit)
            setBackground(_ui.value.background ?: return@launch, fit)
        }
    }

    /** Solid background color (#RRGGBB): replaces any photo, rasterized at rebuild. */
    fun setBackgroundColor(hex: String) {
        viewModelScope.launch(engine) {
            if (_ui.value.projectId.isBlank()) return@launch
            // The photo file stays on disk (harmless), but the project reverts to the
            // seed's background widgets should the color ever be cleared.
            store.removeBackground(_ui.value.projectId, selectedStyleIndex)
            persistBackgroundUri(null)
            persistBackgroundColor(hex)
            _ui.update { it.copy(background = null, backgroundFit = "cover", backgroundColor = hex) }
            refresh()
        }
    }

    /** Drop the solid color; the seed's own background widgets render again. */
    fun clearBackgroundColor() {
        viewModelScope.launch(engine) {
            if (_ui.value.projectId.isBlank()) return@launch
            persistBackgroundColor(null)
            _ui.update { it.copy(backgroundColor = null) }
            refresh()
        }
    }

    /** Drop the custom background: delete the stored copy, persist the removal (a
     *  solid color counts as a background too and is cleared with it), and rebuild —
     *  with no photo the stock background widgets render again. */
    fun clearBackground() {
        viewModelScope.launch(engine) {
            if (_ui.value.projectId.isNotBlank()) {
                store.removeBackground(_ui.value.projectId, selectedStyleIndex)
                persistBackgroundUri(null)
                persistBackgroundColor(null)
            }
            _ui.update { it.copy(background = null, backgroundFit = "cover", backgroundColor = null) }
            refresh()
        }
    }

    /** Fonts + glyph tables a bake must carry so borrowed text widgets draw.
     *
     *  A face's text records (Pair value widgets, Comp composites) reference their font by
     *  an INDEX into the FACE's own font-binding list, and their glyph strings (weekdays,
     *  units, …) by index into the FACE's own locale tables — both are face-local. Left
     *  untouched, a borrowed record points at the wrapped seed's binding at the same slot
     *  (a different family/role) and at the seed's strings, and the firmware draws nothing
     *  — which is exactly the "image renders, text/composite does not" symptom.
     *
     *  Two-part fix ([TextBorrow]):
     *  - Fonts seat on the SEED's own proven bindings by role match (WF_BATT -> WF_BATTARY,
     *    else the seed's WF_VALUE slot, else slot 0): a slot the seed's own text already
     *    renders is a slot the watch demonstrably honors. Only a seed with NO font bindings
     *    at all appends the donor's own bindings (byte-faithful, first-use order) and
     *    points the records at those appended slots.
     *  - The donor's locale tables travel with the borrowed widgets (skipped when the seed
     *    already carries a same-named table), re-targeted at the baked face's directory.
     */
    private class TextBorrowPlan(
        /** libraryIndex -> donor font index -> final container font index. */
        val seats: Map<Int, (Int) -> Int>,
        /** Donor bindings to append (only when the seed has no bindings). */
        val fonts: List<FontBinding>,
        /** Donor glyph-table entries to append, already re-targeted to the baked face. */
        val glyphEntries: List<ContainerEntry>
    )

    private fun textBorrowPlan(layouts: List<List<PlacedEditorWidget>>, faceId: String): TextBorrowPlan {
        val seed = container ?: return TextBorrowPlan(emptyMap(), emptyList(), emptyList())
        val seedBindings = seed.fontBindings
        val used = LinkedHashSet<Int>()
        layouts.forEach { pl -> pl.forEach { pw -> if (pw.donorIndex < 0) used += -pw.donorIndex - 1 } }
        val seats = HashMap<Int, (Int) -> Int>(used.size)
        val fonts = ArrayList<FontBinding>()
        val glyphs = ArrayList<ContainerEntry>()
        var at = seedBindings.size
        used.forEach { lib ->
            val item = foreignDonorCache[lib] ?: return@forEach
            val bindings = item.bindings
            val appendAt = at
            seats[lib] = when {
                bindings.isNotEmpty() && seedBindings.isNotEmpty() ->
                    { idx -> TextBorrow.seatIndex(bindings, seedBindings, idx) ?: 0 }
                bindings.isNotEmpty() -> { idx -> appendAt + idx } // seed had no fonts: appended donor font
                else -> { idx -> idx } // donor has no fonts: nothing to re-point
            }
            if (bindings.isNotEmpty() && seedBindings.isEmpty()) {
                fonts += bindings
                at += bindings.size
            }
            // Glyph strings travel with the borrowed widgets, unless the seed already has a
            // same-named locale table (that table stays the winner, seeded first).
            glyphs += TextBorrow.nonCollidingGlyphEntries(seed, item.glyphEntries)
                .map { it.copy(directory = it.directory.copy(path = "./SM-R390_${faceId}_256x402/${it.name}")) }
        }
        return TextBorrowPlan(seats, fonts, glyphs)
    }

    /** Generate container bytes and validate them (source of truth for preview/install).
     * @param withPreview additionally embed a regenerated preview.bin (one 178x280 RGB565
     *                     thumbnail per style) — the watch's face-carousel thumbnail.
     *                     Only the baked output needs it, so rebuilds stay cheap. */
    fun rebuild(withPreview: Boolean = false) {
        if (container == null) return
        rebuildJob?.cancel()
        viewModelScope.launch(engine) { rebuildNow(withPreview) }
    }

    private fun rebuildNow(withPreview: Boolean = false) {
        val seed = container ?: return
        val active = currentStyle() ?: return
        val activeView = styleView(selectedStyleIndex)
        val activeBg = bgRasterOf(activeView)
        // The watch only shows faces registered in the Wearable app's watch-face list
        // (favorites/downloads); a raw-delivered bin can overwrite a registered face
        // (same id) but cannot introduce a new one. Keeping the seed's id is what makes
        // the edited face appear on the watch at all.
        val faceId = (seed.settingEntry?.payload as? ContainerPayload.Setting)?.data?.faceId
            ?.ifBlank { "90001" } ?: "90001"
        // Borrowed text widgets carry face-local font + glyph indexes; without re-targeting
        // (see textBorrowPlan) the wrapped seed resolves them to the wrong binding and the
        // firmware draws nothing. Applied uniformly to every style the bake rebuilds.
        val borrow = textBorrowPlan(
            buildList {
                styles.forEachIndexed { i, _ ->
                    if (i == selectedStyleIndex || styleStates[i]?.modified == true) add(styleView(i).placed)
                }
                add(activeView.placed)
            },
            faceId
        )
        val fontSeats = borrow.seats
        // Build every style from ITS OWN edits: modified styles get their placed
        // widgets + background baked, untouched styles ship exactly as the seed had
        // them — the per-style requirement ("only the style I changed is affected").
        val variants = styles.mapIndexed { i, style ->
            if (i != selectedStyleIndex && styleStates[i]?.modified != true) {
                style
            } else {
                val v = styleView(i)
                val styleBg = bgRasterOf(v)
                BlankFaceBuilder.buildStyle(
                    placedInStyle(style, i, v.placed, dropBackground = styleBg != null, fontSeats = fontSeats),
                    styleBg
                )
            }
        }
        val placed = placedInStyle(active, selectedStyleIndex, activeView.placed, dropBackground = activeBg != null, fontSeats = fontSeats)
        val seedFontCount = container?.fontBindings?.size ?: 0
        val fontEntries = borrow.fonts.mapIndexed { i, b ->
            ContainerEntry(
                DirectoryEntry("./SM-R390_${faceId}_256x402/font_${seedFontCount + i}.bin", 0, b.encode().size, 0),
                ContainerPayload.FontBinding(b)
            )
        }
        val extra = fontEntries + borrow.glyphEntries +
            if (withPreview) listOf(previewEntry(faceId, variants)) else emptyList()

        try {
            val bytes = BlankFaceBuilder.build(
                seed = seed,
                faceId = faceId,
                faceName = "SM-R390_${faceId}_256x402",
                placed = placed,
                background = activeBg,
                styles = variants,
                extraEntries = extra
            )
            // The reparsed container is dropped: a reparsed real face is ~10 MB of heap
            // and the editor only ever shows ok / errors / size.
            val validation = FaceValidator.validate(bytes, retainReparsed = false)
            _ui.update {
                it.copy(
                    generatedBytes = bytes,
                    validation = validation,
                    seedLoaded = true
                )
            }
        } catch (e: Exception) {
            _ui.update {
                it.copy(validation = ValidationResult.fail(listOf(e.message ?: "Build failed")))
            }
        }
    }

    /** Rebuild from the current layout, write output.bin and return it. Suspends so the
     * caller can navigate only once the file the Preview page reads really exists. The
     * baked file carries the regenerated preview.bin, since it is what the watch reads
     * for the face-selector thumbnail. */
    suspend fun bakeOutput(): java.io.File? = withContext(engine) {
        rebuildJob?.cancel()
        rebuildNow(withPreview = true)
        val bytes = _ui.value.generatedBytes
        if (bytes == null) null else store.writeOutput(_ui.value.projectId, bytes)
    }

    /** Re-render the canvas preview and queue the container rebuild, both on the engine. */
    private fun refresh() {
        val style = currentStyle() ?: return
        viewModelScope.launch(engine) { renderPreview(style) }
        scheduleRebuild()
    }

    /**
     * Coalesce the container build.
     *
     * Measured on a real 4-style face, one build + validate is ~43 ms of CPU and ~18 MB of
     * allocation (the container is 2.6 MB and validation reparses it). Running that per
     * nudge, tap and drag put the process in permanent GC pressure — MIUI's watchdog
     * recorded 5-second frames and the heap sat at 0% free. Waiting for the edit to settle
     * turns a burst into a single build. Nothing downstream depends on it being immediate:
     * the Preview page asks for a fresh build through [bakeOutput] itself.
     */
    private fun scheduleRebuild() {
        if (container == null) return
        rebuildJob?.cancel()
        // Launched on the engine rather than Main: `delay` suspends without holding the
        // engine thread, and nothing here needs the UI thread to tick.
        rebuildJob = viewModelScope.launch(engine) {
            delay(RebuildDebounceMillis)
            rebuildNow()
        }
    }

    /** Render the canvas preview for the current placed list only (no rebuild).
     * [excludeId]: when set, that widget is left out — the clean base a drag blits over. */
    private fun renderPreview(style: StyleEntry, excludeId: Long? = null) {
        val state = _ui.value
        val dropBg = state.background != null || state.backgroundColor != null
        // Preview must resolve fonts exactly like the bake does, or it lies about what the
        // watch draws. Same seat + same carried glyph tables the bake appends.
        val plan = textBorrowPlan(listOf(state.placed), faceIdOf())
        val placedWithTint = state.placed
            .mapNotNull { pw ->
                if (pw.donorIndex >= 0 && pw.donorIndex !in 0 until style.widgets.size)
                    return@mapNotNull null
                if (dropBg && meaningOf(pw, style) == WidgetMeaning.BACKGROUND)
                    return@mapNotNull null
                val donor = if (pw.donorIndex < 0) {
                    val reseated = plan.seats[-pw.donorIndex - 1]
                        ?.let { TextBorrow.reseat(pw.donor, it) } ?: pw.donor
                    PlacedWidget(
                        reseated,
                        overrideRasters(selectedStyleIndex, pw.donorIndex, pw.donorRasters ?: emptyList()),
                        pw.x, pw.y, pw.tint
                    )
                } else {
                    val d = BlankFaceBuilder.extractDonor(style, pw.donorIndex)
                    PlacedWidget(d.donor, overrideRasters(selectedStyleIndex, pw.donorIndex, d.donorRasters), pw.x, pw.y, pw.tint)
                }
                pw to donor
            }
            .filter { excludeId == null || it.first.id != excludeId }
        val placed = placedWithTint.map { it.second }
        val bg = bgRasterOf(styleView(selectedStyleIndex))
        // buildStyle prepends the background widget (index 0) when a background exists,
        // so widget indexes shift by 1 and tint keys must follow.
        val bgOffset = if (bg != null) 1 else 0
        val textTints = placedWithTint
            .mapIndexedNotNull { i, (_, pw) -> pw.tint?.let { (i + bgOffset) to it } }
            .toMap()
        val preview = FaceStyleRenderer.render(
            BlankFaceBuilder.buildStyle(placed, bg),
            PreviewStats(),
            rasterCache,
            null,
            textTints,
            text = textResourcesWith(plan)
        )
        _ui.update { it.copy(preview = preview) }
    }

    /** The face's container id (the baked face keeps the seed's id). */
    private fun faceIdOf(): String =
        (container?.settingEntry?.payload as? ContainerPayload.Setting)?.data?.faceId
            ?.ifBlank { "90001" } ?: "90001"

    /** Font bindings + glyph strings exactly as the baked container resolves them: seed
     *  bindings (with donor text seated on them) and the seed's glyph tables, with the
     *  plan's carried donor tables appended wherever the seed has no same-named one. */
    private fun textResourcesWith(plan: TextBorrowPlan): TextResources {
        val seed = container ?: return TextResources()
        // Same order the bake emits them in (seed tables first, then carried, never
        // colliding), so the "font_en.bin else first table" rule picks the same winner
        // the watch firmware will.
        val glyphEntries = seed.entries.filter { it.payload is ContainerPayload.GlyphTable } + plan.glyphEntries
        val table = glyphEntries.find { it.name == "font_en.bin" }
            ?: glyphEntries.firstOrNull()
        val groups = (table?.payload as? ContainerPayload.GlyphTable)?.data
            ?.groups?.sortedBy { it.index }?.map { it.text }.orEmpty()
        return TextResources(seed.fontBindings, groups)
    }

    /** A solid-color panel raster in RGB565 — the "no picture" background the user
     *  picks by color. The format matches the imageless fill the firmware composites
     *  like any other background widget raster. */
    private fun solidRaster(argb: Int): Raster =
        Rgb565.toRaster565(
            android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888).apply {
                setPixel(0, 0, argb)
            },
            WatchFaceFormat.PANEL_WIDTH,
            WatchFaceFormat.PANEL_HEIGHT,
            mode = "stretch"
        )

    /**
     * The rebuilt face's carousel thumbnail: a preview.bin entry holding one 178x280
     * RGB565 raster per style, each rendered from the edited style. Stock faces ship a
     * preview.bin the watch shows in its face selector; regenerating it here is what
     * makes the installed face's thumbnail show the user's edits instead of the stock art.
     */
    private fun previewEntry(faceId: String, variants: List<StyleEntry>): ContainerEntry {
        val rasters = variants.map { style ->
            val rendered = FaceStyleRenderer.render(
                style, PreviewStats(), rasterCache,
                text = container?.textResources() ?: TextResources()
            )
            // 256:402 and 178:280 share an aspect ratio, so stretch is a pure downscale.
            Rgb565.toRaster565(rendered, PreviewWidth, PreviewHeight, mode = "stretch")
        }
        val out = ByteArray(rasters.sumOf { it.encodedSize() })
        val buf = java.nio.ByteBuffer.wrap(out).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        rasters.forEach { r ->
            buf.putShort(r.width.toShort())
            buf.putShort(r.height.toShort())
            buf.putShort(r.format.toShort())
            buf.putShort(0)
            buf.putInt(r.length)
            r.writePixelsTo(out, buf.position())
            buf.position(buf.position() + r.length)
        }
        return ContainerEntry(
            directory = DirectoryEntry("./SM-R390_${faceId}_256x402/preview.bin", 0, out.size, 0),
            payload = ContainerPayload.Unknown(out)
        )
    }

    /** [donorRasters] with every user-replaced frame of [styleIndex]'s overrides swapped
     *  for a re-encoded raster of the replacement image. Dimension and format stay those
     *  of the original frame, so the firmware's sprite indexing is untouched. */
    private fun overrideRasters(styleIndex: Int, donorIndex: Int, donorRasters: List<Raster>): List<Raster> {
        if (donorRasters.isEmpty()) return donorRasters
        val overrides = styleOverrides(styleIndex)
        if (overrides.isEmpty()) return donorRasters
        var changed = false
        val out = donorRasters.mapIndexed { fi, original ->
            val fit = overrides[donorIndex to fi]
            if (fit == null) {
                original
            } else {
                val f = resolveFrameFile(_ui.value.projectId, styleIndex, donorIndex, fi) ?: return@mapIndexed original
                val bmp = frameBitmapCache[f.absolutePath] ?: run {
                    // Default config (ARGB_8888): replacement frames keep their alpha —
                    // a 565 decode would turn the "fit" letterbox opaque black.
                    val decoded = android.graphics.BitmapFactory.decodeFile(f.absolutePath)
                        ?: return@mapIndexed original
                    frameBitmapCache[f.absolutePath] = decoded
                    decoded
                }
                changed = true
                Rgb565.toRaster565(
                    bmp,
                    original.width,
                    original.height,
                    withAlpha = original.format == WatchFaceFmt.FORMAT_RGB565_A,
                    mode = fit
                )
            }
        }
        return if (changed) out else donorRasters
    }

    /** The persisted replacement image for a frame of [styleIndex], or null. */
    private fun resolveFrameFile(projectId: String, styleIndex: Int, donorIndex: Int, frameIndex: Int): java.io.File? {
        val f = store.frameFile(projectId, styleIndex, donorIndex, frameIndex)
        return if (f.exists()) f else null
    }

    /** How many image frames the donor widget has (0 = it has no raster of its own). */
    fun frameCount(donorIndex: Int): Int = try {
        currentStyle()?.let { BlankFaceBuilder.extractDonor(it, donorIndex).donorRasters.size } ?: 0
    } catch (_: Exception) {
        0
    }

    /** Pixel size of one donor frame, for the fit-mode preview. */
    fun frameSize(donorIndex: Int, frameIndex: Int): Pair<Int, Int> = try {
        currentStyle()?.let {
            BlankFaceBuilder.extractDonor(it, donorIndex).donorRasters.getOrNull(frameIndex)
        }?.let { it.width to it.height } ?: (0 to 0)
    } catch (_: Exception) {
        0 to 0
    }

    /** The bitmap of one donor frame for the inspector's thumbnails: the user's
     *  replacement when the frame has been replaced (so the row shows what the frame
     *  became), else the stock raster. Main-thread safe; decodes are cached. */
    fun donorFrameBitmap(donorIndex: Int, frameIndex: Int): Bitmap? = try {
        val style = currentStyle() ?: return null
        val raster = BlankFaceBuilder.extractDonor(style, donorIndex).donorRasters.getOrNull(frameIndex)
            ?: return null
        val projectId = _ui.value.projectId
        val f = if (projectId.isNotBlank()) resolveFrameFile(projectId, selectedStyleIndex, donorIndex, frameIndex) else null
        if (f != null) {
            frameBitmapCache[f.absolutePath] ?: run {
                android.graphics.BitmapFactory.decodeFile(f.absolutePath) ?: Rgb565.toBitmap(raster)
            }.also { frameBitmapCache[f.absolutePath] = it }
        } else {
            rasterCache.bitmap(raster)
        }
    } catch (_: Exception) {
        null
    }

    /** The stored fit mode of a replaced frame, or null when it is stock. */
    fun frameMode(donorIndex: Int, frameIndex: Int): String? =
        frameOverrides[donorIndex to frameIndex]

    /** The replacement image file of a frame, or null when the frame is stock. */
    fun frameImageFile(donorIndex: Int, frameIndex: Int): java.io.File? {
        val projectId = _ui.value.projectId
        return if (projectId.isBlank()) null else resolveFrameFile(projectId, selectedStyleIndex, donorIndex, frameIndex)
    }

    /** Re-place an already-replaced frame with a different fit mode — no image re-pick,
     *  the stored PNG is simply re-encoded with the new transform. */
    fun setFrameMode(donorIndex: Int, frameIndex: Int, mode: String) {
        viewModelScope.launch(engine) {
            val key = donorIndex to frameIndex
            if (_ui.value.projectId.isBlank() || key !in frameOverrides) return@launch
            frameOverrides = frameOverrides + (key to mode)
            saveStyleEdit(selectedStyleIndex)
            _ui.update { it.copy(frameOverrides = it.frameOverrides + key) }
            refresh()
        }
    }

    /**
     * Replace one frame's image with the picked picture.
     *
     * The frame's raster is swapped for the re-encoded replacement before every build and
     * render, so the change reaches the canvas, the generated face and the PNG saved into
     * the project at once. The picker grant is temporary; the copy is what makes the
     * replacement survive restarts, exactly like the custom background.
     */
    fun setFrameImageFromUri(donorIndex: Int, frameIndex: Int, uri: android.net.Uri, mode: String) {
        viewModelScope.launch(engine) {
            val style = currentStyle() ?: return@launch
            val original = try {
                BlankFaceBuilder.extractDonor(style, donorIndex).donorRasters.getOrNull(frameIndex)
            } catch (_: Exception) {
                null
            } ?: return@launch
            val bytes = store.readUriBytes(uri) ?: return@launch
            val decoded = decodeArgb(bytes, original.width * 2, original.height * 2) ?: return@launch
            val projectId = _ui.value.projectId
            if (projectId.isBlank()) return@launch
            store.saveFrameImage(projectId, selectedStyleIndex, donorIndex, frameIndex, decoded)

            val key = donorIndex to frameIndex
            val fit = mode
            frameOverrides = frameOverrides + (key to fit)
            frameBitmapCache.remove(resolveFrameFile(projectId, selectedStyleIndex, donorIndex, frameIndex)?.absolutePath)
            saveStyleEdit(selectedStyleIndex)
            _ui.update { it.copy(frameOverrides = it.frameOverrides + key) }
            refresh()
        }
    }

    /** Restore one frame's stock image: drop the override, delete the stored copy. */
    fun clearFrameImage(donorIndex: Int, frameIndex: Int) {
        viewModelScope.launch(engine) {
            val projectId = _ui.value.projectId
            if (projectId.isBlank()) return@launch
            store.deleteFrameFile(projectId, selectedStyleIndex, donorIndex, frameIndex)
            val key = donorIndex to frameIndex
            frameOverrides = frameOverrides - key
            saveStyleEdit(selectedStyleIndex)
            _ui.update { it.copy(frameOverrides = it.frameOverrides - key) }
            refresh()
        }
    }

    /** Export/import progress surfaced to the editor UI. */
    data class AssetBundleUiState(
        /** True while an export or import is being prepared on the engine. */
        val busy: Boolean = false,
        /** The exported zip file, ready to share; null until an export finished. */
        val exportedFile: java.io.File? = null,
        /** Set when export/import failed, with a user-facing reason. */
        val error: String? = null,
        /** Human summary after a successful import, e.g. "5 frames replaced, background updated". */
        val importedSummary: String? = null
    )

    private val _assetBundle = MutableStateFlow(AssetBundleUiState())
    val assetBundle: StateFlow<AssetBundleUiState> = _assetBundle.asStateFlow()

    /** Clear a finished bundle result once the UI has consumed it (shared / dismissed). */
    fun consumeAssetBundleResult() {
        _assetBundle.value = AssetBundleUiState()
    }

    /**
     * Export every editable asset — background and every donor widget frame — as one
     * zip the user can edit externally and re-import ("Download assets").
     *
     * The background asset is the *effective* background: the custom photo when one is
     * set, else the seed's background raster. A solid color exports as its color fill.
     * Re-importing an unedited zip is therefore a no-op by construction, and editing a
     * pixel in any PNG lands exactly where the editor showed that pixel's owner.
     */
    fun exportAssets(context: android.content.Context) {
        if (_assetBundle.value.busy) return
        _assetBundle.value = AssetBundleUiState(busy = true)
        viewModelScope.launch(engine) {
            try {
                val parsed = container ?: throw IllegalStateException("No face loaded")
                val state = _ui.value
                // A custom photo overrides the seed's own background raster in the
                // export so the zip always reflects what the editor shows.
                val export = AssetBundle.exportZip(parsed)
                var zip = export.zip
                val photo = state.background
                if (photo != null) {
                    val fit = state.backgroundFit
                    val framed = Rgb565.toRaster565(
                        photo, WatchFaceFormat.PANEL_WIDTH, WatchFaceFormat.PANEL_HEIGHT,
                        withAlpha = fit.startsWith(Rgb565.MANUAL_PREFIX), mode = fit
                    )
                    val bgPng = run {
                        val pixels = IntArray(framed.width * framed.height)
                        for (i in pixels.indices) {
                            val off = i * framed.bpp
                            val half = (framed.pixels[off].toInt() and 0xFF) or
                                ((framed.pixels[off + 1].toInt() and 0xFF) shl 8)
                            val r = ((half ushr 11) and 0x1F) shl 3
                            val g = ((half ushr 5) and 0x3F) shl 2
                            val b = (half and 0x1F) shl 3
                            val a = if (framed.bpp == 3) framed.pixels[off + 2].toInt() and 0xFF else 0xFF
                            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
                        }
                        com.galaxyfit3.core.format.parser.Png.encode(framed.width, framed.height, pixels)
                    }
                    zip = replaceZipEntry(zip, "assets/background.png", bgPng)
                } else if (state.backgroundColor != null) {
                    // Solid color: rasterize the fill and export it as the background.
                    val hex = state.backgroundColor!!
                    val argb = runCatching { android.graphics.Color.parseColor(hex) }.getOrDefault(0xFF000000.toInt())
                    val raster = solidRaster(argb)
                    val bgPng = com.galaxyfit3.core.format.parser.Png.encode(
                        raster.width, raster.height, IntArray(raster.width * raster.height) { argb }
                    )
                    zip = if (export.hasBackground) replaceZipEntry(zip, "assets/background.png", bgPng)
                    else addZipEntry(zip, "assets/background.png", bgPng)
                }
                // Directory the FileProvider already exposes (cache-path name="share").
                val dir = java.io.File(context.cacheDir, "share").apply { mkdirs() }
                val safe = state.projectName.ifBlank { "face" }
                    .replace(Regex("[^A-Za-z0-9_-]+"), "_").take(40)
                val out = java.io.File(dir, "${safe}_assets.zip")
                out.outputStream().use { it.write(zip) }
                _assetBundle.value = AssetBundleUiState(exportedFile = out)
            } catch (e: Exception) {
                _assetBundle.value = AssetBundleUiState(error = e.message ?: "Export failed")
            }
        }
    }

    /** Rewrite one entry's bytes inside a zip, keeping every other entry as-is. */
    private fun replaceZipEntry(zipBytes: ByteArray, name: String, newBytes: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { z ->
            java.util.zip.ZipInputStream(zipBytes.inputStream()).use { zin ->
                var e = zin.nextEntry
                while (e != null) {
                    z.putNextEntry(java.util.zip.ZipEntry(e.name))
                    if (e.name == name) z.write(newBytes) else zin.copyTo(z)
                    z.closeEntry()
                    e = zin.nextEntry
                }
            }
        }
        return out.toByteArray()
    }

    /** Append one entry to an existing zip. */
    private fun addZipEntry(zipBytes: ByteArray, name: String, bytes: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { z ->
            java.util.zip.ZipInputStream(zipBytes.inputStream()).use { zin ->
                var e = zin.nextEntry
                while (e != null) {
                    z.putNextEntry(java.util.zip.ZipEntry(e.name))
                    zin.copyTo(z)
                    z.closeEntry()
                    e = zin.nextEntry
                }
            }
            z.putNextEntry(java.util.zip.ZipEntry(name))
            z.write(bytes)
            z.closeEntry()
        }
        return out.toByteArray()
    }

    /**
     * Map an edited zip back onto the project ("Upload assets").
     *
     * Every zip frame whose pixel size matches its seed raster becomes that widget's
     * replacement image (persisted like a manual frame edit, so re-bakes and sessions
     * keep it); a background PNG becomes the custom background photo. The zip may
     * contain a subset — untouched PNGs can simply be left out.
     */
    fun importAssets(context: android.content.Context, uri: android.net.Uri) {
        if (_assetBundle.value.busy) return
        _assetBundle.value = AssetBundleUiState(busy = true)
        viewModelScope.launch(engine) {
            try {
                val parsed = container ?: throw IllegalStateException("No face loaded")
                val zipBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("Cannot read the zip file")
                val bundle = AssetBundle.importZip(zipBytes)
                if (bundle.frames.isEmpty() && bundle.background == null) {
                    throw IllegalArgumentException(
                        "No matching asset in this zip" +
                            (bundle.ignored.takeIf { it.isNotEmpty() }
                                ?.let { " (" + it.size + " files skipped)" } ?: "")
                    )
                }
                val mapped = AssetBundle.mapToSeed(bundle, parsed)
                val projectId = _ui.value.projectId
                var appliedFrames = 0
                mapped.forEach { (key, pixels) ->
                    val (donorIndex, frameIndex) = key
                    val style = currentStyle()
                    val original = try {
                        if (style == null) null
                        else BlankFaceBuilder.extractDonor(style, donorIndex).donorRasters.getOrNull(frameIndex)
                    } catch (_: Exception) {
                        null
                    } ?: return@forEach
                    // Persist through the same path as a manual frame replacement: the
                    // PNG must be ARGB_8888 so "fit" letterboxes keep their alpha.
                    val bmp = android.graphics.Bitmap.createBitmap(
                        pixels.width, pixels.height, android.graphics.Bitmap.Config.ARGB_8888
                    )
                    bmp.setPixels(pixels.argb, 0, pixels.width, 0, 0, pixels.width, pixels.height)
                    store.saveFrameImage(projectId, selectedStyleIndex, donorIndex, frameIndex, bmp)
                    val keyPair = donorIndex to frameIndex
                    val fit = frameOverrides[keyPair] ?: "fit"
                    frameOverrides = frameOverrides + (keyPair to fit)
                    frameBitmapCache.remove(store.frameFile(projectId, selectedStyleIndex, donorIndex, frameIndex).absolutePath)
                    appliedFrames++
                }
                saveStyleEdit(selectedStyleIndex)
                _ui.update { it.copy(frameOverrides = frameOverrides.keys) }

                var appliedBackground = false
                AssetBundle.mapBackground(bundle)?.let { bg ->
                    val bmp = android.graphics.Bitmap.createBitmap(
                        bg.width, bg.height, android.graphics.Bitmap.Config.ARGB_8888
                    )
                    bmp.setPixels(bg.argb, 0, bg.width, 0, 0, bg.width, bg.height)
                    val path = store.saveBackground(projectId, selectedStyleIndex, pngBytes(bmp))
                    persistBackgroundUri(path, _ui.value.backgroundFit)
                    setBackground(bmp, _ui.value.backgroundFit)
                    appliedBackground = true
                }

                frameBitmapCache.clear()
                _assetBundle.value = AssetBundleUiState(
                    importedSummary = buildString {
                        if (appliedFrames > 0) append("$appliedFrames frames replaced")
                        if (appliedBackground) {
                            if (isNotEmpty()) append(", ")
                            append("background replaced")
                        }
                        if (isEmpty()) append("No changes were applied")
                    }
                )
                refresh()
            } catch (e: Exception) {
                _assetBundle.value = AssetBundleUiState(error = e.message ?: "Import failed")
            }
        }
    }

    /** Encode a bitmap as PNG bytes — import writes backgrounds as real PNGs so the
     *  stored project file stays inspectable, like every other stored image. */
    private fun pngBytes(bmp: Bitmap): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    /** Queue a save of the current placement; the write itself belongs on the engine. */
    private fun persist() {
        if (project == null || _ui.value.projectId.isBlank()) return
        viewModelScope.launch(engine) { saveStyleEdit(selectedStyleIndex) }
    }

    /** Fold the live board into the active style's record, apply [extras], and save it —
     *  the single funnel every edit funnels through. Engine thread. */
    private fun saveActive(extras: (StyleState) -> StyleState) {
        if (_ui.value.projectId.isBlank()) return
        syncActiveIntoMap()
        val st = styleStates[selectedStyleIndex]
        if (st != null) styleStates[selectedStyleIndex] = extras(st)
        saveStyleEdit(selectedStyleIndex, syncBoard = false)
    }

    /** Persist the custom-background path (or its removal with a null uri) together with
     *  the current placement and background placement mode. Runs on the engine. */
    private fun persistBackgroundUri(uri: String?, fit: String = _ui.value.backgroundFit) {
        saveActive { st ->
            st.copy(
                backgroundUri = uri,
                backgroundFit = fit,
                background = if (uri == null) null else st.background
            )
        }
    }

    /** Persist the solid background color (or its removal); a color clears any photo. */
    private fun persistBackgroundColor(hex: String?) {
        saveActive { st ->
            st.copy(backgroundColor = hex, background = null, backgroundUri = null, backgroundFit = "cover")
        }
    }

    /** Decode image [bytes] fully (ARGB_8888, alpha preserved) downsampled to about
     *  [reqWidth]x[reqHeight]. Unlike [Rgb565.decodeSampled] it keeps transparency —
     *  sprite frames are re-encoded with their original alpha format. */
    private fun decodeArgb(bytes: ByteArray, reqWidth: Int, reqHeight: Int): Bitmap? = try {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null
        else {
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= reqWidth && bounds.outHeight / (sample * 2) >= reqHeight) sample *= 2
            android.graphics.BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            )
        }
    } catch (_: Exception) {
        null
    }

    /** #RRGGBB without alpha — the persisted form of a text colour. */
    private fun toHex(argb: Int): String =
        "#%06X".format(java.util.Locale.US, argb and 0xFFFFFF)
}

/** How long an edit must be quiet before the container is rebuilt and revalidated. */
private const val RebuildDebounceMillis = 400L

/** The watch's face-carousel thumbnail size, seen on every sampled stock face. */
private const val PreviewWidth = 178
private const val PreviewHeight = 280