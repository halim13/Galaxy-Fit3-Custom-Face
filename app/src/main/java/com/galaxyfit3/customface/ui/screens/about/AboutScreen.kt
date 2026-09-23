package com.galaxyfit3.customface.ui.screens.about

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

const val PROJECT_URL = "https://github.com/halim13/Galaxy-Fit3-Custom-Face"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val version = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    } catch (_: Exception) {
        "?"
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("About This App") }, navigationIcon = {
                IconButton(onClick = onBack) { Text("←") }
            })
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "READ THIS BEFORE USING",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "This app is a VIBECODING project.\n\n" +
                            "It was written quickly and experimentally, with little to no formal " +
                            "testing, documentation, or design review. Bugs, inconsistencies, and " +
                            "unfinished behavior may appear in any version, now or in the future.\n\n" +
                            "If something is wrong, missing, or looks out of place — it is likely a " +
                            "known side effect of how this app was made, not an accident you should " +
                            "report as a one-off bug.\n\n" +
                            "Use it at your own risk, and please be understanding about anything that " +
                            "behaves unexpectedly in later updates.\n\n" +
                            "If you find that some widgets appear in the wrong position inside " +
                            "the editor but look fine on the watch, please be patient — that " +
                            "mismatch is a known quirk of a vibecoded editor and does not mean " +
                            "your face is broken.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            Text("What Is This?", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "Galaxy Fit 3 — Custom Face lets you build and edit watch faces for the " +
                    "Samsung Galaxy Fit 3 (SM-R390). Start from a seed .bin file, place widgets " +
                    "that the watch firmware actually publishes, preview the result, and " +
                    "install it to your device.",
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(20.dp))

            Text("What It Does", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "• Import a seed .bin as a template\n" +
                    "• Edit and preview watch faces\n" +
                    "• Install a face onto a paired Fit 3\n" +
                    "• Export the original seed back out",
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(20.dp))

            Text("Compatibility", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "Built for the Galaxy Fit 3 (SM-R390). This is a hobby / experimental tool — " +
                    "it is NOT an official Samsung product and is not endorsed by Samsung.",
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(20.dp))

            Text("Project Source", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Card(
                onClick = { uriHandler.openUri(PROJECT_URL) },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text(
                        "The base project lives on GitHub — tap to view or download the source.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Visit →", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                PROJECT_URL,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.height(24.dp))

            Divider()
            Spacer(Modifier.height(16.dp))
            Text("Version $version", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text(
                "Made by vibecoding. Expect experimental behavior. Be kind.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 24.dp)
            )
        }
    }
}