package com.example.readalong.ui.about

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.readalong.ui.launch.LaunchPalettes

@Composable
fun BasicColorPicker(initial: Long, close: () -> Unit, save: (Long) -> Unit) {
    var selected by remember { mutableStateOf(LaunchPalettes.closest(initial).color) }
    AlertDialog(onDismissRequest = close, title = { Text("Accent color") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Choose a matching color for the app, launcher icon and opening animation.", style = MaterialTheme.typography.bodySmall)
            LaunchPalettes.all.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { palette ->
                        FilterChip(selected = selected == palette.color, onClick = { selected = palette.color }, modifier = Modifier.weight(1f),
                            leadingIcon = { Box(Modifier.size(16.dp).background(Color(palette.color), CircleShape)) }, label = { Text(palette.name) })
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { save(selected) }) { Text("Set") } }, dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
}
