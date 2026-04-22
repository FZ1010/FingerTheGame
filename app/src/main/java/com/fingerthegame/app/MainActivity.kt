package com.fingerthegame.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.fingerthegame.app.nav.Screen
import com.fingerthegame.app.screens.AppPickerScreen
import com.fingerthegame.app.screens.BackupsScreen
import com.fingerthegame.app.screens.ConsentScreen
import com.fingerthegame.app.screens.FileBrowserScreen
import com.fingerthegame.app.screens.FileViewerScreen
import com.fingerthegame.app.screens.HomeScreen
import com.fingerthegame.app.util.Ethics
import com.fingerthegame.app.util.RecentEntry
import com.fingerthegame.app.util.RecipeEngine
import com.fingerthegame.app.util.ShizukuExec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    private val tick = mutableIntStateOf(0)
    private val permListener = Shizuku.OnRequestPermissionResultListener { code, _ ->
        if (code == ShizukuExec.PERM_REQ_CODE) tick.intValue++
    }
    private val binderListener = Shizuku.OnBinderReceivedListener { tick.intValue++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(permListener)
        Shizuku.addBinderReceivedListener(binderListener)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot(externalTick = tick.intValue)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(permListener)
        Shizuku.removeBinderReceivedListener(binderListener)
    }
}

@Composable
private fun AppRoot(externalTick: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    val stack = remember {
        mutableStateListOf<Screen>(if (Ethics.hasConsented(ctx)) Screen.Home else Screen.Consent)
    }
    val status by remember(externalTick) { mutableStateOf(ShizukuExec.status()) }

    // Cheap installed-package lookup so the home recipes section can hide
    // recipes for games the user doesn't have.
    val installedPackages by produceState<Set<String>>(initialValue = emptySet(), externalTick) {
        value = withContext(Dispatchers.IO) {
            ctx.packageManager.getInstalledApplications(0).map { it.packageName }.toSet()
        }
    }

    BackHandler(enabled = stack.size > 1) { stack.removeAt(stack.lastIndex) }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHost) }) { inner ->
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (val top = stack.last()) {
                Screen.Consent -> ConsentScreen(onAccept = {
                    Ethics.grantConsent(ctx)
                    stack.clear()
                    stack.add(Screen.Home)
                })
                Screen.Home -> HomeScreen(
                    status = status,
                    installedPackages = installedPackages,
                    onRequestPermission = { ShizukuExec.requestPermission() },
                    onRecheck = { /* status already recomputes via externalTick */ },
                    onPickApp = { stack.add(Screen.AppPicker) },
                    onOpenRecent = { entry -> stack.add(Screen.FileViewer(entry.pkg, entry.label, entry.path)) },
                    onApplyRecipe = { pkg, recipe ->
                        scope.launch {
                            snackbarHost.showSnackbar("Applying ${recipe.title}…", withDismissAction = true)
                            val result = withContext(Dispatchers.IO) {
                                RecipeEngine.apply(ctx, pkg, recipe)
                            }
                            snackbarHost.showSnackbar(result.summary, withDismissAction = true)
                        }
                    },
                    onOpenBackups = { stack.add(Screen.Backups) },
                )
                Screen.Backups -> BackupsScreen(onBack = { stack.removeAt(stack.lastIndex) })
                Screen.AppPicker -> AppPickerScreen(
                    onBack = { stack.removeAt(stack.lastIndex) },
                    onPick = { pkg, label, root ->
                        stack.add(Screen.FileBrowser(pkg, label, root))
                    },
                )
                is Screen.FileBrowser -> FileBrowserScreen(
                    pkg = top.pkg,
                    label = top.label,
                    initialPath = top.path,
                    onBack = { stack.removeAt(stack.lastIndex) },
                    onOpenFile = { path -> stack.add(Screen.FileViewer(top.pkg, top.label, path)) },
                )
                is Screen.FileViewer -> FileViewerScreen(
                    pkg = top.pkg,
                    label = top.label,
                    path = top.path,
                    onBack = { stack.removeAt(stack.lastIndex) },
                )
            }
        }
    }
}
