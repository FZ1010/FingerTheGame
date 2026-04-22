package com.fingerthegame.app.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsentScreen(onAccept: () -> Unit) {
    var checkedSinglePlayer by remember { mutableStateOf(false) }
    var checkedNoBanking by remember { mutableStateOf(false) }
    var checkedOwnRisk by remember { mutableStateOf(false) }
    val canAccept = checkedSinglePlayer && checkedNoBanking && checkedOwnRisk

    Column(
        Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Welcome to FingerTheGame",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold)
        Text(
            "This app edits other apps' save files. Before you start, please read and check each box:",
            style = MaterialTheme.typography.bodyMedium,
        )

        ConsentRow(
            checked = checkedSinglePlayer,
            onChange = { checkedSinglePlayer = it },
            text = "I'll only use this on single-player games. Editing online or multiplayer saves will get my account banned and that's on me.",
        )
        ConsentRow(
            checked = checkedNoBanking,
            onChange = { checkedNoBanking = it },
            text = "I won't point this at banking, finance, authentication, or other apps where modifying data could constitute fraud. The app blocks an obvious-name list, but the responsibility is mine.",
        )
        ConsentRow(
            checked = checkedOwnRisk,
            onChange = { checkedOwnRisk = it },
            text = "Save files can corrupt. The app keeps backups in its cache, but I accept that a bad edit could break my game and I should restore from backup if that happens.",
        )

        Spacer(Modifier.weight(1f))

        Button(
            onClick = onAccept,
            enabled = canAccept,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (canAccept) "I understand — continue" else "Tick all three to continue")
        }
        Text(
            "By continuing you agree to the project's source-available LICENSE (free to use, no commercial sale, no redistribution).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConsentRow(checked: Boolean, onChange: (Boolean) -> Unit, text: String) {
    Surface(
        color = if (checked) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Checkbox(checked = checked, onCheckedChange = onChange)
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
