package com.fingerthegame.app.editors

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
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
import com.fingerthegame.app.util.BigIntLayout
import com.fingerthegame.app.util.DecimalLayout
import com.fingerthegame.app.util.NrbfDocument
import com.fingerthegame.app.util.NrbfField
import com.fingerthegame.app.util.NrbfType
import java.math.BigDecimal
import java.math.BigInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.withContext

// Universal game-cheat vocabulary across the languages most mobile games
// ship in. No per-game stat names — those should be discovered by browsing
// the per-class sections, not hardcoded.
//
// All words are lowercased; matching is substring against the lowercased
// field name. Non-ASCII (CJK / Cyrillic) entries match identically because
// `lowercase()` is a no-op on those.
private val HOT_KEYWORDS = listOf(
    // English
    "money", "coin", "cash", "gold", "gem", "diamond", "balance", "bank",
    "wallet", "wealth", "currency", "dollar", "credit", "token", "ticket",
    "level", "xp", "exp", "experience", "score", "point", "star", "rank",
    "health", "hp", "stamina", "energy", "mana", "mp",
    "damage", "attack", "defense", "armor", "weapon",
    "premium", "vip", "unlock", "owned", "skip", "subscription",
    "ammo", "bullet", "fuel",
    // Japanese
    "金", "お金", "銭", "コイン", "ゴールド", "ジェム", "ダイヤ",
    "レベル", "経験", "体力", "攻撃", "防御",
    // Korean
    "돈", "골드", "보석", "다이아", "레벨", "경험", "체력", "공격", "방어",
    // Simplified Chinese
    "钱", "金币", "金钱", "钻石", "宝石", "等级", "经验", "生命", "攻击", "防御", "伤害",
    // Spanish
    "dinero", "oro", "moneda", "joya", "nivel", "vida", "salud", "ataque", "daño",
    // Portuguese
    "dinheiro", "ouro", "moeda", "joia", "nível", "saúde", "vida", "dano", "ataque",
    // German
    "geld", "münze", "diamant", "edelstein", "stufe", "leben", "schaden", "angriff",
    // French
    "argent", "pièce", "diamant", "niveau", "vie", "santé", "dégât", "attaque",
    // Russian
    "деньги", "золото", "монета", "алмаз", "уровень", "опыт", "здоровье", "урон", "атака",
)

private fun scoreOf(nameLower: String, type: NrbfType, value: Any?): Int {
    var s = 0
    for (k in HOT_KEYWORDS) if (nameLower.contains(k)) s += 10
    if (type != NrbfType.STRING && type != NrbfType.BOOL) s += 3
    if (nameLower.length >= 6) s += 1

    // Value-based heuristics — game counters/currencies tend to be either
    // human-round defaults (100, 1000, 5000) or large magnitudes (a player
    // who's been grinding has ~10^6 of something). These signals fire even
    // when the field name is obfuscated or in an unfamiliar language.
    val abs = magnitudeOrNull(value) ?: return s
    if (abs >= 1_000_000L) s += 6
    else if (abs >= 1_000L) s += 3
    if (looksRound(abs)) s += 4
    return s
}

private fun magnitudeOrNull(value: Any?): Long? = when (value) {
    is Number -> {
        val d = value.toDouble()
        if (d.isFinite()) kotlin.math.abs(d.toLong()) else null
    }
    is BigInteger -> value.abs().let { if (it.bitLength() <= 62) it.toLong() else Long.MAX_VALUE }
    is BigDecimal -> value.abs().toBigInteger().let { if (it.bitLength() <= 62) it.toLong() else Long.MAX_VALUE }
    else -> null
}

/** Round-looking numbers: 1, 10, 50, 100, 1000, 5000, 1_000_000, etc. — a
 *  single non-zero digit followed by any number of zeros. Catches the most
 *  obvious "human chose this default" values across cultures. */
private fun looksRound(abs: Long): Boolean {
    if (abs <= 0) return false
    val s = abs.toString()
    return s.length >= 2 && s.drop(1).all { it == '0' }
}

/** Compact emoji badges that surface why a field is interesting:
 *  🔥 keyword match (likely cheat term in any language)
 *  📈 large magnitude (>= 1M, likely currency/score counter)
 *  💯 round-number value (1000, 5000, …, a human-set default) */
private fun valueBadges(field: NrbfField): String {
    val sb = StringBuilder()
    val nameLower = field.displayName.lowercase()
    if (HOT_KEYWORDS.any { nameLower.contains(it) }) sb.append("🔥")
    val abs = magnitudeOrNull(field.originalValue)
    if (abs != null) {
        if (abs >= 1_000_000L) sb.append("📈")
        if (looksRound(abs)) sb.append("💯")
    }
    return sb.toString()
}

private data class IndexedField(
    val field: NrbfField,
    val nameLower: String,
    val classLower: String,
    val score: Int,
)

private data class Section(
    val id: String,
    val emoji: String,
    val name: String,
    val subtitle: String?,
    val fields: List<IndexedField>,
    val openByDefault: Boolean,
)

private class EditorState(
    val doc: NrbfDocument,
    val byNatural: List<IndexedField>,
    val byOffset: Map<Int, NrbfField>,
    val sections: List<Section>,
)

private fun simpleClassName(full: String): String {
    val afterDot = full.substringAfterLast('.')
    val afterPlus = afterDot.substringAfterLast('+')
    return afterPlus.ifEmpty { full }
}

private data class ClassBucket(
    val cls: String,
    val fields: List<IndexedField>,
    val totalScore: Long,
)

private fun buildSections(indexed: List<IndexedField>): List<Section> {
    val sections = mutableListOf<Section>()

    // Cross-class "hot" section: only fields with at least one universal
    // keyword match. Skip when the file has nothing matching — no point
    // showing an empty starred section.
    val hot = indexed.asSequence()
        .filter { it.score >= 10 }
        .sortedByDescending { it.score }
        .take(50)
        .toList()
    if (hot.isNotEmpty()) {
        sections += Section(
            id = "hot",
            emoji = "⭐",
            name = "Likely Cheat Targets",
            subtitle = "top ${hot.size} numeric fields with cheat-y names",
            fields = hot,
            openByDefault = true,
        )
    }

    // Lifeline for obfuscated saves: sort by raw value magnitude. When
    // names are noise (`a`, `_x`, hash strings, language we don't know)
    // the largest counters are still the cheat targets. Combined with
    // Compare→two-save diff this is enough to cheat anything.
    val byValue = indexed.asSequence()
        .mapNotNull { idx -> magnitudeOrNull(idx.field.originalValue)?.let { idx to it } }
        .filter { it.second > 0 }
        .sortedByDescending { it.second }
        .take(50)
        .map { it.first }
        .toList()
    if (byValue.isNotEmpty()) {
        // Open by default only when the keyword scorer didn't find anything —
        // that's the obfuscated-save case where this section is the user's
        // primary tool.
        sections += Section(
            id = "by-value",
            emoji = "🔢",
            name = "By Value (largest first)",
            subtitle = "top ${byValue.size} numeric fields by absolute magnitude",
            fields = byValue,
            openByDefault = hot.isEmpty(),
        )
    }

    // Group every field by its class. Order by total cheat-score so the
    // most interesting class lands on top, regardless of which game.
    val buckets = indexed.groupBy { it.field.className }
        .map { (cls, list) ->
            ClassBucket(
                cls = cls,
                fields = list.sortedByDescending { it.score },
                totalScore = list.sumOf { it.score.toLong() },
            )
        }
        .sortedWith(
            compareBy<ClassBucket> { it.cls.startsWith("System.") }
                .thenByDescending { it.totalScore }
                .thenByDescending { it.fields.size }
        )

    buckets.forEachIndexed { idx, b ->
        val isSystem = b.cls.startsWith("System.")
        val simple = simpleClassName(b.cls)
        sections += Section(
            id = "cls:${b.cls}",
            emoji = if (isSystem) "🛠" else "📦",
            name = simple,
            subtitle = if (simple == b.cls) null else b.cls,
            fields = b.fields,
            openByDefault = !isSystem && idx < 3,
        )
    }

    return sections
}

@Composable
fun NrbfFieldsEditor(
    bytes: ByteArray,
    onBytesUpdated: (ByteArray) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by produceState<EditorState?>(initialValue = null, bytes) {
        value = withContext(Dispatchers.Default) {
            val doc = NrbfDocument(bytes)
            val indexed = doc.fields.map { f ->
                val nl = f.displayName.lowercase()
                IndexedField(f, nl, f.className.lowercase(), scoreOf(nl, f.type, f.originalValue))
            }
            EditorState(
                doc = doc,
                byNatural = indexed,
                byOffset = doc.fields.associateBy { it.offset },
                sections = buildSections(indexed),
            )
        }
    }

    val s = state
    if (s == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text(
                    "Parsing ${bytes.size / 1024} KB…",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    } else {
        EditorContent(s, bytes, onBytesUpdated, modifier)
    }
}

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
private fun EditorContent(
    state: EditorState,
    originalBytes: ByteArray,
    onBytesUpdated: (ByteArray) -> Unit,
    modifier: Modifier,
) {
    var query by remember { mutableStateOf("") }
    var typeFilter by remember { mutableStateOf<NrbfType?>(null) }
    val patches = remember { mutableStateMapOf<Int, String>() }
    val expanded = remember(state) {
        mutableStateMapOf<String, Boolean>().apply {
            state.sections.forEach { put(it.id, it.openByDefault) }
        }
    }
    val listState = rememberLazyListState()

    val filteredSections by remember(state) {
        derivedStateOf {
            val q = query.trim().lowercase()
            state.sections.mapNotNull { sec ->
                // While searching, hide cross-class duplicate views so each
                // match shows up exactly once (in its real class section).
                if ((sec.id == "hot" || sec.id == "by-value") && q.isNotEmpty()) return@mapNotNull null
                val seq = sec.fields.asSequence()
                    .filter { typeFilter == null || it.field.type == typeFilter }
                    .filter { q.isEmpty() || it.nameLower.contains(q) || it.classLower.contains(q) }
                val list = seq.take(2000).toList()
                if (list.isEmpty()) null else sec.copy(fields = list)
            }
        }
    }

    val totalShown by remember(state) {
        derivedStateOf { filteredSections.sumOf { it.fields.size } }
    }

    // Re-parse means the underlying bytes changed (e.g. after a save).
    // Any in-memory patches were either just applied to disk or are now
    // pointing at stale offsets — drop them.
    LaunchedEffect(state) { patches.clear() }

    LaunchedEffect(state) {
        snapshotFlow { patches.toMap() }
            .debounce(250L)
            .collect { snap ->
                val parsed = snap.mapNotNull { (off, str) ->
                    val f = state.byOffset[off] ?: return@mapNotNull null
                    val v: Any = when (f.type) {
                        NrbfType.BOOL -> str.trim().lowercase().let { it == "true" || it == "1" }
                        NrbfType.F32, NrbfType.F64 -> str.toDoubleOrNull() ?: return@mapNotNull null
                        NrbfType.STRING -> str
                        NrbfType.BIGINT -> str.trim().toBigIntegerOrNull() ?: return@mapNotNull null
                        NrbfType.DECIMAL -> str.trim().toBigDecimalOrNull() ?: return@mapNotNull null
                        NrbfType.DATETIME -> str            // parsed in writeValue
                        NrbfType.TIMESPAN -> str            // parsed in writeValue
                        else -> str.toLongOrNull() ?: return@mapNotNull null
                    }
                    off to v
                }.toMap()
                onBytesUpdated(if (parsed.isEmpty()) originalBytes else state.doc.applyPatches(parsed))
            }
    }

    Column(modifier.fillMaxSize()) {
        SummaryRow(
            totalClasses = state.doc.classCounts.size,
            totalFields = state.byNatural.size,
            shown = totalShown,
            edits = patches.size,
            onClearEdits = { patches.clear() },
        )

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search field or class name…") },
            singleLine = true,
            trailingIcon = if (query.isNotEmpty()) {
                { TextButton(onClick = { query = "" }) { Text("clear") } }
            } else null,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        )

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TypeChip("all", typeFilter == null) { typeFilter = null }
            TypeChip("int", typeFilter in INT_TYPES) {
                typeFilter = if (typeFilter in INT_TYPES) null else NrbfType.I32
            }
            TypeChip("float", typeFilter in FLOAT_TYPES) {
                typeFilter = if (typeFilter in FLOAT_TYPES) null else NrbfType.F32
            }
            TypeChip("bool", typeFilter == NrbfType.BOOL) {
                typeFilter = if (typeFilter == NrbfType.BOOL) null else NrbfType.BOOL
            }
            TypeChip("string", typeFilter == NrbfType.STRING) {
                typeFilter = if (typeFilter == NrbfType.STRING) null else NrbfType.STRING
            }
        }

        if (state.doc.parseError != null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.padding(8.dp),
            ) {
                Text(
                    "Parser bailed partway: ${state.doc.parseError}. Showing what we got.",
                    Modifier.padding(8.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        val searching = query.trim().isNotEmpty()
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            filteredSections.forEach { sec ->
                val isOpen = searching || expanded[sec.id] != false
                item(key = "h:${sec.id}", contentType = "header") {
                    SectionHeader(sec, isOpen) { expanded[sec.id] = !isOpen }
                }
                if (isOpen) {
                    items(
                        items = sec.fields,
                        // Composite key: same field can show up in hot,
                        // by-value, and its real class section.
                        key = { "${sec.id}|${it.field.id}" },
                        contentType = { it.field.type },
                    ) { idx ->
                        FieldRow(
                            field = idx.field,
                            currentInput = patches[idx.field.offset],
                            onEdit = { v -> patches[idx.field.offset] = v },
                            onClear = { patches.remove(idx.field.offset) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

private val INT_TYPES = setOf(NrbfType.I16, NrbfType.U16, NrbfType.I32, NrbfType.U32,
                              NrbfType.I64, NrbfType.U64, NrbfType.BYTE, NrbfType.SBYTE)
private val FLOAT_TYPES = setOf(NrbfType.F32, NrbfType.F64)

@Composable
private fun TypeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun SummaryRow(
    totalClasses: Int,
    totalFields: Int,
    shown: Int,
    edits: Int,
    onClearEdits: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$totalFields fields / $totalClasses classes — showing $shown",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
        if (edits > 0) {
            AssistChip(
                onClick = onClearEdits,
                label = { Text("clear $edits edit${if (edits == 1) "" else "s"}") },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SectionHeader(sec: Section, expanded: Boolean, onToggle: () -> Unit) {
    Surface(
        onClick = onToggle,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(sec.emoji, fontSize = 18.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    sec.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                if (sec.subtitle != null) {
                    Text(
                        sec.subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                    )
                }
            }
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    "${sec.fields.size}",
                    Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(if (expanded) "▼" else "▶", fontSize = 12.sp)
        }
    }
}

@Composable
private fun FieldRow(
    field: NrbfField,
    currentInput: String?,
    onEdit: (String) -> Unit,
    onClear: () -> Unit,
) {
    val origStr = remember(field) { field.originalValue.toString() }
    val displayCur = remember(field) {
        when (field.type) {
            NrbfType.STRING -> "\"${(field.originalValue as String).take(40)}\""
            else -> origStr
        }
    }
    val offsetText = remember(field) { "@0x${field.offset.toString(16)}" }
    val changed = currentInput != null && currentInput != origStr

    var userExpanded by remember(field) { mutableStateOf(false) }
    val expanded = userExpanded || currentInput != null

    val bg = if (changed) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent

    val badges = remember(field) { valueBadges(field) }

    Column(
        Modifier.fillMaxWidth()
            .background(bg)
            .clickable { userExpanded = !userExpanded }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        // Compact header: name | current value | type. The class+offset
        // line is power-user noise — only show when the row is expanded.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                field.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            if (badges.isNotEmpty()) {
                Text(badges, fontSize = 12.sp, modifier = Modifier.padding(end = 6.dp))
            }
            Text(
                if (changed) "$displayCur → ${currentInput ?: ""}" else displayCur,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = if (changed) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Spacer(Modifier.width(6.dp))
            TypeBadge(field.type)
        }

        if (expanded) {
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    offsetText,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
                Text(
                    field.className,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                if (changed) {
                    TextButton(
                        onClick = onClear,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp),
                    ) {
                        Text("× undo", fontSize = 11.sp)
                    }
                }
            }

            if (field.type != NrbfType.STRING) {
                OutlinedTextField(
                    value = currentInput ?: "",
                    onValueChange = { if (it.isEmpty()) onClear() else onEdit(it) },
                    placeholder = { Text("new value") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = when (field.type) {
                            NrbfType.BOOL -> KeyboardType.Text
                            NrbfType.F32, NrbfType.F64, NrbfType.DECIMAL -> KeyboardType.Decimal
                            NrbfType.DATETIME, NrbfType.TIMESPAN -> KeyboardType.Text
                            else -> KeyboardType.Number   // BIGINT also wants the number pad
                        }
                    ),
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                )
                QuickFillButtons(field, currentInput, onEdit)
            } else {
                Text(
                    "(string editing not supported — would shift later offsets)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickFillButtons(field: NrbfField, currentInput: String?, onEdit: (String) -> Unit) {
    FlowRow(
        Modifier.padding(top = 4.dp).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (field.type == NrbfType.BOOL) {
            val cur = parseBool(currentInput) ?: (field.originalValue as? Boolean) ?: false
            QuickButton(if (cur) "→ false" else "→ true") { onEdit(if (cur) "false" else "true") }
        } else if (field.type == NrbfType.DATETIME || field.type == NrbfType.TIMESPAN) {
            // Date/time fields don't get arithmetic chips — they have their
            // own MAX (sentinel max date / 24h span).
            QuickButton("MAX") { onEdit(maxValueFor(field)) }
        } else {
            QuickButton("MAX") { onEdit(maxValueFor(field)) }
            QuickButton("999") { onEdit("999") }
            QuickButton("100") { onEdit("100") }
            QuickButton("+10") { onEdit(plusTen(field, currentInput)) }
            QuickButton("0") { onEdit("0") }
        }
    }
}

/**
 * Adds 10 to whatever the user has typed (or to the original value if they
 * haven't typed anything). Uses BigInteger so very large counters don't lose
 * precision via Double.
 */
private fun plusTen(field: NrbfField, currentInput: String?): String {
    val typed = currentInput?.trim()
    val base: BigInteger = when {
        typed != null -> typed.toBigIntegerOrNull()
            ?: typed.toDoubleOrNull()?.toLong()?.let(BigInteger::valueOf)
            ?: BigInteger.ZERO
        field.originalValue is BigInteger -> field.originalValue
        field.originalValue is Number -> BigInteger.valueOf(field.originalValue.toLong())
        else -> BigInteger.ZERO
    }
    val next = base.add(BigInteger.TEN)
    return if (field.type in FLOAT_TYPES) next.toDouble().toString() else next.toString()
}

private fun parseBool(s: String?): Boolean? = when (s?.trim()?.lowercase()) {
    "true", "1" -> true
    "false", "0" -> false
    else -> null
}

@Composable
private fun QuickButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        modifier = Modifier.height(28.dp),
    ) {
        Text(label, fontSize = 11.sp)
    }
}

private fun maxValueFor(f: NrbfField): String = when (f.type) {
    NrbfType.BOOL -> "1"
    NrbfType.BYTE -> "255"
    NrbfType.SBYTE -> "127"
    NrbfType.I16 -> Short.MAX_VALUE.toString()
    NrbfType.U16 -> "65535"
    NrbfType.I32, NrbfType.U32 -> Int.MAX_VALUE.toString()
    NrbfType.I64, NrbfType.U64 -> Long.MAX_VALUE.toString()
    NrbfType.F32, NrbfType.F64 -> "9999999"
    NrbfType.BIGINT -> bigIntMaxFor(f).toString()
    NrbfType.DECIMAL -> decimalMaxFor(f).toString()
    NrbfType.DATETIME -> "9999-12-31T23:59:59Z"
    NrbfType.TIMESPAN -> "PT24H"
    NrbfType.STRING -> "0"
}

/**
 * MAX for a BigInteger has to respect the original allocation — writing a
 * value larger than what `_bits` can hold would shift later byte offsets.
 * If `_bits` was empty (sign-only), Int32.MAX is the ceiling.
 */
private fun bigIntMaxFor(f: NrbfField): BigInteger {
    val layout = f.meta as? BigIntLayout
        ?: return BigInteger.valueOf(Int.MAX_VALUE.toLong())
    return if (layout.bitsLength == 0) {
        BigInteger.valueOf(Int.MAX_VALUE.toLong())
    } else {
        // 2^(bitsLength * 32) - 1, the largest unsigned value that fits.
        BigInteger.ONE.shiftLeft(layout.bitsLength * 32).subtract(BigInteger.ONE)
    }
}

/**
 * MAX for a Decimal preserves the original scale (so editing $9.99 caps at
 * 9999.99 rather than producing a scaleless 9999999). The mantissa max is
 * 2^96 - 1 ≈ 7.9 × 10^28, but exposing the full ceiling spooks users —
 * use 10^15 as a friendly cap, then divide by 10^scale.
 */
private fun decimalMaxFor(f: NrbfField): BigDecimal {
    val orig = f.originalValue as? BigDecimal ?: return BigDecimal("999999999")
    val scale = orig.scale()
    return BigDecimal(BigInteger.TEN.pow(15), scale)
}

@Composable
private fun TypeBadge(type: NrbfType) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            type.name.lowercase(),
            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}
