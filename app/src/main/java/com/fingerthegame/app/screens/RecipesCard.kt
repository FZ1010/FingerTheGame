package com.fingerthegame.app.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fingerthegame.app.util.GameRecipes
import com.fingerthegame.app.util.Recipe

/**
 * Renders the per-game recipes section on Home — only games actually
 * installed on the device get a card. Tapping a recipe goes through
 * a confirm dialog before any IO.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipesSection(
    games: List<GameRecipes>,
    installedPackages: Set<String>,
    onApply: (pkg: String, recipe: Recipe) -> Unit,
) {
    val visible = remember(games, installedPackages) {
        games.filter { it.pkg in installedPackages }
    }
    if (visible.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "One-tap recipes",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        for (game in visible) {
            GameRecipeCard(game = game, onApply = { onApply(game.pkg, it) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GameRecipeCard(game: GameRecipes, onApply: (Recipe) -> Unit) {
    var pendingConfirm by remember { mutableStateOf<Recipe?>(null) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "🎮 ${game.label}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            for (r in game.recipes) {
                Row(
                    Modifier.fillMaxWidth().clickable { pendingConfirm = r }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(r.title, style = MaterialTheme.typography.bodyMedium)
                        if (r.description != null) {
                            Text(
                                r.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    OutlinedButton(onClick = { pendingConfirm = r }) { Text("Apply") }
                }
                HorizontalDivider()
            }
        }
    }

    pendingConfirm?.let { r ->
        AlertDialog(
            onDismissRequest = { pendingConfirm = null },
            title = { Text(r.title) },
            text = {
                Column {
                    Text(r.description ?: "Apply this recipe?")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "This will force-stop ${game.label}, edit ${r.files.size} file${if (r.files.size == 1) "" else "s"}, and save. The original is backed up.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(onClick = { val toApply = r; pendingConfirm = null; onApply(toApply) }) {
                    Text("Apply")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingConfirm = null }) { Text("Cancel") }
            },
        )
    }
}
