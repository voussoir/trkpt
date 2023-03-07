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
import android.database.Cursor
import android.location.Location
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.net.toUri
import org.y20k.trackbook.Keys
import org.y20k.trackbook.helpers.DateTimeHelper
import org.y20k.trackbook.helpers.LocationHelper
import org.y20k.trackbook.helpers.iso8601
import org.y20k.trackbook.helpers.iso8601_format
import java.io.File
import java.net.URI
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/*
 * Track data class
 */
data class Track (
    val database: Database,
    val device_id: String,
    val start_time: Date,
    val stop_time: Date,
    var name: String = "",
    var dequelimit: Int = 7200,
    var view_latitude: Double = Keys.DEFAULT_LATITUDE,
    var view_longitude: Double = Keys.DEFAULT_LONGITUDE,
    var trackFormatVersion: Int = Keys.CURRENT_TRACK_FORMAT_VERSION,
    val trkpts: ArrayDeque<Trkpt> = ArrayDeque<Trkpt>(dequelimit),
    var zoomLevel: Double = Keys.DEFAULT_ZOOM_LEVEL,
)
{
    fun delete()
    {
    }

    suspend fun delete_suspended(context: Context)
    {
        return suspendCoroutine { cont ->
            cont.resume(this.delete())
        }
    }

    fun get_export_gpx_file(context: Context): File
    {
        val basename: String = DateTimeHelper.convertToSortableDateString(this.start_time) + " " + DateTimeHelper.convertToSortableDateString(this.start_time) + Keys.GPX_FILE_EXTENSION
        return File(File("/storage/emulated/0/Syncthing/GPX"), basename)
    }

    fun export_gpx(context: Context, fileuri: Uri): Uri?
    {
        if (! database.ready)
        {
            Log.i("VOUSSOIR", "Failed to export due to database not ready.")
            return null
        }
        Log.i("VOUSSOIR", "Let's export to " + fileuri.toString())
        val writer = context.contentResolver.openOutputStream(fileuri)
        if (writer == null)
        {
            return null
        }
        // Header
        val write = {x: String -> writer.write(x.encodeToByteArray()); writer.write("\n".encodeToByteArray())}

        write("""
        <?xml version="1.0" encoding="UTF-8" standalone="no" ?>
        <gpx
            version="1.1" creator="Trackbook App (Android)"
            xmlns="http://www.topografix.com/GPX/1/1"
            xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
            xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd"
        >
        """.trimIndent())
        write("\t<metadata>")
        write("\t\t<name>Trackbook Recording: ${this.name}</name>")
        write("\t\t<device>${this.device_id}</device>")
        write("\t</metadata>")

        // TRK
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        dateFormat.timeZone = TimeZone.getTimeZone("UTC")
        write("\t<trk>")
        write("\t\t<name>${this.name}</name>")
        write("\t\t<trkseg>")

        trkpt_generator().forEach { trkpt ->
            write("\t\t\t<trkpt lat=\"${trkpt.latitude}\" lon=\"${trkpt.longitude}\">")
            write("\t\t\t\t<ele>${trkpt.altitude}</ele>")
            write("\t\t\t\t<time>${iso8601_format.format(trkpt.time)}</time>")
            write("\t\t\t\t<sat>${trkpt.numberSatellites}</sat>")
            write("\t\t\t</trkpt>")
        }

        write("\t\t</trkseg>")
        write("\t</trk>")
        write("</gpx>")

        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, fileuri.toString(), Toast.LENGTH_SHORT).show()
        }
        return fileuri
    }

    fun load_trkpts()
    {
        this.trkpts.clear()
        trkpt_generator().forEach { trkpt -> this.trkpts.add(trkpt) }
        if (this.trkpts.size > 0)
        {
            this.view_latitude = this.trkpts.first().latitude
            this.view_longitude = this.trkpts.first().longitude
        }
    }

    fun statistics(): TrackStatistics
    {
        var first: Trkpt? = null
        var last: Trkpt? = null
        var previous: Trkpt? = null
        val stats = TrackStatistics()
        for (trkpt in trkpt_generator())
        {
            if (previous == null)
            {
                first = trkpt
                previous = trkpt
                stats.max_altitude = trkpt.altitude
                stats.min_altitude = trkpt.altitude
                continue
            }
            stats.distance += LocationHelper.calculateDistance(previous.toLocation(), trkpt.toLocation())
            val ascentdiff = trkpt.altitude - previous.altitude
            if (ascentdiff > 0)
            {
                stats.total_ascent += ascentdiff
            }
            else
            {
                stats.total_descent += ascentdiff
            }
            if (trkpt.altitude > stats.max_altitude)
            {
                stats.max_altitude = trkpt.altitude
            }
            if (trkpt.altitude < stats.min_altitude)
            {
                stats.min_altitude = trkpt.altitude
            }
            last = trkpt
        }
        if (first == null || last == null)
        {
            return stats
        }
        stats.duration = last.time.time - first.time.time
        stats.velocity = stats.distance / stats.duration
        return stats
    }

    fun trkpt_generator() = iterator<Trkpt>
    {
        val cursor: Cursor = database.connection.query(
            "trkpt",
            arrayOf("lat", "lon", "time", "ele", "accuracy", "sat"),
            "device_id = ? AND time > ? AND time < ?",
            arrayOf(device_id, iso8601(start_time), iso8601(stop_time)),
            null,
            null,
            "time ASC",
            null,
        )
        val COLUMN_LAT = cursor.getColumnIndex("lat")
        val COLUMN_LON = cursor.getColumnIndex("lon")
        val COLUMN_ELE = cursor.getColumnIndex("ele")
        val COLUMN_SAT = cursor.getColumnIndex("sat")
        val COLUMN_ACCURACY = cursor.getColumnIndex("accuracy")
        val COLUMN_TIME = cursor.getColumnIndex("time")
        try
        {
            while (cursor.moveToNext())
            {
                val trkpt: Trkpt = Trkpt(
                    provider="",
                    latitude=cursor.getDouble(COLUMN_LAT),
                    longitude=cursor.getDouble(COLUMN_LON),
                    altitude=cursor.getDouble(COLUMN_ELE),
                    accuracy=cursor.getFloat(COLUMN_ACCURACY),
                    time=iso8601_format.parse(cursor.getString(COLUMN_TIME)),
                    distanceToStartingPoint=0F,
                    numberSatellites=cursor.getInt(COLUMN_SAT),
                )
                yield(trkpt)
            }
        }
        finally
        {
            cursor.close();
        }
    }
}

data class TrackStatistics(
    var distance: Double = 0.0,
    var duration: Long = 0,
    var velocity: Double = 0.0,
    var total_ascent: Double = 0.0,
    var total_descent: Double = 0.0,
    var max_altitude: Double = 0.0,
    var min_altitude: Double = 0.0,
)
