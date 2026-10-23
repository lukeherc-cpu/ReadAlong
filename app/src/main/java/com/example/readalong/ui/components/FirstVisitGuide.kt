package com.example.readalong.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.readalong.R
import com.example.readalong.ReadAlongApplication
import com.example.readalong.ui.theme.LocalAppSettings
import kotlinx.coroutines.launch

enum class GuidePage(val title: String) {
    HOME("Your library"), PLAYER("Your player"), READER("ReadAlong controls")
}

/** Preferences suppress future visits; local dismissal prevents reopening on this visit. */
@Composable
fun FirstVisitGuide(app: ReadAlongApplication, page: GuidePage, eligible: Boolean) {
    val settings = LocalAppSettings.current
    val hidden = when (page) {
        GuidePage.HOME -> settings.homeGuideHidden
        GuidePage.PLAYER -> settings.playerGuideHidden
        GuidePage.READER -> settings.readerGuideHidden
    }
    key(page) {
        var closed by rememberSaveable { mutableStateOf(false) }
        androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { closed = false }
        val scope = rememberCoroutineScope()
        if (eligible && !hidden && !closed) FirstVisitGuideDialog(page) { dontShow ->
            if (!dontShow) closed = true
            else scope.launch { app.appPreferences.update {
                when (page) {
                    GuidePage.HOME -> it.copy(homeGuideHidden = true)
                    GuidePage.PLAYER -> it.copy(playerGuideHidden = true)
                    GuidePage.READER -> it.copy(readerGuideHidden = true)
                }
            }; closed = true }
        }
    }
}

@Composable
internal fun FirstVisitGuideDialog(page: GuidePage, finish: (Boolean) -> Unit) {
    var dontShow by rememberSaveable(page) { mutableStateOf(true) }
    IllustratedDialog(page.title, { finish(dontShow) }, actions = {
        Button(onClick = { finish(dontShow) }) { Text("Got it") }
    }) {
        when (page) {
            GuidePage.HOME -> {
                GuideItem(R.drawable.ic_add, "Add audiobooks", "Choose an audiobook folder or audio files.")
                GuideItem(R.drawable.ic_search, "Search", "Find books by title or author.")
                GuideRow("Queue", "Tap the logo to manage transcriptions.") {
                    com.example.readalong.ui.launch.PageTurnLogo(0f, Modifier.size(36.dp))
                }
                GuideItem(R.drawable.ic_bookshelf, "Library folders", "Add, rescan or remove library folders.")
                GuideItem(R.drawable.ic_settings, "Settings", "Change appearance, playback and transcription.")
            }
            GuidePage.PLAYER -> {
                GuideItem(R.drawable.ic_image, "Cover art", "Tap the cover to see ReadAlong text or your matched EPUB.")
                GuideItem(R.drawable.ic_play, "Create ReadAlong", "Create text from this audiobook to read as you listen.")
            }
            GuidePage.READER -> {
                GuideItem(R.drawable.ic_settings, "Settings", "Adjust fonts, spacing and reading appearance.")
                GuideItem(R.drawable.ic_search, "Search", "Find words in this book.")
                GuideRow("Reading mode", "Switch between listening with text and reading only.") {
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                        Row(Modifier.padding(4.dp)) {
                            Icon(painterResource(R.drawable.ic_book), null, Modifier.size(22.dp))
                            Icon(painterResource(R.drawable.ic_readalong), null, Modifier.size(22.dp))
                        }
                    }
                }
                GuideItem(R.drawable.ic_chapters, "Chapters", "Browse chapters and jump to a section.")
                GuideItem(R.drawable.ic_back, "Back", "Return to the player.")
            }
        }
        Row(Modifier.fillMaxWidth().testTag("guide_dont_show").toggleable(dontShow, role = Role.Checkbox, onValueChange = { dontShow = it }), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(dontShow, onCheckedChange = null)
            Text("Do not show this again", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun GuideItem(icon: Int, title: String, description: String) {
    GuideRow(title, description) { Icon(painterResource(icon), null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary) }
}
@Composable private fun GuideRow(title: String, description: String, icon: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.width(52.dp), contentAlignment = Alignment.Center) { icon() }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
