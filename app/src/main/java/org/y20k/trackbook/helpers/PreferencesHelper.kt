/*
 * PreferencesHelper.kt
 * Implements the PreferencesHelper object
 * A PreferencesHelper provides helper methods for the saving and loading values from shared preferences
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
import android.content.SharedPreferences
import android.location.Location
import android.location.LocationManager
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.y20k.trackbook.Keys
import org.y20k.trackbook.extensions.getDouble
import org.y20k.trackbook.extensions.putDouble

/*
 * PreferencesHelper object
 */
object PreferencesHelper {

    /* Define log tag */
    private val TAG: String = LogHelper.makeLogTag(PreferencesHelper::class.java)

    /* The sharedPreferences object to be initialized */
    private lateinit var sharedPreferences: SharedPreferences

    
    /* Initialize a single sharedPreferences object when the app is launched */
    fun Context.initPreferences() {
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
    }

    fun load_device_id(): String
    {
        val v = sharedPreferences.getString(Keys.PREF_DEVICE_ID, random_int().toString()).toString();
        Log.i("VOUSSOIR", "Loaded device_id ${v}.")
        return v
    }

    fun loadZoomLevel(): Double {
        return sharedPreferences.getDouble(Keys.PREF_MAP_ZOOM_LEVEL, Keys.DEFAULT_ZOOM_LEVEL)
    }

    fun saveZoomLevel(zoomLevel: Double) {
        sharedPreferences.edit { putDouble(Keys.PREF_MAP_ZOOM_LEVEL, zoomLevel) }
    }

    fun loadTrackingState(): Int {
        return sharedPreferences.getInt(Keys.PREF_TRACKING_STATE, Keys.STATE_TRACKING_STOPPED)
    }

    fun saveTrackingState(trackingState: Int) {
        sharedPreferences.edit { putInt(Keys.PREF_TRACKING_STATE, trackingState) }
    }

    fun loadUseImperialUnits(): Boolean {
        return sharedPreferences.getBoolean(Keys.PREF_USE_IMPERIAL_UNITS, LengthUnitHelper.useImperialUnits())
    }

    fun loadGpsOnly(): Boolean {
        return sharedPreferences.getBoolean(Keys.PREF_GPS_ONLY, false)
    }

    fun loadOmitRests(): Boolean {
        return sharedPreferences.getBoolean(Keys.PREF_OMIT_RESTS, true)
    }

    fun loadCommitInterval(): Int {
        return sharedPreferences.getInt(Keys.PREF_COMMIT_INTERVAL, Keys.COMMIT_INTERVAL)
    }

    /* Loads the state of a map */
    fun loadCurrentBestLocation(): Location {
        val provider: String = sharedPreferences.getString(Keys.PREF_CURRENT_BEST_LOCATION_PROVIDER, LocationManager.NETWORK_PROVIDER) ?: LocationManager.NETWORK_PROVIDER
        // create location
        return Location(provider).apply {
            latitude = sharedPreferences.getDouble(Keys.PREF_CURRENT_BEST_LOCATION_LATITUDE, Keys.DEFAULT_LATITUDE)
            longitude = sharedPreferences.getDouble(Keys.PREF_CURRENT_BEST_LOCATION_LONGITUDE, Keys.DEFAULT_LONGITUDE)
            accuracy = sharedPreferences.getFloat(Keys.PREF_CURRENT_BEST_LOCATION_ACCURACY, Keys.DEFAULT_ACCURACY)
            altitude = sharedPreferences.getDouble(Keys.PREF_CURRENT_BEST_LOCATION_ALTITUDE, Keys.DEFAULT_ALTITUDE)
            time = sharedPreferences.getLong(Keys.PREF_CURRENT_BEST_LOCATION_TIME, Keys.DEFAULT_TIME)
        }

    }

    /* Saves the state of a map */
    fun saveCurrentBestLocation(currentBestLocation: Location) {
        sharedPreferences.edit {
            putDouble(Keys.PREF_CURRENT_BEST_LOCATION_LATITUDE, currentBestLocation.latitude)
            putDouble(Keys.PREF_CURRENT_BEST_LOCATION_LONGITUDE, currentBestLocation.longitude)
            putFloat(Keys.PREF_CURRENT_BEST_LOCATION_ACCURACY, currentBestLocation.accuracy)
            putDouble(Keys.PREF_CURRENT_BEST_LOCATION_ALTITUDE, currentBestLocation.altitude)
            putLong(Keys.PREF_CURRENT_BEST_LOCATION_TIME, currentBestLocation.time)
        }
    }

    fun loadThemeSelection(): String {
        return sharedPreferences.getString(Keys.PREF_THEME_SELECTION, Keys.STATE_THEME_FOLLOW_SYSTEM) ?: Keys.STATE_THEME_FOLLOW_SYSTEM
    }

    /* Checks if housekeeping work needs to be done - used usually in DownloadWorker "REQUEST_UPDATE_COLLECTION" */
    fun isHouseKeepingNecessary(): Boolean {
        return sharedPreferences.getBoolean(Keys.PREF_ONE_TIME_HOUSEKEEPING_NECESSARY, true)
    }

    /* Saves state of housekeeping */
    fun saveHouseKeepingNecessaryState(state: Boolean = false) {
        sharedPreferences.edit { putBoolean(Keys.PREF_ONE_TIME_HOUSEKEEPING_NECESSARY, state) }

    }

    /* Start watching for changes in shared preferences - context must implement OnSharedPreferenceChangeListener */
    fun registerPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
    }

    /* Stop watching for changes in shared preferences - context must implement OnSharedPreferenceChangeListener */
    fun unregisterPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener)
    }
}
