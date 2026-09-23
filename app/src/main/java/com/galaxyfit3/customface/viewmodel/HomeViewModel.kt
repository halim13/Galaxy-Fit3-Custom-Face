package com.galaxyfit3.customface.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.galaxyfit3.core.format.WatchFaceFormat
import com.galaxyfit3.core.format.model.ContainerPayload
import com.galaxyfit3.core.format.parser.WatchFaceParser
import com.galaxyfit3.core.image.FaceStyleRenderer
import com.galaxyfit3.core.image.TextResources
import com.galaxyfit3.core.image.textResources
import com.galaxyfit3.core.image.PreviewStats
import com.galaxyfit3.core.image.Rgb565
import com.galaxyfit3.customface.data.Project
import com.galaxyfit3.customface.data.ProjectStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ImportResult(
    val projectId: String? = null,
    val error: String? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val store: ProjectStore,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    private val _thumbnails = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val thumbnails: StateFlow<Map<String, Bitmap>> = _thumbnails.asStateFlow()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _importResult = MutableStateFlow<ImportResult?>(null)
    val importResult: StateFlow<ImportResult?> = _importResult.asStateFlow()

    fun refresh() {
        val list = store.projects()
        _projects.value = list
        _thumbnails.update { thumbs -> thumbs.filterKeys { id -> list.any { it.id == id } } }
        loadThumbnails()
    }

    /** Load each project's list thumbnail, from disk when it is already there. */
    private fun loadThumbnails() {
        viewModelScope.launch(Dispatchers.Default) {
            val updates = HashMap<String, Bitmap>()
            _projects.value.forEach { p ->
                if (_thumbnails.value.containsKey(p.id)) return@forEach
                thumbnail(p)?.let { updates[p.id] = it }
            }
            if (updates.isNotEmpty()) _thumbnails.update { it + updates }
        }
    }

    /**
     * The thumbnail, rendered once and then cached on disk.
     *
     * Rendering one costs a full parse of the face — measured at ~16 MB of heap and ~53 ms
     * for a 3.7 MB seed — and this ViewModel is recreated on every visit to the list, so
     * re-rendering every project each time was a multi-megabyte GC storm landing exactly
     * while the back transition was animating (the 5-second frame MIUI's watchdog caught).
     * The picture is the seed's own first style and never changes on its own, so the file
     * needs no invalidation.
     */
    private fun thumbnail(project: Project): Bitmap? {
        val file = store.thumbnailFile(project.id)
        if (file.exists()) BitmapFactory.decodeFile(file.absolutePath)?.let { return it }

        val seed = store.seedBytes(project.id) ?: return null
        val full = try {
            val parsed = WatchFaceParser.parse(seed)
            val vendorPreview = parsed.previewRasters.firstOrNull()?.let { raster ->
                Rgb565.toBitmap(raster).let { bmp ->
                    Bitmap.createScaledBitmap(bmp, WatchFaceFormat.PANEL_WIDTH, WatchFaceFormat.PANEL_HEIGHT, true)
                }
            }
            parsed.styleEntries.firstNotNullOfOrNull { (it.payload as? ContainerPayload.Style)?.data }
                ?.let { FaceStyleRenderer.render(it, PreviewStats(), vendorPreview = vendorPreview, text = parsed.textResources()) }
        } catch (_: Exception) {
            null
        } ?: return null

        // The list draws this in a 48dp box, so the full 256x402 render is never shown.
        val scaled = Bitmap.createScaledBitmap(full, THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT, true)
        if (scaled !== full) full.recycle()
        runCatching {
            file.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return scaled
    }

    /**
     * Read the seed face from a SAF Uri, validate it parses, and create a project.
     */
    fun importSeed(binary: ByteArray, originalName: String) {
        _importing.value = true
        _importResult.value = null
        viewModelScope.launch(Dispatchers.Default) {
            val result = try {
                WatchFaceParser.parse(binary) // throws if not a container
                val name = (originalName.ifBlank { "Face" })
                    .substringBeforeLast('.')
                    .take(30)
                val project = store.create(binary, originalName, name)
                ImportResult(projectId = project.id)
            } catch (e: Exception) {
                ImportResult(error = "Not a valid watch face container: ${e.message}",
                    )
            }
            _importing.value = false
            _importResult.value = result
        }
    }

    fun delete(projectId: String) {
        store.delete(projectId)
        refresh()
    }

    /** Rename a project and refresh the list; no-op for blank names. */
    fun rename(projectId: String, newName: String) {
        store.rename(projectId, newName)
        // Update the in-memory list so the new name shows without a full re-scan.
        _projects.value = _projects.value.map {
            if (it.id == projectId) it.copy(name = newName.trim().take(60).ifEmpty { it.name }) else it
        }
    }

    /** Write the project's original seed bytes to a SAF [uri] from a CreateDocument
     *  picker — the untouched vendor .bin, as imported (before any canvas rendering). */
    fun exportSeed(projectId: String, uri: Uri) {
        viewModelScope.launch(Dispatchers.Default) {
            val bytes = store.seedBytes(projectId) ?: return@launch
            runCatching {
                context.contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
            }
        }
    }

    private companion object {
        /** Enough for the 48dp (156px at 3.25x) thumbnail box on the projects list. */
        const val THUMBNAIL_WIDTH = 160
        const val THUMBNAIL_HEIGHT = 251
    }
}