/*
 * Trackbook.kt
 * Implements the Trackbook class
 * Trackbook is the base Application class that sets up day and night theme
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

import android.Manifest.permission.WRITE_EXTERNAL_STORAGE
import android.app.Application
import android.content.pm.PackageManager
import android.database.Cursor
import android.util.Log
import androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale
import androidx.core.content.ContextCompat
import com.google.android.material.color.DynamicColors
import org.y20k.trackbook.core.Database
import org.y20k.trackbook.core.Homepoint
import org.y20k.trackbook.core.Trkpt
import org.y20k.trackbook.helpers.AppThemeHelper
import org.y20k.trackbook.helpers.LogHelper
import org.y20k.trackbook.helpers.PreferencesHelper
import org.y20k.trackbook.helpers.PreferencesHelper.initPreferences
import org.y20k.trackbook.helpers.iso8601
import org.y20k.trackbook.helpers.iso8601_format
import java.io.File

/*
 * Trackbook.class
 */
class Trackbook(): Application() {
    val database: Database = Database()
    val homepoints: ArrayList<Homepoint> = ArrayList()

    override fun onCreate()
    {
        super.onCreate()
        LogHelper.v("VOUSSOIR", "Trackbook application started.")
        DynamicColors.applyToActivitiesIfAvailable(this)
        // initialize single sharedPreferences object when app is launched
        initPreferences()
        // set Dark / Light theme state
        AppThemeHelper.setTheme(PreferencesHelper.loadThemeSelection())

        ContextCompat.checkSelfPermission(applicationContext, android.Manifest.permission.ACCESS_COARSE_LOCATION)
        ContextCompat.checkSelfPermission(applicationContext, android.Manifest.permission.ACCESS_FINE_LOCATION)
        Log.i("VOUSSOIR", "Device ID = ${PreferencesHelper.load_device_id()}")
        if (ContextCompat.checkSelfPermission(applicationContext, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)
        {
            this.database.connect(File("/storage/emulated/0/Syncthing/GPX/trkpt_${PreferencesHelper.load_device_id()}.db"))
        }
    }

    fun load_homepoints()
    {
        this.homepoints.clear()
        homepoint_generator().forEach { homepoint -> this.homepoints.add(homepoint) }
    }

    fun homepoint_generator() = iterator<Homepoint>
    {
        if (! database.ready)
        {
            return@iterator
        }
        val cursor: Cursor = database.connection.query(
            "homepoints",
            arrayOf("lat", "lon", "radius", "name"),
            null,
            null,
            null,
            null,
            null,
            null,
        )
        val COLUMN_LAT = cursor.getColumnIndex("lat")
        val COLUMN_LON = cursor.getColumnIndex("lon")
        val COLUMN_RADIUS = cursor.getColumnIndex("radius")
        val COLUMN_NAME = cursor.getColumnIndex("name")
        try
        {
            while (cursor.moveToNext())
            {
                val homepoint = Homepoint(
                    latitude=cursor.getDouble(COLUMN_LAT),
                    longitude=cursor.getDouble(COLUMN_LON),
                    radius=cursor.getDouble(COLUMN_RADIUS),
                    name=cursor.getString(COLUMN_NAME),
                )
                yield(homepoint)
            }
        }
        finally
        {
            cursor.close();
        }
    }

    override fun onTerminate()
    {
        super.onTerminate()
        LogHelper.v("VOUSSOIR", "Trackbook application terminated.")
        database.close()
    }
}