/*
 * Tracklist.kt
 * Implements the Tracklist data class
 * A Tracklist stores a list of Tracks
 *
 * This file is part of
 * TRACKBOOK - Movement Recorder for Android
 *
 * Copyright (c) 2016-22 - Y20K.org
 * Licensed under the MIT-License
 * http://opensource.org/licenses/MIT
 *
 * Trackbook uses osmdroid - OpenStreetMap-Tools for Android
 * https://github.com/osmdroid/osmdroid
 */


package org.y20k.trackbook.core

import android.content.Context
import android.os.Parcelable
import android.util.Log
import androidx.annotation.Keep
import com.google.gson.annotations.Expose
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.parcelize.Parcelize
import org.y20k.trackbook.Keys

/*
 * Tracklist data class
 */
@Keep
@Parcelize
data class Tracklist (
    @Expose val tracklistFormatVersion: Int = Keys.CURRENT_TRACKLIST_FORMAT_VERSION,
    @Expose val tracks: MutableList<Track> = mutableListOf<Track>()
): Parcelable
{
    fun delete_non_starred(context: Context)
    {
        val to_delete: List<Track> = this.tracks.filter{! it.starred}
        to_delete.forEach { track ->
            if (!track.starred)
            {
                track.delete(context)
            }
        }
        this.tracks.removeIf{! it.starred}
    }
    suspend fun delete_non_starred_suspended(context: Context)
    {
        return suspendCoroutine { cont ->
            cont.resume(this.delete_non_starred(context))
        }
    }

    fun get_total_distance(): Double
    {
        return this.tracks.sumOf {it.distance.toDouble()}
    }

    fun get_total_duration(): Long
    {
        return this.tracks.sumOf {it.duration}
    }

    fun deepCopy(): Tracklist
    {
        return Tracklist(tracklistFormatVersion, mutableListOf<Track>().apply { addAll(tracks) })
    }

}

fun load_tracklist(context: Context): Tracklist {
    Log.i("VOUSSOIR", "Loading tracklist.")
    val folder = context.getExternalFilesDir("tracks")
    var tracklist: Tracklist = Tracklist()
    if (folder == null)
    {
        return tracklist
    }
    folder.walk().filter{ f: File -> f.isFile }.forEach{ json_file ->
        val track = track_from_file(context, json_file)
        tracklist.tracks.add(track)
    }
    tracklist.tracks.sortByDescending {it.recordingStart}
    return tracklist
}

suspend fun load_tracklist_suspended(context: Context): Tracklist
{
    return suspendCoroutine {cont -> cont.resume(load_tracklist(context))}
}
