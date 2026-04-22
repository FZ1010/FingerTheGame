package com.fingerthegame.app.screens

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fingerthegame.app.util.ShizukuExec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class SearchMode { FILENAME, CONTENT }
private enum class SortMode(val label: String) {
    NAME("name"),
    RECENT("recent first"),
    SIZE("biggest first"),
}

private val QUICK_KEYWORDS = listOf(
    "BankBalance", "money", "coin", "gold", "gem", "diamond",
    "level", "xp", "score", "health", "hp", "att_", "karma", "fame", "happiness",
)

private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    pkg: String,
    label: String,
    initialPath: String,
    onBack: () -> Unit,
    onOpenFile: (path: String) -> Unit,
) {
    var path by remember { mutableStateOf(initialPath) }
    var entries by remember { mutableStateOf<List<ShizukuExec.Entry>>(emptyList()) }
    var listLoading by remember { mutableStateOf(true) }
    var listError by remember { mutableStateOf<String?>(null) }

    var query by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(SearchMode.CONTENT) }
    var caseInsensitive by remember { mutableStateOf(true) }
    var sortMode by remember { mutableStateOf(SortMode.RECENT) }

    var searchHits by remember { mutableStateOf<List<ShizukuExec.SearchHit>>(emptyList()) }
    var searchBusy by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }

    val rootPath = "/sdcard/Android/data/$pkg"

    // Load directory whenever the path changes.
    LaunchedEffect(path) {
        listLoading = true; listError = null
        val res = withContext(Dispatchers.IO) {
            runCatching { ShizukuExec.listDir(path) }
        }
        res.onSuccess { e ->
            entries = e
            // Auto-skip the empty `cache/files/` middle step the first time we open
            // a package: if the root has a single `files` dir (or files+cache), jump.
            if (path == rootPath) {
                val files = e.firstOrNull { it.isDir && it.name == "files" }
                val others = e.count { it.isDir && it.name !in setOf("files", "cache", "code_cache", "shared_prefs") }
                if (files != null && others == 0) {
                    path = files.path
                }
            }
        }
        res.onFailure { listError = it.message; entries = emptyList() }
        listLoading = false
    }

    // Debounced search across the package root whenever search params change.
    LaunchedEffect(query, mode, caseInsensitive) {
        if (query.isBlank()) {
            searchHits = emptyList(); searchBusy = false; searchError = null
            return@LaunchedEffect
        }
        delay(300) // debounce
        searchBusy = true; searchError = null
        withContext(Dispatchers.IO) {
            runCatching {
                if (mode == SearchMode.FILENAME)
                    ShizukuExec.searchFilenames(rootPath, query, caseInsensitive)
                else
                    ShizukuExec.searchContent(rootPath, query, caseInsensitive)
            }.onSuccess { searchHits = it }
             .onFailure { searchError = it.message; searchHits = emptyList() }
        }
        searchBusy = false
    }

    val atRoot = path == rootPath
    val searchActive = query.isNotBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(label, style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (searchActive) "search in $pkg"
                            else path.removePrefix("/sdcard/Android/data/"),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                        )
                    }
                },
                navigationIcon = {
                    TextButton(onClick = {
                        when {
                            searchActive -> query = ""
                            atRoot -> onBack()
                            else -> path = path.substringBeforeLast('/').ifEmpty { "/" }
                        }
                    }) {
                        Text(if (searchActive) "Clear" else if (atRoot) "Back" else "Up")
                    }
                },
            )
        },
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize()) {
            SearchBar(
                query = query,
                onQueryChange = { query = it },
                mode = mode,
                onModeChange = { mode = it },
                caseInsensitive = caseInsensitive,
                onCaseToggle = { caseInsensitive = !caseInsensitive },
            )

            if (!searchActive) {
                QuickKeywords(onPick = { kw -> query = kw; mode = SearchMode.CONTENT })
                SortRow(sort = sortMode, onChange = { sortMode = it })
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    searchActive -> SearchResults(
                        hits = searchHits,
                        busy = searchBusy,
                        error = searchError,
                        mode = mode,
                        pkgRoot = rootPath,
                        onOpen = onOpenFile,
                    )
                    listLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    listError != null -> Column(Modifier.padding(16.dp)) {
                        Text("Couldn't list $path", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(listError!!, style = MaterialTheme.typography.bodySmall)
                    }
                    entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("empty")
                    }
                    else -> {
                        val sorted = remember(entries, sortMode) { sortEntries(entries, sortMode) }
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(sorted, key = { it.path }) { e ->
                                EntryRow(e, onClick = {
                                    if (e.isDir) path = e.path else onOpenFile(e.path)
                                })
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun sortEntries(es: List<ShizukuExec.Entry>, mode: SortMode): List<ShizukuExec.Entry> {
    val (dirs, files) = es.partition { it.isDir }
    val sortedDirs = when (mode) {
        SortMode.NAME -> dirs.sortedBy { it.name.lowercase() }
        SortMode.RECENT -> dirs.sortedByDescending { it.mtimeEpoch }
        SortMode.SIZE -> dirs.sortedBy { it.name.lowercase() }
    }
    val sortedFiles = when (mode) {
        SortMode.NAME -> files.sortedBy { it.name.lowercase() }
        SortMode.RECENT -> files.sortedByDescending { it.mtimeEpoch }
        SortMode.SIZE -> files.sortedByDescending { it.size }
    }
    return sortedDirs + sortedFiles
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    mode: SearchMode,
    onModeChange: (SearchMode) -> Unit,
    caseInsensitive: Boolean,
    onCaseToggle: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = {
                Text(
                    if (mode == SearchMode.FILENAME) "Search filenames"
                    else "Search file contents (e.g. BankBalance, coins)"
                )
            },
            singleLine = true,
            trailingIcon = {
                if (query.isNotEmpty()) {
                    TextButton(onClick = { onQueryChange("") }) { Text("✕") }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = mode == SearchMode.CONTENT,
                onClick = { onModeChange(SearchMode.CONTENT) },
                label = { Text("content") },
            )
            FilterChip(
                selected = mode == SearchMode.FILENAME,
                onClick = { onModeChange(SearchMode.FILENAME) },
                label = { Text("filename") },
            )
            Spacer(Modifier.weight(1f))
            FilterChip(
                selected = !caseInsensitive,
                onClick = onCaseToggle,
                label = { Text(if (caseInsensitive) "Aa" else "AA") },
            )
        }
    }
}

@Composable
private fun QuickKeywords(onPick: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("⚡", modifier = Modifier.padding(top = 8.dp))
        QUICK_KEYWORDS.forEach { kw ->
            AssistChip(onClick = { onPick(kw) }, label = { Text(kw) })
        }
    }
}

@Composable
private fun SortRow(sort: SortMode, onChange: (SortMode) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("sort:", style = MaterialTheme.typography.bodySmall)
        SortMode.values().forEach { m ->
            FilterChip(selected = sort == m, onClick = { onChange(m) }, label = { Text(m.label) })
        }
    }
}

@Composable
private fun SearchResults(
    hits: List<ShizukuExec.SearchHit>,
    busy: Boolean,
    error: String?,
    mode: SearchMode,
    pkgRoot: String,
    onOpen: (String) -> Unit,
) {
    if (busy) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(if (mode == SearchMode.CONTENT) "grepping… (a few seconds)" else "scanning filenames…",
                 style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    if (error != null) { Text("search failed: $error", Modifier.padding(16.dp)); return }
    if (hits.isEmpty()) { Text("no matches", Modifier.padding(16.dp)); return }

    Column(Modifier.fillMaxSize()) {
        Text(
            "${hits.size} ${if (mode == SearchMode.CONTENT) "files contain" else "filenames match"} (tap to open)",
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
        )
        LazyColumn(Modifier.fillMaxSize()) {
            items(hits, key = { it.path + (it.snippet ?: "") }) { hit ->
                SearchHitRow(hit, pkgRoot, mode) { onOpen(hit.path) }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun SearchHitRow(
    hit: ShizukuExec.SearchHit,
    pkgRoot: String,
    mode: SearchMode,
    onClick: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        val short = hit.path.removePrefix("$pkgRoot/")
        Text(short.substringAfterLast('/'), fontWeight = FontWeight.Medium)
        Text(
            short,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (mode == SearchMode.CONTENT && hit.snippet != null) {
            Text(
                hit.snippet,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun EntryRow(e: ShizukuExec.Entry, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (e.isDir) "📁" else "📄", modifier = Modifier.padding(end = 12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.name, style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!e.isDir) {
                    Text(
                        formatSize(e.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (e.mtimeEpoch > 0) {
                    Text(
                        timeFmt.format(Date(e.mtimeEpoch * 1000)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

private fun formatSize(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "${b / 1024} KB"
    else -> "${b / (1024 * 1024)} MB"
}
