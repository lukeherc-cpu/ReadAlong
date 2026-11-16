package com.example.readalong

import android.content.Intent
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.readalong.ui.widget.WidgetActionReceiver
import com.example.readalong.ui.widget.Widgets
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Every home screen widget draws, as the launcher would show it, and its buttons are safe with nothing to play. */
class WidgetsTest {
    private val app get() = ApplicationProvider.getApplicationContext<ReadAlongApplication>()

    @Test fun everyWidgetDraws() {
        val views = runBlocking { Widgets.build(app, Widgets.kinds.toSet()) }
        assertEquals(Widgets.kinds.size, views.size)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            views.forEach { (kind, remote) ->
                val shown = remote.apply(app, FrameLayout(app))
                assertNotNull("${kind.simpleName} didn't draw", shown)
            }
            val nowPlaying = views.getValue(com.example.readalong.ui.widget.NowPlayingWidget::class.java).apply(app, FrameLayout(app))
            assert(nowPlaying.findViewById<TextView>(R.id.widget_title).text.isNotEmpty())
        }
    }

    @Test fun buttonsDoNotCrash() {
        listOf(WidgetActionReceiver.SLEEP, WidgetActionReceiver.SLEEP).forEach { action ->
            app.sendBroadcast(Intent(app, WidgetActionReceiver::class.java).setAction(action))
        }
        Thread.sleep(1_500)
        runBlocking { Widgets.update(app) }
    }
}
