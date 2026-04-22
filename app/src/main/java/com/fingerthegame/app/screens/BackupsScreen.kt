package com.fingerthegame.app.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fingerthegame.app.util.ShizukuExec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Visible undo timeline. Lists every save backup we've made (named
 * `backup-<timestamp>-<encoded-original-path>` in the app cache) and lets
 * the user restore one back to its origin path or delete it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    var backups by remember { mutableStateOf(loadBackups(ctx.cacheDir)) }
    var pendingRestore by remember { mutableStateOf<BackupEntry?>(null) }

    val timeFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Backups", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${backups.size} backup${if (backups.size == 1) "" else "s"}, " +
                                "${backups.sumOf { it.size } / 1024} KB total",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize()) {
            if (backups.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No backups yet — they appear after your first save.",
                        style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(backups, key = { it.file.absolutePath }) { b ->
                        BackupRow(
                            entry = b,
                            timeFmt = timeFmt,
                            onRestore = { pendingRestore = b },
                            onDelete = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { b.file.delete() }
                                    backups = loadBackups(ctx.cacheDir)
                                    snackbarHost.showSnackbar("Deleted ${b.file.name}")
                                }
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    pendingRestore?.let { b ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("Restore backup?") },
            text = {
                Column {
                    Text(b.originalPath, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Current contents at that path will be overwritten. The current state isn't itself backed up by this action.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val target = b
                    pendingRestore = null
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                val pkg = packageFromAndroidDataPath(target.originalPath)
                                if (pkg != null) ShizukuExec.validateWritePath(target.originalPath, pkg)
                                val data = target.file.readBytes()
                                if (pkg != null) ShizukuExec.forceStop(pkg)
                                ShizukuExec.writeFile(target.originalPath, data)
                            }
                        }.onSuccess {
                            snackbarHost.showSnackbar("Restored ${target.file.name}")
                        }.onFailure {
                            snackbarHost.showSnackbar("Restore failed: ${it.message ?: "?"}",
                                withDismissAction = true)
                        }
                    }
                }) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestore = null }) { Text("Cancel") }
            },
        )
    }
}

private data class BackupEntry(
    val file: File,
    val originalPath: String,
    val timestamp: Long,
    val size: Long,
)

private fun loadBackups(cacheDir: File): List<BackupEntry> {
    val files = cacheDir.listFiles { f -> f.name.startsWith("backup-") } ?: return emptyList()
    return files.mapNotNull { parseBackupName(it) }.sortedByDescending { it.timestamp }
}

private fun parseBackupName(f: File): BackupEntry? {
    // Format: backup-<timestamp>-<originalPath with / → _>
    val name = f.name
    if (!name.startsWith("backup-")) return null
    val rest = name.removePrefix("backup-")
    val dash = rest.indexOf('-')
    if (dash <= 0) return null
    val ts = rest.substring(0, dash).toLongOrNull() ?: return null
    val pathPart = rest.substring(dash + 1)
    // Reverse the `/` → `_` substitution. This is lossy if the original path
    // contained underscores — for Android paths it never does in practice.
    val origPath = "/" + pathPart.replace('_', '/')
    return BackupEntry(f, origPath, ts, f.length())
}

/** Pull the package name out of a `/sdcard/Android/data/<pkg>/...` path so the
 *  restore can validate its write target. */
private fun packageFromAndroidDataPath(path: String): String? {
    val marker = "/Android/data/"
    val i = path.indexOf(marker)
    if (i < 0) return null
    val after = path.substring(i + marker.length)
    val end = after.indexOf('/')
    return if (end > 0) after.substring(0, end) else null
}

@Composable
private fun BackupRow(
    entry: BackupEntry,
    timeFmt: SimpleDateFormat,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Text(
            entry.originalPath.substringAfterLast('/'),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
        )
        Text(
            entry.originalPath,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            maxLines = 1,
        )
        Text(
            "${timeFmt.format(Date(entry.timestamp))} · ${entry.size / 1024} KB",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(onClick = onRestore, modifier = Modifier.weight(1f)) {
                Text("Restore")
            }
            TextButton(onClick = onDelete) { Text("Delete") }
        }
    }
}
