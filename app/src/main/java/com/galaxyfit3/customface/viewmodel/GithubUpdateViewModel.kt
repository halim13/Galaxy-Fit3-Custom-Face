package com.galaxyfit3.customface.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.galaxyfit3.customface.BuildConfig
import com.galaxyfit3.customface.data.GithubUpdateApi
import com.galaxyfit3.customface.data.LatestRelease
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class UpdateUiState(
    val checking: Boolean = false,
    /** The latest release on GitHub, when one exists. */
    val latest: LatestRelease? = null,
    /** True once checked and no newer APK is published. */
    val upToDate: Boolean = false,
    /** Non-fatal notice, e.g. the repo has no release yet. */
    val notice: String? = null,
    val error: String? = null
)

@HiltViewModel
class GithubUpdateViewModel @Inject constructor(
    private val api: GithubUpdateApi
) : ViewModel() {

    private val _ui = MutableStateFlow(UpdateUiState())
    val ui: StateFlow<UpdateUiState> = _ui.asStateFlow()

    /** True when the release tag declares a version newer than the installed one. */
    private fun hasNewer(tag: String): Boolean {
        val installed = BuildConfig.VERSION_NAME
        val a = parts(tag)
        val b = parts(installed)
        for (i in 0 until maxOf(a.size, b.size)) {
            val lhs = a.getOrElse(i) { 0 }
            val rhs = b.getOrElse(i) { 0 }
            if (lhs != rhs) return lhs > rhs
        }
        return false
    }

    private fun parts(v: String): List<Int> =
        v.trimStart('v', 'V').split('.', '-').mapNotNull { it.toIntOrNull() }.take(3)

    fun check() {
        if (_ui.value.checking) return
        _ui.update { UpdateUiState(checking = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = try {
                val latest = api.latestApk()
                if (latest == null) {
                    UpdateUiState(notice = "No release published on GitHub yet — the check works once the first version is uploaded")
                } else if (hasNewer(latest.tag)) {
                    UpdateUiState(latest = latest)
                } else {
                    UpdateUiState(upToDate = true)
                }
            } catch (e: Exception) {
                UpdateUiState(error = e.message ?: e.javaClass.simpleName)
            }
            _ui.value = result
        }
    }
}