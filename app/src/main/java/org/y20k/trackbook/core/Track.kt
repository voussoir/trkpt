/*
 * Track.kt
 * Implements the Track data class
 * A Track stores a list of WayPoints
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
import android.location.Location
import android.os.Parcelable
import android.util.Log
import androidx.annotation.Keep
import com.google.gson.annotations.Expose
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.random.Random
import kotlinx.parcelize.Parcelize
import org.y20k.trackbook.Keys
import org.y20k.trackbook.helpers.DateTimeHelper
import org.y20k.trackbook.helpers.FileHelper
import org.y20k.trackbook.helpers.LocationHelper

/*
 * Track data class
 */
@Keep
@Parcelize
data class Track (
    @Expose val id: Long = make_random_id(),
    @Expose var trackFormatVersion: Int = Keys.CURRENT_TRACK_FORMAT_VERSION,
    @Expose val wayPoints: MutableList<WayPoint> = mutableListOf<WayPoint>(),
    @Expose var distance: Float = 0f,
    @Expose var duration: Long = 0L,
    @Expose var recordingPaused: Long = 0L,
    @Expose var stepCount: Float = 0f,
    @Expose var recordingStart: Date = GregorianCalendar.getInstance().time,
    @Expose var dateString: String = DateTimeHelper.convertToReadableDate(recordingStart),
    @Expose var recordingStop: Date = recordingStart,
    @Expose var maxAltitude: Double = 0.0,
    @Expose var minAltitude: Double = 0.0,
    @Expose var positiveElevation: Double = 0.0,
    @Expose var negativeElevation: Double = 0.0,
    @Expose var latitude: Double = Keys.DEFAULT_LATITUDE,
    @Expose var longitude: Double = Keys.DEFAULT_LONGITUDE,
    @Expose var zoomLevel: Double = Keys.DEFAULT_ZOOM_LEVEL,
    @Expose var name: String = DateTimeHelper.convertToReadableDate(recordingStart),
    @Expose var starred: Boolean = false,
): Parcelable
{
    fun add_waypoint(location: Location, omitRests: Boolean, resumed: Boolean): Boolean
    {
        // Step 1: Get previous location
        val previousLocation: Location?
        var numberOfWayPoints: Int = this.wayPoints.size

        // CASE: First location
        if (numberOfWayPoints == 0)
        {
            previousLocation = null
        }
        // CASE: Second location - check if first location was plausible & remove implausible location
        else if (numberOfWayPoints == 1 && !LocationHelper.isFirstLocationPlausible(location, this))
        {
            previousLocation = null
            numberOfWayPoints = 0
            this.wayPoints.removeAt(0)
        }
        // CASE: Third location or second location (if first was plausible)
        else
        {
            previousLocation = this.wayPoints[numberOfWayPoints - 1].toLocation()
        }

        // Step 2: Update duration
        val now: Date = GregorianCalendar.getInstance().time
        val difference: Long = now.time - this.recordingStop.time
        this.duration += difference
        this.recordingStop = now

        // Step 3: Add waypoint, if recent and accurate and different enough
        val shouldBeAdded: Boolean = (
            LocationHelper.isRecentEnough(location) &&
            LocationHelper.isAccurateEnough(location, Keys.DEFAULT_THRESHOLD_LOCATION_ACCURACY) &&
            LocationHelper.isDifferentEnough(previousLocation, location, omitRests)
        )
        if (! shouldBeAdded)
        {
            return false
        }
        // Step 3.1: Update distance (do not update if resumed -> we do not want to add values calculated during a recording pause)
        if (!resumed)
        {
            this.distance = this.distance + LocationHelper.calculateDistance(previousLocation, location)
        }
        // Step 3.2: Update altitude values
        val altitude: Double = location.altitude
        if (altitude != 0.0)
        {
            if (numberOfWayPoints == 0)
            {
                this.maxAltitude = altitude
                this.minAltitude = altitude
            }
            else
            {
                if (altitude > this.maxAltitude) this.maxAltitude = altitude
                if (altitude < this.minAltitude) this.minAltitude = altitude
            }
        }
        // Step 3.3: Toggle stop over status, if necessary
        if (this.wayPoints.size < 0)
        {
            this.wayPoints[this.wayPoints.size - 1].isStopOver = LocationHelper.isStopOver(previousLocation, location)
        }

        // Step 3.4: Add current location as point to center on for later display
        this.latitude = location.latitude
        this.longitude = location.longitude

        // Step 3.5: Add location as new waypoint
        this.wayPoints.add(WayPoint(location = location, distanceToStartingPoint = this.distance))

        return true
    }

    fun delete(context: Context)
    {
        Log.i("VOUSSOIR", "Deleting track ${this.id}.")
        val json_file: File = this.get_json_file(context)
        if (json_file.isFile)
        {
            json_file.delete()
        }
        val gpx_file: File = this.get_gpx_file(context)
        if (gpx_file.isFile)
        {
            gpx_file.delete()
        }
    }

    suspend fun delete_suspended(context: Context)
    {
        return suspendCoroutine { cont ->
            cont.resume(this.delete(context))
        }
    }

    fun get_gpx_file(context: Context): File
    {
        val basename: String = this.id.toString() + Keys.GPX_FILE_EXTENSION
        return File(context.getExternalFilesDir(Keys.FOLDER_GPX), basename)
    }

    fun get_json_file(context: Context): File
    {
        val basename: String = this.id.toString() + Keys.TRACKBOOK_FILE_EXTENSION
        return File(context.getExternalFilesDir(Keys.FOLDER_TRACKS), basename)
    }

    fun save_both(context: Context)
    {
        this.save_json(context)
        this.save_gpx(context)
    }

    suspend fun save_both_suspended(context: Context)
    {
        return suspendCoroutine { cont ->
            cont.resume(this.save_both(context))
        }
    }

    fun save_gpx(context: Context)
    {
        val gpx: String = this.to_gpx()
        FileHelper.write_text_file_noblank(gpx, this.get_gpx_file(context))
        Log.i("VOUSSOIR", "Saved ${this.id}.gpx")
    }

    suspend fun save_gpx_suspended(context: Context)
    {
        return suspendCoroutine { cont ->
            cont.resume(this.save_gpx(context))
        }
    }

    fun save_json(context: Context)
    {
        val json: String = this.to_json()
        FileHelper.write_text_file_noblank(json, this.get_json_file(context))
        Log.i("VOUSSOIR", "Saved ${this.id}.json")
    }

    suspend fun save_json_suspended(context: Context)
    {
        return suspendCoroutine { cont ->
            cont.resume(this.save_json(context))
        }
    }

    fun save_temp(context: Context)
    {
        val json: String = this.to_json()
        FileHelper.write_text_file_noblank(json, FileHelper.get_temp_file(context))
    }

    suspend fun save_temp_suspended(context: Context)
    {
        return suspendCoroutine { cont ->
            cont.resume(this.save_temp(context))
        }
    }

    fun to_gpx(): String {
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
        gpxString.appendLine("\t\t<name>Trackbook Recording: ${this.name}</name>")
        gpxString.appendLine("\t</metadata>")

        // POIs
        val poiList: List<WayPoint> =  this.wayPoints.filter { it.starred }
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
        gpxString.appendLine("\t\t<name>${this.name}</name>")
        gpxString.appendLine("\t\t<trkseg>")
        this.wayPoints.forEach { wayPoint ->
            gpxString.appendLine("\t\t\t<trkpt lat=\"${wayPoint.latitude}\" lon=\"${wayPoint.longitude}\">")
            gpxString.appendLine("\t\t\t\t<ele>${wayPoint.altitude}</ele>")
            gpxString.appendLine("\t\t\t\t<time>${dateFormat.format(Date(wayPoint.time))}</time>")
            gpxString.appendLine("\t\t\t\t<sat>${wayPoint.numberSatellites}</sat>")
            gpxString.appendLine("\t\t\t</trkpt>")
        }
        gpxString.appendLine("\t\t</trkseg>")
        gpxString.appendLine("\t</trk>")
        gpxString.appendLine("</gpx>")

        return gpxString.toString()
    }

    fun to_json(): String
    {
        return FileHelper.getCustomGson().toJson(this)
    }
}

fun load_temp_track(context: Context): Track
{
    return track_from_file(context, FileHelper.get_temp_file(context))
}

fun track_from_file(context: Context, file: File): Track
{
    // get JSON from text file
    val json: String = FileHelper.readTextFile(context, file)
    if (json.isEmpty())
    {
        return Track()
    }
    return FileHelper.getCustomGson().fromJson(json, Track::class.java)
}

fun make_random_id(): Long
{
    return (Random.nextBits(31).toLong() shl 32) + Random.nextBits(32)
}
