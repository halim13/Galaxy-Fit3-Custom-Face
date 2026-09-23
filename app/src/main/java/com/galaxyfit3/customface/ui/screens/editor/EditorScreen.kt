package com.galaxyfit3.customface.ui.screens.editor

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.FileProvider
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.galaxyfit3.core.format.WatchFaceFormat
import com.galaxyfit3.core.format.model.WidgetType
import com.galaxyfit3.core.format.parser.WidgetMeaningCatalog
import com.galaxyfit3.core.image.Rgb565
import com.galaxyfit3.customface.ui.components.ConfirmDeleteDialog
import com.galaxyfit3.customface.viewmodel.DonorEntry
import com.galaxyfit3.customface.viewmodel.EditorViewModel
import com.galaxyfit3.customface.viewmodel.FaceSource
import com.galaxyfit3.customface.viewmodel.PlacedEditorWidget
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    projectId: String,
    onPreview: () -> Unit,
    onBack: () -> Unit,
    viewModel: EditorViewModel = hiltViewModel()
) {
    LaunchedEffect(projectId) { viewModel.load(projectId) }

    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showLibrary by remember { mutableStateOf(false) }
    var showWidgetList by remember { mutableStateOf(false) }
    var showStylePicker by remember { mutableStateOf(false) }
    var showBackgroundPlacement by remember { mutableStateOf(false) }
    var showColorPicker by remember { mutableStateOf(false) }
    var showImportResult by remember { mutableStateOf(false) }
    var showExitConfirm by remember { mutableStateOf(false) }

    // System back pops every Dialog/ModalBottomSheet window first; the in-place
    // InspectorPanel is no window, so back deselects (closes it) before asking to leave.
    BackHandler {
        if (ui.selectedId != null) viewModel.select(null) else showExitConfirm = true
    }

    // Asset zip (export → edit externally → import back). The result state is in the
    // ViewModel so the flow survives config changes, but the pickers must live here.
    val assetBundle = viewModel.assetBundle.collectAsState().value
    if (assetBundle.exportedFile != null) {
        ShareZipDialog(
            file = assetBundle.exportedFile,
            onDismiss = { viewModel.consumeAssetBundleResult() }
        )
    }
    if (assetBundle.importedSummary != null) {
        AlertDialog(
            onDismissRequest = { viewModel.consumeAssetBundleResult() },
            title = { Text("Assets applied") },
            text = { Text(assetBundle.importedSummary.orEmpty()) },
            confirmButton = {
                TextButton(onClick = { viewModel.consumeAssetBundleResult() }) { Text("OK") }
            }
        )
    }
    if (assetBundle.error != null) {
        AlertDialog(
            onDismissRequest = { viewModel.consumeAssetBundleResult() },
            title = { Text("Failed") },
            text = { Text(assetBundle.error.orEmpty()) },
            confirmButton = {
                TextButton(onClick = { viewModel.consumeAssetBundleResult() }) { Text("OK") }
            }
        )
    }

    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.importAssets(context, it) }
    }

    // Import another face as a widget library: its style-0 widgets become foreign
    // donors that bake into the current face with their own records + rasters.
    val facePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.importDonorFace(it) }
    }

    ui.libraryNotice?.let { notice ->
        AlertDialog(
            onDismissRequest = { viewModel.consumeLibraryNotice() },
            title = { Text("Import another face") },
            text = { Text(notice) },
            confirmButton = {
                TextButton(onClick = { viewModel.consumeLibraryNotice() }) { Text("OK") }
            }
        )
    }

    // Pending destructive actions: the tap only arms the dialog, the delete runs on
    // confirm. Background, a canvas widget and a list row each need their own slot.
    var confirmDeleteBackground by remember { mutableStateOf(false) }
    var confirmDeleteWidgetId by remember { mutableStateOf<Long?>(null) }
    var confirmDeleteSelected by remember { mutableStateOf(false) }

    // The full-resolution decode + raster conversion happens on the engine thread;
    // decoding a camera photo inline here used to freeze the UI for seconds.
    val bgPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.setBackgroundFromUri(it) }
    }

    // Background placement editor: the photo already stored in the project, re-framed
    // live by pinch/pan with the same matrix the encoder reproduces at bake time.
    val bgBitmap = ui.background
    if (showBackgroundPlacement && bgBitmap != null) {
        BackgroundPlacementDialog(
            source = bgBitmap,
            initialMode = ui.backgroundFit,
            onApply = { mode ->
                viewModel.setBackgroundFit(mode)
                showBackgroundPlacement = false
            },
            onDismiss = { showBackgroundPlacement = false }
        )
    }

    if (showColorPicker) {
        BackgroundColorPickerDialog(
            initial = ui.backgroundColor,
            onApply = { hex ->
                viewModel.setBackgroundColor(hex)
                showColorPicker = false
            },
            onDismiss = { showColorPicker = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ui.projectName.takeIf { it.isNotBlank() } ?: "Editor", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = { showExitConfirm = true }) { Text("←") } },
                actions = {
                    var showBackgroundMenu by remember { mutableStateOf(false) }
                    IconButton(onClick = { showBackgroundMenu = true }) {
                        Icon(Icons.Filled.Image, contentDescription = "Background")
                    }
                    DropdownMenu(expanded = showBackgroundMenu, onDismissRequest = { showBackgroundMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Change Background…") },
                            leadingIcon = { Icon(Icons.Filled.Image, contentDescription = null) },
                            onClick = {
                                showBackgroundMenu = false
                                bgPicker.launch("image/*")
                            }
                        )
                        // Placement editor for the already-chosen photo: the same pinch/
                        // pan dialog the widget frames use, applied to the background.
                        DropdownMenuItem(
                            text = { Text("Adjust background position…") },
                            leadingIcon = { Icon(Icons.Filled.OpenWith, contentDescription = null) },
                            enabled = ui.background != null,
                            onClick = {
                                showBackgroundMenu = false
                                showBackgroundPlacement = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Solid color…") },
                            leadingIcon = { Icon(Icons.Filled.Palette, contentDescription = null) },
                            onClick = {
                                showBackgroundMenu = false
                                showColorPicker = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete Background") },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            // Only meaningful while a custom photo is actually set.
                            enabled = ui.background != null,
                            onClick = {
                                showBackgroundMenu = false
                                confirmDeleteBackground = true
                            }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Download assets (zip)…") },
                            leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
                            enabled = ui.seedLoaded && !assetBundle.busy,
                            onClick = {
                                showBackgroundMenu = false
                                viewModel.exportAssets(context)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Upload assets (zip)…") },
                            leadingIcon = { Icon(Icons.Filled.Upload, contentDescription = null) },
                            enabled = ui.seedLoaded && !assetBundle.busy,
                            onClick = {
                                showBackgroundMenu = false
                                zipPicker.launch("application/zip")
                            }
                        )
                    }
                    if (ui.styleCount > 1) {
                        TextButton(onClick = { showStylePicker = true }) {
                            Text("Style ${ui.selectedStyleIndex + 1}/${ui.styleCount}")
                        }
                    }
                    TextButton(onClick = {
                        // bakeOutput() suspends: the Preview page reads output.bin from
                        // disk, so it has to exist before we navigate to it.
                        scope.launch {
                            viewModel.bakeOutput()
                            onPreview()
                        }
                    }) { Text("Preview") }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("${ui.placed.size} widget", style = MaterialTheme.typography.labelMedium)
                        ui.validation?.let { v ->
                            Text(
                                if (v.ok) "Valid ✓ (${v.sizeBytes / 1024} kB)" else "Error: ${v.errors.firstOrNull()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (v.ok) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedIconButton(onClick = { showWidgetList = true }) {
                            Icon(Icons.Filled.List, contentDescription = "Widget list")
                        }
                        FilledIconButton(onClick = { showLibrary = true }) {
                            Icon(Icons.Filled.Add, contentDescription = "Add widget")
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val error = ui.error
            if (error != null) {
                Text(error, modifier = Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.error)
                return@Box
            }
            if (ui.preview == null && !ui.seedLoaded) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
                return@Box
            }

            CanvasPreview(
                bitmap = ui.preview,
                viewModel = viewModel
            )

            if (ui.selectedId != null) {
                // Park the panel on the opposite side of the canvas from the selection:
                // a widget picked in the bottom area must not end up under the panel.
                val selCenterY = ui.placed.find { it.id == ui.selectedId }
                    ?.let { placedRect(it) }?.center?.y ?: 0
                val panelAtTop = selCenterY > 402 * 0.55f
                InspectorPanel(
                    viewModel = viewModel,
                    onRequestRemoveSelected = { confirmDeleteSelected = true },
                    modifier = Modifier
                        .align(if (panelAtTop) Alignment.TopCenter else Alignment.BottomCenter)
                        .padding(12.dp)
                )
            }
        }
    }

    var showExistingFaces by remember { mutableStateOf(false) }
    val existingFaces = remember { mutableStateOf<List<FaceSource>>(emptyList()) }

    if (showLibrary) {
        WidgetLibrary(
            donors = ui.donors,
            foreignDonors = ui.foreignDonors,
            onAdd = { index -> viewModel.addFromDonor(index); showLibrary = false },
            onAddForeign = { index -> viewModel.addForeignDonor(index); showLibrary = false },
            onImportFace = { facePicker.launch("*/*") },
            onImportFromProject = { showExistingFaces = true },
            onDismiss = { showLibrary = false }
        )
    }

    if (showExistingFaces) {
        if (existingFaces.value.isEmpty()) {
            existingFaces.value = viewModel.otherFaces()
        }
        ExistingFaceDialog(
            faces = existingFaces.value,
            onPick = { id ->
                showExistingFaces = false
                viewModel.importDonorFromProject(id)
            },
            onDismiss = { showExistingFaces = false }
        )
    }

    if (showWidgetList) {
        PlacedWidgetListSheet(
            placed = ui.placed,
            selectedId = ui.selectedId,
            onSelect = { id ->
                viewModel.select(id)
                showWidgetList = false
            },
            onDelete = { id -> confirmDeleteWidgetId = id },
            onDismiss = { showWidgetList = false }
        )
    }

    if (showStylePicker) {
        StylePickerSheet(
            previews = ui.stylePreviews,
            selected = ui.selectedStyleIndex,
            modified = ui.modifiedStyles,
            onSelect = { index ->
                viewModel.selectStyle(index)
                showStylePicker = false
            },
            onDismiss = { showStylePicker = false }
        )
    }

    if (confirmDeleteBackground) {
        ConfirmDeleteDialog(
            title = "Delete Background?",
            message = "The custom background will be deleted and replaced with the face's default background.",
            onConfirm = {
                confirmDeleteBackground = false
                viewModel.clearBackground()
            },
            onDismiss = { confirmDeleteBackground = false }
        )
    }

    confirmDeleteWidgetId?.let { id ->
        ConfirmDeleteDialog(
            title = "Delete Widget?",
            message = "This widget will be removed from the face. The original widget stays available in the library.",
            onConfirm = {
                confirmDeleteWidgetId = null
                viewModel.removePlaced(id)
            },
            onDismiss = { confirmDeleteWidgetId = null }
        )
    }

    if (confirmDeleteSelected) {
        ConfirmDeleteDialog(
            title = "Delete Selected Widget?",
            message = "The selected widget will be removed from the face. The original widget stays available in the library.",
            onConfirm = {
                confirmDeleteSelected = false
                viewModel.removeSelected()
            },
            onDismiss = { confirmDeleteSelected = false }
        )
    }

    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text("Leave editor?") },
            text = { Text("Your changes are saved in this project. Return to the project list?") },
            confirmButton = {
                TextButton(onClick = {
                    showExitConfirm = false
                    onBack()
                }) { Text("Yes") }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

/**
 * The live 256x402 canvas adapted from fitface-studio: the rendered face bitmap as the
 * base, a stroke outline per placed widget at its stored coordinate, the selected one
 * highlighted, and a drag that blits the widget's own raster under the finger — no
 * re-render until the gesture ends.
 */
@Composable
private fun CanvasPreview(
    bitmap: android.graphics.Bitmap?,
    viewModel: EditorViewModel
) {
    if (bitmap == null) return
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    // Gesture blocks only restart when a key changes; they read the freshest state through
    // this instead of capturing a stale snapshot (fitface's "publish on end" lesson).
    val latestUi by rememberUpdatedState(ui)
    var dragWidgetId by remember { mutableStateOf<Long?>(null) }
    var dragRect by remember { mutableStateOf<IntRect?>(null) }
    // The widget's stored coordinate where the gesture began, plus the finger's running
    // total since then — the total is unclamped on purpose: accumulating the clamped
    // position made a widget stick at the edge and then trail behind the finger.
    var dragStart by remember { mutableStateOf(Offset.Zero) }
    var dragTrack by remember { mutableStateOf(Offset.Zero) }
    // Hoisted out of the draw scope: `MaterialTheme` cannot be read from a DrawScope, and
    // wrapping the bitmaps is work a re-draw should not repeat every frame.
    val guideColor = MaterialTheme.colorScheme.primary
    val selectedGuideColor = MaterialTheme.colorScheme.tertiary
    val previewImage = remember(ui.preview) { ui.preview?.asImageBitmap() }
    val dragImage = remember(ui.dragWidgetPreview) { ui.dragWidgetPreview?.asImageBitmap() }
    var showResetConfirm by remember { mutableStateOf(false) }
    var resetMenuOpen by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxW = maxWidth
        val maxH = maxHeight
        val scale = minOf(maxW.value, maxH.value * 256f / 402f) / 256f
        val w = (256 * scale).dp
        val h = (402 * scale).dp

        Box(
            Modifier.align(Alignment.Center).size(w, h)
                .background(Color(0xFF101318))
                .border(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Canvas(
                Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { tap ->
                            val u = latestUi
                            viewModel.select(
                                hitPlaced(
                                    placed = u.placed,
                                    xF = tap.x * 256f / size.width,
                                    yF = tap.y * 402f / size.height,
                                    preferredId = u.selectedId
                                )?.id
                            )
                        }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { pos ->
                                val u = latestUi
                                val hit = hitPlaced(
                                    placed = u.placed,
                                    xF = pos.x * 256f / size.width,
                                    yF = pos.y * 402f / size.height,
                                    preferredId = u.selectedId
                                )
                                val rect = hit?.let { placedRect(it) }
                                if (hit == null || rect == null) {
                                    dragWidgetId = null
                                    dragRect = null
                                    viewModel.select(null)
                                } else {
                                    dragWidgetId = hit.id
                                    dragRect = rect
                                    // The stored coordinate is what the model moves; the
                                    // rectangle only says where the clamp ends.
                                    dragStart = Offset(hit.x.toFloat(), hit.y.toFloat())
                                    dragTrack = Offset.Zero
                                    viewModel.beginDrag(hit.id)
                                }
                            },
                            onDrag = { change, amount ->
                                val id = dragWidgetId
                                val rect = dragRect
                                if (id == null || rect == null) return@detectDragGestures
                                change.consume()
                                val xf = amount.x * 256f / size.width
                                val yf = amount.y * 402f / size.height
                                val (trackX, posX) = stepDragAxis(
                                    track = dragTrack.x + xf, start = dragStart.x,
                                    low = rect.left, high = rect.right, faceExtent = 256
                                )
                                val (trackY, posY) = stepDragAxis(
                                    track = dragTrack.y + yf, start = dragStart.y,
                                    low = rect.top, high = rect.bottom, faceExtent = 402
                                )
                                dragTrack = Offset(trackX, trackY)
                                viewModel.moveSelectedTo(id, posX.roundToInt(), posY.roundToInt())
                            },
                            onDragEnd = { endDrag(viewModel, dragWidgetId) },
                            onDragCancel = { endDrag(viewModel, dragWidgetId) }
                        )
                    }
            ) {
                val sx = size.width / 256f
                val sy = size.height / 402f
                previewImage?.let {
                    drawImage(
                        image = it,
                        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                        filterQuality = FilterQuality.Low
                    )
                }
                // The dragged widget's own raster follows the finger over the clean base.
                val dragging = dragWidgetId
                val dragged = dragging?.let { id -> latestUi.placed.find { it.id == id } }
                val dragBitmap = latestUi.dragWidgetPreview
                if (dragged != null && dragBitmap != null && dragImage != null) {
                    drawImage(
                        image = dragImage,
                        dstOffset = IntOffset((dragged.x * sx).roundToInt(), (dragged.y * sy).roundToInt()),
                        dstSize = IntSize(
                            (dragBitmap.width * sx).roundToInt().coerceAtLeast(1),
                            (dragBitmap.height * sy).roundToInt().coerceAtLeast(1)
                        ),
                        filterQuality = FilterQuality.Low
                    )
                }
                val activeId = dragging ?: latestUi.selectedId
                latestUi.placed.forEach { p ->
                    val rect = placedRect(p) ?: return@forEach
                    val selected = p.id == activeId
                    val topLeft = Offset(rect.left * sx, rect.top * sy)
                    val size = Size(rect.width * sx, rect.height * sy)
                    if (selected) {
                        // Wash + dark under-stroke keep the highlight readable on any
                        // widget colour; brackets make the selection unmistakable.
                        drawRect(
                            color = selectedGuideColor.copy(alpha = 0.15f),
                            topLeft = topLeft, size = size
                        )
                        drawRect(
                            color = Color.Black.copy(alpha = 0.55f),
                            topLeft = topLeft, size = size,
                            style = Stroke(4.dp.toPx())
                        )
                        drawRect(
                            color = selectedGuideColor,
                            topLeft = topLeft, size = size,
                            style = Stroke(2.dp.toPx())
                        )
                        drawSelectionBrackets(topLeft, size, selectedGuideColor, 2.dp.toPx(), 14.dp.toPx())
                    } else {
                        drawRect(
                            color = guideColor.copy(alpha = 0.5f),
                            topLeft = topLeft, size = size,
                            style = Stroke(1.dp.toPx())
                        )
                    }
                }
            }
            Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                FilledTonalIconButton(
                    onClick = { resetMenuOpen = true }
                ) {
                    Icon(Icons.Filled.RestartAlt, contentDescription = "Reset", modifier = Modifier.size(18.dp))
                }
                DropdownMenu(
                    expanded = resetMenuOpen,
                    onDismissRequest = { resetMenuOpen = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Reset widget positions") },
                        onClick = {
                            resetMenuOpen = false
                            viewModel.resetPositions()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Reset style") },
                        onClick = {
                            resetMenuOpen = false
                            showResetConfirm = true
                        }
                    )
                }
            }
        }

        if (showResetConfirm) {
            AlertDialog(
                onDismissRequest = { showResetConfirm = false },
                title = { Text("Reset this style?") },
                text = {
                    Text("The style being edited returns to its default look. Other styles and widgets from other faces are not affected.")
                },
                confirmButton = {
                    TextButton(onClick = {
                        showResetConfirm = false
                        viewModel.resetToDefault()
                    }) { Text("Reset") }
                },
                dismissButton = {
                    TextButton(onClick = { showResetConfirm = false }) { Text("Cancel") }
                }
            )
        }
    }
}

private fun endDrag(viewModel: EditorViewModel, id: Long?) {
    if (id != null) {
        viewModel.select(id)
        viewModel.finishDrag()
    }
}

/** L-shaped corner brackets around the selection, like a photo editor's crop handles.
 * [lenPx] shrinks for tiny widgets so brackets never cross each other. */
private fun DrawScope.drawSelectionBrackets(
    topLeft: Offset,
    size: Size,
    color: Color,
    strokePx: Float,
    lenPx: Float
) {
    val l = minOf(lenPx, size.width / 2f, size.height / 2f)
    val right = topLeft.x + size.width
    val bottom = topLeft.y + size.height
    val cap = StrokeCap.Round
    // Top-left
    drawLine(color, topLeft, Offset(topLeft.x + l, topLeft.y), strokePx, cap)
    drawLine(color, topLeft, Offset(topLeft.x, topLeft.y + l), strokePx, cap)
    // Top-right
    drawLine(color, Offset(right, topLeft.y), Offset(right - l, topLeft.y), strokePx, cap)
    drawLine(color, Offset(right, topLeft.y), Offset(right, topLeft.y + l), strokePx, cap)
    // Bottom-left
    drawLine(color, Offset(topLeft.x, bottom), Offset(topLeft.x + l, bottom), strokePx, cap)
    drawLine(color, Offset(topLeft.x, bottom), Offset(topLeft.x, bottom - l), strokePx, cap)
    // Bottom-right
    drawLine(color, Offset(right, bottom), Offset(right - l, bottom), strokePx, cap)
    drawLine(color, Offset(right, bottom), Offset(right, bottom - l), strokePx, cap)
}

/** The rectangle a widget occupies in face pixels — used for tap hit-testing, drag
 *  clamping and the selection marker. Every widget gets one (all must be tappable and
 *  draggable): the stored w/h when the type carries them, otherwise the widget's own
 *  raster box (clock hands — the record stores the pivot-adjusted origin), otherwise a
 *  nominal 40x40 box anchored at its position (live-value widgets painted by firmware). */
internal fun placedRect(p: PlacedEditorWidget): IntRect? {
    val d = p.donor
    val w = d.wOrX2
    val h = d.hOrY2
    when (d.type) {
        WidgetType.STATIC, WidgetType.SPRITE ->
            if (w > 0 && h > 0) return IntRect(p.x, p.y, p.x + w, p.y + h)
        // Text widgets (value/composite) right/bottom-anchor when x or y is negative;
        // bind the cell the renderer draws into, not the raw stored origin.
        WidgetType.PAIR, WidgetType.COMP ->
            if (w > 0 && h > 0) {
                val bx = if (d.x < 0) WatchFaceFormat.PANEL_WIDTH + d.x - w else d.x
                val by = if (d.y < 0) WatchFaceFormat.PANEL_HEIGHT + d.y - h else d.y
                return IntRect(bx, by, bx + w, by + h)
            }
        // Endpoint-stored widgets (badge, bar): bound both endpoints.
        WidgetType.BADGE, WidgetType.LINE_BAR ->
            IntRect(minOf(p.x, w), minOf(p.y, h), maxOf(p.x, w), maxOf(p.y, h))
                .takeIf { it.width > 0 && it.height > 0 }
                ?.let { return it }
        else -> {}
    }
    p.preview?.takeIf { it.width > 0 && it.height > 0 }?.let {
        val (ox, oy) = if (d.isHand) {
            val pivot = d.wordA.toInt()
            (pivot and 0xFFFF) to (pivot ushr 16 and 0xFFFF)
        } else 0 to 0
        return IntRect(p.x - ox, p.y - oy, p.x - ox + it.width, p.y - oy + it.height)
    }
    // No stored size and no raster of its own: nominal touch box.
    return IntRect(p.x, p.y, p.x + 40, p.y + 40)
}

/** Hit-test a [PlacedEditorWidget] by its rectangle, in face pixels. Half-open bounds;
 * abutting widgets stay distinct. The smallest hit wins; the current selection is only
 * preferred when it is not much larger (fitface's 5/4 rule). */
internal fun hitPlaced(
    placed: List<PlacedEditorWidget>,
    xF: Float,
    yF: Float,
    preferredId: Long?
): PlacedEditorWidget? {
    val hits = placed.mapNotNull { p ->
        val r = placedRect(p) ?: return@mapNotNull null
        if (xF >= r.left && xF < r.right && yF >= r.top && yF < r.bottom) p to r else null
    }
    if (hits.isEmpty()) return null
    val smallest = hits.minWithOrNull(
        compareBy({ it.second.width.toLong() * it.second.height }, { -it.first.id })
    ) ?: return null
    val preferred = hits.singleOrNull { it.first.id == preferredId } ?: return smallest.first
    val smallestArea = smallest.second.width.toLong() * smallest.second.height
    val preferredArea = preferred.second.width.toLong() * preferred.second.height
    return if (preferredArea <= smallestArea * 5 / 4) preferred.first else smallest.first
}

/** One axis of a canvas drag: the unclamped finger total and the stored coordinate to
 * commit. The clamp bounds the widget's rectangle inside the face, never the running
 * total, so overshooting an edge and coming back keeps the widget under the finger. */
internal fun stepDragAxis(
    track: Float,
    start: Float,
    low: Int,
    high: Int,
    faceExtent: Int
): Pair<Float, Float> {
    val minimum = -low.toFloat()
    val maximum = (faceExtent - high).toFloat().coerceAtLeast(minimum)
    return track to start + track.coerceIn(minimum, maximum)
}

@Composable
private fun InspectorPanel(
    viewModel: EditorViewModel,
    onRequestRemoveSelected: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val sel = ui.placed.find { it.id == ui.selectedId } ?: return
    var showTextColor by remember { mutableStateOf(false) }
    val textCapable = sel.donor.type == WidgetType.PAIR || sel.donor.type == WidgetType.COMP
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        // Translucent: whatever the panel still overlaps stays recognizable.
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 4.dp
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(onClick = {}, label = { Text(sel.meaning.label) })
                Text(
                    "(${sel.x}, ${sel.y})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { viewModel.duplicateSelected() }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Duplicate", modifier = Modifier.size(20.dp))
                }
                IconButton(
                    onClick = onRequestRemoveSelected,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
Icon(Icons.Filled.Delete, contentDescription = "Delete", modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Drag a widget directly on the canvas to move it.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            NudgeGroup(label = "X", vertical = false, onNudge = { v -> viewModel.moveSelected(v, 0) })
            Spacer(Modifier.height(4.dp))
            NudgeGroup(label = "Y", vertical = true, onNudge = { v -> viewModel.moveSelected(0, v) })
            Spacer(Modifier.height(6.dp))
            FrameOverrideSection(viewModel, sel)
            if (textCapable) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showTextColor = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Palette, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (sel.tint != null) "Change text color" else "Text color")
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { viewModel.select(null) }) { Text("Done") }
            }
        }
    }

    if (showTextColor) {
        val firmware = if (sel.donor.type == WidgetType.PAIR) {
            sel.donor.words.getOrNull(0)?.takeIf { (it ushr 24) == 0xFF } ?: android.graphics.Color.WHITE
        } else android.graphics.Color.WHITE
        TextColorDialog(
            initial = sel.tint ?: firmware,
            onApply = { argb ->
                viewModel.setTextColor(argb)
                showTextColor = false
            },
            onReset = {
                viewModel.clearTextColor()
                showTextColor = false
            },
            onDismiss = { showTextColor = false }
        )
    }
}

/**
 * Per-frame image replacement for the selected widget (per user preference: pick the
 * frame first, then its replacement image and fit mode). The donor widget's frames are
 * shown as thumbnails; tapping one opens the image picker and asks how the picture
 * should fill the frame. Restoring a frame asks for confirmation first.
 */
@Composable
private fun FrameOverrideSection(viewModel: EditorViewModel, sel: PlacedEditorWidget) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var pickTarget by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var pendingImage by remember { mutableStateOf<android.net.Uri?>(null) }
    var restoreTarget by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    // Tap on an already-replaced frame: choose between changing the image, retuning
    // only the fit mode, or restoring the stock picture.
    var menuTarget by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var modeTarget by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) pendingImage = uri
    }

    val frameCount = viewModel.frameCount(sel.donorIndex)
    if (frameCount <= 0) return
    val overrides = ui.frameOverrides

    Column {
        Text(
            "Frame image",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            // Sprites can carry many frames (battery strips etc.); let the row scroll
            // sideways so no thumbnail is unreachable.
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            repeat(frameCount) { fi ->
                // The thumbnail shows the REPLACEMENT image when the frame has been
                // replaced, so the row always reflects what the face will show. Keyed
                // on the override set so it re-decodes right after a change.
                val donor = remember(sel.donorIndex, fi, overrides) {
                    runCatching {
                        viewModel.donorFrameBitmap(sel.donorIndex, fi)
                    }.getOrNull()
                }
                val replaced = (sel.donorIndex to fi) in overrides
                val border = if (replaced) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                } else Modifier
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .then(border)
                            .clickable {
                                if (replaced) {
                                    menuTarget = sel.donorIndex to fi
                                } else {
                                    pickTarget = sel.donorIndex to fi
                                    launcher.launch("image/*")
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (donor != null) {
                            Image(
                                donor.asImageBitmap(),
                                contentDescription = "Frame ${fi + 1}",
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                Icons.Filled.Image,
                                contentDescription = "Frame ${fi + 1}",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (replaced) {
                        Spacer(Modifier.height(5.dp))
                        // Clickable wraps the padding: the whole padded area taps,
                        // keeping the restore link away from the thumbnail's hit box.
                        Text(
                            "Restore",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { restoreTarget = sel.donorIndex to fi }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
        if (overrides.isNotEmpty()) {
            Text(
                "Tap a frame to replace its image • outlined frame = replaced",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    pendingImage?.let { uri ->
        val target = pickTarget
        if (target != null) {
            FrameFitModeDialog(
                uri = uri,
                frameWidth = viewModel.frameSize(sel.donorIndex, target.second).first,
                frameHeight = viewModel.frameSize(sel.donorIndex, target.second).second,
                onApply = { mode ->
                    viewModel.setFrameImageFromUri(target.first, target.second, uri, mode)
                    pendingImage = null
                    pickTarget = null
                },
                onDismiss = {
                    pendingImage = null
                    pickTarget = null
                }
            )
        } else {
            pendingImage = null
        }
    }

    // Options for an already-replaced frame: re-pick the image, retune just the fit
    // mode from the stored PNG, or restore the stock raster.
    menuTarget?.let { target ->
        FrameOptionsDialog(
            hasImage = viewModel.frameImageFile(target.first, target.second) != null,
            onChangeImage = {
                menuTarget = null
                pickTarget = target
                launcher.launch("image/*")
            },
            onChangeMode = {
                menuTarget = null
                modeTarget = target
            },
            onRestore = {
                menuTarget = null
                restoreTarget = target
            },
            onDismiss = { menuTarget = null }
        )
    }

    // Fit-mode retune for an existing replacement: the stored PNG is re-encoded with
    // the new mode — no image picker round trip.
    modeTarget?.let { target ->
        val f = viewModel.frameImageFile(target.first, target.second)
        if (f == null) {
            modeTarget = null
        } else {
            FrameFitModeDialog(
                uri = android.net.Uri.fromFile(f),
                frameWidth = viewModel.frameSize(sel.donorIndex, target.second).first,
                frameHeight = viewModel.frameSize(sel.donorIndex, target.second).second,
                initialMode = viewModel.frameMode(target.first, target.second) ?: "fit",
                onApply = { mode ->
                    viewModel.setFrameMode(target.first, target.second, mode)
                    modeTarget = null
                },
                onDismiss = { modeTarget = null }
            )
        }
    }

    restoreTarget?.let { target ->
        ConfirmDeleteDialog(
            title = "Restore frame?",
            message = "This frame's image will be restored to the face's original image.",
            confirmLabel = "Restore",
            onConfirm = {
                viewModel.clearFrameImage(target.first, target.second)
                restoreTarget = null
            },
            onDismiss = { restoreTarget = null }
        )
    }
}

/**
 * Background placement: the same pinch/pan/rotate/flip editor the widget frames use,
 * applied to the project's stored background photo. [source] is the already-decoded
 * background bitmap from the editor state — no re-pick, no re-decode.
 */
@Composable
private fun BackgroundPlacementDialog(
    source: Bitmap,
    initialMode: String,
    onApply: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ImagePlacementDialog(
        source = source,
        frameWidth = 256,
        frameHeight = 402,
        title = "Background position",
        initialMode = initialMode,
        onApply = onApply,
        onDismiss = onDismiss
    )
}

/**
 * Solid background color: preset swatches, a hue-less RGB slider trio, and a hex
 * field. Applying replaces any photo; the project keeps the color as #RRGGBB.
 */
@Composable
private fun BackgroundColorPickerDialog(
    initial: String?,
    onApply: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val initialArgb = initial?.let { runCatching { android.graphics.Color.parseColor(it) }.getOrNull() }
    var red by remember { mutableStateOf(android.graphics.Color.red(initialArgb ?: 0xFF101318.toInt())) }
    var green by remember { mutableStateOf(android.graphics.Color.green(initialArgb ?: 0xFF101318.toInt())) }
    var blue by remember { mutableStateOf(android.graphics.Color.blue(initialArgb ?: 0xFF101318.toInt())) }
    var hexText by remember {
        mutableStateOf(initial?.removePrefix("#")?.uppercase() ?: "101318")
    }
    // Hex typing drives the sliders until a slider moves again; the two stay in sync
    // through applyHex so the live swatch always reflects what will be applied.
    fun applyHex(h: String) {
        val v = h.removePrefix("#").toLongOrNull(16)?.toInt() ?: return
        if (v !in 0..0xFFFFFF) return
        red = (v shr 16) and 0xFF
        green = (v shr 8) and 0xFF
        blue = v and 0xFF
    }
    fun sliderHex(): String =
        "%02X%02X%02X".format(java.util.Locale.US, red, green, blue)

    val current = Color(android.graphics.Color.rgb(red, green, blue))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Background color") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Live swatch: the exact panel color being applied.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(current)
                )
                // Presets: watch-face-plausible fills, dark-weighted so the clock face
                // stays readable, plus a couple of accents.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(
                        0xFF000000.toInt(), 0xFF101318.toInt(), 0xFF1E293B.toInt(),
                        0xFF37474F.toInt(), 0xFF7A3C1D.toInt(), 0xFF9C2718.toInt()
                    ).forEach { c ->
                        val color = Color(c)
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(color)
                                .border(
                                    width = if (c == android.graphics.Color.rgb(red, green, blue)) 2.dp else 1.dp,
                                    color = if (c == android.graphics.Color.rgb(red, green, blue)) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable {
                                    red = android.graphics.Color.red(c)
                                    green = android.graphics.Color.green(c)
                                    blue = android.graphics.Color.blue(c)
                                    hexText = "%02X%02X%02X".format(
                                        java.util.Locale.US, red, green, blue
                                    )
                                }
                        )
                    }
                }
                ColorSlider("Red", red) {
                    red = it
                    hexText = sliderHex()
                }
                ColorSlider("Green", green) {
                    green = it
                    hexText = sliderHex()
                }
                ColorSlider("Blue", blue) {
                    blue = it
                    hexText = sliderHex()
                }
                OutlinedTextField(
                    value = hexText,
                    onValueChange = { v ->
                        val cleaned = v.removePrefix("#").filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }.take(6)
                        hexText = cleaned
                        if (cleaned.length == 6) applyHex(cleaned)
                    },
                    label = { Text("Hex (#RRGGBB)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply("#" + sliderHex()) }) { Text("Apply") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** One 8-bit channel slider for the color picker. */
@Composable
private fun ColorSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt().coerceIn(0, 255)) },
            valueRange = 0f..255f
        )
    }
}

/**
 * Custom text colour for a value/composite widget: RGB sliders, presets and a hex
 * field, like the background picker. [onReset] restores the record's firmware colour.
 */
@Composable
private fun TextColorDialog(
    initial: Int,
    onApply: (Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    var red by remember { mutableStateOf(android.graphics.Color.red(initial)) }
    var green by remember { mutableStateOf(android.graphics.Color.green(initial)) }
    var blue by remember { mutableStateOf(android.graphics.Color.blue(initial)) }
    var hexText by remember {
        mutableStateOf("%02X%02X%02X".format(java.util.Locale.US,
            android.graphics.Color.red(initial), android.graphics.Color.green(initial), android.graphics.Color.blue(initial)))
    }
    fun applyHex(h: String) {
        val v = h.removePrefix("#").toLongOrNull(16)?.toInt() ?: return
        if (v !in 0..0xFFFFFF) return
        red = (v shr 16) and 0xFF
        green = (v shr 8) and 0xFF
        blue = v and 0xFF
    }
    fun sliderHex(): String = "%02X%02X%02X".format(java.util.Locale.US, red, green, blue)

    val current = Color(android.graphics.Color.rgb(red, green, blue))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Text color") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(current)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(
                        0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFE53935.toInt(),
                        0xFF1E88E5.toInt(), 0xFF43A047.toInt(), 0xFFFB8C00.toInt()
                    ).forEach { c ->
                        val color = Color(c)
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(color)
                                .border(
                                    width = if (c == android.graphics.Color.rgb(red, green, blue)) 2.dp else 1.dp,
                                    color = if (c == android.graphics.Color.rgb(red, green, blue)) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable {
                                    red = android.graphics.Color.red(c)
                                    green = android.graphics.Color.green(c)
                                    blue = android.graphics.Color.blue(c)
                                    hexText = "%02X%02X%02X".format(java.util.Locale.US, red, green, blue)
                                }
                        )
                    }
                }
                ColorSlider("Red", red) { red = it; hexText = sliderHex() }
                ColorSlider("Green", green) { green = it; hexText = sliderHex() }
                ColorSlider("Blue", blue) { blue = it; hexText = sliderHex() }
                OutlinedTextField(
                    value = hexText,
                    onValueChange = { v ->
                        val cleaned = v.removePrefix("#").filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }.take(6)
                        hexText = cleaned
                        if (cleaned.length == 6) applyHex(cleaned)
                    },
                    label = { Text("Hex (#RRGGBB)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(0xFF000000.toInt() or sliderHex().toInt(16)) }) { Text("Apply") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onReset, enabled = initial != 0xFFFFFFFF.toInt()) { Text("Original") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

/** Frame replacement flow: decode the picked image off the main thread, then open the
 *  shared placement editor once it is ready. */
@Composable
private fun FrameFitModeDialog(
    uri: android.net.Uri,
    frameWidth: Int,
    frameHeight: Int,
    onApply: (String) -> Unit,
    onDismiss: () -> Unit,
    initialMode: String = "fit"
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var source by remember(uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uri) {
        source = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            decodeUriPreview(context, uri)
        }
    }
    val src = source
    if (src == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            text = { CircularProgressIndicator(Modifier.size(28.dp)) },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
        )
        return
    }
    ImagePlacementDialog(
        source = src,
        frameWidth = frameWidth,
        frameHeight = frameHeight,
        title = "Image mode",
        initialMode = initialMode,
        onApply = onApply,
        onDismiss = onDismiss
    )
}

/**
 * Placement editor for a picture. The image is always directly pinch-zoomed
 * and dragged; the mode chips just snap it into a preset framing (fill, letterbox,
 * centre, stretch) which then stays touch-adjustable. The applied transform is always
 * stored as a "manual@" placement string — the exact numbers the encoder reproduces.
 * Shared by widget-frame replacement and the background placement dialog.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ImagePlacementDialog(
    source: Bitmap,
    frameWidth: Int,
    frameHeight: Int,
    title: String,
    initialMode: String,
    onApply: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // Placement in encoder units: scale is a multiplier on the cover baseline, pan
    // in frame-size fractions.
    var scale by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var rotation by remember { mutableStateOf(0f) }
    var flip by remember { mutableStateOf(0) }
    // The named preset currently framing the image ("cover"/"fit"/"stretch"/"center"),
    // or "manual" once the user pinches/drags/rotates/flips. Drives chip selection.
    var mode by remember { mutableStateOf(if (initialMode.startsWith(Rgb565.MANUAL_PREFIX)) "manual" else initialMode) }
    // True once the user has interacted, so an untouched "Stretch" chip can still write
    // the real (non-uniform) stretch mode instead of a manual approximation.
    var touched by remember { mutableStateOf(false) }
    // Exact rotation in degrees, editable; the +90° chip is just a quick nudge.
    var angleText by remember { mutableStateOf("0") }

    // Fold one gesture event's deltas onto the live state: the canvas only ever emits
    // deltas (zoom multiplier, pan/centroid as frame fractions), keeping accumulation in
    // one place so reads are never stale.
    fun foldGesture(zoomDelta: Float, panDelta: Offset, centroid: Offset) {
        touched = true
        mode = "manual"
        val newScale = (scale * zoomDelta).coerceIn(0.25f, 8f)
        scale = newScale
        pan = Offset(
            centroid.x + (pan.x - centroid.x) * zoomDelta + panDelta.x,
            centroid.y + (pan.y - centroid.y) * zoomDelta + panDelta.y
        )
    }

    fun snap(newMode: String) {
        val t = Rgb565.manualPresetParams(
            newMode, source.width, source.height, frameWidth, frameHeight
        )
        scale = t.scale
        pan = Offset(t.tx, t.ty)
        rotation = t.rotation
        flip = t.flip
        mode = newMode
        angleText = t.rotation.toInt().toString()
    }

    /** Set rotation to an exact angle (0-359), marking the placement as manual. */
    fun setRotate(deg: Float) {
        touched = true
        mode = "manual"
        rotation = ((deg % 360f) + 360f) % 360f
        angleText = rotation.toInt().toString()
    }

    fun rotate() {
        setRotate(rotation + 90f)
    }

    fun toggleFlip(bit: Int) {
        touched = true
        mode = "manual"
        flip = flip xor bit
    }

    LaunchedEffect(source) {
        val t = Rgb565.manualPresetParams(
            initialMode, source.width, source.height, frameWidth, frameHeight
        )
        scale = t.scale
        pan = Offset(t.tx, t.ty)
        rotation = t.rotation
        flip = t.flip
        angleText = t.rotation.toInt().toString()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                // AlertDialog clips its text slot rather than scrolling; keep every
                // control (canvas + two chip rows) reachable on short screens.
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                ModeManualCanvas(source, frameWidth, frameHeight, scale, pan, rotation, flip) { z, p, c ->
                    foldGesture(z, p, c)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Pinch to zoom, drag to pan",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                    // FlowRow: chips wrap to a second line on narrow screens instead of
                    // squeezing the last one.
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        FilterChip(selected = mode == "cover", onClick = { snap("cover") }, label = { Text("Fill") })
                        FilterChip(selected = mode == "fit", onClick = { snap("fit") }, label = { Text("Fit") })
                        FilterChip(selected = mode == "stretch", onClick = { snap("stretch") }, label = { Text("Stretch") })
                        FilterChip(selected = mode == "center", onClick = { snap("center") }, label = { Text("Center") })
                    }
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        FilterChip(
                            selected = rotation != 0f,
                            onClick = ::rotate,
                            label = { Text("Rotate +90°") }
                        )
                        FilterChip(
                            selected = flip and 1 != 0,
                            onClick = { toggleFlip(1) },
                            label = { Text("Flip ↔") }
                        )
                        FilterChip(
                            selected = flip and 2 != 0,
                            onClick = { toggleFlip(2) },
                            label = { Text("Flip ↕") }
                        )
                        FilterChip(
                            selected = false,
                            onClick = { snap("cover") },
                            label = { Text("Reset") }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Angle", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = angleText,
                            onValueChange = { input ->
                                angleText = input.filter { it.isDigit() }.take(3)
                                angleText.toIntOrNull()?.let { setRotate(it.toFloat()) }
                            },
                            modifier = Modifier.width(96.dp),
                            singleLine = true,
                            suffix = { Text("°") },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                            )
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "0-359",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // An untouched stretch keeps its distinct distortion; everything
                    // else is stored as the manual placement (identical rendering).
                    val m = if (!touched && mode == "stretch") {
                        "stretch"
                    } else {
                        Rgb565.manualModeString(scale, pan.x, pan.y, rotation, flip)
                    }
                    onApply(m)
                },
                enabled = true
            ) { Text("Apply") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** Interactive placement canvas: pinch to zoom, drag to pan, rotate, flip. The image is
 *  drawn on a Canvas via the same placement matrix the encoder uses (so what you see is
 *  exactly what gets baked), zoom anchored on the pinch centroid so the picture stays
 *  under your fingers. Only the raw event deltas are emitted (zoom multiplier, drag
 *  delta and centroid, all as frame-scale fractions); accumulation happens in the
 *  dialog's live state so nothing reads a stale copy. */
@Composable
private fun ModeManualCanvas(
    source: Bitmap,
    frameWidth: Int,
    frameHeight: Int,
scale: Float,
    pan: Offset,
    rotation: Float,
    flip: Int,
    onChange: (zoomDelta: Float, panDelta: Offset, centroid: Offset) -> Unit
) {
    if (frameWidth <= 0 || frameHeight <= 0) return
    val aspect = frameWidth.toFloat() / frameHeight
    Canvas(
        Modifier
            .fillMaxWidth()
            .aspectRatio(aspect.coerceIn(0.4f, 2.5f))
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF101318))
            .pointerInput(source) {
                // fitface-style: emit only this event's deltas, normalized to the box
                // (which shares the frame's aspect, so 1 box-width = 1 frame-width).
                detectTransformGestures { centroid, panDelta, zoom, _ ->
                    if (zoom > 0f) {
                        onChange(
                            zoom,
                            Offset(panDelta.x / size.width, panDelta.y / size.height),
                            Offset(centroid.x / size.width - 0.5f, centroid.y / size.height - 0.5f)
                        )
                    }
                }
            }
    ) {
        val matrix = Rgb565.manualTransformMatrix(
            source.width, source.height, frameWidth, frameHeight,
            Rgb565.ManualTransform(scale, pan.x, pan.y, rotation, flip)
        )
        // The canvas shows the frame's full area, scaled to its own pixels (box and frame
        // share the aspect ratio, so one uniform factor maps frame units -> screen px).
        val k = size.width / frameWidth
        matrix.postScale(k, k, 0f, 0f)
        clipRect {
            drawContext.canvas.nativeCanvas.drawBitmap(source, matrix, null)
        }
    }
}

/**
 * Options for tapping an already-replaced frame: swap the image, retune only the fit
 * mode, or restore the stock picture.
 */
@Composable
private fun FrameOptionsDialog(
    hasImage: Boolean,
    onChangeImage: () -> Unit,
    onChangeMode: () -> Unit,
    onRestore: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Frame already replaced") },
        text = { Text("Choose what to change on this frame.") },
        confirmButton = {
            TextButton(onClick = onChangeImage) { Text("Change image") }
        },
        dismissButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onChangeMode) { Text("Image mode") }
                TextButton(
                    onClick = onRestore,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Restore") }
            }
        }
    )
}

/** Decode a picked image at preview resolution (~600px), keeping alpha. */
private fun decodeUriPreview(context: android.content.Context, uri: android.net.Uri): Bitmap? = try {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.also {
        android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size, bounds)
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 600 && bounds.outHeight / (sample * 2) >= 600) sample *= 2
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        android.graphics.BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }
} catch (_: Exception) {
    null
}

@Composable
private fun NudgeGroup(label: String, vertical: Boolean, onNudge: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        NudgeButton(label, vertical, -10, onNudge, Modifier.weight(1f))
        NudgeButton(label, vertical, -1, onNudge, Modifier.weight(1f))
        NudgeButton(label, vertical, 1, onNudge, Modifier.weight(1f))
        NudgeButton(label, vertical, 10, onNudge, Modifier.weight(1f))
    }
}

/**
 * One nudge step as a pointing arrow: single chevron moves 1 unit, double chevron 10,
 * in the direction the widget will actually move (left/right for X, up/down for Y) —
 * easier to read at a glance than signed numbers.
 */
@Composable
private fun NudgeButton(
    axis: String,
    vertical: Boolean,
    step: Int,
    onNudge: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val direction = when {
        vertical && step < 0 -> "up"
        vertical -> "down"
        step < 0 -> "left"
        else -> "right"
    }
    OutlinedIconButton(
        onClick = { onNudge(step) },
        modifier = modifier.height(32.dp)
            .semantics { contentDescription = "$axis $direction ${kotlin.math.abs(step)}" }
    ) {
        if (kotlin.math.abs(step) == 10) {
            Box {
                Chevron(vertical, step, Modifier.offset(x = 2.dp))
                Chevron(vertical, step, Modifier.offset(x = (-2).dp))
            }
        } else {
            Chevron(vertical, step)
        }
    }
}

@Composable
private fun Chevron(vertical: Boolean, step: Int, modifier: Modifier = Modifier) {
    val icon = when {
        vertical && step < 0 -> Icons.Filled.KeyboardArrowUp
        vertical -> Icons.Filled.KeyboardArrowDown
        step < 0 -> Icons.AutoMirrored.Filled.KeyboardArrowLeft
        else -> Icons.AutoMirrored.Filled.KeyboardArrowRight
    }
    Icon(icon, contentDescription = null, modifier = modifier.size(18.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WidgetLibrary(
    donors: List<DonorEntry>,
    foreignDonors: List<DonorEntry>,
    onAdd: (Int) -> Unit,
    onAddForeign: (Int) -> Unit,
    onImportFace: () -> Unit,
    onImportFromProject: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Text("Widget library", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(4.dp))
            Text(
                "Widgets reference data-source sequence IDs from the seed face (firmware-defined).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onImportFace,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            ) {
                Text("Import another face as a widget library…")
            }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = onImportFromProject,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            ) {
                Text("From a downloaded face…")
            }
            Spacer(Modifier.height(8.dp))
            if (donors.isEmpty() && foreignDonors.isEmpty()) {
                Text("No widgets.", Modifier.padding(horizontal = 20.dp))
            }
            LazyVerticalGrid(
                modifier = Modifier.weight(1f, fill = false),
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(top = 4.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (donors.isNotEmpty()) {
                    item(span = { GridItemSpan(2) }) {
                        Text("This face", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 4.dp))
                    }
                    items(donors, key = { it.donorIndex }) { donor ->
                        LibraryCard(donor = donor, onClick = { onAdd(donor.donorIndex) })
                    }
                }
                if (foreignDonors.isNotEmpty()) {
                    item(span = { GridItemSpan(2) }) {
                        Text(
                            "From another face",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp)
                        )
                    }
                    items(foreignDonors, key = { "f${it.donorIndex}" }) { donor ->
                        LibraryCard(donor = donor, onClick = { onAddForeign(donor.donorIndex) })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlacedWidgetListSheet(
    placed: List<PlacedEditorWidget>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 12.dp)
        ) {
            Text(
                "Placed Widgets (${placed.size})",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Tap an item to select & edit; the delete icon removes it. " +
                    "Top-to-bottom order = draw order (top = back).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
            Spacer(Modifier.height(12.dp))
            if (placed.isEmpty()) {
                Text("No placed widgets yet.", Modifier.padding(horizontal = 20.dp))
            } else {
                placed.forEach { w ->
                    PlacedRow(
                        widget = w,
                        selected = w.id == selectedId,
                        onSelect = { onSelect(w.id) },
                        onDelete = { onDelete(w.id) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
/**
 * A widget's own raster as a list thumbnail, shown in the widget library and the
 * placed-widget list so the user can recognize what they are picking. Rasters are
 * ARGB_8888 bitmaps from the view model's cache — no extra decode here.
 */
@Composable
private fun WidgetThumb(preview: Bitmap?, size: Dp = 40.dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF101318)),
        contentAlignment = Alignment.Center
    ) {
        if (preview != null) {
            Image(
                bitmap = preview.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Text-only widgets (value/composite) have no raster to show.
            Text("T", color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun PlacedRow(
    widget: PlacedEditorWidget,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            WidgetThumb(widget.preview)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(widget.meaning.label, style = MaterialTheme.typography.titleSmall)
                    if (widget.donorIndex < 0) {
                        Spacer(Modifier.width(6.dp))
                        Badge { Text("other face") }
                    }
                }
                Text(
                    "Type: ${WidgetMeaningCatalog.describe(widget.donor)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "x=${widget.x} y=${widget.y} · #seq ${widget.donor.sequenceId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (selected) {
                Text("Selected", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
            } else {
                OutlinedButton(onClick = onSelect, contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Text("Select")
                }
                Spacer(Modifier.width(8.dp))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete", modifier = Modifier.size(20.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StylePickerSheet(
    previews: List<Bitmap>,
    selected: Int,
    modified: Set<Int>,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(
                "Color styles — each style uses the face's original image",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Edits only apply to the currently open style; other styles keep their original look (dot • = style modified).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
            Spacer(Modifier.height(12.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(previews.size) { i ->
                    val shape = RoundedCornerShape(8.dp)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box {
                            Image(
                                bitmap = previews[i].asImageBitmap(),
                                contentDescription = "Style ${i + 1}",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .width(80.dp)
                                    .aspectRatio(256f / 402f)
                                    .clip(shape)
                                    .background(Color(0xFF101318))
                                    .border(
                                        if (i == selected) 2.dp else 1.dp,
                                        if (i == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                        shape
                                    )
                                    .clickable { onSelect(i) }
                            )
                            if (i in modified) {
                                Badge(
                                    Modifier
                                        .align(Alignment.TopStart)
                                        .offset(x = 4.dp, y = 4.dp)
                                ) { Text("•") }
                            }
                        }
                        Text(
                            "${i + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (i == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (previews.size == 1) "This face has only 1 style." else "Tap a style to re-render the canvas.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
        }
    }
}

@Composable
private fun ExistingFaceDialog(faces: List<FaceSource>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a downloaded face") },
        text = {
            if (faces.isEmpty()) {
                Text("No other projects yet.")
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    faces.forEach { face ->
                        TextButton(
                            onClick = { onPick(face.projectId) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(face.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun LibraryCard(donor: DonorEntry, onClick: () -> Unit) {
    ElevatedCard(Modifier.clickable(onClick = onClick).fillMaxWidth()) {
        Row(
            Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WidgetThumb(donor.preview)
            Spacer(Modifier.width(10.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        donor.meaning.label,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    donor.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Text(
                    "original position (${donor.x}, ${donor.y})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
private fun ShareZipDialog(file: java.io.File, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Assets ready to share") },
        text = {
            Column {
                Text("${file.name} (${file.length() / 1024} kB)")
                Spacer(Modifier.height(6.dp))
                Text(
                    "Edit the PNGs with your favorite app/PC, then upload them back via " +
                        "\"Upload assets (zip)…\" — each image's position & size determine which widget it goes to.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(share, "Share asset zip"))
                onDismiss()
            }) { Text("Share…") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}