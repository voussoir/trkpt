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
import android.app.PendingIntent
import android.app.Service
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
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
    var tracker_service: TrackerService? = null

    private lateinit var navHostFragment: NavHostFragment
    private lateinit var bottomNavigationView: BottomNavigationView

    /* Overrides onCreate from AppCompatActivity */
    override fun onCreate(savedInstanceState: Bundle?)
    {
        Log.i("VOUSSOIR", "MainActivity.onCreate")
        trackbook = (applicationContext as Trackbook)
        super.onCreate(savedInstanceState)
        request_permissions(this)
        // todo: remove after testing finished
        if (BuildConfig.DEBUG && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        {
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
        bottomNavigationView = findViewById(R.id.bottom_navigation_view)
        // Prevents the UI from flickering when clicking the tab that you are already on.
        // Problem: clicking the Tracks nav while looking at a track should bring you back to the
        // list of tracks.
        // bottomNavigationView.setOnItemReselectedListener { null }
        bottomNavigationView.setupWithNavController(navController = navHostFragment.navController)

        navHostFragment.navController.addOnDestinationChangedListener { _, destination, _ ->
            when (destination.id)
            {
                // Without this, opening a TrackFragment causes the History tab to become inactive.
                // Of course we want the History tab to be active when looking at either a
                // TracklistFragment or TrackFragment.
                R.id.fragment_track ->
                {
                    runOnUiThread {
                        run {
                            // mark menu item "Tracks" as checked
                            bottomNavigationView.menu.findItem(R.id.fragment_history).isChecked = true
                        }
                    }
                }
            }
        }

        // register listener for changes in shared preferences
        PreferencesHelper.registerPreferenceChangeListener(sharedPreferenceChangeListener)

        start_tracker_service()
    }

    fun start_tracker_service()
    {
        Log.i("VOUSSOIR", "MainActivity.start_tracker_service")
        if (tracker_service == null)
        {
            val intent = Intent(this.applicationContext, TrackerService::class.java).apply {
                action = Keys.ACTION_START_SERVICE
            }
            bindService(Intent(this.applicationContext, TrackerService::class.java), tracker_service_connection, Context.BIND_AUTO_CREATE)
            ContextCompat.startForegroundService(this, intent)
            tracker_service?.displayNotification()
        }
    }

    private fun request_permissions(activity: Activity)
    {
        Log.i("VOUSSOIR", "MainActivity.request_permissions")
        val permissions_wanted = arrayOf(
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.MANAGE_EXTERNAL_STORAGE,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACTIVITY_RECOGNITION,
        )
        val permissions_needed = ArrayList<String>()
        for (permission in permissions_wanted)
        {
            if (ContextCompat.checkSelfPermission(applicationContext, permission) != PackageManager.PERMISSION_GRANTED)
            {
                Log.i("VOUSSOIR", "MainActivity.request_permissions: We need " + permission)
                permissions_needed.add(permission)
            }
        }
        val result = requestPermissions(permissions_wanted, 1)
        Log.i("VOUSSOIR", "MainActivity.request_permissions: result " + result)
    }

    /* Overrides onDestroy from AppCompatActivity */
    override fun onDestroy()
    {
        Log.i("VOUSSOIR", "MainActivity.onDestroy")
        super.onDestroy()
        PreferencesHelper.unregisterPreferenceChangeListener(sharedPreferenceChangeListener)
        val stopIntent = Intent(this, TrackerService::class.java).apply {
            action = Keys.ACTION_STOP_SERVICE
        }
        startService(stopIntent)
        unbindService(tracker_service_connection)
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
                Log.i("VOUSSOIR", "MainActivity: database_directory has changed.")
                trackbook.load_database()
            }
        }
    }

    private val tracker_service_connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder)
        {
            Log.i("VOUSSOIR", "MainActivity.onServiceConnected")
            val serviceref = (service as TrackerServiceBinder).service.get()
            if (serviceref == null)
            {
                return
            }
            tracker_service = serviceref
            tracker_service!!.startForeground(Keys.TRACKER_SERVICE_NOTIFICATION_ID, tracker_service!!.displayNotification())
            tracker_service!!.displayNotification()
        }
        override fun onServiceDisconnected(arg0: ComponentName)
        {
            // service has crashed, or was killed by the system
            Log.i("VOUSSOIR", "MainActivity.onServiceDisconnected")
            tracker_service = null
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
