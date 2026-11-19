package com.example.readalong.ui.about

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.readalong.R
import com.example.readalong.domain.transcription.TranscriptionLanguages

/** Settings → Languages: the language Whistle transcribes audiobooks in, or detection. */
@Composable
internal fun LanguageSettingsPage(selected: String, onBack: () -> Unit, onChoose: (String) -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    Scaffold { insets -> Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), "Back to settings") }
            Text("Languages", style = MaterialTheme.typography.headlineMedium)
        }
        Text("The language your audiobooks are read in. Choosing it, rather than letting ReadAlong detect it, avoids a wrong guess " +
            "on a short or quiet passage. It applies to the next part of the book that's transcribed.",
            Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = RoundedCornerShape(com.example.readalong.ui.theme.Corners.large), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().selectableGroup()) {
                TranscriptionLanguages.all.forEach { language ->
                    val chosen = language.code == selected
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(chosen, role = Role.RadioButton) { onChoose(language.code) }
                        .testTag("language_${language.code}").padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(language.name, style = MaterialTheme.typography.bodyLarge)
                            val summary = if (language.code == TranscriptionLanguages.AUTO) "Works out the language from each part of the audio" else language.nativeName
                            if (summary != null) Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        RadioButton(chosen, onClick = null)
                    }
                }
            }
        }
        Text("These are the languages Cactus Whistle, the phone's speech model, understands. ReadAlong for computers uses Whisper, " +
            "which knows 99 languages: transcribe a book there and link the computer to read along here.",
            Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } }
}
