package com.galaxyfit3.customface.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.galaxyfit3.core.format.parser.WatchFaceParser
import com.galaxyfit3.customface.data.CatalogFace
import com.galaxyfit3.customface.data.ProjectStore
import com.galaxyfit3.customface.data.SamsungStoreApi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StoreUiState(
    val loading: Boolean = false,
    val faces: List<CatalogFace> = emptyList(),
    val downloadingId: String? = null,
    val importedProjectId: String? = null,
    val error: String? = null
)

@HiltViewModel
class StoreViewModel @Inject constructor(
    private val api: SamsungStoreApi,
    private val store: ProjectStore
) : ViewModel() {

    private val _ui = MutableStateFlow(StoreUiState())
    val ui: StateFlow<StoreUiState> = _ui.asStateFlow()

    fun load() {
        if (_ui.value.faces.isNotEmpty() || _ui.value.loading) return
        _ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val (faces, error) = try {
                api.listFaces() to null
            } catch (e: Exception) {
                emptyList<CatalogFace>() to (e.message ?: e.javaClass.simpleName)
            }
            _ui.update { it.copy(loading = false, faces = faces, error = error) }
        }
    }

    fun refresh() {
        _ui.update { it.copy(faces = emptyList()) }
        load()
    }

    fun download(face: CatalogFace) {
        _ui.update { it.copy(downloadingId = face.id, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = try {
                val seed = api.downloadSeed(face)
                // The imported container doubles as the design seed (donors library).
                WatchFaceParser.parse(seed)
                val project = store.create(seed, face.name, face.name)
                project.id
            } catch (e: Exception) {
                _ui.update { it.copy(downloadingId = null, error = "Failed to download ${face.name}: ${e.message}") }
                return@launch
            }
            _ui.update { it.copy(downloadingId = null, importedProjectId = result) }
        }
    }
}