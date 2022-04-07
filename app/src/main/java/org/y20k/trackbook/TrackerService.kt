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
import android.os.*
import androidx.core.content.ContextCompat
import java.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.Runnable
import org.y20k.trackbook.core.Track
import org.y20k.trackbook.core.load_temp_track
import org.y20k.trackbook.helpers.*

/*
 * TrackerService class
 */
class TrackerService: Service(), SensorEventListener
{
    /* Define log tag */
    private val TAG: String = LogHelper.makeLogTag(TrackerService::class.java)

    /* Main class variables */
    var trackingState: Int = Keys.STATE_TRACKING_NOT_STARTED
    var gpsProviderActive: Boolean = false
    var networkProviderActive: Boolean = false
    var useImperial: Boolean = false
    var gpsOnly: Boolean = false
    var omitRests: Boolean = true
    var currentBestLocation: Location = LocationHelper.getDefaultLocation()
    var lastSave: Date = Keys.DEFAULT_DATE
    var stepCountOffset: Float = 0f
    // The resumed flag will be true for the first point that is received after unpausing a
    // recording, so that the distance travelled while paused is not added to the track.distance.
    var resumed: Boolean = false
    var track: Track = Track()
    var gpsLocationListenerRegistered: Boolean = false
    var networkLocationListenerRegistered: Boolean = false
    var bound: Boolean = false
    private val binder = LocalBinder()
    private val handler: Handler = Handler(Looper.getMainLooper())
    private var altitudeValues: SimpleMovingAverageQueue = SimpleMovingAverageQueue(Keys.DEFAULT_ALTITUDE_SMOOTHING_VALUE)
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var gpsLocationListener: LocationListener
    private lateinit var networkLocationListener: LocationListener

    /* Adds a GPS location listener to location manager */
    private fun addGpsLocationListener()
    {
        if (gpsLocationListenerRegistered)
        {
            LogHelper.v(TAG, "GPS location listener has already been added.")
            return
        }

        gpsProviderActive = LocationHelper.isGpsEnabled(locationManager)
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
            0,
            0f,
            gpsLocationListener,
        )
        gpsLocationListenerRegistered = true
        LogHelper.v(TAG, "Added GPS location listener.")
    }

    /* Adds a Network location listener to location manager */
    private fun addNetworkLocationListener() {
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

        networkProviderActive = LocationHelper.isNetworkEnabled(locationManager)
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

    fun clearTrack()
    {
        track = Track()
        resumed = false
        FileHelper.delete_temp_file(this as Context)
        trackingState = Keys.STATE_TRACKING_NOT_STARTED
        PreferencesHelper.saveTrackingState(trackingState)
        stopForeground(true)
        notificationManager.cancel(Keys.TRACKER_SERVICE_NOTIFICATION_ID) // this call was not necessary prior to Android 12
    }

    private fun createLocationListener(): LocationListener
    {
        return object : LocationListener {
            override fun onLocationChanged(location: Location) {
                // update currentBestLocation if a better location is available
                if (LocationHelper.isBetterLocation(location, currentBestLocation)) {
                    currentBestLocation = location
                }
            }
            override fun onProviderEnabled(provider: String)
            {
                LogHelper.v(TAG, "onProviderEnabled $provider")
                when (provider) {
                    LocationManager.GPS_PROVIDER -> gpsProviderActive = LocationHelper.isGpsEnabled(
                        locationManager
                    )
                    LocationManager.NETWORK_PROVIDER -> networkProviderActive =
                        LocationHelper.isNetworkEnabled(
                            locationManager
                        )
                }
            }
            override fun onProviderDisabled(provider: String)
            {
                LogHelper.v(TAG, "onProviderDisabled $provider")
                when (provider) {
                    LocationManager.GPS_PROVIDER -> gpsProviderActive = LocationHelper.isGpsEnabled(
                        locationManager
                    )
                    LocationManager.NETWORK_PROVIDER -> networkProviderActive =
                        LocationHelper.isNetworkEnabled(
                            locationManager
                        )
                }
            }
            override fun onStatusChanged(p0: String?, p1: Int, p2: Bundle?)
            {
                // deprecated method
            }
        }
    }

    /* Displays or updates notification */
    private fun displayNotification(): Notification {
        val notification: Notification = notificationHelper.createNotification(
            trackingState,
            track.distance,
            track.duration,
            useImperial
        )
        notificationManager.notify(Keys.TRACKER_SERVICE_NOTIFICATION_ID, notification)
        return notification
    }

    /* Overrides onAccuracyChanged from SensorEventListener */
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
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
        gpsOnly = PreferencesHelper.loadGpsOnly()
        useImperial = PreferencesHelper.loadUseImperialUnits()
        omitRests = PreferencesHelper.loadOmitRests()

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        sensorManager = this.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationHelper = NotificationHelper(this)
        gpsProviderActive = LocationHelper.isGpsEnabled(locationManager)
        networkProviderActive = LocationHelper.isNetworkEnabled(locationManager)
        gpsLocationListener = createLocationListener()
        networkLocationListener = createLocationListener()
        trackingState = PreferencesHelper.loadTrackingState()
        currentBestLocation = LocationHelper.getLastKnownLocation(this)
        track = load_temp_track(this)
//        altitudeValues.capacity = PreferencesHelper.loadAltitudeSmoothingValue()
        PreferencesHelper.registerPreferenceChangeListener(sharedPreferenceChangeListener)
    }

    /* Overrides onDestroy from Service */
    override fun onDestroy() {
        super.onDestroy()
        LogHelper.i(TAG, "onDestroy called.")
        if (trackingState == Keys.STATE_TRACKING_ACTIVE)
        {
            stopTracking()
        }
        stopForeground(true)
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
        var steps: Float = 0f
        if (sensorEvent != null) {
            if (stepCountOffset == 0f) {
                // store steps previously recorded by the system
                stepCountOffset = (sensorEvent.values[0] - 1) - track.stepCount // subtract any steps recorded during this session in case the app was killed
            }
            // calculate step count - subtract steps previously recorded
            steps = sensorEvent.values[0] - stepCountOffset
        }
        // update step count in track
        track.stepCount = steps
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
                resumeTracking()
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
        else if (intent.action == Keys.ACTION_RESUME)
        {
            resumeTracking()
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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationManager.removeUpdates(gpsLocationListener)
            gpsLocationListenerRegistered = false
            LogHelper.v(TAG, "Removed GPS location listener.")
        } else {
            LogHelper.w(TAG, "Unable to remove GPS location listener. Location permission is needed.")
        }
    }

    /* Adds location listeners to location manager */
    fun removeNetworkLocationListener()
    {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationManager.removeUpdates(networkLocationListener)
            networkLocationListenerRegistered = false
            LogHelper.v(TAG, "Removed Network location listener.")
        } else {
            LogHelper.w(TAG, "Unable to remove Network location listener. Location permission is needed.")
        }
    }

    /* Resume tracking after stop/pause */
    fun resumeTracking()
    {
        // load temp track - returns an empty track if there is no temp file.
        track = load_temp_track(this)
        // try to mark last waypoint as stopover
        if (track.wayPoints.size > 0) {
            val lastWayPointIndex = track.wayPoints.size - 1
            track.wayPoints[lastWayPointIndex].isStopOver = true
        }
        resumed = true
        // calculate length of recording break
        track.recordingPaused += TrackHelper.calculateDurationOfPause(track.recordingStop)
        startTracking(newTrack = false)
    }

    private fun startStepCounter()
    {
        val stepCounterAvailable = sensorManager.registerListener(this, sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER), SensorManager.SENSOR_DELAY_UI)
        if (!stepCounterAvailable) {
            LogHelper.w(TAG, "Pedometer sensor not available.")
            track.stepCount = -1f
        }
    }

    fun startTracking(newTrack: Boolean = true)
    {
        addGpsLocationListener()
        addNetworkLocationListener()
        // set up new track
        if (newTrack) {
            track = Track()
            resumed = false
            stepCountOffset = 0f
        }
        trackingState = Keys.STATE_TRACKING_ACTIVE
        PreferencesHelper.saveTrackingState(trackingState)
        startStepCounter()
        handler.postDelayed(periodicTrackUpdate, 0)
        startForeground(Keys.TRACKER_SERVICE_NOTIFICATION_ID, displayNotification())
    }

    fun stopTracking()
    {
        track.recordingStop = GregorianCalendar.getInstance().time
        val context: Context = this
        CoroutineScope(IO).launch { track.save_temp_suspended(context) }

        trackingState = Keys.STATE_TRACKING_PAUSED
        PreferencesHelper.saveTrackingState(trackingState)

        altitudeValues.reset()

        sensorManager.unregisterListener(this)
        handler.removeCallbacks(periodicTrackUpdate)

        displayNotification()
        stopForeground(false)
    }

    /*
     * Defines the listener for changes in shared preferences
     */
    private val sharedPreferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
        when (key) {
            // preference "Restrict to GPS"
            Keys.PREF_GPS_ONLY -> {
                gpsOnly = PreferencesHelper.loadGpsOnly()
                when (gpsOnly) {
                    true -> removeNetworkLocationListener()
                    false -> addNetworkLocationListener()
                }
            }
            // preference "Use Imperial Measurements"
            Keys.PREF_USE_IMPERIAL_UNITS -> {
                useImperial = PreferencesHelper.loadUseImperialUnits()
            }
            // preference "Recording Accuracy"
            Keys.PREF_OMIT_RESTS -> {
                omitRests = PreferencesHelper.loadOmitRests()
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

    /*
     * Runnable: Periodically track updates (if recording active)
     */
    private val periodicTrackUpdate: Runnable = object : Runnable
    {
        override fun run() {
            // add waypoint to track - step count is continuously updated in onSensorChanged
            val success = track.add_waypoint(currentBestLocation, omitRests, resumed)
            if (success) {
                resumed = false

                // store previous smoothed altitude
                val previousAltitude: Double = altitudeValues.getAverage()
                // put current altitude into queue
                val currentBestLocationAltitude: Double = currentBestLocation.altitude
                if (currentBestLocationAltitude != Keys.DEFAULT_ALTITUDE) altitudeValues.add(currentBestLocationAltitude)
                // TODO remove
                // uncomment to use test altitude values - useful if testing with an emulator
                //altitudeValues.add(getTestAltitude()) // TODO remove
                // TODO remove

                // only start calculating elevation differences, if enough data has been added to queue
                if (altitudeValues.prepared) {
                    // get current smoothed altitude
                    val currentAltitude: Double = altitudeValues.getAverage()
                    // calculate and store elevation differences
                    track = LocationHelper.calculateElevationDifferences(currentAltitude, previousAltitude, track)
                    // TODO remove
                    LogHelper.d(TAG, "Elevation Calculation || prev = $previousAltitude | curr = $currentAltitude | pos = ${track.positiveElevation} | neg = ${track.negativeElevation}")
                    // TODO remove
                }

                // save a temp track
                val now: Date = GregorianCalendar.getInstance().time
                if (now.time - lastSave.time > Keys.SAVE_TEMP_TRACK_INTERVAL) {
                    lastSave = now
                    CoroutineScope(IO).launch { track.save_temp_suspended(this@TrackerService) }
                }
            }
            // update notification
            displayNotification()
            // re-run this in set interval
            handler.postDelayed(this, Keys.ADD_WAYPOINT_TO_TRACK_INTERVAL)
        }
    }
    /*
     * End of declaration
     */

    /* Simple queue that evicts older elements and holds an average */
    /* Credit: CircularQueue https://stackoverflow.com/a/51923797 */
    class SimpleMovingAverageQueue(var capacity: Int) : LinkedList<Double>()
    {
        var prepared: Boolean = false
        private var sum: Double = 0.0
        override fun add(element: Double): Boolean
        {
            prepared = this.size + 1 >= Keys.MIN_NUMBER_OF_WAYPOINTS_FOR_ELEVATION_CALCULATION
            if (this.size >= capacity) {
                sum -= this.first
                removeFirst()
            }
            sum += element
            return super.add(element)
        }
        fun getAverage(): Double
        {
            return sum / this.size
        }
        fun reset()
        {
            this.clear()
            prepared = false
            sum = 0.0
        }
    }
}
