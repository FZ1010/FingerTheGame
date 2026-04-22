package com.fingerthegame.app.editors

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HexViewer(bytes: ByteArray, modifier: Modifier = Modifier) {
    val rows = remember(bytes) { buildRows(bytes) }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        items(rows.size) { i ->
            Text(
                rows[i],
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun buildRows(bytes: ByteArray): List<String> {
    val sb = StringBuilder(16)
    val out = ArrayList<String>(bytes.size / 16 + 1)
    var i = 0
    while (i < bytes.size) {
        val end = minOf(i + 16, bytes.size)
        sb.setLength(0)
        sb.append(String.format("%06x  ", i))
        for (j in i until end) sb.append(String.format("%02x ", bytes[j].toInt() and 0xFF))
        repeat(16 - (end - i)) { sb.append("   ") }
        sb.append(" ")
        for (j in i until end) {
            val c = bytes[j].toInt() and 0xFF
            sb.append(if (c in 32..126) c.toChar() else '.')
        }
        out.add(sb.toString())
        i = end
    }
    return out
}
