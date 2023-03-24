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

/*
 * Modified by voussoir for trkpt, forked from Trackbook.
 */

package net.voussoir.trkpt

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import net.voussoir.trkpt.helpers.iso8601
import java.text.SimpleDateFormat
import java.util.*

data class Track (
    val database: net.voussoir.trkpt.Database,
    val device_id: String,
    var start_time: Date,
    var end_time: Date,
    var name: String = "",
    val trkpts: ArrayList<Trkpt> = ArrayList<Trkpt>(),
    var view_latitude: Double = Keys.DEFAULT_LATITUDE,
    var view_longitude: Double = Keys.DEFAULT_LONGITUDE,
    var trackFormatVersion: Int = Keys.CURRENT_TRACK_FORMAT_VERSION,
)
{
    fun delete()
    {
        Log.i("VOUSSOIR", "Track.delete ${device_id} ${start_time} -- ${end_time}.")
        database.begin_transaction()
        database.connection.delete("trkpt", "device_id = ? AND time > ? AND time < ?", arrayOf(device_id, start_time.time.toString(), end_time.time.toString()))
        database.commit()
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
        write("\t\t<name>${this.name}</name>")
        write("\t\t<device>${this.device_id}</device>")
        write("\t</metadata>")

        // TRK
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        dateFormat.timeZone = TimeZone.getTimeZone("UTC")
        write("\t<trk>")
        write("\t\t<trkseg>")

        var previous: Trkpt? = null
        for (trkpt in trkpt_generator())
        {
            if (previous != null && (trkpt.time - previous.time) > (Keys.STOP_OVER_THRESHOLD))
            {
                write("\t\t</trkseg>")
                write("\t\t<trkseg>")
            }
            write("\t\t\t<trkpt lat=\"${trkpt.latitude}\" lon=\"${trkpt.longitude}\">")
            write("\t\t\t\t<ele>${trkpt.altitude}</ele>")
            write("\t\t\t\t<time>${iso8601(trkpt.time)}</time>")
            write("\t\t\t\t<unix>${trkpt.time}</unix>")
            write("\t\t\t\t<sat>${trkpt.numberSatellites}</sat>")
            write("\t\t\t</trkpt>")
            previous = trkpt
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
        Log.i("VOUSSOIR", "Track.statistics")
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
            stats.distance += previous.toLocation().distanceTo(trkpt.toLocation())
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
            previous = trkpt
            last = trkpt
        }
        if (first == null || last == null)
        {
            return stats
        }
        stats.duration = last.time - first.time
        stats.velocity = stats.distance / (stats.duration / 1000)
        return stats
    }

    fun trkpt_generator() = iterator<Trkpt>
    {
        var cursor: Cursor = database.connection.rawQuery(
            "SELECT lat, lon, time, ele, accuracy, sat FROM trkpt WHERE device_id = ? AND time > ? AND time < ? ORDER BY time ASC",
            arrayOf(device_id, start_time.time.toString(), end_time.time.toString())
        )
        Log.i("VOUSSOIR", "Track.trkpt_generator: Querying points between ${start_time} -- ${end_time}, ${cursor.count} results")
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
                val trkpt = Trkpt(
                    device_id=device_id,
                    provider="",
                    latitude=cursor.getDouble(COLUMN_LAT),
                    longitude=cursor.getDouble(COLUMN_LON),
                    altitude=cursor.getDouble(COLUMN_ELE),
                    accuracy=cursor.getFloat(COLUMN_ACCURACY),
                    time=cursor.getLong(COLUMN_TIME),
                    numberSatellites=cursor.getInt(COLUMN_SAT),
                )
                yield(trkpt)
            }
        }
        finally
        {
            cursor.close()
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
