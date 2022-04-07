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

import android.os.Parcelable
import androidx.annotation.Keep
import com.google.gson.annotations.Expose
import kotlinx.parcelize.Parcelize
import org.y20k.trackbook.Keys
import org.y20k.trackbook.helpers.TrackHelper
import java.util.*


/*
 * Tracklist data class
 */
@Keep
@Parcelize
data class Tracklist (@Expose val tracklistFormatVersion: Int = Keys.CURRENT_TRACKLIST_FORMAT_VERSION,
                      @Expose val tracklistElements: MutableList<TracklistElement> = mutableListOf<TracklistElement>()): Parcelable {

    /* Return trackelement for given track id */
    fun getTrackElement(trackId: Long): TracklistElement? {
        tracklistElements.forEach { tracklistElement ->
            if (tracklistElement.id == trackId) {
                return tracklistElement
            }
        }
        return null
    }

    fun get_total_distance(): Float
    {
        var total: Float = 0F
        tracklistElements.forEach { tracklist_element ->
            total += tracklist_element.distance
        }
        return total
    }

    fun get_total_duration(): Long
    {
        var total: Long = 0L
        tracklistElements.forEach { tracklist_element ->
            total += tracklist_element.duration
        }
        return total
    }

    /* Create a deep copy */
    fun deepCopy(): Tracklist {
        return Tracklist(tracklistFormatVersion, mutableListOf<TracklistElement>().apply { addAll(tracklistElements) })
    }

}
