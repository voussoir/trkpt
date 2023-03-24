/*
 * LocationHelper.kt
 * Implements the LocationHelper object
 * A LocationHelper offers helper methods for dealing with location issues
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

package net.voussoir.trkpt.helpers

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import androidx.core.content.ContextCompat
import net.voussoir.trkpt.Keys
import kotlin.math.pow

/* Get default location */
fun getDefaultLocation(): Location
{
    val defaultLocation = Location(LocationManager.NETWORK_PROVIDER)
    defaultLocation.latitude = Keys.DEFAULT_LATITUDE
    defaultLocation.longitude = Keys.DEFAULT_LONGITUDE
    defaultLocation.accuracy = Keys.DEFAULT_ACCURACY
    defaultLocation.altitude = Keys.DEFAULT_ALTITUDE
    defaultLocation.time = Keys.DEFAULT_DATE.time
    return defaultLocation
}

/* Tries to return the last location that the system has stored */
fun getLastKnownLocation(context: Context): Location
{
    // get last location that Trackbook has stored
    var lastKnownLocation: Location = PreferencesHelper.loadCurrentBestLocation()
    // try to get the last location the system has stored - it is probably more recent
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val lastKnownLocationGps: Location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lastKnownLocation
        val lastKnownLocationNetwork: Location = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) ?: lastKnownLocation
        when (isBetterLocation(lastKnownLocationGps, lastKnownLocationNetwork)) {
            true -> lastKnownLocation = lastKnownLocationGps
            false -> lastKnownLocation = lastKnownLocationNetwork
        }
    }
    return lastKnownLocation
}

/* Determines whether one location reading is better than the current location fix */
fun isBetterLocation(location: Location, currentBestLocation: Location?): Boolean
{
    // Credit: https://developer.android.com/guide/topics/location/strategies.html#BestEstimate

    if (currentBestLocation == null)
    {
        // a new location is always better than no location
        return true
    }

    // check whether the new location fix is newer or older
    val timeDelta: Long = location.time - currentBestLocation.time
    val isSignificantlyNewer: Boolean = timeDelta > Keys.SIGNIFICANT_TIME_DIFFERENCE
    val isSignificantlyOlder:Boolean = timeDelta < -Keys.SIGNIFICANT_TIME_DIFFERENCE

    when {
        // if it's been more than two minutes since the current location, use the new location because the user has likely moved
        isSignificantlyNewer -> return true
        // if the new location is more than two minutes older, it must be worse
        isSignificantlyOlder -> return false
    }

    // check whether the new location fix is more or less accurate
    val isNewer: Boolean = timeDelta > 0L
    val accuracyDelta: Float = location.accuracy - currentBestLocation.accuracy
    val isLessAccurate: Boolean = accuracyDelta > 0f
    val isMoreAccurate: Boolean = accuracyDelta < 0f
    val isSignificantlyLessAccurate: Boolean = accuracyDelta > 200f

    // check if the old and new location are from the same provider
    val isFromSameProvider: Boolean = location.provider == currentBestLocation.provider

    // determine location quality using a combination of timeliness and accuracy
    return when {
        isMoreAccurate -> true
        isNewer && !isLessAccurate -> true
        isNewer && !isSignificantlyLessAccurate && isFromSameProvider -> true
        else -> false
    }
}

/* Checks if GPS location provider is available and enabled */
fun isGpsEnabled(locationManager: LocationManager): Boolean
{
    if (locationManager.allProviders.contains(LocationManager.GPS_PROVIDER))
    {
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }
    else
    {
        return false
    }
}

/* Checks if Network location provider is available and enabled */
fun isNetworkEnabled(locationManager: LocationManager): Boolean
{
    if (locationManager.allProviders.contains(LocationManager.NETWORK_PROVIDER))
    {
        return locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }
    else
    {
        return false
    }
}

/* Checks if given location is new */
fun isRecentEnough(location: Location): Boolean
{
    val locationAge: Long = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
    return locationAge < Keys.DEFAULT_THRESHOLD_LOCATION_AGE
}

/* Checks if given location is accurate */
fun isAccurateEnough(location: Location, locationAccuracyThreshold: Int): Boolean
{
    if (location.provider == LocationManager.GPS_PROVIDER)
    {
        return location.accuracy < locationAccuracyThreshold
    }
    else
    {
        return location.accuracy < locationAccuracyThreshold + 10 // a bit more relaxed when location comes from network provider
    }
}

/* Checks if given location is different enough compared to previous location */
fun isDifferentEnough(previousLocation: Location?, location: Location, omitRests: Boolean): Boolean
{
    // check if previous location is (not) available
    if (previousLocation == null)
    {
        return true
    }

    if (! omitRests)
    {
        return true
    }

    // location.accuracy is given as 1 standard deviation, with a 68% chance
    // that the true position is within a circle of this radius.
    // These formulas determine if the difference between the last point and
    // new point is statistically significant.
    val accuracy: Float = if (location.accuracy != 0.0f) location.accuracy else Keys.DEFAULT_THRESHOLD_DISTANCE
    val previousAccuracy: Float = if (previousLocation.accuracy != 0.0f) previousLocation.accuracy else Keys.DEFAULT_THRESHOLD_DISTANCE
    val accuracyDelta: Double = Math.sqrt((accuracy.pow(2) + previousAccuracy.pow(2)).toDouble())
    val distance: Float = previousLocation.distanceTo(location)

    // With 1*accuracyDelta we have 68% confidence that the points are
    // different. We can multiply this number to increase confidence but
    // decrease point recording frequency if needed.
    return distance > accuracyDelta
}

/* Get number of satellites from Location extras */
fun getNumberOfSatellites(location: Location): Int
{
    val numberOfSatellites: Int
    val extras: Bundle? = location.extras
    if (extras != null && extras.containsKey("satellites")) {
        numberOfSatellites = extras.getInt("satellites", 0)
    } else {
        numberOfSatellites = 0
    }
    return numberOfSatellites
}
