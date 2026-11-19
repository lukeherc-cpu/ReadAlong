package com.example.readalong.ui.about

import com.example.readalong.domain.i18n.tr
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.readalong.R
import com.example.readalong.domain.transcription.TranscriptionLanguages

/** Settings → Languages: a Books tab for the language books are transcribed in, and an App tab for ReadAlong's own text. */
@Composable
internal fun LanguageSettingsPage(selected: String, initialTab: Int, onBack: () -> Unit, onChoose: (String) -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(initialTab) }
    Scaffold { insets -> Column(Modifier.fillMaxSize().padding(insets)) {
        Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), tr("Back to settings")) }
            Text(tr("Languages"), style = MaterialTheme.typography.headlineMedium)
        }
        PrimaryTabRow(tab, Modifier.padding(top = 8.dp), containerColor = MaterialTheme.colorScheme.surface) {
            Tab(tab == 0, { tab = 0 }, Modifier.testTag("languages_books"), text = { Text(tr("Book transcription")) })
            Tab(tab == 1, { tab = 1 }, Modifier.testTag("languages_app"), text = { Text(tr("App language")) })
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (tab == 0) BookLanguages(selected, onChoose) else AppLanguages()
        }
    } }
}

@Composable
private fun ColumnScope.BookLanguages(selected: String, onChoose: (String) -> Unit) {
    Note(tr("The language your audiobooks are read in. Choosing it, rather than letting ReadAlong detect it, avoids a wrong guess ") +
        tr("on a short or quiet passage. It applies to the next part of the book that's transcribed."), MaterialTheme.typography.bodyMedium)
    Choices(TranscriptionLanguages.all.map { it.code }, selected, onChoose, name = { code -> com.example.readalong.domain.i18n.Translations.languageName(code, TranscriptionLanguages.all.first { it.code == code }.name) },
        summary = { code -> if (code == TranscriptionLanguages.AUTO) "Works out the language from each part of the audio" else TranscriptionLanguages.all.first { it.code == code }.nativeName })
    Note(tr("These are the languages Cactus Whistle, the phone's speech model, understands. ReadAlong for computers uses Whisper, ") +
        tr("which knows 99 languages: transcribe a book there and link the computer to read along here."), MaterialTheme.typography.bodySmall)
}

@Composable
private fun ColumnScope.AppLanguages() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = remember(context) { generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }.firstNotNullOfOrNull { it as? android.app.Activity } }
    var current by remember { mutableStateOf(AppLanguage.current(context)) }
    Note(tr("The language of ReadAlong's menus, buttons and messages. Your books keep their own language."), MaterialTheme.typography.bodyMedium)
    Choices(listOf(AppLanguage.SYSTEM) + AppLanguage.available, current, { tag ->
        current = tag
        activity?.let { AppLanguage.set(it, tag) }
    }, name = AppLanguage::name, summary = { tag -> if (tag == AppLanguage.SYSTEM) "Follows your phone's language when ReadAlong has it" else null })
}

@Composable
private fun Choices(codes: List<String>, selected: String, onChoose: (String) -> Unit, name: (String) -> String, summary: (String) -> String?) {
    Surface(shape = RoundedCornerShape(com.example.readalong.ui.theme.Corners.large), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().selectableGroup()) {
            codes.forEach { code ->
                val chosen = code == selected
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(chosen, role = Role.RadioButton) { onChoose(code) }
                    .testTag("language_${code.ifEmpty { "system" }}").padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(tr(name(code)), style = MaterialTheme.typography.bodyLarge)
                        summary(code)?.let { Text(tr(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    RadioButton(chosen, onClick = null)
                }
            }
        }
    }
}

@Composable
private fun Note(text: String, style: androidx.compose.ui.text.TextStyle) =
    Text(tr(text), Modifier.padding(horizontal = 4.dp), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
