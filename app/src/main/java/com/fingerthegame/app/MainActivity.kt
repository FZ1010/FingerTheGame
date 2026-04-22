package com.fingerthegame.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.fingerthegame.app.nav.Screen
import com.fingerthegame.app.screens.AppPickerScreen
import com.fingerthegame.app.screens.FileBrowserScreen
import com.fingerthegame.app.screens.FileViewerScreen
import com.fingerthegame.app.screens.HomeScreen
import com.fingerthegame.app.util.RecentEntry
import com.fingerthegame.app.util.ShizukuExec
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
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
    val status by remember(externalTick) { mutableStateOf(ShizukuExec.status()) }

    BackHandler(enabled = stack.size > 1) { stack.removeAt(stack.lastIndex) }

    when (val top = stack.last()) {
        Screen.Home -> HomeScreen(
            status = status,
            onRequestPermission = { ShizukuExec.requestPermission() },
            onRecheck = { /* status already recomputes via externalTick */ },
            onPickApp = { stack.add(Screen.AppPicker) },
            onOpenRecent = { entry -> stack.add(Screen.FileViewer(entry.pkg, entry.label, entry.path)) },
        )
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
