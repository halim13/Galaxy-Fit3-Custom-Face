package com.galaxyfit3.customface.ui.screens.preview

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.galaxyfit3.core.format.WatchFaceFormat
import com.galaxyfit3.core.image.Rgb565
import com.galaxyfit3.customface.viewmodel.PreviewViewModel
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(
    projectId: String,
    onInstall: () -> Unit,
    onBack: () -> Unit,
    viewModel: PreviewViewModel = hiltViewModel()
) {
    LaunchedEffect(projectId) { viewModel.load(projectId) }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Preview & Validate", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Text("←") } }
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (ui.loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            ui.error?.let { msg ->
                Text(msg, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            }

            // The watch rotates styles by container order (Style 1 = style0.bin, and so on); these
            // chips make the user check each style before installing, not just the first.
            if (ui.styleCount > 1) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    (0 until ui.styleCount).forEach { i ->
                        FilterChip(
                            selected = ui.selectedStyleIndex == i,
                            onClick = { viewModel.selectStyle(i) },
                            label = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Style ${i + 1}")
                                    if (i in ui.modifiedStyles) {
                                        Badge(Modifier.padding(start = 4.dp)) { Text("•") }
                                    }
                                }
                            }
                        )
                    }
                }
            }

            ui.preview?.let { bmp ->
                Box(
                    Modifier.width(180.dp).aspectRatio(256f / 402f)
                        .background(Color(0xFF101318))
                        .border(1.dp, MaterialTheme.colorScheme.outline),
                    contentAlignment = Alignment.Center
                ) {
                    Image(bmp.asImageBitmap(), contentDescription = "Preview", modifier = Modifier.fillMaxSize())
                }
            } ?: run {
                Spacer(Modifier.aspectRatio(256f / 402f))
            }

            ui.validation?.let { v ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            if (v.ok) "Valid ✓" else "Error",
                            style = MaterialTheme.typography.titleSmall,
                            color = if (v.ok) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                        )
                        if (v.ok) {
                            Text("Size: ${v.sizeBytes / 1024} kB (${v.sizeBytes} bytes)", style = MaterialTheme.typography.bodySmall)
                        }
                        v.errors.forEach { e ->
                            Text(e, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        v.warnings.forEach { w ->
                            Text("⚠ $w", style = MaterialTheme.typography.bodySmall, color = Color(0xFFF57F17))
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val bytes = ui.outputBytes ?: return@OutlinedButton
                        // Share under the project's own name — the bin usually travels
                        // to the web editor or another device, and "face_preview.bin"
                        // tells nothing about which face it is.
                        val safeName = ui.projectName.ifBlank { "face" }
                            .replace(Regex("[^A-Za-z0-9 _.-]+"), "_").take(60)
                        val file = File(context.cacheDir, "$safeName.bin")
                        FileOutputStream(file).use { it.write(bytes) }
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "application/octet-stream"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(share, "Share watch face"))
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Share") }

                Button(
                    onClick = onInstall,
                    enabled = ui.validation?.ok == true,
                    modifier = Modifier.weight(1f)
                ) { Text("Install") }
            }
        }
    }
}