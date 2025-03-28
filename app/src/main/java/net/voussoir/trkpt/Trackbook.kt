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

/*
 * Modified by voussoir for trkpt, forked from Trackbook.
 */

package net.voussoir.trkpt

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.database.Cursor
import android.util.Log
import com.google.android.material.color.DynamicColors
import net.voussoir.trkpt.helpers.AppThemeHelper
import net.voussoir.trkpt.helpers.PreferencesHelper
import net.voussoir.trkpt.helpers.PreferencesHelper.initPreferences
import java.io.File


interface DatabaseChangedListener
{
    fun database_changed()
}

class Trackbook : Application()
{
    val database = Database(this)
    val homepoints: ArrayDeque<Homepoint> = ArrayDeque()
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
        Log.i("VOUSSOIR", "Trackbook.onCreate")
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
        if (this.database.ready)
        {
            Log.i("VOUSSOIR", "Trackbook.load_database: closing and re-opening.")
            this.database.commit()
            this.database.close()
        }
        if (folder == "")
        {
            Log.i("VOUSSOIR", "Trackbook.load_database: folder came up blank.")
            this.database.ready = false
            return
        }
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)
        {
            val device_id = PreferencesHelper.load_device_id()
            this.database.connect(File(folder + "/trkpt_${device_id}.db"))
            this.load_homepoints()
        }
        else
        {
            Log.i("VOUSSOIR", "Trackbook.load_database: lacking WRITE_EXTERNAL_STORAGE permission.")
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
        Log.i("VOUSSOIR", "Trackbook.onTerminate")
        database.close()
    }
}