package com.fingerthegame.app.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fingerthegame.app.util.ShizukuExec

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    status: ShizukuExec.Status,
    onRequestPermission: () -> Unit,
    onRecheck: () -> Unit,
    onPickApp: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Universal Save Editor", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Browse and edit any accessible app's save data via Shizuku.",
            style = MaterialTheme.typography.bodyMedium,
        )

        ShizukuStatusCard(status, onRequestPermission, onRecheck)

        if (status == ShizukuExec.Status.READY) {
            Button(onClick = onPickApp, modifier = Modifier.fillMaxWidth()) {
                Text("Pick an app")
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
