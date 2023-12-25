/*
 * MapFragment.kt
 * Implements the MapFragment fragment
 * A MapFragment displays a map using osmdroid as well as the controls to start / stop a recording
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
import android.app.Dialog
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.location.Location
import android.os.*
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.ItemizedIconOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.OverlayItem
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import net.voussoir.trkpt.helpers.*

class MapFragment : Fragment()
{
    private lateinit var trackbook: Trackbook

    private var tracker_service_bound: Boolean = false
    private var tracker_service: TrackerService? = null

    val handler: Handler = Handler(Looper.getMainLooper())

    var continuous_auto_center: Boolean = true
    var show_debug: Boolean = false

    private lateinit var database_changed_listener: DatabaseChangedListener

    lateinit var rootView: View
    private lateinit var mapView: MapView
    lateinit var mainButton: ExtendedFloatingActionButton

    lateinit var zoom_in_button: FloatingActionButton
    lateinit var zoom_out_button: FloatingActionButton
    lateinit var currentLocationButton: FloatingActionButton
    lateinit var map_current_time: TextView
    private var current_track_overlay: Polyline? = null
    private var current_position_overlays = ArrayList<Overlay>()
    private var homepoints_overlays = ArrayList<Overlay>()

    /* Overrides onCreate from Fragment */
    override fun onCreate(savedInstanceState: Bundle?)
    {
        Log.i("VOUSSOIR", "MapFragment.onCreate")
        super.onCreate(savedInstanceState)
        this.trackbook = (requireContext().applicationContext as Trackbook)
        database_changed_listener = object: DatabaseChangedListener
        {
            override fun database_changed()
            {
                Log.i("VOUSSOIR", "MapFragment database_ready_changed to ${trackbook.database.ready}")
                if (trackbook.database.ready)
                {
                    create_homepoint_overlays()
                }
                else
                {
                    clear_homepoint_overlays()
                }
                update_main_button()
            }
        }
    }

    /* Overrides onStop from Fragment */
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View
    {
        Log.i("VOUSSOIR", "MapFragment.onCreateView")

        rootView = inflater.inflate(R.layout.fragment_map, container, false)
        mapView = rootView.findViewById(R.id.map)
        currentLocationButton = rootView.findViewById(R.id.location_button)
        zoom_in_button = rootView.findViewById(R.id.zoom_in_button)
        zoom_out_button = rootView.findViewById(R.id.zoom_out_button)
        map_current_time = rootView.findViewById(R.id.map_current_time)
        mainButton = rootView.findViewById(R.id.main_button)

        mapView.setOnLongClickListener{
            Log.i("VOUSSOIR", "mapview longpress")
            true
        }
        mapView.isLongClickable = true
        mapView.isTilesScaledToDpi = true
        mapView.isVerticalMapRepetitionEnabled = false
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
        mapView.controller.setZoom(Keys.DEFAULT_ZOOM_LEVEL)

        if (AppThemeHelper.isDarkModeOn(requireActivity()))
        {
            mapView.overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)
        }

        val receiver: MapEventsReceiver = object: MapEventsReceiver
        {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean
            {
                return true
            }

            override fun longPressHelper(point: GeoPoint): Boolean
            {
                Log.i("VOUSSOIR", "MapFragment MapEventsReceiver.longPressHelper")
                val dialog = Dialog(activity as Context)
                dialog.setContentView(R.layout.dialog_homepoint)
                dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                dialog.setTitle("Homepoint")

                (dialog.findViewById(R.id.homepoint_dialog_title) as TextView).text = "Add a homepoint"

                val name_input: EditText = dialog.findViewById(R.id.homepoint_name_input)
                val radius_input: EditText = dialog.findViewById(R.id.homepoint_radius_input)
                val cancel_button: Button = dialog.findViewById(R.id.homepoint_delete_cancel_button)
                val save_button: Button = dialog.findViewById(R.id.homepoint_save_button)
                cancel_button.text = "Cancel"
                cancel_button.setOnClickListener {
                    dialog.cancel()
                }
                save_button.setOnClickListener {
                    val radius = radius_input.text.toString().toDoubleOrNull() ?: 25.0
                    trackbook.database.insert_homepoint(
                        id=random_long(),
                        name=name_input.text.toString(),
                        latitude=point.latitude,
                        longitude=point.longitude,
                        radius=radius,
                    )
                    trackbook.database.commit()
                    trackbook.load_homepoints()
                    create_homepoint_overlays()
                    dialog.dismiss()
                }

                dialog.show()
                return true
            }
        }
        mapView.overlays.add(MapEventsOverlay(receiver))

        trackbook.load_homepoints()
        create_homepoint_overlays()
        if (database_changed_listener !in trackbook.database_changed_listeners)
        {
            trackbook.database_changed_listeners.add(database_changed_listener)
        }

        centerMap(getLastKnownLocation(requireContext()))

        current_track_overlay = null

        mapView.setOnTouchListener { v, event ->
            continuous_auto_center = false
            false
        }

        mainButton.setOnClickListener {
            val tracker = tracker_service
            if (tracker == null)
            {
                return@setOnClickListener
            }
            if (tracker.tracking_state != Keys.STATE_STOP && tracker.tracking_state != Keys.STATE_MAPVIEW)
            {
                tracker.state_mapview()
            }
            else
            {
                startTracking()
            }
            handler.postDelayed(redraw_runnable, 0)
        }
        currentLocationButton.setOnClickListener {
            val tracker = tracker_service
            if (tracker == null)
            {
                return@setOnClickListener
            }
            centerMap(tracker.currentBestLocation)
        }
        zoom_in_button.setOnClickListener {
            mapView.controller.setZoom(mapView.zoomLevelDouble + 0.5)
        }
        zoom_out_button.setOnClickListener {
            mapView.controller.setZoom(mapView.zoomLevelDouble - 0.5)
        }

        show_debug = PreferencesHelper.loadShowDebug()

        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handler.post(redraw_runnable)
        return rootView
    }

    /* Overrides onStart from Fragment */
    override fun onStart()
    {
        Log.i("VOUSSOIR", "MapFragment.onStart")
        super.onStart()
        activity?.bindService(Intent(activity, TrackerService::class.java), tracker_service_connection, Context.BIND_AUTO_CREATE)
        handler.post(redraw_runnable)
    }

    /* Overrides onResume from Fragment */
    override fun onResume()
    {
        Log.i("VOUSSOIR", "MapFragment.onResume")
        super.onResume()
        handler.post(redraw_runnable)
        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // if (bound) {
        //     trackerService.addGpsLocationListener()
        //     trackerService.addNetworkLocationListener()
        // }
    }

    /* Overrides onPause from Fragment */
    override fun onPause()
    {
        Log.i("VOUSSOIR", "MapFragment.onPause")
        super.onPause()
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val tracker = tracker_service
        if (tracker == null)
        {
            return
        }
        saveBestLocationState(tracker.currentBestLocation)
        if (tracker_service_bound && (tracker.tracking_state == Keys.STATE_MAPVIEW || tracker.tracking_state == Keys.STATE_STOP))
        {
            tracker.remove_gps_location_listener()
            tracker.remove_network_location_listener()
            tracker.trackbook.database.commit()
        }
        handler.removeCallbacks(redraw_runnable)
    }

    /* Overrides onStop from Fragment */
    override fun onStop()
    {
        super.onStop()
        // unbind from TrackerService
        if (tracker_service_bound)
        {
            activity?.unbindService(tracker_service_connection)
            handleServiceUnbind()
        }
        handler.removeCallbacks(redraw_runnable)
    }

    override fun onDestroyView()
    {
        Log.i("VOUSSOIR", "MapFragment.onDestroy")
        super.onDestroyView()
        if (database_changed_listener in trackbook.database_changed_listeners)
        {
            trackbook.database_changed_listeners.remove(database_changed_listener)
        }
        handler.removeCallbacks(redraw_runnable)
    }

    override fun onDestroy()
    {
        Log.i("VOUSSOIR", "MapFragment.onDestroy")
        super.onDestroy()
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handler.removeCallbacks(redraw_runnable)
    }

    private fun startTracking()
    {
        // start service via intent so that it keeps running after unbind
        val intent = Intent(activity, TrackerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        {
            // ... start service in foreground to prevent it being killed on Oreo
            activity?.startForegroundService(intent)
        }
        else
        {
            activity?.startService(intent)
        }
        if (tracker_service != null)
        {
            tracker_service!!.state_full_recording()
        }
    }


    /* Handles state when service is being unbound */
    private fun handleServiceUnbind()
    {
        tracker_service_bound = false
        // unregister listener for changes in shared preferences
        PreferencesHelper.unregisterPreferenceChangeListener(sharedPreferenceChangeListener)
    }

    private val sharedPreferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
        if (key == Keys.PREF_SHOW_DEBUG)
        {
            show_debug = sharedPreferences.getBoolean(Keys.PREF_SHOW_DEBUG, Keys.DEFAULT_SHOW_DEBUG)
        }
        redraw()
    }

    fun centerMap(location: Location)
    {
        val position = GeoPoint(location.latitude, location.longitude)
        mapView.controller.setCenter(position)
        continuous_auto_center = true
    }

    fun saveBestLocationState(currentBestLocation: Location)
    {
        PreferencesHelper.saveCurrentBestLocation(currentBestLocation)
        PreferencesHelper.saveZoomLevel(mapView.zoomLevelDouble)
        continuous_auto_center = true
    }

    fun clear_current_position_overlays()
    {
        for (ov in current_position_overlays)
        {
            if (ov in mapView.overlays)
            {
                mapView.overlays.remove(ov)
            }
        }
        current_position_overlays.clear()
    }

    fun create_current_position_overlays()
    {
        clear_current_position_overlays()

        val tracker = tracker_service
        if (tracker == null)
        {
            return
        }

        val locationIsOld: Boolean = !(isRecentEnough(tracker.currentBestLocation))

        val newMarker: Drawable
        val fillcolor: Int
        val description: String
        if (tracker.tracking_state == Keys.STATE_DEAD)
        {
            fillcolor = Color.argb(64, 0, 0, 0)
            newMarker = ContextCompat.getDrawable(requireContext(), R.drawable.ic_skull_24dp)!!
            description = "GPS is struggling; disabled until movement"
        }
        else if (tracker.tracking_state == Keys.STATE_SLEEP)
        {
            fillcolor = Color.argb(64, 220, 61, 51)
            newMarker = ContextCompat.getDrawable(requireContext(), R.drawable.ic_sleep_24dp)!!
            description = "GPS sleeping until movement"
        }
        else if (locationIsOld)
        {
            fillcolor = Color.argb(64, 0, 0, 0)
            newMarker = ContextCompat.getDrawable(requireContext(), R.drawable.ic_marker_location_black_24dp)!!
            description = "GPS tracking at full power"
        }
        else if (tracker.tracking_state == Keys.STATE_FULL_RECORDING || tracker.tracking_state == Keys.STATE_ARRIVED_AT_HOME)
        {
            fillcolor = Color.argb(64, 220, 61, 51)
            newMarker = ContextCompat.getDrawable(requireContext(), R.drawable.ic_marker_location_red_24dp)!!
            description = "GPS tracking at full power"
        }
        else
        {
            fillcolor = Color.argb(64, 60, 152, 219)
            newMarker = ContextCompat.getDrawable(requireContext(), R.drawable.ic_marker_location_blue_24dp)!!
            description = "GPS tracking at full power"
        }

        val current_location_radius = Polygon()
        current_location_radius.points = Polygon.pointsAsCircle(
            GeoPoint(tracker.currentBestLocation.latitude, tracker.currentBestLocation.longitude),
            tracker.currentBestLocation.accuracy.toDouble()
        )
        current_location_radius.fillPaint.color = fillcolor
        current_location_radius.outlinePaint.color = Color.argb(0, 0, 0, 0)
        current_position_overlays.add(current_location_radius)

        val overlayItems: java.util.ArrayList<OverlayItem> = java.util.ArrayList<OverlayItem>()
        val overlayItem: OverlayItem = createOverlayItem(
            tracker.currentBestLocation.latitude,
            tracker.currentBestLocation.longitude,
            title="Current location",
            description=description,
        )
        overlayItem.setMarker(newMarker)
        overlayItems.add(overlayItem)
        current_position_overlays.add(createOverlay(requireContext(), overlayItems))

        for (ov in current_position_overlays)
        {
            mapView.overlays.add(ov)
        }
    }

    fun clear_track_overlay()
    {
        mapView.overlays.remove(current_track_overlay)
    }

    fun create_track_overlay()
    {
        clear_track_overlay()
        val pl = Polyline(mapView)
        pl.outlinePaint.strokeWidth = Keys.POLYLINE_THICKNESS
        pl.outlinePaint.color = requireContext().getColor(R.color.fuchsia)
        pl.infoWindow = null
        mapView.overlays.add(pl)
        current_track_overlay = pl
    }

    fun clear_homepoint_overlays()
    {
        for (ov in homepoints_overlays)
        {
            if (ov in mapView.overlays)
            {
                mapView.overlays.remove(ov)
            }
        }
        homepoints_overlays.clear()
    }

    fun create_homepoint_overlays()
    {
        Log.i("VOUSSOIR", "MapFragmentLayoutHolder.createHomepointOverlays")

        val context = requireContext()
        val newMarker: Drawable = ContextCompat.getDrawable(context, R.drawable.ic_homepoint_24dp)!!

        clear_homepoint_overlays()

        for (homepoint in trackbook.homepoints)
        {
            val p = Polygon()
            p.points = Polygon.pointsAsCircle(GeoPoint(homepoint.location.latitude, homepoint.location.longitude), homepoint.location.accuracy.toDouble())
            p.fillPaint.color = Color.argb(64, 255, 193, 7)
            p.outlinePaint.color = Color.argb(0, 0, 0, 0)
            homepoints_overlays.add(p)

            val overlayItems: java.util.ArrayList<OverlayItem> = java.util.ArrayList<OverlayItem>()
            val overlayItem: OverlayItem = createOverlayItem(
                homepoint.location.latitude,
                homepoint.location.longitude,
                title=homepoint.name,
                description="Radius ${homepoint.radius}"
            )
            overlayItem.setMarker(newMarker)
            overlayItems.add(overlayItem)
            val homepoint_overlay = ItemizedIconOverlay<OverlayItem>(context, overlayItems,
                object : ItemizedIconOverlay.OnItemGestureListener<OverlayItem> {
                    override fun onItemSingleTapUp(index: Int, item: OverlayItem): Boolean
                    {
                        return false
                    }
                    override fun onItemLongPress(index: Int, item: OverlayItem): Boolean
                    {
                        Log.i("VOUSSOIR", "MapFragment homepoint.longpress")
                        val dialog = Dialog(activity as Context)
                        dialog.setContentView(R.layout.dialog_homepoint)
                        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        dialog.setTitle("Homepoint")

                        (dialog.findViewById(R.id.homepoint_dialog_title) as TextView).text = "Edit homepoint"

                        val name_input: EditText = dialog.findViewById(R.id.homepoint_name_input)
                        name_input.setText(homepoint.name)
                        val radius_input: EditText = dialog.findViewById(R.id.homepoint_radius_input)
                        radius_input.setText(homepoint.radius.toString())
                        val delete_button: Button = dialog.findViewById(R.id.homepoint_delete_cancel_button)
                        val save_button: Button = dialog.findViewById(R.id.homepoint_save_button)
                        delete_button.text = "Delete"
                        delete_button.setOnClickListener {
                            trackbook.database.delete_homepoint(homepoint.id)
                            trackbook.database.commit()
                            trackbook.load_homepoints()
                            create_homepoint_overlays()
                            dialog.dismiss()
                        }
                        save_button.setOnClickListener {
                            val radius = radius_input.text.toString().toDoubleOrNull() ?: 25.0
                            trackbook.database.update_homepoint(homepoint.id, name=name_input.text.toString(), radius=radius)
                            trackbook.database.commit()
                            trackbook.load_homepoints()
                            create_homepoint_overlays()
                            dialog.dismiss()
                        }

                        dialog.show()
                        return true
                    }
                }
            )
            homepoints_overlays.add(homepoint_overlay)
        }

        for (ov in homepoints_overlays)
        {
            mapView.overlays.add(ov)
        }
    }

    fun update_main_button()
    {
        val tracker = tracker_service
        mainButton.isEnabled = trackbook.database.ready
        currentLocationButton.isVisible = true
        if (! trackbook.database.ready)
        {
            mainButton.text = requireContext().getString(R.string.button_not_ready)
            mainButton.icon = null
        }
        else if (tracker == null || tracker.tracking_state == Keys.STATE_STOP || tracker.tracking_state == Keys.STATE_MAPVIEW)
        {
            mainButton.setIconResource(R.drawable.ic_fiber_manual_record_inactive_24dp)
            mainButton.text = requireContext().getString(R.string.button_start)
            mainButton.contentDescription = requireContext().getString(R.string.descr_button_start)
        }
        else
        {
            mainButton.setIconResource(R.drawable.ic_fiber_manual_stop_24dp)
            mainButton.text = requireContext().getString(R.string.button_pause)
            mainButton.contentDescription = requireContext().getString(R.string.descr_button_pause)
        }
    }

    private val tracker_service_connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder)
        {
            // get reference to tracker service]
            val serviceref = (service as TrackerServiceBinder).service.get()
            if (serviceref == null)
            {
                return
            }
            tracker_service_bound = true
            tracker_service = serviceref
            // get state of tracking and update button if necessary
            redraw()
            // register listener for changes in shared preferences
            PreferencesHelper.registerPreferenceChangeListener(sharedPreferenceChangeListener)
        }
        override fun onServiceDisconnected(arg0: ComponentName)
        {
            // service has crashed, or was killed by the system
            handleServiceUnbind()
        }
    }

    fun redraw()
    {
        // Log.i("VOUSSOIR", "MapFragment.redraw")
        update_main_button()
        val tracker = tracker_service
        if (tracker == null)
        {
            return
        }
        create_current_position_overlays()
        if (current_track_overlay == null)
        {
            create_track_overlay()
        }
        current_track_overlay!!.setPoints(tracker.recent_trackpoints_for_mapview)

        if (continuous_auto_center)
        {
            centerMap(tracker.currentBestLocation)
        }

        if (show_debug)
        {
            val accuracy_text: String = "%.2f".format(tracker.currentBestLocation.accuracy)
            map_current_time.text = """
                state: ${state_name()}
                clock: ${iso8601_local_noms(System.currentTimeMillis())}
             location: ${iso8601_local_noms(tracker.currentBestLocation.time)}
             database: ${iso8601_local_noms(trackbook.database.ready_at)}
            listeners: ${iso8601_local_noms(tracker.listeners_enabled_at)}
               motion: ${iso8601_local_noms(tracker.last_significant_motion)}
             watchdog: ${iso8601_local_noms(tracker.last_watchdog)}
                 home: ${iso8601_local_noms(tracker.arrived_at_home)}
                 died: ${iso8601_local_noms(tracker.dead_at)}
                power: ${tracker.device_is_charging}
             wakelock: ${tracker.wakelock.isHeld}
             accuracy: ${accuracy_text} m
            """.trimIndent()
        }
        else
        {
            map_current_time.text = iso8601_local_noms(tracker.currentBestLocation.time)
        }

        mapView.invalidate()
    }

    fun state_name(): String
    {
        val tracker = tracker_service
        if (tracker == null)
        {
            return "null"
        }
        return when (tracker.tracking_state)
        {
            Keys.STATE_STOP -> "stop"
            Keys.STATE_FULL_RECORDING -> "recording"
            Keys.STATE_ARRIVED_AT_HOME -> "home"
            Keys.STATE_SLEEP -> "sleep"
            Keys.STATE_DEAD -> "dead"
            Keys.STATE_MAPVIEW -> "mapview"
            else -> tracker.tracking_state.toString()
        }
    }

    val redraw_runnable: Runnable = object : Runnable
    {
        override fun run()
        {
            handler.postDelayed(this, 975)
            redraw()
        }
    }
}
