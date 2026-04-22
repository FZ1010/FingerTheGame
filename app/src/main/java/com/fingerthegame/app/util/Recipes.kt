package com.fingerthegame.app.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-game preset recipes. The actual product: users want a "Max Money in Real War"
 * button, not an NRBF byte-offset editor. Recipes encode "given an installed
 * package, edit these files in these ways" so the parser, diff, and section
 * browser become substrate authors hand-roll on top of, not the user-facing thing.
 *
 * Recipes ship bundled in `assets/recipes/<package>.json` for v2.0; later
 * versions can fetch from a community-curated GitHub registry.
 */

/** A single value to write into a save's NRBF/Protobuf field. */
data class RecipeEdit(
    /** Substring match against the field's `displayName` (case-insensitive).
     *  Has to be unique enough to identify the field; recipe authors pick. */
    val matchDisplayName: String,
    /** Optional: also constrain to this className substring. Useful when
     *  a wrapper field name like "value" is too generic on its own. */
    val matchClassName: String? = null,
    /** New value as a string. Coerced to the field's type at apply time. */
    val value: String,
)

/** A file inside the target package's data dir, plus the edits to apply to it. */
data class RecipeFile(
    /** Path relative to `/sdcard/Android/data/<package>/`. */
    val relativePath: String,
    /** Expected format ("NRBF" supported in v2.0; PROTOBUF in v2.1). */
    val format: String,
    val edits: List<RecipeEdit>,
)

/** A single applicable preset (e.g. "Max Money"). */
data class Recipe(
    val id: String,
    val title: String,
    val description: String?,
    val files: List<RecipeFile>,
)

/** All recipes for one game (matched by package name). */
data class GameRecipes(
    val pkg: String,
    val label: String,
    val recipes: List<Recipe>,
)

/** Outcome of applying a recipe — surfaced to the user via snackbar. */
data class RecipeApplyResult(
    val applied: Int,
    val skipped: Int,
    val rejected: Int,
    val messages: List<String>,
) {
    val ok: Boolean get() = rejected == 0 && applied > 0
    val summary: String get() = when {
        ok && skipped == 0 -> "Applied $applied edit${if (applied == 1) "" else "s"}"
        ok -> "Applied $applied · $skipped skipped"
        applied > 0 -> "Applied $applied · $rejected rejected — see log"
        else -> "Recipe didn't match — ${messages.firstOrNull() ?: "no edits applied"}"
    }
}

object RecipeRegistry {
    /** Read every recipe JSON shipped in the APK's assets/recipes/ directory. */
    fun loadBundled(ctx: Context): List<GameRecipes> {
        val names = runCatching { ctx.assets.list("recipes") ?: emptyArray() }
            .getOrDefault(emptyArray())
        return names.filter { it.endsWith(".json") }.mapNotNull { name ->
            runCatching {
                val text = ctx.assets.open("recipes/$name").bufferedReader().use { it.readText() }
                parse(JSONObject(text))
            }.getOrNull()
        }
    }

    private fun parse(o: JSONObject): GameRecipes {
        val pkg = o.getString("package")
        val label = o.getString("label")
        val recipes = parseArray(o.getJSONArray("recipes"), ::parseRecipe)
        return GameRecipes(pkg, label, recipes)
    }

    private fun parseRecipe(o: JSONObject): Recipe = Recipe(
        id = o.getString("id"),
        title = o.getString("title"),
        description = o.optString("description").ifBlank { null },
        files = parseArray(o.getJSONArray("files"), ::parseFile),
    )

    private fun parseFile(o: JSONObject): RecipeFile = RecipeFile(
        relativePath = o.getString("relativePath"),
        format = o.optString("format", "NRBF"),
        edits = parseArray(o.getJSONArray("edits"), ::parseEdit),
    )

    private fun parseEdit(o: JSONObject): RecipeEdit = RecipeEdit(
        matchDisplayName = o.getString("matchDisplayName"),
        matchClassName = o.optString("matchClassName").ifBlank { null },
        value = o.getString("value"),
    )

    private fun <T> parseArray(arr: JSONArray, of: (JSONObject) -> T): List<T> =
        (0 until arr.length()).map { of(arr.getJSONObject(it)) }
}

/**
 * Engine that executes a recipe against the device. Pure-IO function;
 * call from a coroutine on Dispatchers.IO.
 */
object RecipeEngine {
    fun apply(ctx: Context, pkg: String, recipe: Recipe): RecipeApplyResult {
        var applied = 0; var skipped = 0; var rejected = 0
        val msgs = mutableListOf<String>()

        val storage = currentStorage
        for (file in recipe.files) {
            val absPath = "/sdcard/Android/data/$pkg/${file.relativePath}"
            val readResult = runCatching { storage.read(absPath) }
            if (readResult.isFailure) {
                rejected++; msgs += "${file.relativePath}: read failed (${readResult.exceptionOrNull()?.message ?: "?"})"
                continue
            }
            val raw = readResult.getOrThrow()
            val unwrapped = FormatDetect.unwrap(raw)
            if (file.format.equals("NRBF", true) && unwrapped.format != Format.NRBF) {
                skipped++; msgs += "${file.relativePath}: format ${unwrapped.format}, not NRBF — skipped"
                continue
            }

            val doc = NrbfDocument(unwrapped.effective)
            val patches = mutableMapOf<Int, Any>()
            for (edit in file.edits) {
                val match = doc.fields.firstOrNull { f ->
                    f.displayName.contains(edit.matchDisplayName, ignoreCase = true) &&
                    (edit.matchClassName == null ||
                     f.className.contains(edit.matchClassName, ignoreCase = true))
                }
                if (match == null) {
                    skipped++; msgs += "${file.relativePath}: '${edit.matchDisplayName}' not found"
                } else {
                    patches[match.offset] = edit.value
                }
            }

            if (patches.isEmpty()) continue

            val result = doc.applyPatchesDetailed(patches)
            applied += patches.size - result.failures.size
            rejected += result.failures.size
            result.failures.values.forEach { msgs += "${file.relativePath}: $it" }

            if (result.failures.size == patches.size) continue   // nothing usable

            val toWrite = unwrapped.rewrap(result.bytes)
            runCatching {
                storage.backupLocal(absPath, raw, ctx.cacheDir)
                storage.validateWritePath(absPath, pkg)
                storage.forceStop(pkg)
                storage.write(absPath, toWrite)
            }.onFailure {
                rejected++; msgs += "${file.relativePath}: write failed (${it.message ?: "?"})"
            }
        }

        return RecipeApplyResult(applied, skipped, rejected, msgs)
    }
}
