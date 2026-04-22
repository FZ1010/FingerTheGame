package com.fingerthegame.app.screens

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fingerthegame.app.util.ShizukuExec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppEntry(
    val pkg: String,
    val label: String,
    val icon: ImageBitmap?,
    val hasExternalData: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerScreen(
    onBack: () -> Unit,
    onPick: (pkg: String, label: String, rootPath: String) -> Unit,
) {
    val ctx = LocalContext.current
    val pm = ctx.packageManager
    var query by remember { mutableStateOf("") }
    var all by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showOnlyWithData by remember { mutableStateOf(false) }
    var listError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val (apps, err) = withContext(Dispatchers.IO) {
            val result = runCatching {
                ShizukuExec.listDir("/sdcard/Android/data").map { it.name }.toHashSet()
            }
            val withData = result.getOrDefault(emptySet())
            loadApps(pm, withData, ctx.packageName) to result.exceptionOrNull()?.message
        }
        all = apps
        listError = err
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pick an app") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                },
            )
        },
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search by name or package") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = showOnlyWithData,
                    onCheckedChange = { showOnlyWithData = it },
                )
                Text("Only show apps with an Android/data/… folder")
            }

            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Scaffold
            }

            listError?.let { err ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Text(
                        "Couldn't probe /sdcard/Android/data: $err",
                        Modifier.padding(8.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            val filtered = remember(query, all, showOnlyWithData) {
                all.asSequence()
                    .filter { !showOnlyWithData || it.hasExternalData }
                    .filter {
                        val q = query.trim().lowercase()
                        q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q)
                    }
                    .toList()
            }

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (all.isEmpty()) "No apps returned by PackageManager.\nDoes the app have QUERY_ALL_PACKAGES?"
                        else "No apps match the current filter.",
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(filtered, key = { it.pkg }) { app ->
                        val denied = com.fingerthegame.app.util.Ethics.isDeniedPackage(app.pkg)
                        AppRow(app = app, denied = denied) {
                            if (!denied) {
                                val root = "/sdcard/Android/data/${app.pkg}"
                                onPick(app.pkg, app.label, root)
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: AppEntry, denied: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = !denied, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (app.icon != null) {
            androidx.compose.foundation.Image(
                bitmap = app.icon,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
            )
        } else {
            Box(Modifier.size(40.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    app.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (denied) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (denied) {
                    Spacer(Modifier.width(8.dp))
                    Text("🚫 blocked",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error)
                }
            }
            Text(
                app.pkg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (app.hasExternalData) {
            AssistChip(onClick = {}, label = { Text("data") })
        }
    }
}

private fun loadApps(pm: PackageManager, packagesWithData: Set<String>, selfPkg: String): List<AppEntry> {
    val apps = pm.getInstalledApplications(0)
    return apps.asSequence()
        .filter { info ->
            val isSystem = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
            val isUpdatedSystem = info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
            (!isSystem || isUpdatedSystem) && info.packageName != selfPkg
        }
        .map { info ->
            val label = runCatching { pm.getApplicationLabel(info).toString() }.getOrNull() ?: info.packageName
            val icon = runCatching { pm.getApplicationIcon(info).toImageBitmap() }.getOrNull()
            AppEntry(info.packageName, label, icon, info.packageName in packagesWithData)
        }
        .sortedWith(compareByDescending<AppEntry> { it.hasExternalData }.thenBy { it.label.lowercase() })
        .toList()
}

private fun Drawable.toImageBitmap(): ImageBitmap {
    if (this is BitmapDrawable && bitmap != null) return bitmap.asImageBitmap()
    val w = intrinsicWidth.coerceAtLeast(1)
    val h = intrinsicHeight.coerceAtLeast(1)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    setBounds(0, 0, w, h)
    draw(canvas)
    return bmp.asImageBitmap()
}
