package com.fingerthegame.app.nav

sealed class Screen {
    data object Home : Screen()
    data object AppPicker : Screen()
    data class FileBrowser(val pkg: String, val label: String, val path: String) : Screen()
    data class FileViewer(val pkg: String, val label: String, val path: String) : Screen()
}
