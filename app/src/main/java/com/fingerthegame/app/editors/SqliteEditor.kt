package com.fingerthegame.app.editors

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

data class SqliteRow(val rowid: Long, val values: List<String?>)

/**
 * Minimal SQLite table viewer/editor. Copies the remote DB to our cache, opens it,
 * shows tables and rows, lets the user edit individual cells, and produces the
 * modified file bytes via [onBytesUpdated] so the parent screen can push back.
 */
@Composable
fun SqliteEditor(
    ctx: Context,
    initialBytes: ByteArray,
    onBytesUpdated: (ByteArray) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val dbFile = remember(initialBytes) {
        val hash = MessageDigest.getInstance("SHA-1")
            .digest(initialBytes.size.toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
        File(ctx.cacheDir, "sqlite-$hash.db").apply { writeBytes(initialBytes) }
    }
    var tables by remember { mutableStateOf<List<String>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var columns by remember { mutableStateOf<List<String>>(emptyList()) }
    var rows by remember { mutableStateOf<List<SqliteRow>>(emptyList()) }
    var editing by remember { mutableStateOf<SqliteRow?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun openDb(): SQLiteDatabase = SQLiteDatabase.openDatabase(
        dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE,
    )

    fun reloadTables() {
        error = null
        runCatching {
            openDb().use { db ->
                val list = mutableListOf<String>()
                db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name", null).use { c ->
                    while (c.moveToNext()) list.add(c.getString(0))
                }
                tables = list
            }
        }.onFailure { error = it.message }
    }

    fun loadRows(table: String, limit: Int = 200) {
        error = null
        runCatching {
            openDb().use { db ->
                db.rawQuery("SELECT rowid, * FROM " + quote(table) + " LIMIT $limit", null).use { c ->
                    val cols = (1 until c.columnCount).map { c.getColumnName(it) }
                    val rs = mutableListOf<SqliteRow>()
                    while (c.moveToNext()) {
                        val rowid = c.getLong(0)
                        val vals = (1 until c.columnCount).map { i ->
                            if (c.isNull(i)) null else c.getString(i)
                        }
                        rs.add(SqliteRow(rowid, vals))
                    }
                    columns = cols
                    rows = rs
                }
            }
        }.onFailure { error = it.message }
    }

    fun commitRow(table: String, row: SqliteRow, new: List<String?>) {
        error = null
        runCatching {
            openDb().use { db ->
                val cv = ContentValues()
                for ((i, col) in columns.withIndex()) cv.put(col, new[i])
                db.update(quote(table), cv, "rowid=?", arrayOf(row.rowid.toString()))
            }
            onBytesUpdated(dbFile.readBytes())
            loadRows(table)
        }.onFailure { error = it.message }
    }

    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { reloadTables() } }

    Column(modifier.fillMaxSize().padding(8.dp)) {
        error?.let {
            Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Text(it, Modifier.padding(8.dp))
            }
        }
        if (selected == null) {
            Text("Tables", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(8.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(tables) { t ->
                    Text(
                        t,
                        Modifier.fillMaxWidth().clickable {
                            selected = t
                            scope.launch(Dispatchers.IO) { loadRows(t) }
                        }.padding(16.dp),
                    )
                    HorizontalDivider()
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { selected = null; rows = emptyList() }) { Text("← Tables") }
                Text(selected ?: "", style = MaterialTheme.typography.titleMedium)
            }
            // Column headers
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
            ) {
                Text("rowid  ", fontFamily = FontFamily.Monospace)
                for (c in columns) Text("$c   ", fontFamily = FontFamily.Monospace)
            }
            HorizontalDivider()
            LazyColumn(Modifier.weight(1f)) {
                items(rows, key = { it.rowid }) { r ->
                    Row(
                        Modifier.fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .clickable { editing = r }
                            .padding(vertical = 8.dp),
                    ) {
                        Text("${r.rowid}  ", fontFamily = FontFamily.Monospace)
                        for (v in r.values) Text("${v ?: "NULL"}   ", fontFamily = FontFamily.Monospace)
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    val tbl = selected
    val e = editing
    if (tbl != null && e != null) {
        RowEditDialog(
            columns = columns,
            row = e,
            onDismiss = { editing = null },
            onConfirm = { new ->
                editing = null
                scope.launch(Dispatchers.IO) { commitRow(tbl, e, new) }
            },
        )
    }
}

@Composable
private fun RowEditDialog(
    columns: List<String>,
    row: SqliteRow,
    onDismiss: () -> Unit,
    onConfirm: (List<String?>) -> Unit,
) {
    val state = remember(row) {
        mutableStateListOf<String>().apply { addAll(row.values.map { it ?: "" }) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(state.toList().map { if (it.isEmpty()) null else it }) }) {
                Text("Save row")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Edit rowid ${row.rowid}") },
        text = {
            Column {
                for ((i, col) in columns.withIndex()) {
                    OutlinedTextField(
                        value = state[i],
                        onValueChange = { state[i] = it },
                        label = { Text(col) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    )
                }
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    )
}

private fun quote(ident: String): String = "\"" + ident.replace("\"", "\"\"") + "\""
