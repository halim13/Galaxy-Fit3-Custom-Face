package com.galaxyfit3.customface.viewmodel

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.galaxyfit3.core.format.model.ContainerPayload
import com.galaxyfit3.core.format.parser.WatchFaceParser
import com.galaxyfit3.core.format.validator.FaceValidator
import com.galaxyfit3.core.format.validator.ValidationResult
import com.galaxyfit3.core.image.FaceStyleRenderer
import com.galaxyfit3.core.image.TextResources
import com.galaxyfit3.core.image.textResources
import com.galaxyfit3.core.image.PreviewStats
import com.galaxyfit3.customface.data.ProjectStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PreviewUiState(
    val loading: Boolean = false,
    val outputBytes: ByteArray? = null,
    val validation: ValidationResult? = null,
    val preview: Bitmap? = null,
    val error: String? = null,
    /** The style selected in the editor, so Preview opens on the style the user edited. */
    val selectedStyleIndex: Int = 0,
    val styleCount: Int = 0,
    /** Styles whose data was edited; the style chips badge these. */
    val modifiedStyles: Set<Int> = emptySet(),
    /** Project display name, used as the shared bin's filename. */
    val projectName: String = ""
)

@HiltViewModel
class PreviewViewModel @Inject constructor(
    private val store: ProjectStore
) : ViewModel() {

    private val _ui = MutableStateFlow(PreviewUiState())
    val ui: StateFlow<PreviewUiState> = _ui.asStateFlow()

    private var styles: List<com.galaxyfit3.core.format.model.StyleEntry> = emptyList()
    private var container: com.galaxyfit3.core.format.model.WatchFaceContainer? = null

    /** Load the editor-baked output, then render + validate it. */
    fun load(projectId: String) {
        _ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch(Dispatchers.Default) {
            val output = java.io.File(store.projectDir(projectId), "output.bin")
            if (!output.exists()) {
                _ui.update {
                    it.copy(loading = false, error = "Output not built yet. Open the editor and press Preview.")
                }
                return@launch
            }
            val bytes = output.readBytes()
            // Parse once and reuse: validation and the preview render both need the parsed
            // container, and a second parse of a real face is another ~10 MB of heap.
            val parsed = try {
                WatchFaceParser.parse(bytes)
            } catch (e: Exception) {
                _ui.update {
                    it.copy(
                        loading = false,
                        outputBytes = bytes,
                        validation = ValidationResult.fail(listOf(e.message ?: e.javaClass.simpleName), bytes.size)
                    )
                }
                return@launch
            }
            container = parsed
            styles = parsed.styleEntries.mapNotNull { (it.payload as? ContainerPayload.Style)?.data }
            val validation = FaceValidator.validateParsed(parsed, bytes.size)
            // The baked output keeps every style edited, so the preview opens on the style
            // the editor was working in — not always style 0.
            val saved = store.get(projectId)?.styleIndex ?: 0
            val idx = saved.coerceIn(0, (styles.size - 1).coerceAtLeast(0))
            val preview = styles.getOrNull(idx)?.let {
                FaceStyleRenderer.render(it, PreviewStats(), vendorPreview = null, text = container?.textResources() ?: TextResources())
            }
            _ui.update {
                it.copy(
                    loading = false,
                    outputBytes = bytes,
                    validation = validation,
                    preview = preview,
                    selectedStyleIndex = idx,
                    styleCount = styles.size,
                    modifiedStyles = store.get(projectId)?.styleEdits?.keys ?: emptySet(),
                    projectName = store.get(projectId)?.name.orEmpty()
                )
            }
        }
    }

    /** Render another style (saved editor selection stays untouched). */
    fun selectStyle(index: Int) {
        val style = styles.getOrNull(index) ?: return
        viewModelScope.launch(Dispatchers.Default) {
            _ui.update {
                it.copy(
                    preview = FaceStyleRenderer.render(style, PreviewStats(), vendorPreview = null, text = container?.textResources() ?: TextResources()),
                    selectedStyleIndex = index
                )
            }
        }
    }
}