package com.example.readalong.data.link

import android.content.Context
import com.example.readalong.domain.link.FolderSyncPlan
import com.example.readalong.domain.link.SyncedState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64

/** The computer this phone is linked to, and where it was last reached on the home network. */
data class LinkedComputer(val id: String, val name: String, val key: ByteArray, val hosts: List<String>, val port: Int, val linkedAt: Long)

/** A folder on the computer kept in step with one on this phone (a document tree it has access to). */
data class FolderPair(val remoteId: String, val remoteName: String, val localUri: String, val localName: String)

data class LinkState(
    val phoneId: String,
    val computer: LinkedComputer? = null,
    val pairs: List<FolderPair> = emptyList(),
    /** False until "Sync your audiobook folders?" has been answered for this link. */
    val foldersAsked: Boolean = false,
    val lastSyncAt: Long? = null,
    /** A one-line outcome of the last sync, for Settings. */
    val lastResult: String? = null,
)

/** Kept as small JSON files in the app's private storage: link.json, and one record of synced files per folder pair. */
class LinkStore(context: Context) {
    private val directory = File(context.filesDir, "link").apply { mkdirs() }
    private val file = File(directory, "link.json")
    private val mutable = MutableStateFlow(load())
    val state: StateFlow<LinkState> = mutable
    val value get() = mutable.value

    @Synchronized fun update(change: (LinkState) -> LinkState) {
        mutable.update(change)
        save(mutable.value)
    }

    fun unlink() {
        update { LinkState(it.phoneId) }
        directory.listFiles { f -> f.name.startsWith("synced-") }?.forEach { it.delete() }
    }

    fun synced(pair: FolderPair): Map<String, SyncedState> = syncedFile(pair).takeIf { it.isFile }?.let { FolderSyncPlan.decode(it.readText()) }.orEmpty()
    fun saveSynced(pair: FolderPair, state: Map<String, SyncedState>) = writeAtomically(syncedFile(pair), FolderSyncPlan.encode(state))
    private fun syncedFile(pair: FolderPair) = File(directory, "synced-" + com.example.readalong.domain.link.LinkChannel.keyId((pair.remoteId + "\n" + pair.localUri).toByteArray()) + ".json")

    private fun save(s: LinkState) {
        val computer = s.computer?.let { c ->
            JSONObject().put("id", c.id).put("name", c.name).put("key", Base64.getEncoder().encodeToString(c.key))
                .put("hosts", JSONArray(c.hosts)).put("port", c.port).put("linkedAt", c.linkedAt)
        }
        writeAtomically(file, JSONObject().put("phoneId", s.phoneId).put("computer", computer ?: JSONObject.NULL)
            .put("pairs", JSONArray(s.pairs.map { JSONObject().put("remoteId", it.remoteId).put("remoteName", it.remoteName).put("localUri", it.localUri).put("localName", it.localName) }))
            .put("foldersAsked", s.foldersAsked).put("lastSyncAt", s.lastSyncAt ?: JSONObject.NULL).put("lastResult", s.lastResult ?: JSONObject.NULL).toString())
    }

    private fun load(): LinkState {
        val fresh = LinkState(com.example.readalong.domain.link.LinkChannel.newId())
        if (!file.isFile) return fresh.also(::save)
        return runCatching {
            val o = JSONObject(file.readText())
            val computer = o.optJSONObject("computer")?.let { c ->
                val hosts = c.getJSONArray("hosts")
                LinkedComputer(c.getString("id"), c.getString("name"), Base64.getDecoder().decode(c.getString("key")),
                    List(hosts.length()) { hosts.getString(it) }, c.getInt("port"), c.optLong("linkedAt"))
            }
            val pairs = o.optJSONArray("pairs") ?: JSONArray()
            LinkState(o.getString("phoneId"), computer,
                List(pairs.length()) { i -> pairs.getJSONObject(i).let { FolderPair(it.getString("remoteId"), it.getString("remoteName"), it.getString("localUri"), it.getString("localName")) } },
                o.optBoolean("foldersAsked"), if (o.isNull("lastSyncAt")) null else o.optLong("lastSyncAt"), if (o.isNull("lastResult")) null else o.optString("lastResult"))
        }.getOrDefault(fresh)
    }

    private fun writeAtomically(target: File, text: String) {
        val temporary = File(target.parentFile, target.name + ".tmp")
        temporary.writeText(text)
        if (!temporary.renameTo(target)) { target.delete(); temporary.renameTo(target) }
    }
}
