package com.example.readalong.desktop.transcription

import com.example.readalong.compat.*
import org.json.JSONArray

/** On the desktop, the transcriber process's main loop; on iOS only its device list parsing is needed (see TranscriberProcess). */
object Worker {
    fun parseDevices(json: String): List<Device> = JSONArray(json).let { a ->
        List(a.length()) { a.getJSONObject(it).let { d -> Device(d.getString("name"), d.optString("description"), d.optString("backend"), d.getString("type"), d.optLong("memoryTotal")) } }
    }
}
