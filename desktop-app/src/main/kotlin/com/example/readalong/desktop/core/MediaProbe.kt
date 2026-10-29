package com.example.readalong.desktop.core

import com.example.readalong.domain.audio.AudioChapter
import com.example.readalong.domain.audio.Id3Chapters
import com.example.readalong.domain.audio.Mp4Chapters
import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

data class ProbedFile(val file: File, val durationMs: Long, val tags: Map<String, String>, val chapters: List<AudioChapter>, val cover: ByteArray?)

/** Length, tags, chapters and embedded artwork of one audio file. Chapters use the phone's own MP4 and ID3 readers. */
object MediaProbe {
    init { avutil.av_log_set_level(avutil.AV_LOG_ERROR) }

    fun probe(file: File, wantCover: Boolean): ProbedFile {
        val grabber = FFmpegFrameGrabber(file)
        try {
            grabber.start()
            val tags = grabber.metadata.orEmpty().mapKeys { it.key.lowercase() }
            var duration = grabber.lengthInTime / 1000
            var cover: ByteArray? = null
            if (wantCover) {
                val format = grabber.formatContext
                for (i in 0 until format.nb_streams()) {
                    val stream = format.streams(i)
                    if (stream.disposition() and avformat.AV_DISPOSITION_ATTACHED_PIC != 0) {
                        val packet = stream.attached_pic()
                        if (packet.size() > 0) cover = ByteArray(packet.size()).also { packet.data().get(it) }
                        break
                    }
                }
            }
            val chapters = FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
                when (file.extension.lowercase()) {
                    "m4b", "m4a", "mp4", "aac" -> {
                        Mp4Chapters.duration(channel)?.let { duration = it }
                        Mp4Chapters.read(channel, duration)
                    }
                    "mp3" -> Id3Chapters.read(channel, duration)
                    else -> emptyList()
                }
            }
            return ProbedFile(file, duration, tags, chapters, cover)
        } finally {
            runCatching { grabber.stop() }; runCatching { grabber.release() }
        }
    }
}
