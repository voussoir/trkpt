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

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.hardware.*
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.*
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import net.voussoir.trkpt.helpers.*
import org.osmdroid.util.GeoPoint
import java.lang.ref.WeakReference
import java.util.*

class TrackerService: Service()
{
    lateinit var trackbook: Trackbook
    val handler: Handler = Handler(Looper.getMainLooper())

    var trackingState: Int = Keys.STATE_TRACKING_STOPPED
    var useImperial: Boolean = false
    var omitRests: Boolean = true
    var max_accuracy: Float = Keys.DEFAULT_MAX_ACCURACY
    var allow_sleep: Boolean = true
    var device_id: String = random_device_id()
    var currentBestLocation: Location = getDefaultLocation()
    var lastCommit: Long = 0
    var listeners_enabled_at: Long = 0
    var last_significant_motion: Long = 0
    var last_watchdog: Long = 0
    var gave_up_at: Long = 0
    var arrived_at_home: Long = 0
    var location_interval: Long = 0
    val TIME_UNTIL_SLEEP: Long = 5 * Keys.ONE_MINUTE_IN_MILLISECONDS
    val TIME_UNTIL_DEAD: Long = 3 * Keys.ONE_MINUTE_IN_MILLISECONDS
    val WATCHDOG_INTERVAL: Long = 61 * Keys.ONE_SECOND_IN_MILLISECONDS
    private val RECENT_TRKPT_COUNT = 3600
    private val DISPLACEMENT_LOCATION_COUNT = 5
    lateinit var recent_displacement_locations: Deque<Location>
    lateinit var recent_trackpoints_for_mapview: MutableList<GeoPoint>
    var bound: Boolean = false
    private val binder = TrackerServiceBinder(this)

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

    private lateinit var sensor_manager: SensorManager
    private var significant_motion_sensor: Sensor? = null
    private var step_counter_sensor: Sensor? = null
    var has_motion_sensor: Boolean = false

    var device_is_charging: Boolean = false
    private var charging_broadcast_receiver: BroadcastReceiver? = null

    lateinit var wakelock: PowerManager.WakeLock

    private fun addGpsLocationListener(interval: Long): Boolean
    {
        gpsLocationListenerRegistered = false
        gpsProviderActive = isGpsEnabled(locationManager)
        if (! gpsProviderActive)
        {
            Log.w("VOUSSOIR", "Device GPS is not enabled.")
            return false
        }

        val has_permission: Boolean = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (! has_permission)
        {
            Log.w("VOUSSOIR", "Location permission is not granted.")
            return false
        }

        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            interval,
            0f,
            gpsLocationListener,
        )
        gpsLocationListenerRegistered = true
        Log.i("VOUSSOIR", "Added GPS location listener.")
        return true
    }

    private fun addNetworkLocationListener(interval: Long): Boolean
    {
        networkLocationListenerRegistered = false
        networkProviderActive = isNetworkEnabled(locationManager)
        if (!networkProviderActive)
        {
            Log.w("VOUSSOIR", "Unable to add Network location listener.")
            return false
        }

        val has_permission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (! has_permission)
        {
            Log.w("VOUSSOIR", "Unable to add Network location listener. Location permission is not granted.")
            return false
        }

        locationManager.requestLocationUpdates(
            LocationManager.NETWORK_PROVIDER,
            interval,
            0f,
            networkLocationListener,
        )
        networkLocationListenerRegistered = true
        Log.i("VOUSSOIR", "Added Network location listener.")
        return true
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

    fun reset_location_listeners(interval: Long)
    {
        Log.i("VOUSSOIR", "TrackerService.reset_location_listeners")
        location_interval = interval
        if (use_gps_location && interval != Keys.LOCATION_INTERVAL_DEAD && interval != Keys.LOCATION_INTERVAL_STOP)
        {
            addGpsLocationListener(interval)
        }
        else if (gpsLocationListenerRegistered)
        {
            removeGpsLocationListener()
        }
        if (use_network_location && interval != Keys.LOCATION_INTERVAL_DEAD && interval != Keys.LOCATION_INTERVAL_STOP)
        {
            addNetworkLocationListener(interval)
        }
        else if (networkLocationListenerRegistered)
        {
            removeNetworkLocationListener()
        }

        if (interval != Keys.LOCATION_INTERVAL_DEAD)
        {
            gave_up_at = 0
        }

        if (gpsLocationListenerRegistered || networkLocationListenerRegistered)
        {
            listeners_enabled_at = System.currentTimeMillis()
            if (interval != Keys.LOCATION_INTERVAL_SLEEP)
            {
                arrived_at_home = 0
            }
        }
        else if (interval == Keys.LOCATION_INTERVAL_STOP)
        {
            listeners_enabled_at = 0
        }
        else
        {
            listeners_enabled_at = 0
            location_interval = Keys.LOCATION_INTERVAL_DEAD
        }

        val should_wakelock = (
            (gpsLocationListenerRegistered || networkLocationListenerRegistered) &&
            trackingState == Keys.STATE_TRACKING_ACTIVE &&
            interval == Keys.LOCATION_INTERVAL_FULL_POWER
        )
        if (should_wakelock)
        {
            if (! wakelock.isHeld)
            {
                wakelock.acquire()
            }
        }
        else if (wakelock.isHeld)
        {
            wakelock.release()
        }

        displayNotification()
    }

    private fun createLocationListener(): LocationListener
    {
        return object : LocationListener
        {
            override fun onLocationChanged(location: Location)
            {
                Log.i("VOUSSOIR", "Processing point ${location.time} ${location.latitude}, ${location.longitude}.")

                if (location.time <= currentBestLocation.time)
                {
                    return
                }

                currentBestLocation = location

                if (trackingState != Keys.STATE_TRACKING_ACTIVE)
                {
                    return
                }
                if(! trackbook.database.ready)
                {
                    Log.i("VOUSSOIR", "Omitting due to database not ready.")
                    return
                }

                displayNotification()

                if (location.latitude == 0.0 || location.longitude == 0.0)
                {
                    Log.i("VOUSSOIR", "Omitting due to 0,0 location.")
                    return
                }

                // The Homepoint checks need to come before the other checks because if there
                // is even the slightest chance that the user has left the homepoint, we want to
                // wake back up to full power. We do not want to put this below the isAccurateEnough
                // or isRecentEnough checks because we already know that the sleeping GPS produces
                // very inaccurate points so if those bail early we'd stay in sleep mode.
                for ((index, homepoint) in trackbook.homepoints.withIndex())
                {
                    if (homepoint.location.distanceTo(location) > homepoint.radius)
                    {
                        continue
                    }
                    Log.i("VOUSSOIR", "Omitting due to homepoint ${index} ${homepoint.name}.")
                    if (index > 0)
                    {
                        trackbook.homepoints.remove(homepoint)
                        trackbook.homepoints.addFirst(homepoint)
                    }
                    if (arrived_at_home == 0L)
                    {
                        Log.i("VOUSSOIR", "Arrived at home.")
                        arrived_at_home = System.currentTimeMillis()
                        trackbook.database.commit()
                    }
                    else if (
                        allow_sleep &&
                        has_motion_sensor &&
                        location_interval != Keys.LOCATION_INTERVAL_SLEEP &&
                        (System.currentTimeMillis() - arrived_at_home) > TIME_UNTIL_SLEEP &&
                        (System.currentTimeMillis() - last_significant_motion) > TIME_UNTIL_SLEEP
                    )
                    {
                        Log.i("VOUSSOIR", "Staying at home, sleeping.")
                        reset_location_listeners(interval=Keys.LOCATION_INTERVAL_SLEEP)
                    }
                    return
                }
                if (arrived_at_home > 0)
                {
                    Log.i("VOUSSOIR", "Leaving home.")
                    reset_location_listeners(interval=Keys.LOCATION_INTERVAL_FULL_POWER)
                }

                if (! isRecentEnough(location))
                {
                    Log.i("VOUSSOIR", "Omitting due to not recent enough.")
                    return
                }
                if (recent_displacement_locations.isEmpty())
                {
                    // pass
                }
                else if (omitRests && recent_displacement_locations.last().latitude == location.latitude && recent_displacement_locations.last().longitude == location.longitude)
                {
                    Log.i("VOUSSOIR", "Omitting due to identical to previous.")
                    return
                }
                else if (omitRests && !isDifferentEnough(recent_displacement_locations.first(), location))
                {
                    Log.i("VOUSSOIR", "Omitting due to too close to previous.")
                    return
                }

                val trkpt = Trkpt(device_id=device_id, location=location)
                trackbook.database.insert_trkpt(trkpt, commit=false)

                if (trkpt.accuracy <= max_accuracy)
                {
                    recent_trackpoints_for_mapview.add(trkpt)
                }
                while (recent_trackpoints_for_mapview.size > RECENT_TRKPT_COUNT)
                {
                    recent_trackpoints_for_mapview.removeFirst()
                }

                recent_displacement_locations.add(location)
                while (recent_displacement_locations.size > DISPLACEMENT_LOCATION_COUNT)
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
        notification_builder.setWhen(System.currentTimeMillis())
        if (shouldCreateNotificationChannel())
        {
            createNotificationChannel()
        }

        val timestamp = iso8601_local_noms(currentBestLocation.time)
        if (trackingState == Keys.STATE_TRACKING_ACTIVE)
        {
            notification_builder.setContentTitle(this.getString(R.string.notification_title_trackbook_running))
            if (location_interval == Keys.LOCATION_INTERVAL_FULL_POWER && arrived_at_home > 0)
            {
                notification_builder.setContentTitle("${timestamp} (home)")
                notification_builder.setSmallIcon(R.drawable.ic_satellite_24dp)
            }
            else if (location_interval == Keys.LOCATION_INTERVAL_FULL_POWER)
            {
                notification_builder.setContentTitle("${timestamp} (recording)")
                notification_builder.setSmallIcon(R.drawable.ic_satellite_24dp)
            }
            else if (location_interval == Keys.LOCATION_INTERVAL_SLEEP)
            {
                notification_builder.setContentTitle("${timestamp} (sleeping)")
                notification_builder.setSmallIcon(R.drawable.ic_sleep_24dp)
            }
            else if (location_interval == Keys.LOCATION_INTERVAL_DEAD)
            {
                notification_builder.setContentTitle("${timestamp} (dead)")
                notification_builder.setSmallIcon(R.drawable.ic_skull_24dp)
            }
            else
            {
                notification_builder.setContentText(timestamp)
                notification_builder.setSmallIcon(R.drawable.ic_fiber_manual_record_inactive_24dp)
            }
        }
        else
        {
            notification_builder.setContentTitle("${timestamp} (stopped)")
            notification_builder.setSmallIcon(R.drawable.ic_fiber_manual_stop_24dp)
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

    /* Overrides onBind from Service */
    override fun onBind(p0: Intent?): IBinder
    {
        Log.i("VOUSSOIR", "TrackerService.onBind")
        if (listeners_enabled_at == 0L && location_interval != Keys.LOCATION_INTERVAL_DEAD)
        {
            reset_location_listeners(interval=Keys.LOCATION_INTERVAL_FULL_POWER)
        }
        displayNotification()
        bound = true
        return binder
    }

    /* Overrides onRebind from Service */
    override fun onRebind(intent: Intent?)
    {
        Log.i("VOUSSOIR", "TrackerService.onRebind")
        if (listeners_enabled_at == 0L && location_interval != Keys.LOCATION_INTERVAL_DEAD)
        {
            reset_location_listeners(interval=Keys.LOCATION_INTERVAL_FULL_POWER)
        }
        displayNotification()
        bound = true
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
            reset_location_listeners(interval=Keys.LOCATION_INTERVAL_STOP)
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
        allow_sleep = PreferencesHelper.loadAllowSleep()
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

        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        has_motion_sensor = false
        sensor_manager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        significant_motion_sensor = sensor_manager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
        if (significant_motion_sensor != null)
        {
            val significant_motion_listener = object : TriggerEventListener() {
                override fun onTrigger(event: TriggerEvent?) {
                    Log.i("VOUSSOIR", "Significant motion")
                    last_significant_motion = System.currentTimeMillis()
                    if (trackingState == Keys.STATE_TRACKING_ACTIVE && location_interval != Keys.LOCATION_INTERVAL_FULL_POWER)
                    {
                        vibrator.vibrate(100)
                        reset_location_listeners(interval=Keys.LOCATION_INTERVAL_FULL_POWER)
                    }
                    sensor_manager.requestTriggerSensor(this, significant_motion_sensor)
                }
            }
            Log.i("VOUSSOIR", "Got significant motion sensor.")
            sensor_manager.requestTriggerSensor(significant_motion_listener, significant_motion_sensor)
            has_motion_sensor = true
        }

        step_counter_sensor = sensor_manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (step_counter_sensor != null)
        {
            val step_counter_listener = object: SensorEventListener {
                override fun onSensorChanged(event: SensorEvent?) {
                    Log.i("VOUSSOIR", "Step counter changed")
                    last_significant_motion = System.currentTimeMillis()
                    if (trackingState == Keys.STATE_TRACKING_ACTIVE && location_interval != Keys.LOCATION_INTERVAL_FULL_POWER)
                    {
                        // beeper.startTone(ToneGenerator.TONE_PROP_ACK, 150)
                        vibrator.vibrate(100)
                        reset_location_listeners(interval=Keys.LOCATION_INTERVAL_FULL_POWER)
                    }
                }

                override fun onAccuracyChanged(p0: Sensor?, p1: Int) {
                }
            }
            Log.i("VOUSSOIR", "Got step count sensor.")
            sensor_manager.registerListener(step_counter_listener, step_counter_sensor, 5_000_000, 5_000_000)
            has_motion_sensor = true
        }

        device_is_charging = get_device_charging()
        charging_broadcast_receiver = object: BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent)
            {
                if (intent.action == Intent.ACTION_POWER_CONNECTED)
                {
                    device_is_charging = true
                }
                else if (intent.action == Intent.ACTION_POWER_DISCONNECTED)
                {
                    device_is_charging = false
                }
                if (trackingState == Keys.STATE_TRACKING_ACTIVE)
                {
                    reset_location_listeners(interval=Keys.LOCATION_INTERVAL_FULL_POWER)
                }
            }
        }
        val charging_intent_filter = IntentFilter()
        charging_intent_filter.addAction(Intent.ACTION_POWER_CONNECTED)
        charging_intent_filter.addAction(Intent.ACTION_POWER_DISCONNECTED)
        registerReceiver(charging_broadcast_receiver, charging_intent_filter)

        val powermanager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakelock = powermanager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "trkpt::wakelock")

        handler.post(background_watchdog)
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
        PreferencesHelper.unregisterPreferenceChangeListener(sharedPreferenceChangeListener)
        reset_location_listeners(interval=Keys.LOCATION_INTERVAL_DEAD)
        handler.removeCallbacks(background_watchdog)
        unregisterReceiver(charging_broadcast_receiver)
    }

    fun startTracking()
    {
        Log.i("VOUSSOIR", "TrackerService.startTracking")
        trackingState = Keys.STATE_TRACKING_ACTIVE
        reset_location_listeners(interval=Keys.LOCATION_INTERVAL_FULL_POWER)
        PreferencesHelper.saveTrackingState(trackingState)
        recent_displacement_locations.clear()
        startForeground(Keys.TRACKER_SERVICE_NOTIFICATION_ID, displayNotification())
    }

    fun stopTracking()
    {
        Log.i("VOUSSOIR", "TrackerService.stopTracking")
        trackbook.database.commit()
        trackingState = Keys.STATE_TRACKING_STOPPED
        reset_location_listeners(interval=Keys.LOCATION_INTERVAL_FULL_POWER)
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
                reset_location_listeners(interval=Keys.LOCATION_INTERVAL_FULL_POWER)
            }
            Keys.PREF_LOCATION_NETWORK ->
            {
                use_network_location = PreferencesHelper.load_location_network()
                reset_location_listeners(interval=Keys.LOCATION_INTERVAL_FULL_POWER)
            }
            Keys.PREF_USE_IMPERIAL_UNITS ->
            {
                useImperial = PreferencesHelper.loadUseImperialUnits()
            }
            Keys.PREF_OMIT_RESTS ->
            {
                omitRests = PreferencesHelper.loadOmitRests()
            }
            Keys.PREF_MAX_ACCURACY ->
            {
                max_accuracy = PreferencesHelper.load_max_accuracy()
            }
            Keys.PREF_ALLOW_SLEEP ->
            {
                allow_sleep = PreferencesHelper.loadAllowSleep()
                if (! allow_sleep && trackingState == Keys.STATE_TRACKING_ACTIVE && location_interval != Keys.LOCATION_INTERVAL_FULL_POWER)
                {
                    reset_location_listeners(interval=Keys.LOCATION_INTERVAL_FULL_POWER)
                }
            }
            Keys.PREF_DEVICE_ID ->
            {
                device_id = PreferencesHelper.load_device_id()
            }
        }
    }

    fun get_device_charging(): Boolean
    {
        val intent = applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent!!.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        var plugged = (
            status == BatteryManager.BATTERY_PLUGGED_AC ||
            status == BatteryManager.BATTERY_PLUGGED_USB ||
            status == BatteryManager.BATTERY_PLUGGED_WIRELESS
        )
        Log.i("VOUSSOIR", "Charging: ${plugged}")
        return plugged
    }

    val background_watchdog: Runnable = object : Runnable
    {
        override fun run()
        {
            Log.i("VOUSSOIR", "TrackerService.background_watchdog")
            handler.postDelayed(this, WATCHDOG_INTERVAL)
            val now = System.currentTimeMillis()
            last_watchdog = now

            if (
                allow_sleep &&
                has_motion_sensor &&
                !device_is_charging &&
                // We only go to dead during active tracking because if you are looking at the
                // device in non-tracking mode you are probably waiting for your signal to recover.
                trackingState == Keys.STATE_TRACKING_ACTIVE &&
                // We only go to dead from full power because in the sleep state, the wakelock is
                // turned off and the device may go into doze. During doze, the device stops
                // updating the location listeners anyway, so there is no benefit in going to dead.
                // When the user interacts with the device and it leaves doze, it's better to come
                // from sleep state than dead state.
                location_interval == Keys.LOCATION_INTERVAL_FULL_POWER &&
                (now - listeners_enabled_at) > TIME_UNTIL_DEAD &&
                (now - currentBestLocation.time) > TIME_UNTIL_DEAD &&
                (now - last_significant_motion) > TIME_UNTIL_DEAD
            )
            {
                reset_location_listeners(interval=Keys.LOCATION_INTERVAL_DEAD)
                gave_up_at = now
            }
        }
    }
}
class TrackerServiceBinder(trackerservice: TrackerService) : Binder()
{
    val service: WeakReference<TrackerService> = WeakReference(trackerservice)
}
