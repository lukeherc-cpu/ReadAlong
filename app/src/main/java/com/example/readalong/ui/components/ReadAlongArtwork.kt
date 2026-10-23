package com.example.readalong.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.readalong.R

/** Paper illustrations isolated from the supplied designs, in the logo's blue palette. */
@Composable
fun ReadAlongArtwork(resource: Int = R.drawable.art_readalong, modifier: Modifier = Modifier.fillMaxWidth().height(112.dp)) {
    Image(painterResource(resource), null, modifier)
}

@Composable
fun BackgroundWaveform() {
    val ink = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(56.dp)) {
        val heights = listOf(.2f,.5f,.9f,.6f,1f,.65f,.35f,.18f)
        val width = 96.dp.toPx()
        heights.forEachIndexed { i, h ->
            val x = (size.width - width)/2 + (i + .5f)*width/heights.size
            drawLine(ink, Offset(x, size.height*(1-h)/2), Offset(x,size.height*(1+h)/2), 5.dp.toPx(), StrokeCap.Round)
        }
    }
}
