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

package org.y20k.trackbook

import android.location.Location
import org.y20k.trackbook.helpers.getNumberOfSatellites

data class Trkpt(
    val provider: String,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val accuracy: Float,
    val time: Long,
    val numberSatellites: Int = 0,
)
{
    constructor(location: Location) : this (
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
}
