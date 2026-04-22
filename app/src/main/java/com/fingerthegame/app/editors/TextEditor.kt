package com.fingerthegame.app.editors

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
fun TextEditor(
    initial: String,
    onChange: (String) -> Unit,
    monospace: Boolean,
    modifier: Modifier = Modifier,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onChange(it) },
        textStyle = androidx.compose.ui.text.TextStyle(
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
        ),
        modifier = modifier.fillMaxSize().padding(8.dp),
    )
}
