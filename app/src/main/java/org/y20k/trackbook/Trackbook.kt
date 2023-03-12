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

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import android.database.Cursor
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.color.DynamicColors
import org.y20k.trackbook.helpers.AppThemeHelper
import org.y20k.trackbook.helpers.LogHelper
import org.y20k.trackbook.helpers.PreferencesHelper
import org.y20k.trackbook.helpers.PreferencesHelper.initPreferences
import java.io.File


interface DatabaseChangedListener
{
    fun database_changed()
}

class Trackbook : Application() {
    val database: Database = Database(this)
    val homepoints: ArrayList<Homepoint> = ArrayList()
    val database_changed_listeners = ArrayList<DatabaseChangedListener>()

    fun call_database_changed_listeners()
    {
        for (listener in this.database_changed_listeners)
        {
            listener.database_changed()
        }
    }

    override fun onCreate()
    {
        super.onCreate()
        LogHelper.v("VOUSSOIR", "Trackbook application started.")
        DynamicColors.applyToActivitiesIfAvailable(this)
        // initialize single sharedPreferences object when app is launched
        initPreferences()
        // set Dark / Light theme state
        AppThemeHelper.setTheme(PreferencesHelper.loadThemeSelection())

        Log.i("VOUSSOIR", "Device ID = ${PreferencesHelper.load_device_id()}")
    }

    fun load_database()
    {
        Log.i("VOUSSOIR", "Trackbook.load_database")
        val folder = PreferencesHelper.load_database_folder()
        this.database.commit()
        if (this.database.ready)
        {
            this.database.close()
        }
        if (folder == "")
        {
            this.database.ready = false
            return
        }
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)
        {
            this.database.connect(File(folder + "/trkpt_${PreferencesHelper.load_device_id()}.db"))
            this.load_homepoints()
        }
        else
        {
            this.database.ready = false
        }
        this.call_database_changed_listeners()
    }

    fun load_homepoints()
    {
        Log.i("VOUSSOIR", "Trackbook.load_homepoints")
        this.homepoints.clear()
        homepoint_generator().forEach { homepoint -> this.homepoints.add(homepoint) }
    }

    fun homepoint_generator() = iterator<Homepoint>
    {
        if (! database.ready)
        {
            Log.i("VOUSSOIR", "Trackbook.homepoint_generator: database is not ready.")
            return@iterator
        }
        val cursor: Cursor = database.connection.rawQuery(
            "SELECT id, lat, lon, radius, name FROM homepoints",
            arrayOf()
        )
        Log.i("VOUSSOIR", "Trackbook.homepoint_generator: Got ${cursor.count} homepoints.")
        val COLUMN_ID = cursor.getColumnIndex("id")
        val COLUMN_LAT = cursor.getColumnIndex("lat")
        val COLUMN_LON = cursor.getColumnIndex("lon")
        val COLUMN_RADIUS = cursor.getColumnIndex("radius")
        val COLUMN_NAME = cursor.getColumnIndex("name")
        try
        {
            while (cursor.moveToNext())
            {
                val homepoint = Homepoint(
                    id=cursor.getLong(COLUMN_ID),
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
            cursor.close()
        }
    }

    override fun onTerminate()
    {
        super.onTerminate()
        LogHelper.v("VOUSSOIR", "Trackbook application terminated.")
        database.close()
    }
}