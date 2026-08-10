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

package net.voussoir.trkpt.fragments

import android.app.Dialog
import android.content.*
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
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import net.voussoir.trkpt.*
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

class RecordFragment : Fragment()
{
    private lateinit var trackbook: Trackbook

    val handler: Handler = Handler(Looper.getMainLooper())

    val main_activity: MainActivity
        get() = requireActivity() as MainActivity

    var continuous_auto_center: Boolean = true
    var show_debug: Boolean = false

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
    }

    /* Overrides onStop from Fragment */
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View
    {
        Log.i("VOUSSOIR", "MapFragment.onCreateView")

        rootView = inflater.inflate(R.layout.fragment_record, container, false)
        mapView = rootView.findViewById(R.id.map)
        currentLocationButton = rootView.findViewById(R.id.location_button)
        zoom_in_button = rootView.findViewById(R.id.zoom_in_button)
        zoom_out_button = rootView.findViewById(R.id.zoom_out_button)
        map_current_time = rootView.findViewById(R.id.map_current_time)
        mainButton = rootView.findViewById(R.id.main_button)

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
                if(! trackbook.database.ready)
                {
                    Log.i("VOUSSOIR", "MapFragment.longpress: Database is not ready to add a homepoint.")
                    return false;
                }
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
        trackbook.add_database_changed_listener(database_changed_listener)

        centerMap(getLastKnownLocation(requireContext()))

        current_track_overlay = null

        mapView.setOnTouchListener { v, event ->
            continuous_auto_center = false
            false
        }

        mainButton.setOnClickListener {
            if (main_activity.tracker_service == null)
            {
                return@setOnClickListener
            }
            val tracker = main_activity.tracker_service!!
            if (! tracker.tracking_state_recordable)
            {
                tracker.state_full_recording()
            }
            else
            {
                tracker.state_mapview()
            }
            handler.postDelayed(redraw_runnable, 0)
        }
        currentLocationButton.setOnClickListener {
            if (main_activity.tracker_service == null)
            {
                return@setOnClickListener
            }
            centerMap(main_activity.tracker_service!!.currentBestLocation)
        }
        zoom_in_button.setOnClickListener {
            mapView.controller.setZoom(mapView.zoomLevelDouble + 0.5)
        }
        zoom_out_button.setOnClickListener {
            mapView.controller.setZoom(mapView.zoomLevelDouble - 0.5)
        }

        show_debug = PreferencesHelper.loadShowDebug()

        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        PreferencesHelper.registerPreferenceChangeListener(sharedPreferenceChangeListener)

        handler.post(redraw_runnable)
        return rootView
    }

    /* Overrides onStart from Fragment */
    override fun onStart()
    {
        Log.i("VOUSSOIR", "MapFragment.onStart")
        super.onStart()
        handler.post(redraw_runnable)
        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /* Overrides onResume from Fragment */
    override fun onResume()
    {
        Log.i("VOUSSOIR", "MapFragment.onResume")
        super.onResume()
        handler.post(redraw_runnable)
        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (main_activity.tracker_service?.tracking_state == Keys.STATE_STOP)
        {
            main_activity.tracker_service?.state_mapview()
        }
    }

    /* Overrides onPause from Fragment */
    override fun onPause()
    {
        Log.i("VOUSSOIR", "MapFragment.onPause")
        super.onPause()
        handler.removeCallbacks(redraw_runnable)
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (main_activity.tracker_service?.tracking_state == Keys.STATE_MAPVIEW)
        {
            main_activity.tracker_service?.state_stop()
        }
    }

    /* Overrides onStop from Fragment */
    override fun onStop()
    {
        super.onStop()
        handler.removeCallbacks(redraw_runnable)
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onDestroyView()
    {
        Log.i("VOUSSOIR", "MapFragment.onDestroyView")
        super.onDestroyView()
        trackbook.remove_database_changed_listener(database_changed_listener)
        handler.removeCallbacks(redraw_runnable)
    }

    override fun onDestroy()
    {
        Log.i("VOUSSOIR", "MapFragment.onDestroy")
        super.onDestroy()
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handler.removeCallbacks(redraw_runnable)
    }

    private val database_changed_listener = object: DatabaseChangedListener {
        override fun database_changed()
        {
            Log.i("VOUSSOIR", "MapFragment: database_ready changed to ${trackbook.database.ready}")
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

    private val sharedPreferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
        if (key == Keys.PREF_SHOW_DEBUG)
        {
            show_debug = sharedPreferences.getBoolean(Keys.PREF_SHOW_DEBUG, Keys.DEFAULT_SHOW_DEBUG)
        }
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

        if (main_activity.tracker_service == null)
        {
            return
        }
        val tracker = main_activity.tracker_service!!

        val locationIsOld: Boolean = !(isRecentEnough(tracker.currentBestLocation))

        val newMarker: Drawable
        val radiuscolor: Int
        val description: String
        if (tracker.tracking_state == Keys.STATE_DEAD)
        {
            radiuscolor = Color.argb(64, 0, 0, 0)
            newMarker = ContextCompat.getDrawable(requireContext(), R.drawable.ic_skull_24dp)!!
            description = "GPS is struggling; disabled until movement"
        }
        else if (tracker.tracking_state == Keys.STATE_SLEEP)
        {
            radiuscolor = Color.argb(64, 220, 61, 51)
            newMarker = ContextCompat.getDrawable(requireContext(), R.drawable.ic_sleep_24dp)!!
            description = "GPS sleeping until movement"
        }
        else if (locationIsOld)
        {
            radiuscolor = Color.argb(64, 0, 0, 0)
            newMarker = ContextCompat.getDrawable(requireContext(),
                R.drawable.ic_marker_location_black_24dp
            )!!
            description = "GPS tracking at full power"
        }
        else if (tracker.tracking_state == Keys.STATE_FULL_RECORDING || tracker.tracking_state == Keys.STATE_ARRIVED_AT_HOME)
        {
            radiuscolor = Color.argb(64, 220, 61, 51)
            newMarker = ContextCompat.getDrawable(requireContext(),
                R.drawable.ic_marker_location_red_24dp
            )!!
            description = "GPS tracking at full power"
        }
        else
        {
            radiuscolor = Color.argb(64, 60, 152, 219)
            newMarker = ContextCompat.getDrawable(requireContext(),
                R.drawable.ic_marker_location_blue_24dp
            )!!
            description = "GPS tracking at full power"
        }

        val current_location_radius = Polygon()
        current_location_radius.points = Polygon.pointsAsCircle(
            GeoPoint(tracker.currentBestLocation.latitude, tracker.currentBestLocation.longitude),
            tracker.currentBestLocation.accuracy.toDouble()
        )
        current_location_radius.fillPaint.color = radiuscolor
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
        Log.i("VOUSSOIR", "create_track_overlay made this for you: " + pl.toString())
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
        currentLocationButton.isVisible = true
        if (! trackbook.database.ready)
        {
            mainButton.isEnabled = false
            mainButton.text = requireContext().getString(R.string.button_not_ready_database)
            mainButton.icon = null
        }
        else if (main_activity.tracker_service == null)
        {
            mainButton.isEnabled = false
            mainButton.text = requireContext().getString(R.string.button_not_ready_tracker)
            mainButton.icon = null
        }
        else if (! main_activity.tracker_service!!.anyLocationListenerRegistered)
        {
            mainButton.isEnabled = false
            mainButton.text = requireContext().getString(R.string.button_not_ready_listeners)
            mainButton.icon = null
        }
        else if (main_activity.tracker_service!!.tracking_state_recordable)
        {
            mainButton.isEnabled = true
            mainButton.setIconResource(R.drawable.ic_mainbutton_stopped)
            mainButton.text = requireContext().getString(R.string.button_pause)
            mainButton.contentDescription = requireContext().getString(R.string.descr_button_pause)
        }
        else
        {
            mainButton.isEnabled = true
            mainButton.setIconResource(R.drawable.ic_mainbutton_recording)
            mainButton.text = requireContext().getString(R.string.button_start)
            mainButton.contentDescription = requireContext().getString(R.string.descr_button_start)
        }

    }

    fun redraw()
    {
        update_main_button()
        if (main_activity.tracker_service == null)
        {
            return
        }
        val tracker = main_activity.tracker_service!!
        create_current_position_overlays()
        if (current_track_overlay == null)
        {
            create_track_overlay()
        }
        else
        {
            current_track_overlay!!.setPoints(tracker.recent_trackpoints_for_mapview)
        }

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
                alarm: ${iso8601_local_noms(tracker.last_alarm)}
           next alarm: ${iso8601_local_noms(tracker.next_alarm)}
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
        if (main_activity.tracker_service == null)
        {
            return "null"
        }
        val tracker = main_activity.tracker_service!!
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
