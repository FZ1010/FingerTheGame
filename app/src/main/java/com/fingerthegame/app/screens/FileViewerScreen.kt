package com.fingerthegame.app.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.fingerthegame.app.editors.HexViewer
import com.fingerthegame.app.editors.NrbfFieldsEditor
import com.fingerthegame.app.editors.ProtobufEditor
import com.fingerthegame.app.editors.SqliteEditor
import com.fingerthegame.app.editors.TextEditor
import com.fingerthegame.app.util.Es3Crack
import com.fingerthegame.app.util.Format
import com.fingerthegame.app.util.FormatDetect
import com.fingerthegame.app.util.NrbfDiff
import com.fingerthegame.app.util.NrbfDiffEntry
import com.fingerthegame.app.util.NrbfDocument
import com.fingerthegame.app.util.RecentEntry
import com.fingerthegame.app.util.Recents
import com.fingerthegame.app.util.ShizukuExec
import com.fingerthegame.app.util.Unwrapped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileViewerScreen(
    pkg: String,
    label: String,
    path: String,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    var loading by remember { mutableStateOf(true) }
    var original by remember { mutableStateOf<ByteArray?>(null) }
    var current by remember { mutableStateOf<ByteArray?>(null) }
    var format by remember { mutableStateOf(Format.BINARY) }
    // Captured wrapping (e.g. base64) so we can re-apply on save.
    var wrap by remember { mutableStateOf<Unwrapped?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Diff workflow state.
    var showComparePicker by remember { mutableStateOf(false) }
    var diffEntries by remember { mutableStateOf<List<NrbfDiffEntry>?>(null) }
    var comparePath by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(path) {
        loading = true
        runCatching {
            withContext(Dispatchers.IO) {
                val sz = runCatching { ShizukuExec.statSize(path) }.getOrDefault(-1L)
                if (sz > ShizukuExec.MAX_FILE_BYTES) {
                    error("File is ${sz / 1024 / 1024} MB — refusing to load (>${ShizukuExec.MAX_FILE_BYTES / 1024 / 1024} MB cap)")
                }
                val raw = ShizukuExec.readFile(path)
                FormatDetect.unwrap(raw)
            }
        }.onSuccess { unwrapped ->
            original = unwrapped.effective
            current = unwrapped.effective
            format = unwrapped.format
            wrap = unwrapped
            Recents.add(ctx, RecentEntry(pkg, label, path, System.currentTimeMillis()))
            if (unwrapped.wrappedAsBase64) {
                snackbarHost.showSnackbar("Detected base64 wrapper · decoded inside")
            }
        }.onFailure {
            snackbarHost.showSnackbar("Read failed: ${it.message ?: "unknown"}")
        }
        loading = false
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(path.substringAfterLast('/'), style = MaterialTheme.typography.titleMedium)
                        val sizeText = current?.let { "${it.size} B · ${format.label()}" } ?: "loading…"
                        Text(
                            sizeText,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    if (format == Format.NRBF && current != null) {
                        TextButton(onClick = { showComparePicker = true }) { Text("Compare") }
                    }
                    val orig = original
                    if (format == Format.BINARY && orig != null && Es3Crack.looksEncrypted(orig)) {
                        TextButton(onClick = {
                            scope.launch {
                                snackbarHost.showSnackbar("Pulling APK + scanning for ES3 password…",
                                    withDismissAction = true)
                                val res = withContext(Dispatchers.IO) {
                                    val apks = Es3Crack.pullApkFiles(ctx, pkg)
                                    if (apks.isEmpty()) return@withContext null
                                    val cands = Es3Crack.candidatesFromApks(apks, ctx.cacheDir)
                                    Es3Crack.crack(orig, cands)
                                }
                                if (res == null) {
                                    snackbarHost.showSnackbar("Couldn't read $pkg's APK")
                                } else if (res.ok) {
                                    val plain = res.plain!!
                                    val u = FormatDetect.unwrap(plain)
                                    original = u.effective
                                    current = u.effective
                                    format = u.format
                                    snackbarHost.showSnackbar("✓ ${res.message}", withDismissAction = true)
                                } else {
                                    snackbarHost.showSnackbar(
                                        "ES3 crack failed: ${res.message}",
                                        withDismissAction = true,
                                    )
                                }
                            }
                        }) { Text("🔐 Try decrypt") }
                    }
                },
            )
        },
        bottomBar = {
            val cur = current
            if (cur != null && !loading) {
                BottomAppBar {
                    val hasDiff = original != null && !cur.contentEquals(original)
                    Button(
                        enabled = hasDiff && !busy,
                        onClick = {
                            scope.launch {
                                busy = true
                                try {
                                    val backup = withContext(Dispatchers.IO) {
                                        original?.let { ShizukuExec.backupLocal(path, it, ctx.cacheDir) }
                                    }
                                    withContext(Dispatchers.IO) {
                                        // Confine writes to the picked package's data dir —
                                        // a poisoned `path` shouldn't ever get us writing to
                                        // /data/local/tmp/ or /sdcard/ at large.
                                        ShizukuExec.validateWritePath(path, pkg)
                                        ShizukuExec.forceStop(pkg)
                                        val toWrite = wrap?.rewrap(cur) ?: cur
                                        ShizukuExec.writeFile(path, toWrite)
                                    }
                                    original = cur
                                    snackbarHost.showSnackbar(
                                        message = "Saved · backup ${backup?.name ?: "—"}",
                                        withDismissAction = true,
                                    )
                                } catch (t: Throwable) {
                                    snackbarHost.showSnackbar(
                                        message = "Save failed: ${t.message ?: "unknown"}",
                                        withDismissAction = true,
                                    )
                                } finally { busy = false }
                            }
                        },
                        modifier = Modifier.padding(8.dp).weight(1f),
                    ) {
                        Text(when {
                            busy -> "Saving…"
                            hasDiff -> "Save (closes $label)"
                            else -> "No changes"
                        })
                    }
                }
            }
        },
    ) { inner ->
        Box(Modifier.padding(inner).fillMaxSize()) {
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                current == null -> Text("no data", Modifier.padding(16.dp))
                else -> FileBody(
                    bytes = original!!,
                    format = format,
                    onUpdate = { current = it },
                )
            }
        }

        if (showComparePicker) {
            ComparePickerSheet(
                currentPath = path,
                cacheDir = ctx.cacheDir,
                onDismiss = { showComparePicker = false },
                onPick = { picked ->
                    showComparePicker = false
                    scope.launch {
                        val orig = original ?: return@launch
                        runCatching {
                            withContext(Dispatchers.IO) {
                                val raw = if (picked.startsWith("/data/data") || picked.startsWith(ctx.cacheDir.absolutePath))
                                    java.io.File(picked).readBytes()
                                else
                                    ShizukuExec.readFile(picked)
                                val unwrapped = FormatDetect.unwrap(raw)
                                val docCur = NrbfDocument(orig)
                                val docOther = NrbfDocument(unwrapped.effective)
                                NrbfDiff.compute(docCur, docOther)
                            }
                        }.onSuccess {
                            diffEntries = it
                            comparePath = picked
                        }.onFailure {
                            snackbarHost.showSnackbar("Compare failed: ${it.message ?: "unknown"}")
                        }
                    }
                },
            )
        }

        diffEntries?.let { entries ->
            DiffSheet(
                diff = entries,
                comparisonName = comparePath ?: "",
                onApply = { selected ->
                    diffEntries = null
                    // Apply against `current` (which carries any in-progress
                    // user edits) rather than `original` — otherwise selecting
                    // a diff silently drops everything the user has typed.
                    val base = current ?: original ?: return@DiffSheet
                    val patches = selected.associate { it.field.offset to it.newValue }
                    if (patches.isNotEmpty()) {
                        val doc = NrbfDocument(base)
                        val result = doc.applyPatchesDetailed(patches)
                        current = result.bytes
                        scope.launch {
                            val ok = patches.size - result.failures.size
                            val msg = if (result.failures.isEmpty()) {
                                "Applied $ok change${if (ok == 1) "" else "s"} — Save to write"
                            } else {
                                "Applied $ok of ${patches.size} (${result.failures.size} rejected: ${result.failures.values.first().take(80)})"
                            }
                            snackbarHost.showSnackbar(msg, withDismissAction = true)
                        }
                    }
                },
                onDismiss = { diffEntries = null },
            )
        }
    }
}

@Composable
private fun FileBody(
    bytes: ByteArray,
    format: Format,
    onUpdate: (ByteArray) -> Unit,
) {
    val ctx = LocalContext.current
    when (format) {
        Format.TEXT, Format.JSON, Format.XML -> {
            val initial = remember(bytes) { String(bytes, Charsets.UTF_8) }
            TextEditor(
                initial = initial,
                monospace = format != Format.TEXT,
                onChange = { onUpdate(it.toByteArray(Charsets.UTF_8)) },
            )
        }
        Format.NRBF -> NrbfFieldsEditor(
            bytes = bytes,
            onBytesUpdated = onUpdate,
        )
        Format.PROTOBUF -> ProtobufEditor(
            bytes = bytes,
            onBytesUpdated = onUpdate,
        )
        Format.SQLITE -> SqliteEditor(
            ctx = ctx,
            initialBytes = bytes,
            onBytesUpdated = onUpdate,
        )
        Format.BINARY -> HexViewer(bytes)
    }
}

private fun Format.label(): String = when (this) {
    Format.TEXT -> "text"
    Format.JSON -> "JSON"
    Format.XML -> "XML"
    Format.NRBF -> ".NET NRBF"
    Format.PROTOBUF -> "Protobuf"
    Format.SQLITE -> "SQLite DB"
    Format.BINARY -> "binary (hex)"
}
