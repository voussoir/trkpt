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
import android.location.Location
import android.widget.Toast
import androidx.core.net.toUri
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import org.y20k.trackbook.Keys
import org.y20k.trackbook.R
import org.y20k.trackbook.core.Track
import org.y20k.trackbook.core.Tracklist
import org.y20k.trackbook.core.TracklistElement
import org.y20k.trackbook.core.WayPoint

/*
 * TrackHelper object
 */
object TrackHelper {

    /* Define log tag */
    private val TAG: String = LogHelper.makeLogTag(TrackHelper::class.java)

    /* Adds given locatiom as waypoint to track */
    fun addWayPointToTrack(track: Track, location: Location, accuracyMultiplier: Int, resumed: Boolean): Pair<Boolean, Track> {
        // Step 1: Get previous location
        val previousLocation: Location?
        var numberOfWayPoints: Int = track.wayPoints.size

        // CASE: First location
        if (numberOfWayPoints == 0) {
            previousLocation = null
        }
        // CASE: Second location - check if first location was plausible & remove implausible location
        else if (numberOfWayPoints == 1 && !LocationHelper.isFirstLocationPlausible(location, track)) {
            previousLocation = null
            numberOfWayPoints = 0
            track.wayPoints.removeAt(0)
        }
        // CASE: Third location or second location (if first was plausible)
        else {
            previousLocation = track.wayPoints[numberOfWayPoints - 1].toLocation()
        }

        // Step 2: Update duration
        val now: Date = GregorianCalendar.getInstance().time
        val difference: Long = now.time - track.recordingStop.time
        track.duration = track.duration + difference
        track.recordingStop = now

        // Step 3: Add waypoint, ifrecent and accurate and different enough
        val shouldBeAdded: Boolean = (LocationHelper.isRecentEnough(location) &&
                                      LocationHelper.isAccurateEnough(location, Keys.DEFAULT_THRESHOLD_LOCATION_ACCURACY) &&
                                      LocationHelper.isDifferentEnough(previousLocation, location, accuracyMultiplier))
        if (shouldBeAdded) {
            // Step 3.1: Update distance (do not update if resumed -> we do not want to add values calculated during a recording pause)
            if (!resumed) {
                track.distance = track.distance + LocationHelper.calculateDistance(previousLocation, location)
            }
            // Step 3.2: Update altitude values
            val altitude: Double = location.altitude
            if (altitude != 0.0) {
                if (numberOfWayPoints == 0) {
                    track.maxAltitude = altitude
                    track.minAltitude = altitude
                }
                else {
                    if (altitude > track.maxAltitude) track.maxAltitude = altitude
                    if (altitude < track.minAltitude) track.minAltitude = altitude
                }
            }
            // Step 3.3: Toggle stop over status, if necessary
            if (track.wayPoints.size < 0) {
                track.wayPoints[track.wayPoints.size - 1].isStopOver = LocationHelper.isStopOver(previousLocation, location)
            }

            // Step 3.4: Add current location as point to center on for later display
            track.latitude = location.latitude
            track.longitude = location.longitude

            // Step 3.5: Add location as new waypoint
            track.wayPoints.add(WayPoint(location = location, distanceToStartingPoint = track.distance))
        }

        return Pair(shouldBeAdded, track)
    }

    /* Calculates time passed since last stop of recording */
    fun calculateDurationOfPause(recordingStop: Date): Long = GregorianCalendar.getInstance().time.time - recordingStop.time

    /* Creates GPX string for given track */
    fun createGpxString(track: Track): String {
        val gpxString = StringBuilder("")

        // Header
        gpxString.appendLine("""
        <?xml version="1.0" encoding="UTF-8" standalone="no" ?>
        <gpx
            version="1.1" creator="Trackbook App (Android)"
            xmlns="http://www.topografix.com/GPX/1/1"
            xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
            xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd"
        >
        """.trimIndent())
        gpxString.appendLine("\t<metadata>")
        gpxString.appendLine("\t\t<name>Trackbook Recording: ${track.name}</name>")
        gpxString.appendLine("\t</metadata>")

        // POIs
        val poiList: List<WayPoint> =  track.wayPoints.filter { it.starred }
        poiList.forEach { poi ->
            gpxString.appendLine("\t<wpt lat=\"${poi.latitude}\" lon=\"${poi.longitude}\">")
            gpxString.appendLine("\t\t<name>Point of interest</name>")
            gpxString.appendLine("\t\t<ele>${poi.altitude}</ele>")
            gpxString.appendLine("\t</wpt>")
        }

        // TRK
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        dateFormat.timeZone = TimeZone.getTimeZone("UTC")
        gpxString.appendLine("\t<trk>")
        gpxString.appendLine("\t\t<name>${track.name}</name>")
        gpxString.appendLine("\t\t<trkseg>")
        track.wayPoints.forEach { wayPoint ->
            gpxString.appendLine("\t\t\t<trkpt lat=\"${wayPoint.latitude}\" lon=\"${wayPoint.longitude}\">")
            gpxString.appendLine("\t\t\t\t<ele>${wayPoint.altitude}</ele>")
            gpxString.appendLine("\t\t\t\t<time>${dateFormat.format(Date(wayPoint.time))}</time>")
            gpxString.appendLine("\t\t\t</trkpt>")
        }
        gpxString.appendLine("\t\t</trkseg>")
        gpxString.appendLine("\t</trk>")
        gpxString.appendLine("</gpx>")

        return gpxString.toString()
    }

    /* Toggles starred flag for given position */
    fun toggleStarred(context: Context, track: Track, latitude: Double, longitude: Double): Track {
        track.wayPoints.forEach { waypoint ->
            if (waypoint.latitude == latitude && waypoint.longitude == longitude) {
                waypoint.starred = !waypoint.starred
                when (waypoint.starred) {
                    true -> Toast.makeText(context, R.string.toast_message_poi_added, Toast.LENGTH_LONG).show()
                    false -> Toast.makeText(context, R.string.toast_message_poi_removed, Toast.LENGTH_LONG).show()
                }
            }
        }
        return track
    }
}
