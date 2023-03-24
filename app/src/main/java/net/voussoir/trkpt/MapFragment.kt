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

package net.voussoir.trkpt

import android.Manifest
import android.app.Dialog
import android.content.*
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.location.Location
import android.os.*
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
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
import org.osmdroid.views.overlay.compass.CompassOverlay
import org.osmdroid.views.overlay.compass.InternalCompassOrientationProvider
import net.voussoir.trkpt.helpers.*
import net.voussoir.trkpt.R

class MapFragment : Fragment()
{
    private lateinit var trackbook: Trackbook

    private var bound: Boolean = false
    val handler: Handler = Handler(Looper.getMainLooper())
    private var trackingState: Int = Keys.STATE_TRACKING_STOPPED
    private var gpsProviderActive: Boolean = false
    private var networkProviderActive: Boolean = false
    private lateinit var currentBestLocation: Location

    var continuous_auto_center: Boolean = true
    private lateinit var trackerService: TrackerService
    private lateinit var database_changed_listener: DatabaseChangedListener

    var thismapfragment: MapFragment? = null
    lateinit var rootView: View
    private lateinit var mapView: MapView
    lateinit var mainButton: ExtendedFloatingActionButton

    lateinit var zoom_in_button: FloatingActionButton
    lateinit var zoom_out_button: FloatingActionButton
    lateinit var currentLocationButton: FloatingActionButton
    private var current_track_overlay: Polyline? = null
    private var current_position_overlays = ArrayList<Overlay>()
    private var homepoints_overlays = ArrayList<Overlay>()
    private lateinit var locationErrorBar: Snackbar

    /* Overrides onCreate from Fragment */
    override fun onCreate(savedInstanceState: Bundle?)
    {
        Log.i("VOUSSOIR", "MapFragment.onCreate")
        super.onCreate(savedInstanceState)
        thismapfragment = this
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
        currentBestLocation = getLastKnownLocation(requireContext())
        trackingState = PreferencesHelper.loadTrackingState()
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
        mainButton = rootView.findViewById(R.id.main_button)
        locationErrorBar = Snackbar.make(mapView, String(), Snackbar.LENGTH_INDEFINITE)

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

        val densityScalingFactor: Float = UiHelper.getDensityScalingFactor(requireContext())
        val compassOverlay = CompassOverlay(requireContext(), InternalCompassOrientationProvider(requireContext()), mapView)
        compassOverlay.enableCompass()
        val screen_width = Resources.getSystem().displayMetrics.widthPixels
        compassOverlay.setCompassCenter((screen_width / densityScalingFactor) - 36f, 36f)
        mapView.overlays.add(compassOverlay)

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

        create_current_position_overlays(currentBestLocation, trackingState)

        centerMap(currentBestLocation)

        current_track_overlay = null

        mapView.setOnTouchListener { v, event ->
            continuous_auto_center = false
            false
        }

        update_main_button()
        mainButton.setOnClickListener {
            if (trackingState == Keys.STATE_TRACKING_ACTIVE)
            {
                trackerService.stopTracking()
            }
            else
            {
                startTracking()
            }
            handler.postDelayed(location_update_redraw, 0)
        }
        currentLocationButton.setOnClickListener {
            centerMap(currentBestLocation, animated=true)
        }
        zoom_in_button.setOnClickListener {
            mapView.controller.setZoom(mapView.zoomLevelDouble + 0.5)
        }
        zoom_out_button.setOnClickListener {
            mapView.controller.setZoom(mapView.zoomLevelDouble - 0.5)
        }

        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        return rootView
    }

    /* Overrides onStart from Fragment */
    override fun onStart()
    {
        Log.i("VOUSSOIR", "MapFragment.onStart")
        super.onStart()
        // request location permission if denied
        if (ContextCompat.checkSelfPermission(activity as Context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_DENIED)
        {
            requestLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        // bind to TrackerService
        activity?.bindService(Intent(activity, TrackerService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    /* Overrides onResume from Fragment */
    override fun onResume()
    {
        Log.i("VOUSSOIR", "MapFragment.onResume")
        super.onResume()
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
        if (::trackerService.isInitialized)
        {
            trackerService.mapfragment = null
        }
        saveBestLocationState(currentBestLocation)
        if (bound && trackingState != Keys.STATE_TRACKING_ACTIVE) {
            trackerService.removeGpsLocationListener()
            trackerService.removeNetworkLocationListener()
            trackerService.trackbook.database.commit()
        }
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /* Overrides onStop from Fragment */
    override fun onStop()
    {
        super.onStop()
        if (::trackerService.isInitialized)
        {
            trackerService.mapfragment = null
        }
        // unbind from TrackerService
        if (bound)
        {
            activity?.unbindService(connection)
            handleServiceUnbind()
        }
    }

    override fun onDestroy()
    {
        Log.i("VOUSSOIR", "MapFragment.onDestroy")
        super.onDestroy()
        trackerService.mapfragment = null
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (database_changed_listener in trackbook.database_changed_listeners)
        {
            trackbook.database_changed_listeners.remove(database_changed_listener)
        }
    }

    private val requestLocationPermissionLauncher = registerForActivityResult(RequestPermission()) { isGranted: Boolean ->
        if (isGranted)
        {
            // permission was granted - re-bind service
            activity?.unbindService(connection)
            activity?.bindService(Intent(activity, TrackerService::class.java),  connection,  Context.BIND_AUTO_CREATE)
            Log.i("VOUSSOIR", "Request result: Location permission has been granted.")
        }
        else
        {
            // permission denied - unbind service
            activity?.unbindService(connection)
        }
        toggleLocationErrorBar(gpsProviderActive, networkProviderActive)
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
        trackerService.startTracking()
    }


    /* Handles state when service is being unbound */
    private fun handleServiceUnbind()
    {
        bound = false
        // unregister listener for changes in shared preferences
        PreferencesHelper.unregisterPreferenceChangeListener(sharedPreferenceChangeListener)
        if (::trackerService.isInitialized)
        {
            trackerService.mapfragment = null
        }
    }

    private val sharedPreferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
        when (key)
        {
            Keys.PREF_TRACKING_STATE ->
            {
                if (activity != null)
                {
                    trackingState = PreferencesHelper.loadTrackingState()
                }
            }
        }
        update_main_button()
    }

    fun centerMap(location: Location, animated: Boolean = false) {
        val position = GeoPoint(location.latitude, location.longitude)
        if (animated)
        {
            mapView.controller.animateTo(position)
        }
        else
        {
            mapView.controller.setCenter(position)
        }
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

    /* Mark current position on map */
    fun create_current_position_overlays(location: Location, trackingState: Int = Keys.STATE_TRACKING_STOPPED)
    {
        clear_current_position_overlays()

        val locationIsOld: Boolean = !(isRecentEnough(location))

        val newMarker: Drawable
        val fillcolor: Int
        if (locationIsOld)
        {
            fillcolor = Color.argb(64, 0, 0, 0)
            newMarker = ContextCompat.getDrawable(requireContext(), R.drawable.ic_marker_location_black_24dp)!!
        }
        else if (trackingState == Keys.STATE_TRACKING_ACTIVE)
        {
            fillcolor = Color.argb(64, 220, 61, 51)
            newMarker = ContextCompat.getDrawable(requireContext(), R.drawable.ic_marker_location_red_24dp)!!
        }
        else
        {
            fillcolor = Color.argb(64, 60, 152, 219)
            newMarker = ContextCompat.getDrawable(requireContext(), R.drawable.ic_marker_location_blue_24dp)!!
        }

        val current_location_radius = Polygon()
        current_location_radius.points = Polygon.pointsAsCircle(GeoPoint(location.latitude, location.longitude), location.accuracy.toDouble())
        current_location_radius.fillPaint.color = fillcolor
        current_location_radius.outlinePaint.color = Color.argb(0, 0, 0, 0)
        current_position_overlays.add(current_location_radius)

        val overlayItems: java.util.ArrayList<OverlayItem> = java.util.ArrayList<OverlayItem>()
        val overlayItem: OverlayItem = createOverlayItem(requireContext(), location.latitude, location.longitude, location.accuracy, location.provider.toString(), location.time)
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
                context,
                homepoint.location.latitude,
                homepoint.location.longitude,
                homepoint.location.accuracy,
                homepoint.location.provider.toString(),
                homepoint.location.time
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
                            trackbook.load_homepoints()
                            create_homepoint_overlays()
                            dialog.dismiss()
                        }
                        save_button.setOnClickListener {
                            val radius = radius_input.text.toString().toDoubleOrNull() ?: 25.0
                            trackbook.database.update_homepoint(homepoint.id, name=name_input.text.toString(), radius=radius)
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
        mainButton.isEnabled = trackbook.database.ready
        currentLocationButton.isVisible = true
        if (! trackbook.database.ready)
        {
            mainButton.text = requireContext().getString(R.string.button_not_ready)
            mainButton.icon = null
        }
        else if (trackingState == Keys.STATE_TRACKING_STOPPED)
        {
            mainButton.setIconResource(R.drawable.ic_fiber_manual_record_inactive_24dp)
            mainButton.text = requireContext().getString(R.string.button_start)
            mainButton.contentDescription = requireContext().getString(R.string.descr_button_start)
        }
        else if (trackingState == Keys.STATE_TRACKING_ACTIVE)
        {
            mainButton.setIconResource(R.drawable.ic_fiber_manual_stop_24dp)
            mainButton.text = requireContext().getString(R.string.button_pause)
            mainButton.contentDescription = requireContext().getString(R.string.descr_button_pause)
        }
    }

    fun toggleLocationErrorBar(gpsProviderActive: Boolean, networkProviderActive: Boolean)
    {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_DENIED)
        {
            // CASE: Location permission not granted
            locationErrorBar.setText(R.string.snackbar_message_location_permission_denied)
            if (!locationErrorBar.isShown) locationErrorBar.show()
        }
        else if (!gpsProviderActive && !networkProviderActive)
        {
            // CASE: Location setting is off
            locationErrorBar.setText(R.string.snackbar_message_location_offline)
            if (!locationErrorBar.isShown) locationErrorBar.show()
        }
        else
        {
            if (locationErrorBar.isShown) locationErrorBar.dismiss()
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder)
        {
            bound = true
            // get reference to tracker service
            val binder = service as TrackerService.LocalBinder
            trackerService = binder.service
            trackerService.mapfragment = thismapfragment
            // get state of tracking and update button if necessary
            trackingState = trackerService.trackingState
            update_main_button()
            handler.postDelayed(location_update_redraw, 0)
            // register listener for changes in shared preferences
            PreferencesHelper.registerPreferenceChangeListener(sharedPreferenceChangeListener)
            // start listening for location updates
        }
        override fun onServiceDisconnected(arg0: ComponentName)
        {
            // service has crashed, or was killed by the system
            handleServiceUnbind()
        }
    }

    val location_update_redraw: Runnable = object : Runnable
    {
        override fun run()
        {
            Log.i("VOUSSOIR", "MapFragment.location_update_redraw")
            currentBestLocation = trackerService.currentBestLocation
            gpsProviderActive = trackerService.gpsProviderActive
            networkProviderActive = trackerService.networkProviderActive
            trackingState = trackerService.trackingState

            create_current_position_overlays(currentBestLocation, trackingState)
            if (current_track_overlay == null)
            {
                create_track_overlay()
            }
            current_track_overlay!!.setPoints(trackerService.recent_trackpoints_for_mapview)

            if (continuous_auto_center)
            {
                centerMap(currentBestLocation, animated=false)
            }
        }
    }
}
