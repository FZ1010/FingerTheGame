package com.fingerthegame.app.screens

import androidx.compose.foundation.clickable
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
import com.fingerthegame.app.util.RecentEntry
import com.fingerthegame.app.util.Recents
import com.fingerthegame.app.util.ShizukuExec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    status: ShizukuExec.Status,
    onRequestPermission: () -> Unit,
    onRecheck: () -> Unit,
    onPickApp: () -> Unit,
    onOpenRecent: (RecentEntry) -> Unit,
) {
    val ctx = LocalContext.current
    // Re-read on every entry to Home so we pick up files just opened.
    val recents = remember { mutableStateOf(emptyList<RecentEntry>()) }
    LaunchedEffect(Unit) { recents.value = Recents.read(ctx) }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("FingerTheGame", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Browse and edit any accessible app's save data via Shizuku.",
            style = MaterialTheme.typography.bodyMedium,
        )

        ShizukuStatusCard(status, onRequestPermission, onRecheck)

        if (status == ShizukuExec.Status.READY) {
            Button(onClick = onPickApp, modifier = Modifier.fillMaxWidth()) {
                Text("Pick an app")
            }
            if (recents.value.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                RecentsCard(
                    entries = recents.value,
                    onOpen = onOpenRecent,
                    onForget = { p ->
                        Recents.remove(ctx, p)
                        recents.value = Recents.read(ctx)
                    },
                )
            }
        }

        Spacer(Modifier.weight(1f))
        Text(
            "Writing to another app's files requires Shizuku to be running. " +
            "Edits are backed up to this app's cache before overwriting.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ShizukuStatusCard(
    status: ShizukuExec.Status,
    onRequestPermission: () -> Unit,
    onRecheck: () -> Unit,
) {
    val (text, label, action) = when (status) {
        ShizukuExec.Status.READY -> Triple("Shizuku: ready.", null, null)
        ShizukuExec.Status.NEEDS_PERMISSION ->
            Triple("Shizuku is running but hasn't granted this app access.", "Grant", onRequestPermission)
        ShizukuExec.Status.NOT_RUNNING ->
            Triple("Shizuku service isn't running. Start it from the Shizuku app.", "Recheck", onRecheck)
        ShizukuExec.Status.NOT_INSTALLED ->
            Triple("Shizuku isn't installed on this device.", "Recheck", onRecheck)
    }
    Surface(
        color = if (status == ShizukuExec.Status.READY) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            if (action != null && label != null) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = action) { Text(label) }
            }
        }
    }
}

@Composable
private fun RecentsCard(
    entries: List<RecentEntry>,
    onOpen: (RecentEntry) -> Unit,
    onForget: (path: String) -> Unit,
) {
    val timeFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(8.dp)) {
            Text(
                "Recent",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
            // Cap height so a long history doesn't push the rest of the
            // home screen off-screen.
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                items(entries, key = { it.path }) { e ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpen(e) }
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(e.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            Text(
                                "${e.label} · ${timeFmt.format(Date(e.openedAt))}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                maxLines = 1,
                            )
                        }
                        TextButton(onClick = { onForget(e.path) }) { Text("×") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
