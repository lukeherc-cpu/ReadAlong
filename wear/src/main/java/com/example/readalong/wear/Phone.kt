package com.example.readalong.wear

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the phone's player is doing, as the phone last sent it. */
data class PhoneState(
    val stamp: Long = 0,
    val bookId: String = "",
    val title: String = "",
    val author: String = "",
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val playing: Boolean = false,
    val speed: Float = 1f,
    val sleepEndsAt: Long = 0,
    val sleepAtChapterEnd: Boolean = false,
    val chapterIndex: Int = 0,
    val chapters: List<String> = emptyList(),
    val chapterStarts: List<Long> = emptyList(),
    val background: Int = 0xFF182A3B.toInt(),
    val accent: Int = 0xFFB9D3EA.toInt(),
    val started: List<Pair<String, String>> = emptyList(),
    val cover: Bitmap? = null,
    val connected: Boolean = false,
) {
    /** Where playback is now, moving on from the phone's last report while it plays. */
    fun positionAt(now: Long): Long = if (playing) (positionMs + ((now - stamp) * speed).toLong()).coerceIn(0, durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE) else positionMs
    val chapter get() = chapters.getOrNull(chapterIndex).orEmpty()
}

/** The phone link: reads the phone's state data item and sends buttons as messages. */
class Phone(private val context: Context, private val scope: CoroutineScope) : DataClient.OnDataChangedListener {
    private val mutable = MutableStateFlow(PhoneState())
    val state = mutable.asStateFlow()

    fun start() {
        Wearable.getDataClient(context).addListener(this)
        scope.launch(Dispatchers.IO) {
            runCatching {
                val items = Tasks.await(Wearable.getDataClient(context).dataItems)
                try { items.firstOrNull { it.uri.path == PATH_STATE }?.let { read(DataMapItem.fromDataItem(it).dataMap) } } finally { items.release() }
            }
            send("hello")
        }
    }

    fun stop() { Wearable.getDataClient(context).removeListener(this) }

    override fun onDataChanged(events: DataEventBuffer) {
        val changed = events.filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == PATH_STATE }.map { DataMapItem.fromDataItem(it.dataItem).dataMap }
        events.release()
        changed.lastOrNull()?.let { map -> scope.launch(Dispatchers.IO) { read(map) } }
    }

    private fun read(map: DataMap) {
        val cover = map.getAsset("cover")?.let { asset ->
            runCatching { Tasks.await(Wearable.getDataClient(context).getFdForAsset(asset)).inputStream.use { BitmapFactory.decodeStream(it) } }.getOrNull()
        }
        val ids = map.getStringArray("startedIds")?.toList().orEmpty()
        val titles = map.getStringArray("startedTitles")?.toList().orEmpty()
        mutable.value = PhoneState(
            stamp = map.getLong("stamp"), bookId = map.getString("bookId", ""), title = map.getString("title", ""), author = map.getString("author", ""),
            positionMs = map.getLong("positionMs"), durationMs = map.getLong("durationMs"), playing = map.getBoolean("playing"),
            speed = map.getFloat("speed", 1f), sleepEndsAt = map.getLong("sleepEndsAt"), sleepAtChapterEnd = map.getBoolean("sleepAtChapterEnd"),
            chapterIndex = map.getInt("chapterIndex"), chapters = map.getStringArray("chapters")?.toList().orEmpty(),
            chapterStarts = map.getLongArray("chapterStarts")?.toList().orEmpty(),
            background = map.getInt("background", 0xFF182A3B.toInt()), accent = map.getInt("accent", 0xFFB9D3EA.toInt()),
            started = ids.zip(titles), cover = cover, connected = true)
    }

    /** Sends a button to the phone: toggle, back, forward, speed, sleep, chapter, book, volume or hello. */
    fun send(command: String, data: String = "") {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val nodes = Tasks.await(Wearable.getNodeClient(context).connectedNodes)
                if (nodes.isEmpty()) mutable.value = mutable.value.copy(connected = false)
                nodes.forEach { node -> Tasks.await(Wearable.getMessageClient(context).sendMessage(node.id, PATH_COMMAND + command, data.toByteArray())) }
            }
        }
    }

    companion object {
        const val PATH_STATE = "/readalong/state"
        const val PATH_COMMAND = "/readalong/command/"
    }
}
