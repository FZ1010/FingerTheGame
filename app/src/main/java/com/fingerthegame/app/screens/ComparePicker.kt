package com.fingerthegame.app.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fingerthegame.app.util.ShizukuExec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bottom sheet that lists every other regular file in the same directory
 * as [currentPath], plus a separate "App backups" section for files saved
 * in this app's cache by ShizukuExec.backupLocal. User taps one to pick.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComparePickerSheet(
    currentPath: String,
    cacheDir: java.io.File,
    onDismiss: () -> Unit,
    onPick: (path: String) -> Unit,
) {
    var siblings by remember { mutableStateOf<List<ShizukuExec.Entry>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val backups = remember { cacheDir.listFiles()?.toList()?.sortedByDescending { it.lastModified() } ?: emptyList() }

    LaunchedEffect(currentPath) {
        runCatching {
            withContext(Dispatchers.IO) {
                ShizukuExec.listDir(currentPath.substringBeforeLast('/'))
            }
        }.onSuccess {
            siblings = it.filter { e -> !e.isDir && e.path != currentPath }
        }.onFailure { loadError = it.message }
    }

    val timeFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 16.dp)) {
            Text(
                "Compare against another save",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))

            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                if (siblings == null && loadError == null) {
                    item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() } }
                }
                if (loadError != null) {
                    item {
                        Text(
                            "Couldn't list directory: $loadError",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
                siblings?.let { list ->
                    if (list.isNotEmpty()) {
                        item { SectionLabel("Same folder") }
                        items(list, key = { it.path }) { e ->
                            FileRow(
                                title = e.name,
                                subtitle = "${e.size} B · ${timeFmt.format(Date(e.mtimeEpoch * 1000))}",
                                onClick = { onPick(e.path) },
                            )
                        }
                    }
                }
                if (backups.isNotEmpty()) {
                    item { SectionLabel("App backups") }
                    items(backups, key = { it.absolutePath }) { f ->
                        FileRow(
                            title = f.name,
                            subtitle = "${f.length()} B · ${timeFmt.format(Date(f.lastModified()))}",
                            onClick = { onPick(f.absolutePath) },
                        )
                    }
                }
                if (siblings?.isEmpty() == true && backups.isEmpty()) {
                    item {
                        Text(
                            "No other files to compare against. Save once first to create a backup.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
    )
}

@Composable
private fun FileRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
    }
    HorizontalDivider()
}
