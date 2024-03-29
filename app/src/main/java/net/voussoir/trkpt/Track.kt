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
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import net.voussoir.trkpt.helpers.iso8601
import java.text.SimpleDateFormat
import java.util.*

data class Track (
    val database: Database,
    var device_id: String,
    var name: String = "",
    var _start_time: Long = 0L,
    var _end_time: Long = 0L,
    val trkpts: ArrayDeque<Trkpt> = ArrayDeque<Trkpt>(),
    var trackFormatVersion: Int = Keys.CURRENT_TRACK_FORMAT_VERSION,
)
{
    /**
     * Discover the true bounds of this trkseg by querying for points until the stop over threshold.
     * This is extremely helpful when using the "when was I here" button on an area, then expanding
     * the trkseg to its ends.
     */
    fun expand_to_trkseg_bounds()
    {
        if (trkpts.isEmpty())
        {
            return
        }
        var previous = trkpts.first()
        for (trkpt in database.select_trkpt_start_end(device_id, start_time=0L, end_time=trkpts.first().time, order="DESC"))
        {
            if ((previous.time - trkpt.time) > Keys.STOP_OVER_THRESHOLD)
            {
                break
            }
            trkpts.addFirst(trkpt)
            previous = trkpt
        }
        previous = trkpts.last()
        for (trkpt in database.select_trkpt_start_end(device_id, start_time=trkpts.last().time, end_time=Long.MAX_VALUE, order="ASC"))
        {
            if ((trkpt.time - previous.time) > Keys.STOP_OVER_THRESHOLD)
            {
                break
            }
            trkpts.add(trkpt)
            previous = trkpt
        }
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
            version="1.1" creator="${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME}"
            xmlns="http://www.topografix.com/GPX/1/1"
            xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
            xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd"
        >
        """.trimIndent())
        val name_escaped = this.name.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        write("\t<metadata>")
        write("\t\t<name>${name_escaped}</name>")
        write("\t\t<application>${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME}</application>")
        write("\t\t<device>${this.device_id}</device>")
        write("\t</metadata>")

        // TRK
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        dateFormat.timeZone = TimeZone.getTimeZone("UTC")
        write("\t<trk>")
        write("\t\t<trkseg>")

        var previous: Trkpt? = null
        for (trkpt in this.trkpts)
        {
            if (previous != null && (trkpt.time - previous.time) > (Keys.STOP_OVER_THRESHOLD))
            {
                write("\t\t</trkseg>")
                write("\t\t<trkseg>")
            }
            write("\t\t\t<trkpt lat=\"${trkpt.latitude}\" lon=\"${trkpt.longitude}\">")
            write("\t\t\t\t<time>${iso8601(trkpt.time)}</time>")
            write("\t\t\t\t<unix>${trkpt.time}</unix>")
            write("\t\t\t\t<ele>${trkpt.altitude}</ele>")
            write("\t\t\t\t<accuracy>${trkpt.accuracy}</accuracy>")
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

    fun load_trkpts(points: Iterator<Trkpt>)
    {
        this.trkpts.clear()
        points.forEach { trkpt -> this.trkpts.add(trkpt) }
        this.name = suggested_name()
    }

    fun suggested_name(): String
    {
        if (trkpts.size == 0)
        {
            return ""
        }
        val df = SimpleDateFormat("yyyy-MM-dd")
        val start_date = df.format(trkpts.first().time)
        val end_date = df.format(trkpts.last().time)
        if (start_date == end_date)
        {
            return "$start_date $device_id"
        }
        else
        {
            return "$start_date--$end_date $device_id"
        }
    }
}

data class TrackStatistics(
    val trkpts: ArrayDeque<Trkpt>,
    var distance: Double = 0.0,
    var duration: Long = 0,
    var pause_duration: Long = 0,
    var velocity: Double = 0.0,
    var total_ascent: Double = 0.0,
    var total_descent: Double = 0.0,
    var max_altitude: Double = 0.0,
    var min_altitude: Double = 0.0
)
{
    init
    {
        Log.i("VOUSSOIR", "Track.statistics")
        var first: Trkpt? = null
        var last: Trkpt? = null
        var previous: Trkpt? = null
        for (trkpt in trkpts)
        {
            if (previous == null)
            {
                first = trkpt
                previous = trkpt
                max_altitude = trkpt.altitude
                min_altitude = trkpt.altitude
                continue
            }
            if (trkpt.time - previous.time > Keys.STOP_OVER_THRESHOLD)
            {
                pause_duration += (trkpt.time - previous.time)
                previous = trkpt
                last = trkpt
                continue
            }
            distance += previous.toLocation().distanceTo(trkpt.toLocation())
            val ascentdiff = trkpt.altitude - previous.altitude
            if (ascentdiff > 0)
            {
                total_ascent += ascentdiff
            }
            else
            {
                total_descent += ascentdiff
            }
            if (trkpt.altitude > max_altitude)
            {
                max_altitude = trkpt.altitude
            }
            if (trkpt.altitude < min_altitude)
            {
                min_altitude = trkpt.altitude
            }
            previous = trkpt
            last = trkpt
        }
        if (first != null && last != null)
        {
            duration = (last.time - first.time) - pause_duration
            if (duration > 1000)
            {
                velocity = distance / (duration / 1000)
            }
        }
    }
}
