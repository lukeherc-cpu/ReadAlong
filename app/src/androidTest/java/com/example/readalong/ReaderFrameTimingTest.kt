package com.example.readalong

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/** Real vsync clock, without Compose's test animation clock. Comparable on 0.23 and 0.24. */
class ReaderFrameTimingTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (predicate(node)) return node
            for (i in 0 until node.childCount) visit(node.getChild(i))?.let { return it }
            return null
        }
        return visit(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun waitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val until = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < until) {
            find(predicate)?.let { return it }
            // Android's first-use immersive-mode tutorial is a separate system window.
            find { it.text?.toString() == "Got it" }?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            SystemClock.sleep(50)
        }
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        fun dump(node: AccessibilityNodeInfo?, depth: Int = 0): String {
            if (node == null) return ""
            return "  ".repeat(depth) + "${node.className} text=${node.text} desc=${node.contentDescription} visible=${node.isVisibleToUser} actions=${node.actionList}\n" +
                (0 until node.childCount).joinToString("") { dump(node.getChild(it), depth+1) }
        }
        java.io.File(app.getExternalFilesDir(null), "verification-frame-ui.txt").writeText(dump(instrumentation.uiAutomation.rootInActiveWindow))
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap -> java.io.File(app.getExternalFilesDir(null), "verification-frame-ui.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        error("UI target did not become available")
    }
    private fun click(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        while (target != null) {
            if (target.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK } && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            target = target.parent
        }
        val bounds = android.graphics.Rect(); node.getBoundsInScreen(bounds)
        val time = SystemClock.uptimeMillis()
        listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP).forEach { action ->
            val event = android.view.MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            instrumentation.uiAutomation.injectInputEvent(event, true); event.recycle()
        }
    }
    @Test fun repeatedExpansionAndMinimizationFrameTimes() {
        val app = ApplicationProvider.getApplicationContext<ReadAlongApplication>()
        val id = "demo-night-orchard"
        runBlocking {
            app.database.bookDao().insertBooks(com.example.readalong.data.book.DemoBooks.books)
            app.database.bookDao().insertProgress(com.example.readalong.data.book.DemoBooks.progress)
            app.transcriptRepository.prepareDemo(id)
        }
        val thread = HandlerThread("reader-frame-metrics").apply { start() }
        val recording = AtomicBoolean(false)
        val frames = Collections.synchronizedList(mutableListOf<Double>())
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            if (recording.get()) frames.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000.0)
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            click(waitNode { it.contentDescription?.toString() == "Search library" })
            val search = waitNode { it.isEditable }
            search.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Mira") })
            click(waitNode { it.text?.toString() == "The Night Orchard" })
            waitNode { it.contentDescription?.toString() == "Collapse player" }
            scenario.onActivity { app.playback.pause(); app.playback.seek(20_000) }
            click(waitNode { it.actionList.any { action -> action.label?.toString() == "Show reading preview" } })
            SystemClock.sleep(1500)
            scenario.onActivity { it.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper)) }
            repeat(7) { iteration ->
                val expand = waitNode { it.contentDescription?.toString() == "Read in full screen" }
                recording.set(iteration > 0)
                click(expand)
                waitNode { it.contentDescription?.toString() == "Settings" }
                SystemClock.sleep(150)
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                waitNode { it.contentDescription?.toString() == "Read in full screen" }
                SystemClock.sleep(150)
                recording.set(false)
                org.junit.Assert.assertEquals(20_000L, app.playback.state.value.positionMs)
            }
            click(waitNode { it.contentDescription?.toString() == "Read in full screen" })
            waitNode { it.contentDescription?.toString() == "Settings" }
            // Semantics exists during the cached transform; capture the settled reader.
            SystemClock.sleep(1000)
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap -> java.io.File(app.getExternalFilesDir(null), "verification-reader-toolbar.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
            val sorted = synchronized(frames) { frames.toList().sorted() }
            org.junit.Assert.assertTrue("Frame metrics must be collected", sorted.isNotEmpty())
            val version = app.packageManager.getPackageInfo(app.packageName, 0).versionName
            val json = org.json.JSONObject().put("version", version).put("frames", sorted.size)
                .put("clock", "real vsync; software-rendered Pixel 9 API 37 emulator")
                .put("p50_ms", sorted[sorted.size/2]).put("p95_ms", sorted[(sorted.size*.95).toInt().coerceAtMost(sorted.lastIndex)])
                .put("over_16_67_ms", sorted.count { it > 16.67 }).put("over_33_33_ms", sorted.count { it > 33.33 })
                .put("frame_times_ms", org.json.JSONArray(sorted))
            java.io.File(app.getExternalFilesDir(null), "verification-reader-realtime-$version.json").writeText(json.toString(2))
        } finally {
            recording.set(false)
            scenario.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener); app.playback.pause() }
            scenario.close(); thread.quitSafely()
            runBlocking { app.playback.releaseBook(id); app.database.bookDao().removeSamples() }
        }
    }
}
