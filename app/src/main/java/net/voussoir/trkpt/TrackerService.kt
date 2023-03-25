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

/*
 * Modified by voussoir for trkpt, forked from Trackbook.
 */

package net.voussoir.trkpt

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
import android.app.NotificationChannel
import android.app.PendingIntent
import android.app.TaskStackBuilder
import android.os.*
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import org.osmdroid.util.GeoPoint
import java.util.*
import net.voussoir.trkpt.helpers.*

class TrackerService: Service()
{
    lateinit var trackbook: Trackbook

    var trackingState: Int = Keys.STATE_TRACKING_STOPPED
    var useImperial: Boolean = false
    var omitRests: Boolean = true
    var device_id: String = random_device_id()
    var currentBestLocation: Location = getDefaultLocation()
    var lastCommit: Long = 0
    var location_min_time_ms: Long = 0
    private val RECENT_TRKPT_COUNT = 7200
    lateinit var recent_displacement_locations: Deque<Location>
    lateinit var recent_trackpoints_for_mapview: MutableList<GeoPoint>
    var bound: Boolean = false
    private val binder = LocalBinder()

    private lateinit var notificationManager: NotificationManager
    private lateinit var notification_builder: NotificationCompat.Builder

    private lateinit var locationManager: LocationManager
    private lateinit var gpsLocationListener: LocationListener
    private lateinit var networkLocationListener: LocationListener
    var use_gps_location: Boolean = false
    var use_network_location: Boolean = false
    var gpsProviderActive: Boolean = false
    var networkProviderActive: Boolean = false
    var gpsLocationListenerRegistered: Boolean = false
    var networkLocationListenerRegistered: Boolean = false

    var mapfragment: MapFragment? = null

    private fun addGpsLocationListener()
    {
        if (! use_gps_location)
        {
            Log.i("VOUSSOIR", "Skipping GPS listener.")
            return
        }

        if (gpsLocationListenerRegistered)
        {
            Log.i("VOUSSOIR", "GPS location listener has already been added.")
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
        Log.i("VOUSSOIR", "Added GPS location listener.")
    }

    private fun addNetworkLocationListener()
    {
        if (! use_network_location)
        {
            Log.i("VOUSSOIR", "Skipping Network listener.")
            return
        }

        if (networkLocationListenerRegistered)
        {
            Log.i("VOUSSOIR", "Network location listener has already been added.")
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
        Log.i("VOUSSOIR", "Added Network location listener.")
    }

    fun removeGpsLocationListener()
    {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
        {
            locationManager.removeUpdates(gpsLocationListener)
            gpsLocationListenerRegistered = false
            Log.i("VOUSSOIR", "Removed GPS location listener.")
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
            Log.i("VOUSSOIR", "Removed Network location listener.")
        }
        else
        {
            Log.w("VOUSSOIR", "Unable to remove Network location listener. Location permission is needed.")
        }
    }

    private fun createLocationListener(): LocationListener
    {
        return object : LocationListener
        {
            override fun onLocationChanged(location: Location)
            {
                Log.i("VOUSSOIR", "Processing point ${location.time} ${location.latitude}, ${location.longitude}.")

                if (location.time == currentBestLocation.time)
                {
                    return
                }

                // if (! isBetterLocation(location, currentBestLocation))
                // {
                //     Log.i("VOUSSOIR", "Not better than previous.")
                //     return
                // }

                currentBestLocation = location

                val mf = mapfragment
                mf?.handler?.postDelayed(mf.location_update_redraw, 0)

                if (trackingState != Keys.STATE_TRACKING_ACTIVE)
                {
                    return
                }

                displayNotification()

                if(! trackbook.database.ready)
                {
                    Log.i("VOUSSOIR", "Omitting due to database not ready.")
                    return
                }
                if (location.latitude == 0.0 || location.longitude == 0.0)
                {
                    Log.i("VOUSSOIR", "Omitting due to 0,0 location.")
                    return
                }
                if (! isRecentEnough(location))
                {
                    Log.i("VOUSSOIR", "Omitting due to not recent enough.")
                    return
                }
                if (! isAccurateEnough(location, Keys.DEFAULT_THRESHOLD_LOCATION_ACCURACY))
                {
                    Log.i("VOUSSOIR", "Omitting due to not accurate enough.")
                    return
                }
                for (homepoint in trackbook.homepoints)
                {
                    if (homepoint.location.distanceTo(location) < homepoint.radius)
                    {
                        Log.i("VOUSSOIR", "Omitting due to homepoint ${homepoint.name}.")
                        return
                    }
                }
                if (recent_displacement_locations.isEmpty())
                {
                    // pass
                }
                else if (! isDifferentEnough(recent_displacement_locations.first(), location, omitRests))
                {
                    Log.i("VOUSSOIR", "Omitting due to too close to previous.")
                    return
                }

                val trkpt = Trkpt(device_id=device_id, location=location)
                trackbook.database.insert_trkpt(trkpt)

                recent_trackpoints_for_mapview.add(trkpt)
                while (recent_trackpoints_for_mapview.size > RECENT_TRKPT_COUNT)
                {
                    recent_trackpoints_for_mapview.removeFirst()
                }

                recent_displacement_locations.add(location)
                while (recent_displacement_locations.size > 5)
                {
                    recent_displacement_locations.removeFirst()
                }

                if (location.time - lastCommit > Keys.COMMIT_INTERVAL)
                {
                    trackbook.database.commit()
                    lastCommit  = location.time
                }
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

    private fun displayNotification(): Notification
    {
        val timestamp = iso8601(currentBestLocation.time)
        if (shouldCreateNotificationChannel())
        {
            createNotificationChannel()
        }

        notification_builder.setContentText(timestamp)
        notification_builder.setWhen(currentBestLocation.time)

        if (trackingState == Keys.STATE_TRACKING_ACTIVE)
        {
            notification_builder.setContentTitle(this.getString(R.string.notification_title_trackbook_running))
            notification_builder.setLargeIcon(AppCompatResources.getDrawable(this, R.drawable.ic_notification_icon_large_tracking_active_48dp)!!.toBitmap())
        }
        else
        {
            notification_builder.setContentTitle(this.getString(R.string.notification_title_trackbook_not_running))
            notification_builder.setLargeIcon(AppCompatResources.getDrawable(this, R.drawable.ic_notification_icon_large_tracking_stopped_48dp)!!.toBitmap())
        }

        val notification = notification_builder.build()
        notificationManager.notify(Keys.TRACKER_SERVICE_NOTIFICATION_ID, notification)
        return notification
    }

    /* Checks if notification channel should be created */
    private fun shouldCreateNotificationChannel() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !nowPlayingChannelExists()

    /* Checks if notification channel exists */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun nowPlayingChannelExists() = notificationManager.getNotificationChannel(Keys.NOTIFICATION_CHANNEL_RECORDING) != null

    /* Create a notification channel */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel()
    {
        val notificationChannel = NotificationChannel(
            Keys.NOTIFICATION_CHANNEL_RECORDING,
            this.getString(R.string.notification_channel_recording_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = this@TrackerService.getString(R.string.notification_channel_recording_description) }
        notificationManager.createNotificationChannel(notificationChannel)
    }

    /* Notification pending intents */
    // private val stopActionPendingIntent = PendingIntent.getService(
    //     this,
    //     14,
    //     Intent(this, TrackerService::class.java).setAction(Keys.ACTION_STOP),
    //     PendingIntent.FLAG_IMMUTABLE
    // )
    // private val resumeActionPendingIntent = PendingIntent.getService(
    //     this,
    //     16,
    //     Intent(this, TrackerService::class.java).setAction(Keys.ACTION_START),
    //     PendingIntent.FLAG_IMMUTABLE
    // )
    /* Notification actions */
    // private val stopAction = NotificationCompat.Action(
    //     R.drawable.ic_notification_action_stop_24dp,
    //     this.getString(R.string.notification_pause),
    //     stopActionPendingIntent
    // )
    // private val resumeAction = NotificationCompat.Action(
    //     R.drawable.ic_notification_action_resume_36dp,
    //     this.getString(R.string.notification_resume),
    //     resumeActionPendingIntent
    // )
    // private val showAction = NotificationCompat.Action(
    //     R.drawable.ic_notification_action_show_36dp,
    //     this.getString(R.string.notification_show),
    //     showActionPendingIntent
    // )

    /* Overrides onBind from Service */
    override fun onBind(p0: Intent?): IBinder
    {
        Log.i("VOUSSOIR", "TrackerService.onBind")
        bound = true
        addGpsLocationListener()
        addNetworkLocationListener()
        return binder
    }

    /* Overrides onRebind from Service */
    override fun onRebind(intent: Intent?)
    {
        Log.i("VOUSSOIR", "TrackerService.onRebind")
        bound = true
        addGpsLocationListener()
        addNetworkLocationListener()
    }

    /* Overrides onUnbind from Service */
    override fun onUnbind(intent: Intent?): Boolean
    {
        super.onUnbind(intent)
        Log.i("VOUSSOIR", "TrackerService.onUnbind")
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

    /* Overrides onCreate from Service */
    override fun onCreate()
    {
        super.onCreate()
        Log.i("VOUSSOIR", "TrackerService.onCreate")
        trackbook = (applicationContext as Trackbook)
        trackbook.load_homepoints()
        recent_displacement_locations = ArrayDeque<Location>(5)
        recent_trackpoints_for_mapview = mutableListOf()
        use_gps_location = PreferencesHelper.load_location_gps()
        use_network_location = PreferencesHelper.load_location_network()
        device_id = PreferencesHelper.load_device_id()
        useImperial = PreferencesHelper.loadUseImperialUnits()
        omitRests = PreferencesHelper.loadOmitRests()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notification_builder = NotificationCompat.Builder(this, Keys.NOTIFICATION_CHANNEL_RECORDING)
        val showActionPendingIntent: PendingIntent? = TaskStackBuilder.create(this).run {
            addNextIntentWithParentStack(Intent(this@TrackerService, MainActivity::class.java))
            getPendingIntent(10, PendingIntent.FLAG_IMMUTABLE)
        }
        notification_builder.setContentIntent(showActionPendingIntent)
        notification_builder.setSmallIcon(R.drawable.ic_notification_icon_small_24dp)
        gpsProviderActive = isGpsEnabled(locationManager)
        networkProviderActive = isNetworkEnabled(locationManager)
        gpsLocationListener = createLocationListener()
        networkLocationListener = createLocationListener()
        trackingState = PreferencesHelper.loadTrackingState()
        currentBestLocation = getLastKnownLocation(this)
        PreferencesHelper.registerPreferenceChangeListener(sharedPreferenceChangeListener)
    }

    /* Overrides onStartCommand from Service */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int
    {
        Log.i("VOUSSOIR", "TrackerService.onStartCommand")
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

    fun startTracking()
    {
        Log.i("VOUSSOIR", "TrackerService.startTracking")
        addGpsLocationListener()
        addNetworkLocationListener()
        trackingState = Keys.STATE_TRACKING_ACTIVE
        PreferencesHelper.saveTrackingState(trackingState)
        recent_displacement_locations.clear()
        startForeground(Keys.TRACKER_SERVICE_NOTIFICATION_ID, displayNotification())
    }

    fun stopTracking()
    {
        Log.i("VOUSSOIR", "TrackerService.stopTracking")
        trackbook.database.commit()

        trackingState = Keys.STATE_TRACKING_STOPPED
        PreferencesHelper.saveTrackingState(trackingState)
        recent_displacement_locations.clear()
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

    inner class LocalBinder : Binder() {
        val service: TrackerService = this@TrackerService
    }
}
