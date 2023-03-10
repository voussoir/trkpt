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

package org.y20k.trackbook

import android.Manifest
import android.app.Activity
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
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import org.osmdroid.api.IMapController
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.ItemizedIconOverlay
import org.osmdroid.views.overlay.OverlayItem
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.views.overlay.compass.CompassOverlay
import org.osmdroid.views.overlay.compass.InternalCompassOrientationProvider
import org.osmdroid.views.overlay.simplefastpoint.SimpleFastPointOverlay
import org.y20k.trackbook.helpers.*

/*
 * MapFragment class
 */
class MapFragment : Fragment()
{
    /* Define log tag */
    private val TAG: String = LogHelper.makeLogTag(MapFragment::class.java)

    /* Main class variables */
    private var bound: Boolean = false
    private val handler: Handler = Handler(Looper.getMainLooper())
    private var trackingState: Int = Keys.STATE_TRACKING_STOPPED
    private var gpsProviderActive: Boolean = false
    private var networkProviderActive: Boolean = false
    private lateinit var track: Track
    private lateinit var currentBestLocation: Location
    private lateinit var trackerService: TrackerService

    lateinit var rootView: View
    var userInteraction: Boolean = false
    lateinit var currentLocationButton: FloatingActionButton
    lateinit var mainButton: ExtendedFloatingActionButton
    private lateinit var mapView: MapView
    private lateinit var current_position_folder: FolderOverlay
    private var currentTrackOverlay: SimpleFastPointOverlay? = null
    private var currentTrackSpecialMarkerOverlay: ItemizedIconOverlay<OverlayItem>? = null
    private lateinit var locationErrorBar: Snackbar
    private lateinit var controller: IMapController
    private var zoomLevel: Double = Keys.DEFAULT_ZOOM_LEVEL
    private lateinit var homepoints_overlay_folder: FolderOverlay

    /* Overrides onCreate from Fragment */
    override fun onCreate(savedInstanceState: Bundle?)
    {
        Log.i("VOUSSOIR", "MapFragment.onCreate")
        super.onCreate(savedInstanceState)
        // TODO make only MapFragment's status bar transparent - see:
        // https://gist.github.com/Dvik/a3de88d39da9d1d6d175025a56c5e797#file-viewextension-kt and
        // https://proandroiddev.com/android-full-screen-ui-with-transparent-status-bar-ef52f3adde63
        // get current best location
        currentBestLocation = getLastKnownLocation(activity as Context)
        // get saved tracking state
        trackingState = PreferencesHelper.loadTrackingState()
    }

    /* Overrides onStop from Fragment */
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View
    {
        Log.i("VOUSSOIR", "MapFragment.onCreateView")
        val context = activity as Context
        // find views
        rootView = inflater.inflate(R.layout.fragment_map, container, false)
        mapView = rootView.findViewById(R.id.map)
        currentLocationButton = rootView.findViewById(R.id.location_button)
        mainButton = rootView.findViewById(R.id.main_button)
        locationErrorBar = Snackbar.make(mapView, String(), Snackbar.LENGTH_INDEFINITE)

        // basic map setup
        controller = mapView.controller
        mapView.isTilesScaledToDpi = true
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
        zoomLevel = PreferencesHelper.loadZoomLevel()
        controller.setZoom(zoomLevel)

        // set dark map tiles, if necessary
        if (AppThemeHelper.isDarkModeOn(requireActivity()))
        {
            mapView.overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)
        }

        // store Density Scaling Factor
        val densityScalingFactor: Float = UiHelper.getDensityScalingFactor(context)

        // add compass to map
        val compassOverlay = CompassOverlay(context, InternalCompassOrientationProvider(context), mapView)
        compassOverlay.enableCompass()
        // compassOverlay.setCompassCenter(36f, 36f + (statusBarHeight / densityScalingFactor)) // TODO uncomment when transparent status bar is re-implemented
        val screen_width = Resources.getSystem().displayMetrics.widthPixels;
        compassOverlay.setCompassCenter((screen_width / densityScalingFactor) - 36f, 36f)
        mapView.overlays.add(compassOverlay)

        val app: Trackbook = (context.applicationContext as Trackbook)
        app.load_homepoints()
        homepoints_overlay_folder = FolderOverlay()
        mapView.overlays.add(homepoints_overlay_folder)
        createHomepointOverlays(context, mapView, app.homepoints)

        // add my location overlay
        current_position_folder = FolderOverlay()
        mapView.overlays.add(current_position_folder)


        centerMap(currentBestLocation)

        // initialize track overlays
        currentTrackOverlay = null
        currentTrackSpecialMarkerOverlay = null

        // initialize main button state
        updateMainButton(trackingState)

        // listen for user interaction
        addInteractionListener()

        // set up buttons
        currentLocationButton.setOnClickListener {
            centerMap(currentBestLocation, animated = true)
        }
        mainButton.setOnClickListener {
            handleTrackingManagementMenu()
        }

        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        return rootView
    }

    /* Overrides onStart from Fragment */
    override fun onStart()
    {
        super.onStart()
        // request location permission if denied
        if (ContextCompat.checkSelfPermission(activity as Context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_DENIED) {
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
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /* Register the permission launcher for requesting location */
    private val requestLocationPermissionLauncher = registerForActivityResult(RequestPermission()) { isGranted: Boolean ->
        if (isGranted) {
            // permission was granted - re-bind service
            activity?.unbindService(connection)
            activity?.bindService(Intent(activity, TrackerService::class.java),  connection,  Context.BIND_AUTO_CREATE)
            LogHelper.i(TAG, "Request result: Location permission has been granted.")
        } else {
            // permission denied - unbind service
            activity?.unbindService(connection)
        }
        toggleLocationErrorBar(gpsProviderActive, networkProviderActive)
    }

    /* Register the permission launcher for starting the tracking service */
    private val startTrackingPermissionLauncher = registerForActivityResult(RequestPermission()) { isGranted: Boolean ->
        if (isGranted)
        {
            LogHelper.i(TAG, "Request result: Activity Recognition permission has been granted.")
        }
        else
        {
            LogHelper.i(TAG, "Request result: Activity Recognition permission has NOT been granted.")
        }
        // start service via intent so that it keeps running after unbind
        startTrackerService()
        trackerService.startTracking()
    }

    /* Start recording waypoints */
    private fun startTracking() {
        // request activity recognition permission on Android Q+ if denied
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && ContextCompat.checkSelfPermission(activity as Context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_DENIED)
        {
            startTrackingPermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        else
        {
            // start service via intent so that it keeps running after unbind
            startTrackerService()
            trackerService.startTracking()
        }
    }

    /* Start tracker service */
    private fun startTrackerService()
    {
        val intent = Intent(activity, TrackerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // ... start service in foreground to prevent it being killed on Oreo
            activity?.startForegroundService(intent)
        } else {
            activity?.startService(intent)
        }
    }

    /* Handles state when service is being unbound */
    private fun handleServiceUnbind()
    {
        bound = false
        // unregister listener for changes in shared preferences
        PreferencesHelper.unregisterPreferenceChangeListener(sharedPreferenceChangeListener)
        // stop receiving location updates
        handler.removeCallbacks(periodicLocationRequestRunnable)
    }

    /* Starts / pauses tracking and toggles the recording sub menu_bottom_navigation */
    private fun handleTrackingManagementMenu()
    {
        when (trackingState) {
            Keys.STATE_TRACKING_ACTIVE -> trackerService.pauseTracking()
            Keys.STATE_TRACKING_STOPPED -> startTracking()
        }
    }

    /*
     * Defines the listener for changes in shared preferences
     */
    private val sharedPreferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
        when (key)
        {
            Keys.PREF_TRACKING_STATE ->
            {
                if (activity != null)
                {
                    trackingState = PreferencesHelper.loadTrackingState()
                    updateMainButton(trackingState)
                }
            }
        }
    }
    private fun addInteractionListener() {
        mapView.setOnTouchListener { v, event ->
            userInteraction = true
            false
        }
    }

    fun centerMap(location: Location, animated: Boolean = false) {
        val position = GeoPoint(location.latitude, location.longitude)
        when (animated) {
            true -> controller.animateTo(position)
            false -> controller.setCenter(position)
        }
        userInteraction = false
    }

    fun saveBestLocationState(currentBestLocation: Location) {
        PreferencesHelper.saveCurrentBestLocation(currentBestLocation)
        PreferencesHelper.saveZoomLevel(mapView.zoomLevelDouble)
        // reset user interaction state
        userInteraction = false
    }

    /* Mark current position on map */
    fun markCurrentPosition(location: Location, trackingState: Int = Keys.STATE_TRACKING_STOPPED)
    {
        Log.i("VOUSSOIR", "MapFragmentLayoutHolder.markCurrentPosition")
        val locationIsOld: Boolean = !(isRecentEnough(location))

        // create marker
        val newMarker: Drawable
        val fillcolor: Int
        if (trackingState == Keys.STATE_TRACKING_ACTIVE)
        {
            fillcolor = Color.argb(64, 220, 61, 51)
            if (locationIsOld)
            {
                newMarker = ContextCompat.getDrawable(requireContext(), R.drawable.ic_marker_location_black_24dp)!!
            }
            else
            {
                newMarker = ContextCompat.getDrawable(requireContext(), R.drawable.ic_marker_location_red_24dp)!!
            }
        }
        else
        {
            fillcolor = Color.argb(64, 60, 152, 219)
            if(locationIsOld)
            {
                newMarker = ContextCompat.getDrawable(requireContext(), R.drawable.ic_marker_location_black_24dp)!!
            }
            else
            {
                newMarker = ContextCompat.getDrawable(requireContext(), R.drawable.ic_marker_location_blue_24dp)!!
            }
        }

        current_position_folder.items.clear()

        val current_location_radius = Polygon()
        current_location_radius.points = Polygon.pointsAsCircle(GeoPoint(location.latitude, location.longitude), location.accuracy.toDouble())
        current_location_radius.fillPaint.color = fillcolor
        current_location_radius.outlinePaint.color = Color.argb(0, 0, 0, 0)
        current_position_folder.add(current_location_radius)

        val overlayItems: java.util.ArrayList<OverlayItem> = java.util.ArrayList<OverlayItem>()
        val overlayItem: OverlayItem = createOverlayItem(requireContext(), location.latitude, location.longitude, location.accuracy, location.provider.toString(), location.time)
        overlayItem.setMarker(newMarker)
        overlayItems.add(overlayItem)
        current_position_folder.add(createOverlay(requireContext(), overlayItems))
    }

    fun createHomepointOverlays(context: Context, map_view: MapView, homepoints: List<Homepoint>)
    {
        Log.i("VOUSSOIR", "MapFragmentLayoutHolder.createHomepointOverlays")
        val overlayItems: java.util.ArrayList<OverlayItem> = java.util.ArrayList<OverlayItem>()

        val newMarker: Drawable = ContextCompat.getDrawable(context, R.drawable.ic_homepoint_24dp)!!

        homepoints_overlay_folder.items.clear()

        for (homepoint in homepoints)
        {
            val overlayItem: OverlayItem = createOverlayItem(context, homepoint.location.latitude, homepoint.location.longitude, homepoint.location.accuracy, homepoint.location.provider.toString(), homepoint.location.time)
            overlayItem.setMarker(newMarker)
            overlayItems.add(overlayItem)
            homepoints_overlay_folder.add(createOverlay(context, overlayItems))

            val p = Polygon()
            p.points = Polygon.pointsAsCircle(GeoPoint(homepoint.location.latitude, homepoint.location.longitude), homepoint.location.accuracy.toDouble())
            p.fillPaint.color = Color.argb(64, 255, 193, 7)
            p.outlinePaint.color = Color.argb(0, 0, 0, 0)
            homepoints_overlay_folder.add(p)
        }
    }

    /* Overlay current track on map */
    fun overlayCurrentTrack(track: Track, trackingState: Int) {
        if (currentTrackOverlay != null) {
            mapView.overlays.remove(currentTrackOverlay)
        }
        if (currentTrackSpecialMarkerOverlay != null) {
            mapView.overlays.remove(currentTrackSpecialMarkerOverlay)
        }
        if (track.trkpts.isNotEmpty()) {
            createTrackOverlay(requireContext(), mapView, track, trackingState)
            createSpecialMakersTrackOverlay(requireContext(), mapView, track, trackingState)
        }
    }

    /* Toggles state of main button and additional buttons (save & resume) */
    fun updateMainButton(trackingState: Int)
    {
        when (trackingState) {
            Keys.STATE_TRACKING_STOPPED -> {
                mainButton.setIconResource(R.drawable.ic_fiber_manual_record_inactive_24dp)
                mainButton.text = requireContext().getString(R.string.button_start)
                mainButton.contentDescription = requireContext().getString(R.string.descr_button_start)
                currentLocationButton.isVisible = true
            }
            Keys.STATE_TRACKING_ACTIVE -> {
                mainButton.setIconResource(R.drawable.ic_fiber_manual_stop_24dp)
                mainButton.text = requireContext().getString(R.string.button_pause)
                mainButton.contentDescription = requireContext().getString(R.string.descr_button_pause)
                currentLocationButton.isVisible = true
            }
        }
    }

    fun toggleLocationErrorBar(gpsProviderActive: Boolean, networkProviderActive: Boolean) {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_DENIED) {
            // CASE: Location permission not granted
            locationErrorBar.setText(R.string.snackbar_message_location_permission_denied)
            if (!locationErrorBar.isShown) locationErrorBar.show()
        } else if (!gpsProviderActive && !networkProviderActive) {
            // CASE: Location setting is off
            locationErrorBar.setText(R.string.snackbar_message_location_offline)
            if (!locationErrorBar.isShown) locationErrorBar.show()
        } else {
            if (locationErrorBar.isShown) locationErrorBar.dismiss()
        }
    }
    /*
     * End of declaration
     */

    /*
     * Defines callbacks for service binding, passed to bindService()
     */
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            bound = true
            // get reference to tracker service
            val binder = service as TrackerService.LocalBinder
            trackerService = binder.service
            // get state of tracking and update button if necessary
            trackingState = trackerService.trackingState
            updateMainButton(trackingState)
            // register listener for changes in shared preferences
            PreferencesHelper.registerPreferenceChangeListener(sharedPreferenceChangeListener)
            // start listening for location updates
            handler.removeCallbacks(periodicLocationRequestRunnable)
            handler.postDelayed(periodicLocationRequestRunnable, 0)
        }
        override fun onServiceDisconnected(arg0: ComponentName) {
            // service has crashed, or was killed by the system
            handleServiceUnbind()
        }
    }
    /*
     * End of declaration
     */

    /*
     * Runnable: Periodically requests location
     */
    private val periodicLocationRequestRunnable: Runnable = object : Runnable {
        override fun run() {
            // pull current state from service
            currentBestLocation = trackerService.currentBestLocation
            track = trackerService.track
            gpsProviderActive = trackerService.gpsProviderActive
            networkProviderActive = trackerService.networkProviderActive
            trackingState = trackerService.trackingState
            // update location and track
            markCurrentPosition(currentBestLocation, trackingState)
            overlayCurrentTrack(track, trackingState)
            // center map, if it had not been dragged/zoomed before
            if (!userInteraction)
            {
                centerMap(currentBestLocation, true)
            }
            // show error snackbar if necessary
            toggleLocationErrorBar(gpsProviderActive, networkProviderActive)
            // use the handler to start runnable again after specified delay
            handler.postDelayed(this, Keys.REQUEST_CURRENT_LOCATION_INTERVAL)
        }
    }
    /*
     * End of declaration
     */
}
