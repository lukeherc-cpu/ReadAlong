package com.example.readalong.desktop.core

import com.example.readalong.compat.*
import com.example.readalong.desktop.platform.toByteArray
import com.example.readalong.domain.audio.AudioChapter
import com.example.readalong.domain.audio.Id3Chapters
import com.example.readalong.domain.audio.Mp4Chapters
import java.io.File
import java.nio.channels.FileChannel
import kotlinx.cinterop.*
import platform.AVFoundation.*
import platform.CoreMedia.CMTimeGetSeconds
import platform.Foundation.NSData
import platform.Foundation.NSURL

data class ProbedFile(val file: File, val durationMs: Long, val tags: Map<String, String>, val chapters: List<AudioChapter>, val cover: ByteArray?)

/**
 * Length, tags, chapters and embedded artwork of one audio file, through AVFoundation. Tags use the desktop's
 * FFmpeg names (title, album, artist, album_artist, composer); chapters use the phone's own MP4 and ID3 readers.
 */
object MediaProbe {
    fun probe(file: File, wantCover: Boolean): ProbedFile {
        val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(file.absolutePath), options = null)
        var duration = (CMTimeGetSeconds(asset.duration) * 1000).let { if (it.isNaN()) 0L else it.toLong() }
        val tags = HashMap<String, String>()
        var cover: ByteArray? = null
        val items = (asset.commonMetadata + asset.metadata).filterIsInstance<AVMetadataItem>()
        for (item in items) {
            val key = item.commonKey ?: (item.key as? String) ?: continue
            when (key) {
                AVMetadataCommonKeyArtwork, "covr", "APIC" -> if (wantCover && cover == null) cover = (item.dataValue ?: item.value as? NSData)?.toByteArray()
                else -> {
                    val value = item.stringValue?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                    val name = when (key) {
                        AVMetadataCommonKeyTitle, "©nam", "TIT2" -> "title"
                        AVMetadataCommonKeyAlbumName, "©alb", "TALB" -> "album"
                        AVMetadataCommonKeyArtist, "©ART", "TPE1" -> "artist"
                        "aART", "TPE2" -> "album_artist"
                        AVMetadataCommonKeyCreator, "©wrt", "TCOM" -> "composer"
                        AVMetadataCommonKeyDescription, "desc", "©cmt", "COMM" -> "comment"
                        else -> continue
                    }
                    tags.putIfAbsent(name, value)
                }
            }
        }
        val chapters = runCatching {
            FileChannel.open(file, readOnly = true).use { channel ->
                when (file.extension.lowercase()) {
                    "m4b", "m4a", "mp4", "aac" -> {
                        Mp4Chapters.duration(channel)?.let { duration = it }
                        Mp4Chapters.read(channel, duration)
                    }
                    "mp3" -> Id3Chapters.read(channel, duration)
                    else -> emptyList()
                }
            }
        }.getOrDefault(emptyList())
        return ProbedFile(file, duration, tags, chapters, cover)
    }
}
