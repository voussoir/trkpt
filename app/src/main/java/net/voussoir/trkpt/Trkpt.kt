/*
 * WayPoint.kt
 * Implements the WayPoint data class
 * A WayPoint stores a location plus additional metadata
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

package net.voussoir.trkpt

import android.location.Location
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Polyline
import net.voussoir.trkpt.helpers.getNumberOfSatellites
import net.voussoir.trkpt.helpers.iso8601_local

class Trkpt(
    val device_id: String,
    val provider: String,
    latitude: Double,
    longitude: Double,
    altitude: Double,
    val accuracy: Float,
    val time: Long,
    val numberSatellites: Int = 0,
    var rendered_by_polyline: Polyline? = null
) : GeoPoint(latitude, longitude, altitude)
{
    constructor(device_id: String, location: Location) : this(
        device_id=device_id,
        provider=location.provider.toString(),
        latitude=location.latitude,
        longitude=location.longitude,
        altitude=location.altitude,
        accuracy=location.accuracy,
        time=location.time,
        numberSatellites=getNumberOfSatellites(location),
    )

    fun toLocation(): Location {
        val location = Location(provider)
        location.latitude = latitude
        location.longitude = longitude
        location.altitude = altitude
        location.accuracy = accuracy
        location.time = this.time
        return location
    }

    override fun toString(): String
    {
        return "${device_id} ${iso8601_local(time)} ${latitude}/${longitude}"
    }
}
