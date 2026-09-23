package com.galaxyfit3.customface.ui.screens.home

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.verticalScroll
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.galaxyfit3.customface.data.Project
import com.galaxyfit3.customface.ui.components.ConfirmDeleteDialog
import com.galaxyfit3.customface.viewmodel.GithubUpdateViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenEditor: (String) -> Unit,
    onOpenStore: () -> Unit,
    onOpenAbout: () -> Unit,
    viewModel: com.galaxyfit3.customface.viewmodel.HomeViewModel = hiltViewModel(),
    updateViewModel: GithubUpdateViewModel = hiltViewModel()
) {
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val thumbnails by viewModel.thumbnails.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val importResult by viewModel.importResult.collectAsStateWithLifecycle()
    val update by updateViewModel.ui.collectAsStateWithLifecycle()
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val context = LocalContext.current
    val appVersion: String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }
    // The header icon shows a result only for user-triggered checks; the silent
    // auto-check on open must not spam a snackbar on every launch.
    var manualCheck by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(update) {
        if (manualCheck && !update.checking) {
            manualCheck = false
            val msg = when {
                update.error != null -> "Update check failed: ${update.error}"
                update.notice != null -> update.notice!!
                update.latest != null -> "Update available: v${update.latest!!.tag} (installed v$appVersion) — tap the download icon"
                update.upToDate -> "You are on the latest version (v$appVersion)"
                else -> return@LaunchedEffect
            }
            snackbarHostState.showSnackbar(msg)
        }
    }
    LaunchedEffect(Unit) { updateViewModel.check() }

    // Delete only arms the dialog here; the tap that removed the whole project
    // folder straight through once was one mistap away from weeks of editing.
    var confirmDeleteProject by remember { mutableStateOf<String?>(null) }
    // Rename: the card's pencil icon arms this; the dialog commits via the ViewModel.
    var renameTarget by remember { mutableStateOf<String?>(null) }
    // Export: the card's download icon arms this; CreateDocument picks where the
    // original seed .bin bytes go (before any canvas rendering).
    var exportTarget by remember { mutableStateOf<String?>(null) }
    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        uri?.let { u -> exportTarget?.let { id -> viewModel.exportSeed(id, u) } }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val bytes = context.contentResolver.openInputStream(it)?.use { r -> r.readBytes() }
            if (bytes != null) {
                val name = it.lastPathSegment?.substringAfterLast('/') ?: "face.bin"
                viewModel.importSeed(bytes, name)
            }
        }
    }

    LaunchedEffect(Unit) { viewModel.refresh() }
    LaunchedEffect(importResult?.projectId) {
        importResult?.projectId?.let { onOpenEditor(it) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Galaxy Fit 3") },
                actions = {
                    IconButton(onClick = onOpenStore) {
                        Icon(Icons.Filled.CloudDownload, contentDescription = "Download Face")
                    }
                    when {
                        update.checking -> IconButton(onClick = {}) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                        update.latest != null -> IconButton(
                            onClick = {
                                scope.launch {
                                    snackbarHostState.showSnackbar("Opening download for v${update.latest!!.tag} (installed v$appVersion) from GitHub…")
                                }
                                uriHandler.openUri(update.latest!!.apkUrl)
                            }
                        ) {
                            Icon(
                                Icons.Filled.Download,
                                contentDescription = "New version available — download",
                                tint = MaterialTheme.colorScheme.tertiary
                            )
                        }
                        else -> IconButton(onClick = {
                            manualCheck = true
                            updateViewModel.check()
                        }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Check for update")
                        }
                    }
                    IconButton(onClick = onOpenAbout) {
                        Icon(Icons.Filled.Info, contentDescription = "About")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { picker.launch("application/octet-stream") }) {
                Icon(Icons.Filled.Add, contentDescription = "New face")
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (importing) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            importResult?.error?.let { err ->
                AssistChip(onClick = { viewModel.refresh() }, label = { Text(err) })
                Spacer(Modifier.height(4.dp))
            }
            if (projects.isEmpty()) {
                EmptyState(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    onPick = { picker.launch("application/octet-stream") }
                )
            } else {
                Column(
                    Modifier.padding(16.dp).verticalScroll(rememberScrollState())
                ) {
                    Text(
                        "Start from a seed face (a .bin file exported from the watch or a template). " +
                            "The available widgets follow the data that watch firmware publishes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    projects.forEach { project ->
                        Column(Modifier.padding(bottom = 12.dp)) {
                            ProjectCard(
                                project = project,
                                thumbnail = thumbnails[project.id],
                                onClick = { onOpenEditor(project.id) },
                                onDelete = { confirmDeleteProject = project.id },
                                onRename = { renameTarget = project.id },
                                onExport = {
                                    exportTarget = project.id
                                    exporter.launch(project.name.ifBlank { "face" } + ".bin")
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(48.dp))
                }
            }
        }
    }

    renameTarget?.let { id ->
        val target = projects.firstOrNull { it.id == id }
        RenameDialog(
            currentName = target?.name ?: "",
            onConfirm = { newName ->
                renameTarget = null
                viewModel.rename(id, newName)
            },
            onDismiss = { renameTarget = null }
        )
    }

    confirmDeleteProject?.let { id ->
        val target = projects.firstOrNull { it.id == id }
        ConfirmDeleteDialog(
            title = "Delete Project?",
            message = "\"${target?.name ?: "Project"}\" with its seed, background, and any generated faces will be deleted permanently.",
            confirmLabel = "Delete Project",
            onConfirm = {
                confirmDeleteProject = null
                viewModel.delete(id)
            },
            onDismiss = { confirmDeleteProject = null }
        )
    }
}

@Composable
private fun EmptyState(onPick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("No projects yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Pick a Galaxy Fit 3 (SM-R390) watch face .bin as a template to get the " +
                "firmware data sources.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onPick) { Text("Choose seed face (.bin)") }
    }
}

@Composable
private fun ProjectCard(
    project: Project,
    thumbnail: Bitmap?,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(48.dp).clip(MaterialTheme.shapes.small).background(
                    Color(0xFF101318),
                    MaterialTheme.shapes.small
                ),
                contentAlignment = Alignment.Center
            ) {
                if (thumbnail == null) {
                    Text("WF", color = Color.White, style = MaterialTheme.typography.labelLarge)
                } else {
                    Image(
                        bitmap = thumbnail.asImageBitmap(),
                        contentDescription = project.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(project.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${project.placed.size} widget · ${fmt(project.lastModified)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onExport) {
                Icon(
                    Icons.Filled.FileDownload,
                    contentDescription = "Download original .bin",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onRename) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = "Rename",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete) {
                Text("✕")
            }
        }
    }
}

private fun fmt(ts: Long): String {
    val sdf = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
    return sdf.format(Date(ts))
}

/** Rename a project: prefilled text field, confirm disabled when unchanged or blank. */
@Composable
private fun RenameDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(currentName) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Project") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(60) },
                singleLine = true,
                label = { Text("Project name") }
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank() && text.trim() != currentName
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}