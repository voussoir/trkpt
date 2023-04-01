/*
 * MainActivity.kt
 * Implements the main activity of the app
 * The MainActivity hosts fragments for: current map, track list,  settings
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
import android.app.Activity
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.StrictMode
import android.os.StrictMode.VmPolicy
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.osmdroid.config.Configuration
import net.voussoir.trkpt.helpers.AppThemeHelper
import net.voussoir.trkpt.helpers.PreferencesHelper

class MainActivity: AppCompatActivity()
{
    lateinit var trackbook: Trackbook
    private lateinit var navHostFragment: NavHostFragment
    private lateinit var bottomNavigationView: BottomNavigationView

    /* Overrides onCreate from AppCompatActivity */
    override fun onCreate(savedInstanceState: Bundle?)
    {
        trackbook = (applicationContext as Trackbook)
        super.onCreate(savedInstanceState)
        request_permissions(this)
        // todo: remove after testing finished
        if (net.voussoir.trkpt.BuildConfig.DEBUG && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        {
            StrictMode.setVmPolicy(
                VmPolicy.Builder()
                    .detectNonSdkApiUsage()
                    .penaltyLog()
                    .build()
            )
        }

        // set user agent to prevent getting banned from the osm servers
        Configuration.getInstance().userAgentValue = net.voussoir.trkpt.BuildConfig.APPLICATION_ID
        // set the path for osmdroid's files (e.g. tile cache)
        Configuration.getInstance().osmdroidBasePath = this.getExternalFilesDir(null)

        // set up views
        setContentView(R.layout.activity_main)
        navHostFragment  = supportFragmentManager.findFragmentById(R.id.main_container) as NavHostFragment
        bottomNavigationView = findViewById<BottomNavigationView>(R.id.bottom_navigation_view)
        // Prevents the UI from flickering when clicking the tab that you are already on.
        // Problem: clicking the Tracks nav while looking at a track should bring you back to the
        // list of tracks.
        // bottomNavigationView.setOnItemReselectedListener { null }
        bottomNavigationView.setupWithNavController(navController = navHostFragment.navController)

        // listen for navigation changes
        navHostFragment.navController.addOnDestinationChangedListener { _, destination, _ ->
            when (destination.id)
            {
                R.id.fragment_track ->
                {
                    runOnUiThread {
                        run {
                            // mark menu item "Tracks" as checked
                            bottomNavigationView.menu.findItem(R.id.tracklist_fragment).isChecked = true
                        }
                    }
                }
                else ->
                {
                    // do nothing
                }
            }
        }

        // register listener for changes in shared preferences
        PreferencesHelper.registerPreferenceChangeListener(sharedPreferenceChangeListener)
    }

    private fun request_permissions(activity: Activity)
    {
        Log.i("VOUSSOIR", "MainActivity requests permissions.")
        val permissions_wanted = arrayOf(
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACTIVITY_RECOGNITION,
        )
        val permissions_needed = ArrayList<String>()
        for (permission in permissions_wanted)
        {
            if (ContextCompat.checkSelfPermission(applicationContext, permission) != PackageManager.PERMISSION_GRANTED)
            {
                Log.i("VOUSSOIR", "We need " + permission)
                permissions_needed.add(permission)
            }
        }
        val result = requestPermissions(permissions_wanted, 1)
        Log.i("VOUSSOIR", "Permissions result " + result)
    }

    /* Overrides onDestroy from AppCompatActivity */
    override fun onDestroy()
    {
        super.onDestroy()
        // unregister listener for changes in shared preferences
        PreferencesHelper.unregisterPreferenceChangeListener(sharedPreferenceChangeListener)
    }

    /*
     * Defines the listener for changes in shared preferences
     */
    private val sharedPreferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
        when (key)
        {
            Keys.PREF_THEME_SELECTION ->
            {
                AppThemeHelper.setTheme(PreferencesHelper.loadThemeSelection())
            }

            Keys.PREF_DEVICE_ID ->
            {
                Log.i("VOUSSOIR", "MainActivity: device_id has changed.")
                trackbook.load_database()
            }

            Keys.PREF_DATABASE_DIRECTORY ->
            {
                trackbook.load_database()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    )
    {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Log.i("VOUSSOIR", "MainActivity tries to load the database.")
        trackbook.load_database()
    }
}
