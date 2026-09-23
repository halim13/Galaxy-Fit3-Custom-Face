package com.galaxyfit3.customface.ui.screens.install

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.galaxyfit3.core.delivery.DirectInstallPhase
import com.galaxyfit3.core.delivery.SetupStep
import com.galaxyfit3.customface.viewmodel.InstallViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstallScreen(
    projectId: String,
    onBack: () -> Unit,
    viewModel: InstallViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sizeError by viewModel.sizeError.collectAsStateWithLifecycle()
    val installUi by viewModel.ui.collectAsStateWithLifecycle()
    LaunchedEffect(projectId) { viewModel.load(projectId) }

    // Coming back from plugin/permission settings: let the step update itself.
    LifecycleResumeEffect(Unit) { viewModel.refresh(); onPauseOrDispose { } }

    val context = LocalContext.current
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* permission handled via installer refresh */ }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Install to watch") },
                navigationIcon = { IconButton(onClick = onBack) { Text("←") } }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                "Steps to send the face to your Galaxy Fit 3.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Status: ${state.phase.name}",
                    style = MaterialTheme.typography.labelMedium
                )
                TextButton(onClick = { viewModel.refresh() }) {
                    Text("Refresh")
                }
            }

            ChecklistStep(
                number = 1,
                label = "Companion app & plugin installed",
                done = state.isStepDone(SetupStep.COMPANION_PRESENT),
                busy = false,
                onClick = { viewModel.openCompanionApp() },
                actionLabel = "Open companion",
            )

            ChecklistStep(
                number = 2,
                label = "Nearby-devices Bluetooth permission",
                done = state.isStepDone(SetupStep.HELPER_PERMISSION),
                busy = false,
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                        permLauncher.launch(
                            arrayOf(
                                Manifest.permission.BLUETOOTH_CONNECT,
                                Manifest.permission.BLUETOOTH_SCAN,
                            )
                        )
                },
                actionLabel = "Allow",
            )

            ChecklistStep(
                number = 3,
                label = "Discover watchface & OTA peers",
                done = state.isStepDone(SetupStep.PEERS_DISCOVERED),
                busy = state.isStepBusy(SetupStep.PEERS_DISCOVERED),
                onClick = { viewModel.initializeAndDiscover() },
                actionLabel = "Discover",
            )

            ChecklistStep(
                number = 4,
                label = "Release plugin in Nearby devices (manual step)",
                done = state.isStepDone(SetupStep.PLUGIN_RELEASED),
                busy = false,
                onClick = {
                    if (state.pluginNearbyGranted != false) {
                        viewModel.openPluginSettings()
                    } else {
                        viewModel.confirmChannelReleased()
                    }
                },
                actionLabel = if (state.pluginNearbyGranted != false)
                    "Open plugin settings"
                else
                    "Confirm release",
            )

            // Status message
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        state.phase == DirectInstallPhase.FAILED ->
                            MaterialTheme.colorScheme.errorContainer
                        state.phase == DirectInstallPhase.COMPLETE ->
                            MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = state.message,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        state.phase == DirectInstallPhase.FAILED ->
                            MaterialTheme.colorScheme.onErrorContainer
                        state.phase == DirectInstallPhase.COMPLETE ->
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            // Transfer progress
            if (state.phase == DirectInstallPhase.TRANSFERRING ||
                state.phase == DirectInstallPhase.INSTALLING
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Window ${state.acknowledgedWindows}/${state.totalWindows}  •  " +
                            "${state.acknowledgedBytes}/${state.totalBytes} bytes",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            // Size guard: faces over 4 MB never reach the watch, so block the send.
            sizeError?.let {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        it,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            // Face-identity check: the install command's id must agree with the
            // container's own filename and setting.bin — a mismatch is what corrupts
            // the watch's face list. Refused faces get an id assigned and a rewrite.
            if (installUi.identityError != null) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            installUi.identityError.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            "Give this face a new id 1–255. It will be installed as " +
                                "a new face in the watch's list." +
                                (installUi.installedIds.takeIf { it.isNotEmpty() }
                                    ?.let { used ->
                                        " Already used on this watch: " +
                                            used.sorted().joinToString(", ") { it.trimStart('0') }
                                    } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        var idText by remember { mutableStateOf("") }
                        OutlinedTextField(
                            value = idText,
                            onValueChange = { v -> idText = v.filter { it.isDigit() }.take(3) },
                            label = { Text("New face id (1–255)") },
                            isError = idText.toIntOrNull()?.let { it !in 1..255 } ?: false,
                            supportingText = {
                                val n = idText.toIntOrNull()
                                when {
                                    idText.isEmpty() -> Text("Blank: 1–255")
                                    n == null || n !in 1..255 -> Text("Must be 1–255")
                                    n.toString().padStart(5, '0') in installUi.installedIds ->
                                        Text("Id $n was already installed by this app — it will overwrite that face")
                                    else -> Text("The face will be installed as id $n")
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                idText.toIntOrNull()?.let {
                                    viewModel.assignId(it)
                                    viewModel.installWithAssignedId()
                                }
                            },
                            enabled = (idText.toIntOrNull() in 1..255) &&
                                !state.isActive && sizeError == null,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "Install as a new face" +
                                    (idText.toIntOrNull()?.let { " (id $it)" } ?: "")
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            // Install button — only when the face's own identity is sendable as-is.
            Button(
                onClick = { viewModel.install() },
                enabled = sizeError == null && installUi.identityError == null &&
                    state.setupComplete && !state.isActive,
                modifier = Modifier.fillMaxWidth()
            ) {
Text(
                        when {
                            sizeError != null -> "Too large"
                            installUi.identityError != null -> "Face not registered on the watch"
                            state.phase == DirectInstallPhase.FAILED -> "Try again"
                            state.phase == DirectInstallPhase.COMPLETE -> "Done"
                            state.isActive -> "Sending…"
                            !state.setupComplete -> "Not ready"
                            else -> "Send to watch"
                        }
                    )
            }

            if (state.phase == DirectInstallPhase.FAILED ||
                state.phase == DirectInstallPhase.COMPLETE
            ) {
                OutlinedButton(
                    onClick = { viewModel.restartDiscovery() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Restart discovery") }
            }

            state.failure?.let {
                if (state.phase == DirectInstallPhase.FAILED) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            it,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChecklistStep(
    number: Int,
    label: String,
    done: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    actionLabel: String,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (done)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                when {
                    done -> "✓"
                    busy -> "…"
                    else -> "$number"
                },
                style = MaterialTheme.typography.titleMedium,
                color = when {
                    done -> MaterialTheme.colorScheme.primary
                    busy -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.width(28.dp)
            )
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            if (!done) {
                TextButton(onClick = onClick) {
                    Text(actionLabel)
                }
            }
        }
    }
}
