package com.galaxyfit3.customface.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.galaxyfit3.core.delivery.DirectInstallPayload
import com.galaxyfit3.core.delivery.Fit3DirectInstaller
import com.galaxyfit3.core.format.parser.FaceIdRemapper
import com.galaxyfit3.core.format.parser.FaceIdentity
import com.galaxyfit3.core.format.parser.WatchFaceParser
import com.galaxyfit3.customface.data.ProjectStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class InstallUiState(
    /** True once the output has been read, its identity checked and a payload exists. */
    val ready: Boolean = false,
    /** The id the container itself carries, e.g. "00031" or "90001". */
    val ownFaceId: String? = null,
    /**
     * Why the baked output cannot be sent with its own identity, or null when it can.
     * Sending a container whose install-command id disagrees with its filename and
     * setting.bin is what corrupts the watch's face list, so that shape is refused.
     */
    val identityError: String? = null,
    /**
     * The new id (five digits) the user assigned to a face whose own id cannot be
     * sent; the container is rewritten onto it before install. Null until chosen.
     */
    val assignedId: String? = null,
    /** Ids this app has already installed, so a colliding pick can be flagged. */
    val installedIds: Set<String> = emptySet(),
    /** The style index the install command will activate on the watch. */
    val samplerId: Int = 0,
)

@HiltViewModel
class InstallViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val store: ProjectStore,
    private val installer: Fit3DirectInstaller,
) : ViewModel() {

    /** Watch face containers over this size are rejected on install; see [load]. */
    private companion object {
        const val MAX_FACE_BYTES = 4 * 1024 * 1024

        /** Preferences file remembering the ids this app has installed. */
        const val PREFS_NAME = "fit3_face_slots"
        const val KEY_INSTALLED = "installed_ids"
    }

    val state = installer.state.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        installer.state.value,
    )

    private val _sizeError = MutableStateFlow<String?>(null)
    val sizeError: StateFlow<String?> = _sizeError.asStateFlow()

    private val _ui = MutableStateFlow(InstallUiState())
    val ui: StateFlow<InstallUiState> = _ui.asStateFlow()

    private var pendingPayload: DirectInstallPayload? = null
    private var currentProjectId: String? = null

    fun load(projectId: String) {
        currentProjectId = projectId
        viewModelScope.launch {
            _sizeError.value = null
            try {
                val output = java.io.File(store.projectDir(projectId), "output.bin")
                if (!output.exists()) return@launch
                val bytes = output.readBytes()
                if (bytes.size > MAX_FACE_BYTES) {
                    _sizeError.value =
                        "Face too large: ${bytes.size / 1024} kB, exceeding the 4 MB limit. " +
                            "Shrink the widget/background images and bake again."
                    return@launch
                }
                withContext(Dispatchers.Default) {
                    preparePayload(bytes)
                }
                installer.initializeAndDiscover()
            } catch (e: Exception) {
                installer.restartDiscovery(e.message ?: "Failed to load output")
            }
        }
    }

    /**
     * Reads the baked output's own identity and builds the payload from it.
     *
     * A face whose id fits the protocol byte and agrees across filename, setting.bin
     * and the install command ships as-is — this is how a store face installs as a
     * new entry, and an edited store face overwrites its own entry. A face the watch
     * cannot address (the builder's placeholder 90001, or any id past 255) is refused
     * as-is. Silently clamping the install command's id byte used to register a slot
     * pointing at a file the container is not — that mismatch is what corrupted the
     * watch's face list and locked the stock wearable out of installing anything else.
     * Those faces get [assignId] instead: the container is rewritten onto a
     * consistent id of the user's choosing ([FaceIdRemapper]) and installs the same
     * way a store face does.
     */
    private fun preparePayload(bytes: ByteArray) {
        val parsed = WatchFaceParser.parse(bytes)
        val identity = FaceIdentity.of(parsed)
        val ownId = identity.settingFaceId
        val ownNumber = identity.faceIdNumber
        val sampler = samplerIdFromProject()

        val payload: DirectInstallPayload?
        val identityError: String?
        if (identity.installable) {
            payload = buildPayload(ownNumber!!, bytes, sampler)
            identityError = null
        } else {
            payload = null
            identityError = when {
                ownNumber == null ->
                    "Face id \"$ownId\" is not numeric, so it cannot be sent to the watch. " +
                        "Give it a new id below."
                else ->
                    "Face id $ownId is outside the 1..255 range the watch accepts. " +
                        "Give it a new id below."
            }
        }

        _ui.value = InstallUiState(
            ready = payload != null,
            ownFaceId = ownId.ifEmpty { null },
            identityError = identityError,
            installedIds = installedIds(),
            samplerId = sampler,
        )
        pendingPayload = payload
    }

    /** Choose the id a non-installable face is rewritten onto, e.g. 31 → "00031". */
    fun assignId(faceIdNumber: Int) {
        if (faceIdNumber !in 1..255) return
        val id = faceIdNumber.toString().padStart(5, '0')
        _ui.value = _ui.value.copy(assignedId = id)
    }

    /** Send as the container already is — its own identity is consistent and legal. */
    fun install() {
        val payload = pendingPayload ?: return
        installer.install(payload)
    }

    /**
     * Rewrite the baked output onto the assigned id and send it. The rewritten
     * container is persisted as the project's output so Preview and any later install
     * show exactly what was sent — and a second install needs no rewrite at all,
     * because the project's identity now is the assigned one.
     */
    fun installWithAssignedId() {
        val id = _ui.value.assignedId ?: return
        val faceIdNumber = id.toIntOrNull() ?: return
        val projectId = currentProjectId ?: return
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val output = java.io.File(store.projectDir(projectId), "output.bin")
                if (!output.exists()) return@launch
                val remapped = FaceIdRemapper.remap(
                    WatchFaceParser.parse(output.readBytes()),
                    FaceIdRemapper.Target.of(faceIdNumber),
                )
                // A rewritten container must parse clean into a consistent identity
                // before it is allowed near the watch — the same fail-closed rule the
                // editor rebuild has. Remap cannot fail this check, but the project's
                // output file could have been replaced since load().
                val reparsed = WatchFaceParser.parse(remapped)
                check(FaceIdentity.of(reparsed).installable) {
                    "Rewrite to id $id produced an inconsistent container"
                }
                store.writeOutput(projectId, remapped)
                val payload = buildPayload(faceIdNumber, remapped, _ui.value.samplerId)
                pendingPayload = payload
                _ui.value = _ui.value.copy(
                    ready = true,
                    identityError = null,
                    ownFaceId = id,
                )
                rememberInstalled(id)
                installer.install(payload)
            } catch (e: Exception) {
                installer.restartDiscovery(e.message ?: "Failed to write face as id $id")
            }
        }
    }

    /**
     * The style the watch should activate, from the editor's saved selection — not
     * hardcoded 0. The install command carries it in its sampler byte, bounded by the
     * styles the output actually carries.
     */
    private fun samplerIdFromProject(): Int {
        val projectId = currentProjectId ?: return 0
        val styleIndex = store.get(projectId)?.styleIndex ?: 0
        return try {
            val styles = WatchFaceParser.parse(
                java.io.File(store.projectDir(projectId), "output.bin").readBytes()
            ).styleEntries.size
            if (styles <= 0) 0 else styleIndex.coerceIn(0, styles - 1)
        } catch (_: Exception) {
            0
        }
    }

    /** Ids this app has sent before, so a colliding assignment can be flagged. */
    private fun installedIds(): Set<String> {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_INSTALLED, emptySet()).orEmpty()
    }

    /** Remember an id as installed; used right after an install is sent. */
    private fun rememberInstalled(faceId: String) {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_INSTALLED, installedIds() + faceId).apply()
        _ui.value = _ui.value.copy(installedIds = installedIds())
    }

    fun initializeAndDiscover() = installer.initializeAndDiscover()

    fun refresh() = installer.refreshEnvironment()

    fun confirmChannelReleased() = installer.confirmPluginChannelReleased()

    fun restartDiscovery() = installer.restartDiscovery()

    fun openCompanionApp() { installer.openCompanionApp() }

    fun openPluginSettings() { installer.openPluginSettings() }

    fun reset() = installer.reset()

    private fun buildPayload(faceIdNumber: Int, bytes: ByteArray, sampler: Int): DirectInstallPayload {
        val faceId = faceIdNumber.toString().padStart(5, '0')
        val fileName = "SM-R390_${faceId}_256x402.bin"
        return DirectInstallPayload.create(
            faceId = faceIdNumber,
            samplerId = sampler,
            fileName = fileName,
            bytes = bytes,
        )
    }
}
