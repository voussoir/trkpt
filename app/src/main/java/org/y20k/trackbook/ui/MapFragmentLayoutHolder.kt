/*
 * MapFragmentLayoutHolder.kt
 * Implements the MapFragmentLayoutHolder class
 * A MapFragmentLayoutHolder hold references to the main views of a map fragment
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

package org.y20k.trackbook.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.location.Location
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import org.osmdroid.api.IMapController
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.ItemizedIconOverlay
import org.osmdroid.views.overlay.OverlayItem
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.views.overlay.compass.CompassOverlay
import org.osmdroid.views.overlay.compass.InternalCompassOrientationProvider
import org.osmdroid.views.overlay.simplefastpoint.SimpleFastPointOverlay
import org.y20k.trackbook.Homepoint
import org.y20k.trackbook.Keys
import org.y20k.trackbook.R
import org.y20k.trackbook.Trackbook
import org.y20k.trackbook.Track
import org.y20k.trackbook.helpers.*

/*
 * MapFragmentLayoutHolder class
 */
data class MapFragmentLayoutHolder(
    private var context: Context,
    private var inflater: LayoutInflater,
    private var container: ViewGroup?,
    private var statusBarHeight: Int,
    private val startLocation: Location,
    private val trackingState: Int
)
{
    val rootView: View
    var userInteraction: Boolean = false
    val currentLocationButton: FloatingActionButton
    val mainButton: ExtendedFloatingActionButton
    private val mapView: MapView
    private var currentPositionOverlay: ItemizedIconOverlay<OverlayItem>
    private var current_location_radius: Polygon = Polygon()
    private var currentTrackOverlay: SimpleFastPointOverlay?
    private var currentTrackSpecialMarkerOverlay: ItemizedIconOverlay<OverlayItem>?
    private var locationErrorBar: Snackbar
    private var controller: IMapController
    private var zoomLevel: Double

    init
    {
        Log.i("VOUSSOIR", "MapFragmentLayoutHolder.init")
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
        if (AppThemeHelper.isDarkModeOn(context as Activity)) {
            mapView.overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)
        }

        // store Density Scaling Factor
        val densityScalingFactor: Float = UiHelper.getDensityScalingFactor(context)

        // add compass to map
        val compassOverlay = CompassOverlay(context, InternalCompassOrientationProvider(context), mapView)
        compassOverlay.enableCompass()
//        compassOverlay.setCompassCenter(36f, 36f + (statusBarHeight / densityScalingFactor)) // TODO uncomment when transparent status bar is re-implemented
        val screen_width = Resources.getSystem().getDisplayMetrics().widthPixels;
        compassOverlay.setCompassCenter((screen_width / densityScalingFactor) - 36f, 36f)
        mapView.overlays.add(compassOverlay)

        val app: Trackbook = (context.applicationContext as Trackbook)
        app.load_homepoints()
        createHomepointOverlays(context, mapView, app.homepoints)

        // add my location overlay
        currentPositionOverlay = createOverlay(context, ArrayList<OverlayItem>())
        mapView.overlays.add(currentPositionOverlay)
        centerMap(startLocation)

        // initialize track overlays
        currentTrackOverlay = null
        currentTrackSpecialMarkerOverlay = null

        // initialize main button state
        updateMainButton(trackingState)

        // listen for user interaction
        addInteractionListener()
    }

    /* Listen for user interaction */
    @SuppressLint("ClickableViewAccessibility")
    private fun addInteractionListener() {
        mapView.setOnTouchListener { v, event ->
            userInteraction = true
            false
        }
    }

    /* Set map center */
    fun centerMap(location: Location, animated: Boolean = false) {
        val position = GeoPoint(location.latitude, location.longitude)
        when (animated) {
            true -> controller.animateTo(position)
            false -> controller.setCenter(position)
        }
        userInteraction = false
    }

    /* Save current best location and state of map to shared preferences */
    fun saveState(currentBestLocation: Location) {
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
                newMarker = ContextCompat.getDrawable(context, R.drawable.ic_marker_location_black_24dp)!!
            }
            else
            {
                newMarker = ContextCompat.getDrawable(context, R.drawable.ic_marker_location_red_24dp)!!
            }
        }
        else
        {
            fillcolor = Color.argb(64, 60, 152, 219)
            if(locationIsOld)
            {
                newMarker = ContextCompat.getDrawable(context, R.drawable.ic_marker_location_black_24dp)!!
            }
            else
            {
                newMarker = ContextCompat.getDrawable(context, R.drawable.ic_marker_location_blue_24dp)!!
            }
        }

        // add marker to list of overlay items
        val overlayItem: OverlayItem = createOverlayItem(context, location.latitude, location.longitude, location.accuracy, location.provider.toString(), location.time)
        overlayItem.setMarker(newMarker)
        currentPositionOverlay.removeAllItems()
        currentPositionOverlay.addItem(overlayItem)

        if (current_location_radius in mapView.overlays)
        {
            mapView.overlays.remove(current_location_radius)
        }
        current_location_radius = Polygon()
        current_location_radius.points = Polygon.pointsAsCircle(GeoPoint(location.latitude, location.longitude), location.accuracy.toDouble())
        current_location_radius.fillPaint.color = fillcolor
        current_location_radius.outlinePaint.color = Color.argb(0, 0, 0, 0)
        mapView.overlays.add(current_location_radius)
    }

    fun createHomepointOverlays(context: Context, map_view: MapView, homepoints: List<Homepoint>)
    {
        Log.i("VOUSSOIR", "MapFragmentLayoutHolder.createHomepointOverlays")
        val overlayItems: java.util.ArrayList<OverlayItem> = java.util.ArrayList<OverlayItem>()

        val newMarker: Drawable = ContextCompat.getDrawable(context, R.drawable.ic_homepoint_24dp)!!

        for (homepoint in homepoints)
        {
            val overlayItem: OverlayItem = createOverlayItem(context, homepoint.location.latitude, homepoint.location.longitude, homepoint.location.accuracy, homepoint.location.provider.toString(), homepoint.location.time)
            overlayItem.setMarker(newMarker)
            overlayItems.add(overlayItem)
            map_view.overlays.add(createOverlay(context, overlayItems))

            val p = Polygon()
            p.points = Polygon.pointsAsCircle(GeoPoint(homepoint.location.latitude, homepoint.location.longitude), homepoint.location.accuracy.toDouble())
            p.fillPaint.color = Color.argb(64, 255, 193, 7)
            p.outlinePaint.color = Color.argb(0, 0, 0, 0)
            map_view.overlays.add(p)
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
            createTrackOverlay(context, mapView, track, trackingState)
            createSpecialMakersTrackOverlay(context, mapView, track, trackingState)
        }
    }

    /* Toggles state of main button and additional buttons (save & resume) */
    fun updateMainButton(trackingState: Int)
    {
        when (trackingState) {
            Keys.STATE_TRACKING_STOPPED -> {
                mainButton.setIconResource(R.drawable.ic_fiber_manual_record_inactive_24dp)
                mainButton.text = context.getString(R.string.button_start)
                mainButton.contentDescription = context.getString(R.string.descr_button_start)
                currentLocationButton.isVisible = true
            }
            Keys.STATE_TRACKING_ACTIVE -> {
                mainButton.setIconResource(R.drawable.ic_fiber_manual_stop_24dp)
                mainButton.text = context.getString(R.string.button_pause)
                mainButton.contentDescription = context.getString(R.string.descr_button_pause)
                currentLocationButton.isVisible = true
            }
        }
    }

    /* Toggles content and visibility of the location error snackbar */
    fun toggleLocationErrorBar(gpsProviderActive: Boolean, networkProviderActive: Boolean) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_DENIED) {
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
}
