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
import androidx.core.app.AlarmManagerCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.location.GnssStatusCompat
import androidx.core.location.LocationManagerCompat
import net.voussoir.trkpt.helpers.*
import org.osmdroid.util.GeoPoint
import java.lang.ref.WeakReference
import java.util.*

class TrackerService: Service()
{
    lateinit var trackbook: Trackbook
    val handler: Handler = Handler(Looper.getMainLooper())

    var tracking_state: Int = Keys.STATE_STOP
    // These are the states where the user's desire is for the recorder to be active. The app should
    // never transition from one of these states to the STOP or MAPVIEW states except by the user
    // clicking the stop button. This computed property simplifies conditional checks throughout the
    // app.
    val tracking_state_recordable: Boolean
        get() = (
            tracking_state == Keys.STATE_FULL_RECORDING ||
            tracking_state == Keys.STATE_ARRIVED_AT_HOME ||
            tracking_state == Keys.STATE_SLEEP ||
            tracking_state == Keys.STATE_DEAD
        )
    var useImperial: Boolean = false
    var omitRests: Boolean = true
    var max_accuracy: Float = Keys.DEFAULT_MAX_ACCURACY
    var show_debug: Boolean = false
    var allow_sleep: Boolean = true
    var device_id: String = random_device_id()
    var currentBestLocation: Location = getDefaultLocation()
    var last_commit: Long = 0
    var listeners_enabled_at: Long = 0
    var last_significant_motion: Long = 0
    var last_watchdog: Long = 0
    var last_alarm: Long = 0
    var next_alarm: Long = 0
    var dead_at: Long = 0
    var dead_reason: String = ""
    var arrived_at_home: Long = 0
    val TIME_UNTIL_SLEEP: Long = 5 * Keys.ONE_MINUTE_IN_MILLISECONDS
    val TIME_UNTIL_DEAD: Long = 3 * Keys.ONE_MINUTE_IN_MILLISECONDS
    val WATCHDOG_INTERVAL: Long = 61 * Keys.ONE_SECOND_IN_MILLISECONDS
    private val RECENT_TRKPT_COUNT = 3600
    private val DISPLACEMENT_LOCATION_COUNT = 5
    lateinit var recent_displacement_locations: Deque<Location>
    lateinit var recent_trackpoints_for_mapview: MutableList<GeoPoint>
    private val binder = TrackerServiceBinder(this)

    private lateinit var notification_manager: NotificationManager
    private lateinit var notification_builder: NotificationCompat.Builder

    private lateinit var location_manager: LocationManager
    private lateinit var gpsLocationListener: LocationListener
    private lateinit var networkLocationListener: LocationListener
    var satellites_in_fix: Int = 0
    var satellites_known: Int = 0

    var use_gps_location: Boolean = false
    var use_network_location: Boolean = false

    var gpsLocationListenerRegistered: Boolean = false
    var networkLocationListenerRegistered: Boolean = false
    val anyLocationListenerRegistered: Boolean
        get() = gpsLocationListenerRegistered || networkLocationListenerRegistered

    private lateinit var sensor_manager: SensorManager
    private var significant_motion_sensor: Sensor? = null
    private var step_counter_sensor: Sensor? = null
    var has_motion_sensor: Boolean = false

    var device_is_charging: Boolean = false
    private var charging_broadcast_receiver: BroadcastReceiver? = null

    lateinit var wakelock: PowerManager.WakeLock
    lateinit var alarm_manager: AlarmManager

    private fun add_gps_location_listener(interval: Long): Boolean
    {
        gpsLocationListenerRegistered = false
        if (! isGpsEnabled(location_manager))
        {
            Log.w("VOUSSOIR", "Device GPS is not enabled.")
            return false
        }

        val has_location_permission: Boolean = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (! has_location_permission)
        {
            Log.w("VOUSSOIR", "Location permission is not granted.")
            return false
        }

        location_manager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            interval,
            0f,
            gpsLocationListener,
        )

        LocationManagerCompat.registerGnssStatusCallback(
            location_manager,
            gnss_status_listener,
            handler
        )

        gpsLocationListenerRegistered = true
        Log.i("VOUSSOIR", "Added GPS location listener.")
        return true
    }

    private fun add_network_location_listener(interval: Long): Boolean
    {
        networkLocationListenerRegistered = false
        if (! isNetworkEnabled(location_manager))
        {
            Log.w("VOUSSOIR", "Unable to add Network location listener.")
            return false
        }

        val has_location_permission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (! has_location_permission)
        {
            Log.w("VOUSSOIR", "Unable to add Network location listener. Location permission is not granted.")
            return false
        }

        location_manager.requestLocationUpdates(
            LocationManager.NETWORK_PROVIDER,
            interval,
            0f,
            networkLocationListener,
        )
        networkLocationListenerRegistered = true
        Log.i("VOUSSOIR", "Added Network location listener.")
        return true
    }

    fun remove_gps_location_listener()
    {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
        {
            location_manager.removeUpdates(gpsLocationListener)
            LocationManagerCompat.unregisterGnssStatusCallback(
                location_manager,
                gnss_status_listener
            )
            gpsLocationListenerRegistered = false
            Log.i("VOUSSOIR", "Removed GPS location listener.")
        }
        else
        {
            Log.w("VOUSSOIR", "Unable to remove GPS location listener. Location permission is needed.")
        }
    }

    fun remove_network_location_listener()
    {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
        {
            location_manager.removeUpdates(networkLocationListener)
            networkLocationListenerRegistered = false
            Log.i("VOUSSOIR", "Removed Network location listener.")
        }
        else
        {
            Log.w("VOUSSOIR", "Unable to remove Network location listener. Location permission is needed.")
        }
    }

    fun load_tracking_state()
    {
        tracking_state = PreferencesHelper.loadTrackingState()
        Log.i("VOUSSOIR", "TrackerService.load_tracking_state ${tracking_state}" )
        when (tracking_state)
        {
            Keys.STATE_STOP -> state_stop()
            Keys.STATE_MAPVIEW -> state_mapview()
            Keys.STATE_FULL_RECORDING -> state_full_recording()
            Keys.STATE_ARRIVED_AT_HOME -> state_arrived_at_home()
            Keys.STATE_SLEEP -> state_sleep()
            Keys.STATE_DEAD -> state_dead(dead_reason="Loaded previous state")
        }
    }

    fun state_stop()
    {
        // This state is activated when the user intentionally stops the recording, or leaves the
        // RecordFragment without pressing the record button.
        Log.i("VOUSSOIR", "TrackerService.state_stop")
        tracking_state = Keys.STATE_STOP
        PreferencesHelper.saveTrackingState(tracking_state)
        reset_location_listeners(Keys.LOCATION_INTERVAL_STOP)
        trackbook.database.commit()
        recent_displacement_locations.clear()
        arrived_at_home = 0
        dead_at = 0
        dead_reason = ""
        stop_wakelock()
        displayNotification()
    }
    fun state_full_recording()
    {
        // This state is the only one that will record points into the database.
        // Location is tracked at full power. A wakelock is used to resist Android's doze.
        // This state should be active while out and about.
        val try_id = random_int()
        Log.i("VOUSSOIR", "TrackerService.trying state_full_power ${try_id}")

        // Reloading the database can help if the file handle has become invalid while the app was
        // in a different state. For example, when at home and the database is being synced with
        // Syncthing. In my case, I had edited the database on my computer which synced back to the
        // phone, but when I left the house the next day, points did not record.
        // So reloading the database whenever switching to full power is a harmless security net.
        // However, I add a 10 second check because this call will close the current database
        // connection, triggering our database_changed_listener which would set us to dead, then
        // load the new database connection, triggering this call in an infinite loop.
        if (System.currentTimeMillis() - trackbook.database.ready_at > 10 * Keys.ONE_SECOND_IN_MILLISECONDS)
        {
            trackbook.load_database()
        }

        if (! trackbook.database.ready)
        {
            state_dead("Database not ready")
            return
        }

        Log.i("VOUSSOIR", "TrackerService.state_full_power ${try_id}")
        tracking_state = Keys.STATE_FULL_RECORDING
        PreferencesHelper.saveTrackingState(tracking_state)
        reset_location_listeners(Keys.LOCATION_INTERVAL_FULL_POWER)
        arrived_at_home = 0
        dead_at = 0
        dead_reason = ""
        //start_wakelock()
        displayNotification()
    }
    fun state_arrived_at_home()
    {
        // This state is activated when the user enters the radius of a homepoint. The GPS will
        // remain at full power for a few minutes before we transition to sleep.
        Log.i("VOUSSOIR", "TrackerService.state_arrived_at_home")
        tracking_state = Keys.STATE_ARRIVED_AT_HOME
        PreferencesHelper.saveTrackingState(tracking_state)
        reset_location_listeners(Keys.LOCATION_INTERVAL_FULL_POWER)
        trackbook.database.commit()
        arrived_at_home = System.currentTimeMillis()
        dead_at = 0
        dead_reason = ""
        stop_wakelock()
        displayNotification()
    }
    fun state_sleep()
    {
        // This state is activated when the user stays at a homepoint for several minutes. It will
        // be woken up again by the acceleromters or by plugging / unplugging power.
        Log.i("VOUSSOIR", "TrackerService.state_sleep")
        tracking_state = Keys.STATE_SLEEP
        PreferencesHelper.saveTrackingState(tracking_state)
        reset_location_listeners(Keys.LOCATION_INTERVAL_SLEEP)
        arrived_at_home = arrived_at_home
        dead_at = 0
        dead_reason = ""
        stop_wakelock()
        displayNotification()
    }
    fun state_dead(dead_reason: String)
    {
        // This state is activated when the device is struggling to receive a GPS fix due to being
        // indoors / underground. It will be woken up again by the accelerometers or by plugging /
        // unplugging power.
        Log.i("VOUSSOIR", "TrackerService.state_dead")
        tracking_state = Keys.STATE_DEAD
        PreferencesHelper.saveTrackingState(tracking_state)
        reset_location_listeners(Keys.LOCATION_INTERVAL_STOP)
        trackbook.database.commit()
        recent_displacement_locations.clear()
        arrived_at_home = 0
        dead_at = System.currentTimeMillis()
        this.dead_reason = dead_reason
        stop_wakelock()
        displayNotification()
    }
    fun state_mapview()
    {
        // This state should be activated when the user has the app open to the mapfragment, but is
        // not recording. If the user closes the app while in this state, we can go to stop.
        Log.i("VOUSSOIR", "TrackerService.state_mapview")
        tracking_state = Keys.STATE_MAPVIEW
        PreferencesHelper.saveTrackingState(tracking_state)
        reset_location_listeners(Keys.LOCATION_INTERVAL_FULL_POWER)
        arrived_at_home = 0
        dead_at = 0
        dead_reason = ""
        stop_wakelock()
        displayNotification()
    }

    fun start_wakelock(timeout: Long)
    {
        if (!wakelock.isHeld)
        {
            wakelock.acquire(timeout)
        }
    }

    fun stop_wakelock()
    {
        if (wakelock.isHeld)
        {
            wakelock.release()
        }
    }

    fun reset_location_listeners(interval: Long)
    {
        Log.i("VOUSSOIR", "TrackerService.reset_location_listeners")
        satellites_in_fix = 0
        satellites_known = 0
        if (use_gps_location && interval != Keys.LOCATION_INTERVAL_STOP)
        {
            add_gps_location_listener(interval)
        }
        else if (gpsLocationListenerRegistered)
        {
            remove_gps_location_listener()
        }
        if (use_network_location && interval != Keys.LOCATION_INTERVAL_STOP)
        {
            add_network_location_listener(interval)
        }
        else if (networkLocationListenerRegistered)
        {
            remove_network_location_listener()
        }

        if (anyLocationListenerRegistered)
        {
            listeners_enabled_at = System.currentTimeMillis()
        }
        else
        {
            listeners_enabled_at = 0
        }
        displayNotification()
    }

    val gnss_status_listener = object : GnssStatusCompat.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatusCompat)
        {
            satellites_known = status.satelliteCount
            satellites_in_fix = 0
            for (index in 0 until satellites_known)
            {
                if (status.usedInFix(index))
                {
                    satellites_in_fix += 1
                }
            }
        }

        override fun onStarted()
        {
            super.onStarted()
            Log.i("VOUSSOIR", "GNSS status listener started.")
        }

        override fun onStopped()
        {
            super.onStopped()
            Log.i("VOUSSOIR", "GNSS status listener stopped.")
        }
    }

    private fun createLocationListener(): LocationListener
    {
        return object : LocationListener
        {
            override fun onLocationChanged(location: Location)
            {
                // The early returns from this function mean the point will be discarded.
                // The very bottom of this function actually adds the point to the track.
                Log.i("VOUSSOIR", "Processing point ${location.time} ${location.latitude}, ${location.longitude}.")

                if (location.time <= currentBestLocation.time)
                {
                    return
                }

                currentBestLocation = location

                if (!tracking_state_recordable)
                {
                    return
                }

                if(! trackbook.database.ready)
                {
                    Log.i("VOUSSOIR", "TrackerService.onLocationChanged: database is not ready!!.")
                    state_dead(dead_reason="Database not ready")
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

                    // Move this homepoint to the front of the list so that on subsequent location
                    // updates it hits on the first loop. I'm sure this is a trivial amount of
                    // savings but oh well.
                    if (index > 0)
                    {
                        trackbook.homepoints.remove(homepoint)
                        trackbook.homepoints.addFirst(homepoint)
                    }
                    if (tracking_state != Keys.STATE_ARRIVED_AT_HOME && tracking_state != Keys.STATE_SLEEP)
                    {
                        Log.i("VOUSSOIR", "Arrived at home.")
                        state_arrived_at_home()
                    }
                    else if (
                        allow_sleep &&
                        has_motion_sensor &&
                        tracking_state == Keys.STATE_ARRIVED_AT_HOME &&
                        (System.currentTimeMillis() - arrived_at_home) > TIME_UNTIL_SLEEP &&
                        (System.currentTimeMillis() - last_significant_motion) > TIME_UNTIL_SLEEP
                    )
                    {
                        Log.i("VOUSSOIR", "Staying at home, sleeping.")
                        state_sleep()
                    }
                    Log.i("VOUSSOIR", "Omitting due to homepoint ${homepoint.id} ${homepoint.name}.")
                    return
                }

                // All of the homepoint checks failed so we have left home and it's time to wake up.
                // In practice we expect that the accelerometers would have triggered this change
                // already, but this acts as a backup in case you somehow leave home without too
                // much movement (maybe you sat in the car for several minutes before finally
                // driving away or something).
                if (tracking_state == Keys.STATE_ARRIVED_AT_HOME || tracking_state == Keys.STATE_SLEEP)
                {
                    Log.i("VOUSSOIR", "Leaving home.")
                    state_full_recording()
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
                trackbook.database.insert_trkpt(trkpt)

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

                if ((location.time - last_commit) > Keys.COMMIT_INTERVAL)
                {
                    trackbook.database.commit()
                    last_commit  = location.time
                }
            }
            override fun onProviderEnabled(provider: String)
            {
                Log.v("VOUSSOIR", "onProviderEnabled $provider")
            }
            override fun onProviderDisabled(provider: String)
            {
                Log.v("VOUSSOIR", "onProviderDisabled $provider")
            }
        }
    }

    fun displayNotification(): Notification
    {
        notification_builder.setWhen(System.currentTimeMillis())
        if (shouldCreateNotificationChannel())
        {
            createNotificationChannel()
        }

        val timestamp = iso8601_local_noms(currentBestLocation.time)
        if (tracking_state == Keys.STATE_FULL_RECORDING)
        {
            notification_builder.setContentTitle("${timestamp} (recording)")
            notification_builder.setContentText(null)
            notification_builder.setSmallIcon(R.drawable.ic_satellite_24dp)
        }
        else if (tracking_state == Keys.STATE_ARRIVED_AT_HOME)
        {
            notification_builder.setContentTitle("${timestamp} (home)")
            notification_builder.setContentText(null)
            notification_builder.setSmallIcon(R.drawable.ic_homepoint_24dp)
        }
        else if (tracking_state == Keys.STATE_SLEEP)
        {
            notification_builder.setContentTitle("${timestamp} (sleeping)")
            notification_builder.setContentText(null)
            notification_builder.setSmallIcon(R.drawable.ic_sleep_24dp)
        }
        else if (tracking_state == Keys.STATE_DEAD)
        {
            notification_builder.setContentTitle("${timestamp} (dead)")
            notification_builder.setContentText(dead_reason)
            notification_builder.setSmallIcon(R.drawable.ic_skull_24dp)
        }
        else
        {
            notification_builder.setContentTitle("${timestamp} (stopped)")
            notification_builder.setContentText(null)
            notification_builder.setSmallIcon(R.drawable.ic_notification_action_stop_24dp)
        }

        val notification = notification_builder.build()
        notification_manager.notify(Keys.TRACKER_SERVICE_NOTIFICATION_ID, notification)
        return notification
    }

    /* Checks if notification channel should be created */
    private fun shouldCreateNotificationChannel() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !nowPlayingChannelExists()

    /* Checks if notification channel exists */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun nowPlayingChannelExists() = notification_manager.getNotificationChannel(Keys.NOTIFICATION_CHANNEL_RECORDING) != null

    /* Create a notification channel */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel()
    {
        val notificationChannel = NotificationChannel(
            Keys.NOTIFICATION_CHANNEL_RECORDING,
            this.getString(R.string.notification_channel_recording_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = this@TrackerService.getString(R.string.notification_channel_recording_description) }
        notification_manager.createNotificationChannel(notificationChannel)
    }

    /* Overrides onBind from Service */
    override fun onBind(p0: Intent?): IBinder
    {
        Log.i("VOUSSOIR", "TrackerService.onBind")
        if (tracking_state == Keys.STATE_STOP)
        {
            state_mapview()
        }
        displayNotification()
        return binder
    }

    /* Overrides onRebind from Service */
    override fun onRebind(intent: Intent?)
    {
        Log.i("VOUSSOIR", "TrackerService.onRebind")
        if (tracking_state == Keys.STATE_STOP)
        {
            state_mapview()
        }
        else
        {
            displayNotification()
        }
    }

    /* Overrides onUnbind from Service */
    override fun onUnbind(intent: Intent?): Boolean
    {
        super.onUnbind(intent)
        Log.i("VOUSSOIR", "TrackerService.onUnbind")

        // the user was only perusing the map and did not start recording, so we'll just stop.
        if (! tracking_state_recordable)
        {
            state_stop()
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
        max_accuracy = PreferencesHelper.load_max_accuracy()
        show_debug = PreferencesHelper.loadShowDebug()
        allow_sleep = PreferencesHelper.loadAllowSleep()
        location_manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        notification_manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notification_builder = NotificationCompat.Builder(this, Keys.NOTIFICATION_CHANNEL_RECORDING)
        val notification_intent = Intent(this@TrackerService, MainActivity::class.java).apply {
            // Resumes the existing activity instead of killing it and starting a new one.
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val notification_pendingintent = PendingIntent.getActivity(
            this@TrackerService,
            0, // Request code
            notification_intent,
            // IMMUTABLE is standard; UPDATE_CURRENT updates any extras if sent again
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        notification_builder.setContentIntent(notification_pendingintent)
        notification_builder.setSmallIcon(R.drawable.ic_notification_icon_small_24dp)
        gpsLocationListener = createLocationListener()
        networkLocationListener = createLocationListener()
        currentBestLocation = getLastKnownLocation(this)

        trackbook.add_database_changed_listener(database_changed_listener)

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
                    if (tracking_state == Keys.STATE_SLEEP || tracking_state == Keys.STATE_DEAD)
                    {
                        if (show_debug)
                        {
                            vibrator.vibrate(100)
                        }
                        state_full_recording()
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
                    if (tracking_state == Keys.STATE_SLEEP || tracking_state == Keys.STATE_DEAD)
                    {
                        if (show_debug)
                        {
                            vibrator.vibrate(100)
                        }
                        state_full_recording()
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
                if (tracking_state == Keys.STATE_SLEEP || tracking_state == Keys.STATE_DEAD)
                {
                    state_full_recording()
                }
            }
        }
        val charging_intent_filter = IntentFilter()
        charging_intent_filter.addAction(Intent.ACTION_POWER_CONNECTED)
        charging_intent_filter.addAction(Intent.ACTION_POWER_DISCONNECTED)
        registerReceiver(charging_broadcast_receiver, charging_intent_filter)

        val powermanager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakelock = powermanager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "trkpt::wakelock")

        alarm_manager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val heartbeat_filter = IntentFilter(Keys.ACTION_HEARTBEAT_SERVICE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(heartbeat_alarm_receiver, heartbeat_filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(heartbeat_alarm_receiver, heartbeat_filter)
        }

        load_tracking_state()
        handler.post(background_watchdog)
    }

    /* Overrides onStartCommand from Service */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int
    {
        Log.i("VOUSSOIR", "TrackerService.onStartCommand " + intent?.action)

        // SERVICE RESTART (via START_STICKY)
        if (intent == null)
        {
            if (tracking_state_recordable)
            {
                Log.w("VOUSSOIR", "Trackbook has been killed by the operating system. Trying to resume recording.")
                state_full_recording()
            }
            //schedule_heartbeat_alarm()
            return START_STICKY
        }
        else if (intent.action == Keys.ACTION_START_SERVICE)
        {
            // START_STICKY is used for services that are explicitly started and stopped as needed
            //schedule_heartbeat_alarm()
            return START_STICKY
        }
        else if (intent.action == Keys.ACTION_STOP_SERVICE)
        {
            if (gentle_stop())
            {
                return START_NOT_STICKY
            }
            else
            {
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    fun gentle_stop(): Boolean
    {
        Log.i("VOUSSOIR", "TrackerService.gentle_stop")
        if (tracking_state_recordable)
        {
            Log.i("VOUSSOIR", "TrackerService.gentle_stop: Not stopping because recording active.")
            return false
        }
        Log.i("VOUSSOIR", "TrackerService.gentle_stop: stopSelf")
        displayNotification()
        stopForeground(Service.STOP_FOREGROUND_DETACH)
        stopSelf()
        return true
    }

    override fun onTaskRemoved(rootIntent: Intent?)
    {
        // This method is called when the user swipes away the app from recents.
        Log.i("VOUSSOIR", "TrackerService.onTaskRemoved.")
        super.onTaskRemoved(rootIntent)
        gentle_stop()
    }

    /* Overrides onDestroy from Service */
    override fun onDestroy()
    {
        Log.i("VOUSSOIR", "TrackerService.onDestroy.")
        super.onDestroy()
        trackbook.remove_database_changed_listener(database_changed_listener)
        PreferencesHelper.unregisterPreferenceChangeListener(sharedPreferenceChangeListener)
        handler.removeCallbacks(background_watchdog)
        unregisterReceiver(charging_broadcast_receiver)
        unregisterReceiver(heartbeat_alarm_receiver)
    }

    private val database_changed_listener = object: DatabaseChangedListener {
        override fun database_changed()
        {
            Log.i("VOUSSOIR", "TrackerService.database_changed_listener: database_ready changed to ${trackbook.database.ready}")
            if (trackbook.database.ready && tracking_state_recordable)
            {
                state_full_recording()
            }
            else if (tracking_state_recordable)
            {
                state_dead("Database not ready")
            }
        }
    }

    private val sharedPreferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
        when (key)
        {
            Keys.PREF_LOCATION_GPS ->
            {
                use_gps_location = PreferencesHelper.load_location_gps()
                load_tracking_state()
            }
            Keys.PREF_LOCATION_NETWORK ->
            {
                use_network_location = PreferencesHelper.load_location_network()
                load_tracking_state()
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
            Keys.PREF_SHOW_DEBUG ->
            {
                show_debug = PreferencesHelper.loadShowDebug()
            }
            Keys.PREF_ALLOW_SLEEP ->
            {
                allow_sleep = PreferencesHelper.loadAllowSleep()
                if (! allow_sleep && (tracking_state == Keys.STATE_SLEEP || tracking_state == Keys.STATE_DEAD))
                {
                    state_full_recording()
                }
            }
            Keys.PREF_DEVICE_ID ->
            {
                device_id = PreferencesHelper.load_device_id()
                trackbook.database.commit()
                trackbook.load_database()
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

    private val heartbeat_alarm_receiver = object : BroadcastReceiver() {
        @Override
        override fun onReceive(context: Context, intent: Intent)
        {
            Log.i("VOUSSOIR", "TrackerService.heartbeat_alarm_receiver")
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (intent.action != Keys.ACTION_HEARTBEAT_SERVICE)
            {
                return
            }
            val has_location_permission = ContextCompat.checkSelfPermission(this@TrackerService, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (! has_location_permission)
            {
                return
            }
            start_wakelock(10 * Keys.ONE_SECOND_IN_MILLISECONDS)
            vibrator.vibrate(100)
            //load_tracking_state()
            //if (gpsLocationListenerRegistered)
            //{
            //    LocationManagerCompat.getCurrentLocation(
            //        location_manager,
            //        LocationManager.GPS_PROVIDER,
            //        null,
            //        Executors.newSingleThreadExecutor(),
            //    ) { location ->
            //        Log.i("VOUSSOIR", "hearbeat alarm received location: ${location}.")
            //        vibrator.vibrate(100)
            //        gpsLocationListener.onLocationChanged(location)
            //    }
            //}
            //if (networkLocationListenerRegistered)
            //{
            //    LocationManagerCompat.getCurrentLocation(
            //        location_manager,
            //        LocationManager.NETWORK_PROVIDER,
            //        null,
            //        Executors.newSingleThreadExecutor(),
            //    ) { location ->
            //        vibrator.vibrate(100)
            //        gpsLocationListener.onLocationChanged(location)
            //    }
            //}
            last_alarm = System.currentTimeMillis()
            //schedule_heartbeat_alarm()
            //stop_wakelock()
        }
    }

    private fun schedule_heartbeat_alarm()
    {
        Log.i("VOUSSOIR", "TrackerService.schedule_heartbeat_alarm")
        val intent = Intent(Keys.ACTION_HEARTBEAT_SERVICE)
        val pendingintent = PendingIntent.getBroadcast(
            this,
            Keys.TRACKER_SERVICE_HEARTBEAT_ALARM_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        //val interval = 30 * 1000
        val interval = 16 * Keys.ONE_MINUTE_IN_MILLISECONDS
        val trigger_time = SystemClock.elapsedRealtime() + interval
        next_alarm = System.currentTimeMillis() + interval

        AlarmManagerCompat.setExactAndAllowWhileIdle(
            alarm_manager,
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            trigger_time,
            pendingintent!!
        )
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
                // device in mapview state you are probably waiting for your signal to recover.
                // We only go to dead from full power because in the sleep state, the wakelock is
                // turned off and the device may go into doze. During doze, the device stops
                // updating the location listeners anyway, so there is no benefit in going to dead.
                // When the user interacts with the device and it leaves doze, it's better to come
                // from sleep state than dead state.
                tracking_state == Keys.STATE_FULL_RECORDING &&
                (now - listeners_enabled_at) > TIME_UNTIL_DEAD &&
                (now - currentBestLocation.time) > TIME_UNTIL_DEAD &&
                (now - last_significant_motion) > TIME_UNTIL_DEAD
            )
            {
                state_dead(dead_reason="No GPS reception")
            }
        }
    }
}
class TrackerServiceBinder(trackerservice: TrackerService) : Binder()
{
    val service: WeakReference<TrackerService> = WeakReference(trackerservice)
}
