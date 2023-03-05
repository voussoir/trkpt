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


package org.y20k.trackbook

import android.Manifest
import android.app.Activity
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.StrictMode
import android.os.StrictMode.VmPolicy
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.osmdroid.config.Configuration
import org.y20k.trackbook.helpers.AppThemeHelper
import org.y20k.trackbook.helpers.LogHelper
import org.y20k.trackbook.helpers.PreferencesHelper

private const val REQUEST_EXTERNAL_STORAGE = 1
private val PERMISSIONS_STORAGE = arrayOf<String>(
    Manifest.permission.READ_EXTERNAL_STORAGE,
    Manifest.permission.WRITE_EXTERNAL_STORAGE
)

/**
 * Checks if the app has permission to write to device storage
 *
 * If the app does not has permission then the user will be prompted to grant permissions
 *
 * @param activity
 */
fun verifyStoragePermissions(activity: Activity?)
{
    // Check if we have write permission
    val permission = ActivityCompat.checkSelfPermission(activity!!,
        Manifest.permission.WRITE_EXTERNAL_STORAGE)
    if (permission != PackageManager.PERMISSION_GRANTED)
    {
        // We don't have permission so prompt the user
        ActivityCompat.requestPermissions(
            activity,
            PERMISSIONS_STORAGE,
            REQUEST_EXTERNAL_STORAGE
        )
    }
}

/*
 * MainActivity class
 */
class MainActivity : AppCompatActivity() {

    /* Define log tag */
    private val TAG: String = LogHelper.makeLogTag(MainActivity::class.java)


    /* Main class variables */
    private lateinit var navHostFragment: NavHostFragment
    private lateinit var bottomNavigationView: BottomNavigationView


    /* Overrides onCreate from AppCompatActivity */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        verifyStoragePermissions(this)
        // todo: remove after testing finished
        if (BuildConfig.DEBUG && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            StrictMode.setVmPolicy(
                VmPolicy.Builder()
                    .detectNonSdkApiUsage()
                    .penaltyLog()
                    .build()
            )
        }

        // set user agent to prevent getting banned from the osm servers
        Configuration.getInstance().userAgentValue = BuildConfig.APPLICATION_ID
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
            when (destination.id) {
                R.id.fragment_track -> {
                    runOnUiThread {
                        run {
                            // mark menu item "Tracks" as checked
                            bottomNavigationView.menu.findItem(R.id.tracklist_fragment).isChecked = true
                        }
                    }
                }
                else -> {
                    // do nothing
                }
            }
        }

        // register listener for changes in shared preferences
        PreferencesHelper.registerPreferenceChangeListener(sharedPreferenceChangeListener)
    }


    /* Overrides onDestroy from AppCompatActivity */
    override fun onDestroy() {
        super.onDestroy()
        // unregister listener for changes in shared preferences
        PreferencesHelper.unregisterPreferenceChangeListener(sharedPreferenceChangeListener)
    }


    /*
     * Defines the listener for changes in shared preferences
     */
    private val sharedPreferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
        when (key) {
            Keys.PREF_THEME_SELECTION -> {
                AppThemeHelper.setTheme(PreferencesHelper.loadThemeSelection())
            }
        }
    }
    /*
     * End of declaration
     */
}
