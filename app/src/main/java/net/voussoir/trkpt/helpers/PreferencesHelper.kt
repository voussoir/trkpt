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

package net.voussoir.trkpt.helpers

import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import android.location.LocationManager
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import net.voussoir.trkpt.Keys
import net.voussoir.trkpt.extensions.getDouble
import net.voussoir.trkpt.extensions.putDouble

/*
 * PreferencesHelper object
 */
object PreferencesHelper
{
    /* The sharedPreferences object to be initialized */
    private lateinit var sharedPreferences: SharedPreferences

    
    /* Initialize a single sharedPreferences object when the app is launched */
    fun Context.initPreferences()
    {
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
    }

    fun load_device_id(): String
    {
        val fallback = random_device_id()
        val v = sharedPreferences.getString(Keys.PREF_DEVICE_ID, fallback).toString()
        if (v == fallback)
        {
            sharedPreferences.edit { putString(Keys.PREF_DEVICE_ID, fallback) }
        }
        Log.i("VOUSSOIR", "PreferencesHelper.load_device_id: Got ${v}.")
        return v
    }

    fun load_database_folder(): String
    {
        return sharedPreferences.getString(Keys.PREF_DATABASE_DIRECTORY, "") ?: ""
    }

    fun save_database_folder(path: String)
    {
        sharedPreferences.edit { putString(Keys.PREF_DATABASE_DIRECTORY, path) }
    }

    fun loadZoomLevel(): Double
    {
        return sharedPreferences.getDouble(Keys.PREF_MAP_ZOOM_LEVEL, Keys.DEFAULT_ZOOM_LEVEL)
    }

    fun saveZoomLevel(zoomLevel: Double)
    {
        sharedPreferences.edit { putDouble(Keys.PREF_MAP_ZOOM_LEVEL, zoomLevel) }
    }

    fun loadTrackingState(): Int {
        return sharedPreferences.getInt(Keys.PREF_TRACKING_STATE, Keys.STATE_STOP)
    }

    fun saveTrackingState(trackingState: Int) {
        sharedPreferences.edit { putInt(Keys.PREF_TRACKING_STATE, trackingState) }
    }

    fun loadUseImperialUnits(): Boolean {
        return sharedPreferences.getBoolean(Keys.PREF_USE_IMPERIAL_UNITS, LengthUnitHelper.useImperialUnits())
    }

    fun load_location_gps(): Boolean {
        return sharedPreferences.getBoolean(Keys.PREF_LOCATION_GPS, false)
    }

    fun load_location_network(): Boolean {
        return sharedPreferences.getBoolean(Keys.PREF_LOCATION_NETWORK, false)
    }

    fun loadOmitRests(): Boolean {
        return sharedPreferences.getBoolean(Keys.PREF_OMIT_RESTS, Keys.DEFAULT_OMIT_RESTS)
    }

    fun loadAllowSleep(): Boolean {
        return sharedPreferences.getBoolean(Keys.PREF_ALLOW_SLEEP, Keys.DEFAULT_ALLOW_SLEEP)
    }

    fun loadShowDebug(): Boolean {
        return sharedPreferences.getBoolean(Keys.PREF_SHOW_DEBUG, Keys.DEFAULT_SHOW_DEBUG)
    }

    fun load_max_accuracy(): Float {
        return sharedPreferences.getInt(Keys.PREF_MAX_ACCURACY, Keys.DEFAULT_MAX_ACCURACY.toInt() * 10) / 10f
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
