package com.example.readalong.domain.update

import org.json.JSONObject

/**
 * Version rules for the files the phone and desktop apps write and share (transcripts, listening progress,
 * the desktop library), so an older app can still read what a newer one wrote, and the other way round.
 *
 * Each file carries "version" (the format it was written in). A newer writer that only adds fields keeps
 * writing the old version number, because apps already released accept only that number. When a format
 * really changes, the writer bumps "version" and sets "minReaderVersion" to the oldest format a reader may
 * know and still read the file correctly by ignoring the fields it doesn't recognise.
 */
object FormatVersion {
    /** Whether a reader that knows formats up to [known] can read [json]. Files written before versions existed count as 1. */
    fun readable(json: JSONObject, known: Int): Boolean {
        val version = json.optInt("version", 1)
        if (version in 1..known) return true
        val oldest = json.optInt("minReaderVersion", version)
        return version > known && oldest in 1..known
    }

    /** Stamps a file as format [version], readable by readers that know [minReader] or later. */
    fun stamp(json: JSONObject, version: Int, minReader: Int = version): JSONObject =
        json.put("version", version).apply { if (minReader != version) put("minReaderVersion", minReader) }
}
