/*
 * TrackHelper.kt
 * Implements the TrackHelper object
 * A TrackHelper offers helper methods for dealing with track objects
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

package org.y20k.trackbook.helpers

import android.content.Context
import android.widget.Toast
import java.util.*
import org.y20k.trackbook.R
import org.y20k.trackbook.core.Track

/*
 * TrackHelper object
 */
object TrackHelper {

    /* Define log tag */
    private val TAG: String = LogHelper.makeLogTag(TrackHelper::class.java)

    /* Adds given locatiom as waypoint to track */


    /* Calculates time passed since last stop of recording */
    fun calculateDurationOfPause(recordingStop: Date): Long = GregorianCalendar.getInstance().time.time - recordingStop.time

    /* Toggles starred flag for given position */
    fun toggle_waypoint_starred(context: Context, track: Track, latitude: Double, longitude: Double)
    {
        track.wayPoints.forEach { waypoint ->
            if (waypoint.latitude == latitude && waypoint.longitude == longitude) {
                waypoint.starred = !waypoint.starred
                when (waypoint.starred) {
                    true -> Toast.makeText(context, R.string.toast_message_poi_added, Toast.LENGTH_LONG).show()
                    false -> Toast.makeText(context, R.string.toast_message_poi_removed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
