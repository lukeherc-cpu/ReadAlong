package com.example.readalong.ui.reading

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp

/** A quiet placeholder for pages that have not arrived from local storage yet. */
@Composable
internal fun PassageSkeleton() {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("passage_skeleton")
        .clearAndSetSemantics { contentDescription = "Loading reading text" },
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(1f, .94f, .62f).forEach { width ->
            Box(Modifier.fillMaxWidth(width).height(12.dp)
                .background(MaterialTheme.colorScheme.onBackground.copy(alpha = .09f), RoundedCornerShape(6.dp)))
        }
    }
}
