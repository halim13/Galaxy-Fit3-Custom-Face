package com.galaxyfit3.customface.ui.screens.store

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.galaxyfit3.customface.data.CatalogFace
import com.galaxyfit3.customface.viewmodel.StoreViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoreScreen(
    onBack: () -> Unit,
    onDownloaded: (String) -> Unit,
    viewModel: StoreViewModel = hiltViewModel()
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var selectedFace by remember { mutableStateOf<CatalogFace?>(null) }
    var showSearch by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    // Back first clears the query, then closes search, and only then leaves the screen —
    // so the system back never fights the search field.
    androidx.activity.compose.BackHandler(enabled = showSearch || query.isNotEmpty()) {
        if (query.isNotEmpty()) query = "" else showSearch = false
    }
    val filtered = ui.faces.filter {
        query.isBlank() || it.name.contains(query, ignoreCase = true) || it.id.contains(query)
    }

    LaunchedEffect(Unit) { viewModel.load() }
    LaunchedEffect(ui.importedProjectId) {
        ui.importedProjectId?.let {
            selectedFace = null
            onDownloaded(it)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                // In search mode the field replaces the title so it spans the whole bar
                // without shoving the back button, instead of squeezing into `actions`.
                title = {
                    if (showSearch) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Search faces…") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                IconButton(onClick = { query = ""; showSearch = false }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Close search")
                                }
                            }
                        )
                    } else {
                        Text("Download Face (SM-R390)")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!showSearch) {
                        IconButton(onClick = { showSearch = true }) {
                            Icon(Icons.Filled.Search, contentDescription = "Search faces")
                        }
                        TextButton(onClick = { viewModel.refresh() }) { Text("Reload") }
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (ui.loading && ui.faces.isEmpty()) {
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(24.dp))
            }
            ui.error?.let { err ->
                AssistChip(onClick = { viewModel.refresh() }, label = { Text(err) })
                Spacer(Modifier.height(4.dp))
            }
            if (ui.faces.isNotEmpty()) {
                Text(
                    if (query.isBlank()) {
                        "Official Galaxy Store catalog. Each face downloads as an APK package; " +
                            "the SM-R390_*_256x402.bin container is extracted automatically."
                    } else "${filtered.size} faces match \"$query\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                if (filtered.isEmpty()) {
                    Text(
                        "No matching faces.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(24.dp)
                    )
                }
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filtered, key = { it.id }) { face ->
                        FaceRow(
                            face = face,
                            selected = selectedFace?.id == face.id,
                            onClick = { selectedFace = face }
                        )
                    }
                }
            }
        }
    }

    // Pick first, review the preview, then download — avoids downloading the wrong face.
    selectedFace?.let { face ->
        FacePreviewSheet(
            face = face,
            downloading = ui.downloadingId == face.id,
            error = if (ui.downloadingId == null) ui.error else null,
            onDownload = { viewModel.download(face) },
            onDismiss = { selectedFace = null }
        )
    }
}

@Composable
private fun FaceRow(face: CatalogFace, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Row(
            Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = face.previewUrl,
                contentDescription = "Preview ${face.name}",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
                placeholder = ColorPainter(Color(0xFF1C1C1E)),
                error = ColorPainter(Color(0xFF1C1C1E))
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(face.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "#${face.id} · ${face.sizeBytes / 1024 / 1024} MB",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 12.dp)) {
                Text("View")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FacePreviewSheet(
    face: CatalogFace,
    downloading: Boolean,
    error: String?,
    onDownload: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // The sheet rests at partially-expanded by default, so the bottom content is hidden
        // behind the nav bar; force it fully expanded (to the top of the screen).
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            Modifier.fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                face.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
            Spacer(Modifier.height(12.dp))
            // The original 256x402 preview from the catalog — exactly how it looks on the watch.
            AsyncImage(
                model = face.previewUrl,
                contentDescription = "Preview ${face.name}",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .width(180.dp)
                    .aspectRatio(256f / 402f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF101318))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)),
                placeholder = ColorPainter(Color(0xFF1C1C1E)),
                error = ColorPainter(Color(0xFF1C1C1E))
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "#${face.id} · ${face.sizeBytes / 1024 / 1024} MB · v${face.versionCode}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onDownload, enabled = !downloading) {
                if (downloading) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Downloading…")
                } else {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Download this face")
                }
            }
        }
    }
}