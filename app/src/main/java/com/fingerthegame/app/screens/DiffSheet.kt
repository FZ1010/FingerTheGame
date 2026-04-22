package com.fingerthegame.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fingerthegame.app.util.NrbfDiffEntry

/**
 * Modal sheet showing the diff between the current save and a comparison
 * save. User checks the changes they want to pull in; [onApply] receives
 * the selected entries.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiffSheet(
    diff: List<NrbfDiffEntry>,
    comparisonName: String,
    onApply: (selected: List<NrbfDiffEntry>) -> Unit,
    onDismiss: () -> Unit,
) {
    val checked = remember(diff) { mutableStateMapOf<Long, Boolean>() }
    LaunchedEffect(diff) {
        // Default to "all selected" — the common case is "make my save look
        // like that backup".
        diff.forEach { checked[it.field.id] = true }
    }
    val selectedCount = checked.values.count { it }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Text(
                "Diff vs ${comparisonName.substringAfterLast('/')}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${diff.size} changed field${if (diff.size == 1) "" else "s"}.  Tap to toggle, Apply pulls the selected values into the current save.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            if (diff.isEmpty()) {
                Text(
                    "No differences.",
                    Modifier.padding(vertical = 24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 480.dp)) {
                    items(diff, key = { it.field.id }) { entry ->
                        DiffRow(
                            entry = entry,
                            selected = checked[entry.field.id] ?: false,
                            onToggle = { checked[entry.field.id] = !(checked[entry.field.id] ?: false) },
                        )
                        HorizontalDivider()
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text("Cancel")
                }
                Button(
                    onClick = { onApply(diff.filter { checked[it.field.id] == true }) },
                    enabled = selectedCount > 0,
                    modifier = Modifier.weight(2f),
                ) {
                    Text(if (selectedCount > 0) "Apply $selectedCount change${if (selectedCount == 1) "" else "s"}" else "Nothing selected")
                }
            }
        }
    }
}

@Composable
private fun DiffRow(entry: NrbfDiffEntry, selected: Boolean, onToggle: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    Row(
        Modifier.fillMaxWidth().background(bg).clickable(onClick = onToggle).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = { onToggle() })
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.field.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Text(
                "${entry.field.originalValue} → ${entry.newValue}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                entry.field.className,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                maxLines = 1,
            )
        }
    }
}
