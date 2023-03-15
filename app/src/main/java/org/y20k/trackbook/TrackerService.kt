/*
 * TrackerService.kt
 * Implements the app's movement tracker service
 * The TrackerService keeps track of the current location
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

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.Manifest
import android.os.*
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.*
import org.y20k.trackbook.helpers.*

/*
 * TrackerService class
 */
class TrackerService: Service()
{
    /* Main class variables */
    var trackingState: Int = Keys.STATE_TRACKING_STOPPED
    var gpsProviderActive: Boolean = false
    var networkProviderActive: Boolean = false
    var useImperial: Boolean = false
    var use_gps_location: Boolean = false
    var use_network_location: Boolean = false
    var omitRests: Boolean = true
    var device_id: String = random_device_id()
    var currentBestLocation: Location = getDefaultLocation()
    var lastCommit: Long = 0
    var location_min_time_ms: Long = 0
    private val RECENT_TRKPT_COUNT = 7200
    lateinit var recent_trkpts: Deque<Trkpt>
    lateinit var recent_displacement_trkpts: Deque<Trkpt>
    var gpsLocationListenerRegistered: Boolean = false
    var networkLocationListenerRegistered: Boolean = false
    var bound: Boolean = false
    private val binder = LocalBinder()
    lateinit var trackbook: Trackbook
    private lateinit var locationManager: LocationManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var gpsLocationListener: LocationListener
    private lateinit var networkLocationListener: LocationListener

    private fun addGpsLocationListener()
    {
        if (! use_gps_location)
        {
            Log.v("VOUSSOIR", "Skipping GPS listener.")
            return
        }

        if (gpsLocationListenerRegistered)
        {
            Log.v("VOUSSOIR", "GPS location listener has already been added.")
            return
        }

        gpsProviderActive = isGpsEnabled(locationManager)
        if (! gpsProviderActive)
        {
            Log.w("VOUSSOIR", "Device GPS is not enabled.")
            return
        }

        val has_permission: Boolean = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (! has_permission)
        {
            Log.w("VOUSSOIR", "Location permission is not granted.")
            return
        }

        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            location_min_time_ms,
            0f,
            gpsLocationListener,
        )
        gpsLocationListenerRegistered = true
        Log.v("VOUSSOIR", "Added GPS location listener.")
    }

    private fun addNetworkLocationListener()
    {
        if (! use_network_location)
        {
            Log.v("VOUSSOIR", "Skipping Network listener.")
            return
        }

        if (networkLocationListenerRegistered)
        {
            Log.v("VOUSSOIR", "Network location listener has already been added.")
            return
        }

        networkProviderActive = isNetworkEnabled(locationManager)
        if (!networkProviderActive)
        {
            Log.w("VOUSSOIR", "Unable to add Network location listener.")
            return
        }

        val has_permission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (! has_permission)
        {
            Log.w("VOUSSOIR", "Unable to add Network location listener. Location permission is not granted.")
            return
        }

        locationManager.requestLocationUpdates(
            LocationManager.NETWORK_PROVIDER,
            0,
            0f,
            networkLocationListener,
        )
        networkLocationListenerRegistered = true
        Log.v("VOUSSOIR", "Added Network location listener.")
    }

    private fun createLocationListener(): LocationListener
    {
        return object : LocationListener {
            override fun onLocationChanged(location: Location)
            {
                if (! isBetterLocation(location, currentBestLocation))
                {
                    return
                }
                currentBestLocation = location
                if (trackingState != Keys.STATE_TRACKING_ACTIVE)
                {
                    return
                }
                Log.i("VOUSSOIR", "Processing point ${location.latitude}, ${location.longitude} ${location.time}.")
                if (should_keep_point((location)))
                {
                    val now: Long = location.time
                    // val now: Date = GregorianCalendar.getInstance().time
                    val trkpt = Trkpt(location=location)
                    trackbook.database.insert_trkpt(device_id, trkpt)
                    recent_trkpts.add(trkpt)
                    while (recent_trkpts.size > RECENT_TRKPT_COUNT)
                    {
                        recent_trkpts.removeFirst()
                    }

                    recent_displacement_trkpts.add(trkpt)
                    while (recent_displacement_trkpts.size > 5)
                    {
                        recent_displacement_trkpts.removeFirst()
                    }

                    if (now - lastCommit > Keys.COMMIT_INTERVAL)
                    {
                        trackbook.database.commit()
                        lastCommit  = now
                    }
                }
                displayNotification()
            }
            override fun onProviderEnabled(provider: String)
            {
                Log.v("VOUSSOIR", "onProviderEnabled $provider")
                when (provider) {
                    LocationManager.GPS_PROVIDER -> gpsProviderActive = isGpsEnabled(locationManager)
                    LocationManager.NETWORK_PROVIDER -> networkProviderActive = isNetworkEnabled(locationManager)
                }
            }
            override fun onProviderDisabled(provider: String)
            {
                Log.v("VOUSSOIR", "onProviderDisabled $provider")
                when (provider) {
                    LocationManager.GPS_PROVIDER -> gpsProviderActive = isGpsEnabled(locationManager)
                    LocationManager.NETWORK_PROVIDER -> networkProviderActive = isNetworkEnabled(locationManager)
                }
            }
        }
    }

    /* Displays or updates notification */
    private fun displayNotification(): Notification
    {
        val notification: Notification = notificationHelper.createNotification(
            trackingState,
            iso8601(GregorianCalendar.getInstance().time)
        )
        notificationManager.notify(Keys.TRACKER_SERVICE_NOTIFICATION_ID, notification)
        return notification
    }

    /* Overrides onBind from Service */
    override fun onBind(p0: Intent?): IBinder
    {
        bound = true
        // start receiving location updates
        addGpsLocationListener()
        addNetworkLocationListener()
        // return reference to this service
        return binder
    }

    /* Overrides onCreate from Service */
    override fun onCreate()
    {
        super.onCreate()
        trackbook = (applicationContext as Trackbook)
        trackbook.load_homepoints()
        recent_trkpts = ArrayDeque<Trkpt>(RECENT_TRKPT_COUNT)
        recent_displacement_trkpts = ArrayDeque<Trkpt>(5)
        use_gps_location = PreferencesHelper.load_location_gps()
        use_network_location = PreferencesHelper.load_location_network()
        device_id = PreferencesHelper.load_device_id()
        useImperial = PreferencesHelper.loadUseImperialUnits()
        omitRests = PreferencesHelper.loadOmitRests()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationHelper = NotificationHelper(this)
        gpsProviderActive = isGpsEnabled(locationManager)
        networkProviderActive = isNetworkEnabled(locationManager)
        gpsLocationListener = createLocationListener()
        networkLocationListener = createLocationListener()
        trackingState = PreferencesHelper.loadTrackingState()
        currentBestLocation = getLastKnownLocation(this)
        PreferencesHelper.registerPreferenceChangeListener(sharedPreferenceChangeListener)
    }

    /* Overrides onDestroy from Service */
    override fun onDestroy()
    {
        Log.i("VOUSSOIR", "TrackerService.onDestroy.")
        super.onDestroy()
        if (trackingState == Keys.STATE_TRACKING_ACTIVE)
        {
            stopTracking()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationManager.cancel(Keys.TRACKER_SERVICE_NOTIFICATION_ID) // this call was not necessary prior to Android 12
        PreferencesHelper.unregisterPreferenceChangeListener(sharedPreferenceChangeListener)
        removeGpsLocationListener()
        removeNetworkLocationListener()
    }

    /* Overrides onRebind from Service */
    override fun onRebind(intent: Intent?)
    {
        bound = true
        addGpsLocationListener()
        addNetworkLocationListener()
    }

    /* Overrides onStartCommand from Service */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int
    {
        // SERVICE RESTART (via START_STICKY)
        if (intent == null)
        {
            if (trackingState == Keys.STATE_TRACKING_ACTIVE)
            {
                Log.w("VOUSSOIR", "Trackbook has been killed by the operating system. Trying to resume recording.")
                startTracking()
            }
        }
        else if (intent.action == Keys.ACTION_STOP)
        {
            stopTracking()
        }
        else if (intent.action == Keys.ACTION_START)
        {
            startTracking()
        }

        // START_STICKY is used for services that are explicitly started and stopped as needed
        return START_STICKY
    }

    /* Overrides onUnbind from Service */
    override fun onUnbind(intent: Intent?): Boolean
    {
        bound = false
        // stop receiving location updates - if not tracking
        if (trackingState != Keys.STATE_TRACKING_ACTIVE)
        {
            removeGpsLocationListener()
            removeNetworkLocationListener()
        }
        // ensures onRebind is called
        return true
    }

    /* Adds location listeners to location manager */
    fun removeGpsLocationListener()
    {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
        {
            locationManager.removeUpdates(gpsLocationListener)
            gpsLocationListenerRegistered = false
            Log.v("VOUSSOIR", "Removed GPS location listener.")
        }
        else
        {
            Log.w("VOUSSOIR", "Unable to remove GPS location listener. Location permission is needed.")
        }
    }

    fun removeNetworkLocationListener()
    {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
        {
            locationManager.removeUpdates(networkLocationListener)
            networkLocationListenerRegistered = false
            Log.v("VOUSSOIR", "Removed Network location listener.")
        }
        else
        {
            Log.w("VOUSSOIR", "Unable to remove Network location listener. Location permission is needed.")
        }
    }

    fun startTracking()
    {
        addGpsLocationListener()
        addNetworkLocationListener()
        trackingState = Keys.STATE_TRACKING_ACTIVE
        PreferencesHelper.saveTrackingState(trackingState)
        recent_displacement_trkpts.clear()
        startForeground(Keys.TRACKER_SERVICE_NOTIFICATION_ID, displayNotification())
    }

    fun stopTracking()
    {
        trackbook.database.commit()

        trackingState = Keys.STATE_TRACKING_STOPPED
        PreferencesHelper.saveTrackingState(trackingState)
        recent_displacement_trkpts.clear()
        displayNotification()
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    private val sharedPreferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
        when (key)
        {
            Keys.PREF_LOCATION_GPS ->
            {
                use_gps_location = PreferencesHelper.load_location_gps()
                if (use_gps_location)
                {
                    addGpsLocationListener()
                }
                else
                {
                    removeGpsLocationListener()
                }
            }
            Keys.PREF_LOCATION_NETWORK ->
            {
                use_network_location = PreferencesHelper.load_location_network()
                if (use_network_location)
                {
                    addNetworkLocationListener()
                }
                else
                {
                    removeNetworkLocationListener()
                }
            }
            Keys.PREF_USE_IMPERIAL_UNITS ->
            {
                useImperial = PreferencesHelper.loadUseImperialUnits()
            }
            Keys.PREF_OMIT_RESTS ->
            {
                omitRests = PreferencesHelper.loadOmitRests()
            }
            Keys.PREF_DEVICE_ID ->
            {
                device_id = PreferencesHelper.load_device_id()
            }
        }
    }
    /*
     * End of declaration
     */

    /*
     * Inner class: Local Binder that returns this service
     */
    inner class LocalBinder : Binder() {
        val service: TrackerService = this@TrackerService
    }
    /*
     * End of inner class
     */

    fun should_keep_point(location: Location): Boolean
    {
        if(! trackbook.database.ready)
        {
            Log.i("VOUSSOIR", "Omitting due to database not ready.")
            return false
        }
        if (location.latitude == 0.0 || location.longitude == 0.0)
        {
            Log.i("VOUSSOIR", "Omitting due to 0,0 location.")
            return false
        }
        if (! isRecentEnough(location))
        {
            Log.i("VOUSSOIR", "Omitting due to not recent enough.")
            return false
        }
        if (! isAccurateEnough(location, Keys.DEFAULT_THRESHOLD_LOCATION_ACCURACY))
        {
            Log.i("VOUSSOIR", "Omitting due to not accurate enough.")
            return false
        }
        for (homepoint in trackbook.homepoints)
        {
            if (homepoint.location.distanceTo(location) < homepoint.radius)
            {
                Log.i("VOUSSOIR", "Omitting due to homepoint ${homepoint.name}.")
                return false
            }
        }
        if (recent_displacement_trkpts.isEmpty())
        {
            return true
        }
        if (! isDifferentEnough(recent_displacement_trkpts.first().toLocation(), location, omitRests))
        {
            Log.i("VOUSSOIR", "Omitting due to too close to previous.")
            return false
        }
        return true
    }
}
