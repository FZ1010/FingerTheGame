package com.fingerthegame.app.editors

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fingerthegame.app.util.ProtoField
import com.fingerthegame.app.util.ProtoWireType
import com.fingerthegame.app.util.ProtobufDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.withContext
import java.lang.Float.intBitsToFloat
import java.lang.Double.longBitsToDouble

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun ProtobufEditor(
    bytes: ByteArray,
    onBytesUpdated: (ByteArray) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Async parse so a 1-2 MB protobuf doesn't block first paint.
    val doc by produceState<ProtobufDocument?>(initialValue = null, bytes) {
        value = withContext(Dispatchers.Default) { ProtobufDocument(bytes) }
    }

    val d = doc
    if (d == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("Parsing ${bytes.size / 1024} KB protobuf…",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        return
    }

    var query by remember { mutableStateOf("") }
    var typeFilter by remember { mutableStateOf<ProtoWireType?>(null) }
    val patches = remember { mutableStateMapOf<Int, String>() }
    val listState = rememberLazyListState()

    LaunchedEffect(d) { patches.clear() }

    LaunchedEffect(d) {
        snapshotFlow { patches.toMap() }
            .debounce(250L)
            .collect { snap ->
                val parsed = snap.mapNotNull { (off, str) ->
                    val f = d.fields.firstOrNull { it.tagOffset == off } ?: return@mapNotNull null
                    val v: Any = when (f.wireType) {
                        ProtoWireType.LENGTH_DELIMITED -> str       // bytes/string — keep as string
                        else -> str.trim().toLongOrNull() ?: return@mapNotNull null
                    }
                    off to v
                }.toMap()
                onBytesUpdated(if (parsed.isEmpty()) bytes else d.applyPatches(parsed))
            }
    }

    val filtered by remember(d) {
        derivedStateOf {
            val q = query.trim().lowercase()
            d.fields.asSequence()
                .filter { typeFilter == null || it.wireType == typeFilter }
                .filter { f ->
                    if (q.isEmpty()) return@filter true
                    "field${f.fieldNumber}".contains(q) ||
                    "#${f.fieldNumber}".contains(q) ||
                    f.fieldNumber.toString() == q
                }
                .take(2000)
                .toList()
        }
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${d.fields.size} fields · showing ${filtered.size}" +
                    (d.parseError?.let { " · ⚠ $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            if (patches.isNotEmpty()) {
                AssistChip(onClick = { patches.clear() },
                    label = { Text("clear ${patches.size} edit${if (patches.size == 1) "" else "s"}") })
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search by field number (e.g. 5, #5, field5)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        )

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(selected = typeFilter == null, onClick = { typeFilter = null },
                label = { Text("all") })
            for (wt in ProtoWireType.values()) {
                FilterChip(selected = typeFilter == wt, onClick = { typeFilter = wt },
                    label = { Text(wt.name.lowercase()) })
            }
        }

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(filtered, key = { it.id }, contentType = { it.wireType }) { f ->
                ProtoFieldRow(
                    field = f,
                    currentInput = patches[f.tagOffset],
                    onEdit = { v -> patches[f.tagOffset] = v },
                    onClear = { patches.remove(f.tagOffset) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ProtoFieldRow(
    field: ProtoField,
    currentInput: String?,
    onEdit: (String) -> Unit,
    onClear: () -> Unit,
) {
    val origStr = remember(field) { renderValue(field) }
    val changed = currentInput != null && currentInput != origStr
    var userExpanded by remember(field) { mutableStateOf(false) }
    val expanded = userExpanded || currentInput != null

    val bg = if (changed) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent

    Column(
        Modifier.fillMaxWidth().background(bg)
            .clickable { userExpanded = !userExpanded }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "#${field.fieldNumber}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.width(72.dp),
            )
            Text(
                if (changed) "$origStr → ${currentInput ?: ""}" else origStr,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = if (changed) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Spacer(Modifier.width(6.dp))
            WireBadge(field.wireType)
        }

        if (expanded) {
            Spacer(Modifier.height(4.dp))
            Text(
                "@0x${field.tagOffset.toString(16)} · payload ${field.payloadLength} B",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )

            if (field.wireType == ProtoWireType.LENGTH_DELIMITED) {
                // LD bytes only safely editable when the new content is the
                // same length — mention that and offer raw-text editing.
                Text(
                    "(length-delimited — new value must be exactly ${field.payloadLength} bytes)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = currentInput ?: "",
                onValueChange = { if (it.isEmpty()) onClear() else onEdit(it) },
                placeholder = { Text("new value") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = when (field.wireType) {
                        ProtoWireType.LENGTH_DELIMITED -> KeyboardType.Text
                        else -> KeyboardType.Number
                    }
                ),
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )

            if (changed) {
                TextButton(onClick = onClear) { Text("× undo") }
            }
        }
    }
}

private fun renderValue(f: ProtoField): String = when (f.wireType) {
    ProtoWireType.VARINT -> (f.value as Long).toString()
    ProtoWireType.FIXED32 -> {
        val raw = f.value as Int
        // Ambiguous: could be int32, uint32, or float. Show int + float to
        // help the user recognise which it is.
        "$raw  (float ${"%.6g".format(intBitsToFloat(raw))})"
    }
    ProtoWireType.FIXED64 -> {
        val raw = f.value as Long
        "$raw  (double ${"%.6g".format(longBitsToDouble(raw))})"
    }
    ProtoWireType.LENGTH_DELIMITED -> {
        val bytes = f.value as ByteArray
        val asText = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
        if (asText != null && asText.all { it.code in 9..127 || it.code >= 0x80 } && asText.length == bytes.size) {
            "\"${asText.take(40)}\"" + if (asText.length > 40) "…" else ""
        } else {
            "<${bytes.size} bytes: ${bytes.take(8).joinToString(" ") { "%02x".format(it.toInt() and 0xff) }}…>"
        }
    }
}

@Composable
private fun WireBadge(wt: ProtoWireType) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            wt.name.lowercase().replace("_delimited", ""),
            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}
