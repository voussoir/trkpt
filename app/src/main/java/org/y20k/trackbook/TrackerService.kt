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
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.Manifest
import android.content.ContentValues
import android.os.*
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.*
import kotlinx.coroutines.Runnable
import org.y20k.trackbook.helpers.*

/*
 * TrackerService class
 */
class TrackerService: Service(), SensorEventListener
{
    /* Define log tag */
    private val TAG: String = LogHelper.makeLogTag(TrackerService::class.java)

    /* Main class variables */
    var trackingState: Int = Keys.STATE_TRACKING_STOPPED
    var gpsProviderActive: Boolean = false
    var networkProviderActive: Boolean = false
    var useImperial: Boolean = false
    var gpsOnly: Boolean = false
    var omitRests: Boolean = true
    var device_id: String = random_device_id()
    var recording_started: Date = GregorianCalendar.getInstance().time
    var commitInterval: Int = Keys.COMMIT_INTERVAL
    var currentBestLocation: Location = getDefaultLocation()
    var lastCommit: Date = Keys.DEFAULT_DATE
    var location_min_time_ms: Long = 0
    var stepCountOffset: Float = 0f
    lateinit var track: Track
    var gpsLocationListenerRegistered: Boolean = false
    var networkLocationListenerRegistered: Boolean = false
    var bound: Boolean = false
    private val binder = LocalBinder()
    private val handler: Handler = Handler(Looper.getMainLooper())
    lateinit var trackbook: Trackbook
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var gpsLocationListener: LocationListener
    private lateinit var networkLocationListener: LocationListener

    private fun addGpsLocationListener()
    {
        if (gpsLocationListenerRegistered)
        {
            LogHelper.v(TAG, "GPS location listener has already been added.")
            return
        }

        gpsProviderActive = isGpsEnabled(locationManager)
        if (! gpsProviderActive)
        {
            LogHelper.w(TAG, "Device GPS is not enabled.")
            return
        }

        val has_permission: Boolean = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (! has_permission)
        {
            LogHelper.w(TAG, "Location permission is not granted.")
            return
        }

        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            location_min_time_ms,
            0f,
            gpsLocationListener,
        )
        gpsLocationListenerRegistered = true
        LogHelper.v(TAG, "Added GPS location listener.")
    }

    private fun addNetworkLocationListener()
    {
        if (gpsOnly)
        {
            LogHelper.v(TAG, "Skipping Network listener. User prefers GPS-only.")
            return
        }

        if (networkLocationListenerRegistered)
        {
            LogHelper.v(TAG, "Network location listener has already been added.")
            return
        }

        networkProviderActive = isNetworkEnabled(locationManager)
        if (!networkProviderActive)
        {
            LogHelper.w(TAG, "Unable to add Network location listener.")
            return
        }

        val has_permission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (! has_permission)
        {
            LogHelper.w(TAG, "Unable to add Network location listener. Location permission is not granted.")
            return
        }

        locationManager.requestLocationUpdates(
            LocationManager.NETWORK_PROVIDER,
            0,
            0f,
            networkLocationListener,
        )
        networkLocationListenerRegistered = true
        LogHelper.v(TAG, "Added Network location listener.")
    }

    private fun createLocationListener(): LocationListener
    {
        return object : LocationListener {
            override fun onLocationChanged(location: Location)
            {
                if (isBetterLocation(location, currentBestLocation)) {
                    currentBestLocation = location
                }
            }
            override fun onProviderEnabled(provider: String)
            {
                LogHelper.v(TAG, "onProviderEnabled $provider")
                when (provider) {
                    LocationManager.GPS_PROVIDER -> gpsProviderActive = isGpsEnabled(locationManager)
                    LocationManager.NETWORK_PROVIDER -> networkProviderActive = isNetworkEnabled(locationManager)
                }
            }
            override fun onProviderDisabled(provider: String)
            {
                LogHelper.v(TAG, "onProviderDisabled $provider")
                when (provider) {
                    LocationManager.GPS_PROVIDER -> gpsProviderActive = isGpsEnabled(locationManager)
                    LocationManager.NETWORK_PROVIDER -> networkProviderActive = isNetworkEnabled(locationManager)
                }
            }
            override fun onStatusChanged(p0: String?, p1: Int, p2: Bundle?)
            {
                // deprecated method
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

    /* Overrides onAccuracyChanged from SensorEventListener */
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int)
    {
        LogHelper.v(TAG, "Accuracy changed: $accuracy")
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
        gpsOnly = PreferencesHelper.loadGpsOnly()
        device_id = PreferencesHelper.load_device_id()
        track = Track(trackbook.database, device_id, start_time=GregorianCalendar.getInstance().time, stop_time=Date(GregorianCalendar.getInstance().time.time + 86400))
        useImperial = PreferencesHelper.loadUseImperialUnits()
        omitRests = PreferencesHelper.loadOmitRests()
        commitInterval = PreferencesHelper.loadCommitInterval()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        sensorManager = this.getSystemService(Context.SENSOR_SERVICE) as SensorManager
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
        LogHelper.i("VOUSSOIR", "TrackerService.onDestroy.")
        super.onDestroy()
        if (trackingState == Keys.STATE_TRACKING_ACTIVE)
        {
            pauseTracking()
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

    /* Overrides onSensorChanged from SensorEventListener */
    override fun onSensorChanged(sensorEvent: SensorEvent?) {
        var steps = 0f
        if (sensorEvent != null)
        {
            if (stepCountOffset == 0f)
            {
                // store steps previously recorded by the system
                stepCountOffset = (sensorEvent.values[0] - 1) - 0 // subtract any steps recorded during this session in case the app was killed
            }
            // calculate step count - subtract steps previously recorded
            steps = sensorEvent.values[0] - stepCountOffset
        }
    }

    /* Overrides onStartCommand from Service */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int
    {
        // SERVICE RESTART (via START_STICKY)
        if (intent == null)
        {
            if (trackingState == Keys.STATE_TRACKING_ACTIVE)
            {
                LogHelper.w(TAG, "Trackbook has been killed by the operating system. Trying to resume recording.")
                startTracking()
            }
        }
        else if (intent.action == Keys.ACTION_STOP)
        {
            pauseTracking()
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
            LogHelper.v(TAG, "Removed GPS location listener.")
        }
        else
        {
            LogHelper.w(TAG, "Unable to remove GPS location listener. Location permission is needed.")
        }
    }

    fun removeNetworkLocationListener()
    {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
        {
            locationManager.removeUpdates(networkLocationListener)
            networkLocationListenerRegistered = false
            LogHelper.v(TAG, "Removed Network location listener.")
        }
        else
        {
            LogHelper.w(TAG, "Unable to remove Network location listener. Location permission is needed.")
        }
    }

    private fun startStepCounter()
    {
        val stepCounterAvailable = sensorManager.registerListener(this, sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER), SensorManager.SENSOR_DELAY_UI)
        if (!stepCounterAvailable)
        {
            LogHelper.w(TAG, "Pedometer sensor not available.")
        }
    }

    fun startTracking(newTrack: Boolean = true)
    {
        addGpsLocationListener()
        addNetworkLocationListener()
        trackingState = Keys.STATE_TRACKING_ACTIVE
        if (newTrack)
        {
            this.recording_started = GregorianCalendar.getInstance().time
        }
        PreferencesHelper.saveTrackingState(trackingState)
        startStepCounter()
        handler.postDelayed(periodicTrackUpdate, 0)
        startForeground(Keys.TRACKER_SERVICE_NOTIFICATION_ID, displayNotification())
    }

    fun pauseTracking()
    {
        trackbook.database.commit()

        trackingState = Keys.STATE_TRACKING_STOPPED
        PreferencesHelper.saveTrackingState(trackingState)

        sensorManager.unregisterListener(this)
        handler.removeCallbacks(periodicTrackUpdate)

        displayNotification()
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    private val sharedPreferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
        when (key)
        {
            Keys.PREF_GPS_ONLY ->
            {
                gpsOnly = PreferencesHelper.loadGpsOnly()
                when (gpsOnly)
                {
                    true -> removeNetworkLocationListener()
                    false -> addNetworkLocationListener()
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
        if (track.trkpts.isEmpty())
        {
            return true
        }
        if (! isDifferentEnough(track.trkpts.last().toLocation(), location, omitRests))
        {
            Log.i("VOUSSOIR", "Omitting due to too close to previous.")
            return false
        }
        return true
    }

    private val periodicTrackUpdate: Runnable = object : Runnable
    {
        override fun run() {
            val now: Date = GregorianCalendar.getInstance().time
            val trkpt = Trkpt(location=currentBestLocation)
            Log.i("VOUSSOIR", "Processing point ${currentBestLocation.latitude}, ${currentBestLocation.longitude} ${now.time}.")
            if (should_keep_point((currentBestLocation)))
            {
                trackbook.database.insert_trkpt(device_id, trkpt)
                track.trkpts.add(trkpt)

                while (track.trkpts.size > 7200)
                {
                    track.trkpts.removeFirst()
                }

                if (now.time - lastCommit.time > Keys.SAVE_TEMP_TRACK_INTERVAL)
                {
                    trackbook.database.commit()
                    lastCommit  = now
                }
            }
            displayNotification()
            handler.postDelayed(this, Keys.TRACKING_INTERVAL)
        }
    }
}
