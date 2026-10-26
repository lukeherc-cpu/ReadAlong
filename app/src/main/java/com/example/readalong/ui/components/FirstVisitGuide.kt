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
        if (OnboardingSheets.turn("guide:${page.name}", eligible && !hidden && !closed && !OnboardingSheets.guidesPaused)) FirstVisitGuideDialog(page) { dontShow ->
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
                GuideRow("Queue", "Tap the logo to see the ReadAlongs (time-matched book transcripts) being made.") {
                    com.example.readalong.ui.launch.PageTurnLogo(0f, Modifier.size(36.dp))
                }
                GuideItem(R.drawable.ic_bookshelf, "Library folders", "Add or remove the folders your books come from.")
                GuideItem(R.drawable.ic_settings, "Settings", "Change appearance, playback and ReadAlong options.")
            }
            GuidePage.PLAYER -> {
                GuideItem(R.drawable.ic_image, "Cover art", "Tap the cover to see the text.")
                GuideItem(R.drawable.ic_play, "Make a ReadAlong", "Get this book's text on screen, highlighted as you listen.")
            }
            GuidePage.READER -> {
                GuideItem(R.drawable.ic_settings, "Settings", "Adjust fonts, spacing and reading appearance.")
                GuideItem(R.drawable.ic_search, "Search", "Find words in this book.")
                GuideRow("Reading mode", "Switch between listening along and reading on your own.") {
                    Surface(shape = RoundedCornerShape(com.example.readalong.ui.theme.Corners.medium), color = MaterialTheme.colorScheme.secondaryContainer) {
                        Row(Modifier.padding(4.dp)) {
                            Icon(painterResource(R.drawable.ic_read_only), null, Modifier.size(22.dp))
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
            Text("Don't show this again", style = MaterialTheme.typography.bodyMedium)
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
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
