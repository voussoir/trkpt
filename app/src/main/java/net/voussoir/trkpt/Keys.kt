/*
 * Keys.kt
 * Implements the keys used throughout the app
 * This object hosts all keys used to control Trackbook's state
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

import java.util.*

/*
 * Keys object
 */
object Keys {
    // axioms
    const val ONE_SECOND_IN_MILLISECONDS: Long = 1000
    const val ONE_MINUTE_IN_MILLISECONDS: Long = 60 * ONE_SECOND_IN_MILLISECONDS
    const val ONE_HOUR_IN_MILLISECONDS: Long = 60 * ONE_MINUTE_IN_MILLISECONDS

    // version numbers
    const val CURRENT_TRACK_FORMAT_VERSION: Int = 4
    const val DATABASE_VERSION: Int = 1

    // intent actions
    const val ACTION_START: String = "net.voussoir.trkpt.action.START"
    const val ACTION_STOP: String = "net.voussoir.trkpt.action.STOP"

    // args
    const val ARG_TRACK_TITLE: String = "ArgTrackTitle"
    const val ARG_TRACK_ID: String = "ArgTrackID"
    const val ARG_TRACK_DEVICE_ID: String = "ArgTrackDeviceID"
    const val ARG_TRACK_START_TIME: String = "ArgTrackStartTime"
    const val ARG_TRACK_STOP_TIME: String = "ArgTrackStopTime"

    // preferences
    const val PREF_ONE_TIME_HOUSEKEEPING_NECESSARY = "ONE_TIME_HOUSEKEEPING_NECESSARY_VERSIONCODE_38" // increment to current app version code to trigger housekeeping that runs only once
    const val PREF_THEME_SELECTION: String= "prefThemeSelection"
    const val PREF_CURRENT_BEST_LOCATION_PROVIDER: String = "prefCurrentBestLocationProvider"
    const val PREF_CURRENT_BEST_LOCATION_LATITUDE: String = "prefCurrentBestLocationLatitude"
    const val PREF_CURRENT_BEST_LOCATION_LONGITUDE: String = "prefCurrentBestLocationLongitude"
    const val PREF_CURRENT_BEST_LOCATION_ACCURACY: String = "prefCurrentBestLocationAccuracy"
    const val PREF_CURRENT_BEST_LOCATION_ALTITUDE: String = "prefCurrentBestLocationAltitude"
    const val PREF_CURRENT_BEST_LOCATION_TIME: String = "prefCurrentBestLocationTime"
    const val PREF_MAP_ZOOM_LEVEL: String = "prefMapZoomLevel"
    const val PREF_TRACKING_STATE: String = "prefTrackingState"
    const val PREF_USE_IMPERIAL_UNITS: String = "prefUseImperialUnits"
    const val PREF_LOCATION_NETWORK: String = "prefLocationNetwork"
    const val PREF_LOCATION_GPS: String = "prefLocationGPS"
    const val PREF_OMIT_RESTS: String = "prefOmitRests"
    const val PREF_ALLOW_SLEEP: String = "prefAllowSleep"
    const val PREF_SHOW_DEBUG: String = "prefShowDebug"
    const val PREF_DEVICE_ID: String = "prefDeviceID"
    const val PREF_DATABASE_DIRECTORY: String = "prefDatabaseDirectory"

    // states
    const val STATE_TRACKING_STOPPED: Int = 0
    const val STATE_TRACKING_ACTIVE: Int = 1
    const val LOCATION_INTERVAL_FULL_POWER: Long = 0
    const val LOCATION_INTERVAL_SLEEP: Long = ONE_MINUTE_IN_MILLISECONDS
    const val LOCATION_INTERVAL_DEAD: Long = -1
    const val LOCATION_INTERVAL_STOP: Long = -2
    const val STATE_THEME_FOLLOW_SYSTEM: String = "stateFollowSystem"
    const val STATE_THEME_LIGHT_MODE: String = "stateLightMode"
    const val STATE_THEME_DARK_MODE: String = "stateDarkMode"

    // dialog types
    const val DIALOG_DELETE_TRACK: Int = 1

    // dialog results
    const val DIALOG_EMPTY_PAYLOAD_STRING: String = ""
    const val DIALOG_EMPTY_PAYLOAD_INT: Int = -1

    // file names and extensions
    const val MIME_TYPE_GPX: String = "application/gpx+xml"
    const val GPX_FILE_EXTENSION: String = ".gpx"

    // view types
    const val VIEW_TYPE_STATISTICS: Int = 1
    const val VIEW_TYPE_TRACK: Int = 2

    // default values
    val DEFAULT_DATE: Date = Date(0L)
    const val EMPTY_STRING_RESOURCE: Int = 0
    const val SIGNIFICANT_TIME_DIFFERENCE: Long = 1 * ONE_MINUTE_IN_MILLISECONDS
    const val STOP_OVER_THRESHOLD: Long = 5 * ONE_MINUTE_IN_MILLISECONDS
    const val DEFAULT_LATITUDE: Double = 71.172500                              // latitude Nordkapp, Norway
    const val DEFAULT_LONGITUDE: Double = 25.784444                             // longitude Nordkapp, Norway
    const val DEFAULT_ACCURACY: Float = 300f                                    // in meters
    const val DEFAULT_ALTITUDE: Double = 0.0
    const val DEFAULT_TIME: Long = 0L
    const val COMMIT_INTERVAL: Long = 30 * ONE_SECOND_IN_MILLISECONDS
    const val DEFAULT_THRESHOLD_LOCATION_ACCURACY: Int = 30                     // 30 meters
    const val DEFAULT_THRESHOLD_LOCATION_AGE: Long = 5_000_000_000L             // 5s in nanoseconds
    const val DEFAULT_THRESHOLD_DISTANCE: Float = 15f                           // 15 meters
    const val DEFAULT_ZOOM_LEVEL: Double = 16.0
    const val DEFAULT_OMIT_RESTS: Boolean = true
    const val DEFAULT_ALLOW_SLEEP: Boolean = true
    const val DEFAULT_SHOW_DEBUG: Boolean = false

    // notification
    const val TRACKER_SERVICE_NOTIFICATION_ID: Int = 1
    const val NOTIFICATION_CHANNEL_RECORDING: String = "notificationChannelIdRecordingChannel"

    const val POLYLINE_THICKNESS = 4F
}
