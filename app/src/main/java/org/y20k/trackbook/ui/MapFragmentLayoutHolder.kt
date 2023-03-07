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
import android.graphics.Paint
import android.location.Location
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.Group
import androidx.core.content.ContextCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textview.MaterialTextView
import org.osmdroid.api.IMapController
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.ItemizedIconOverlay
import org.osmdroid.views.overlay.OverlayItem
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.views.overlay.compass.CompassOverlay
import org.osmdroid.views.overlay.compass.InternalCompassOrientationProvider
import org.osmdroid.views.overlay.simplefastpoint.SimpleFastPointOverlay
import org.y20k.trackbook.Keys
import org.y20k.trackbook.R
import org.y20k.trackbook.Trackbook
import org.y20k.trackbook.core.Track
import org.y20k.trackbook.helpers.*

/*
 * MapFragmentLayoutHolder class
 */
data class MapFragmentLayoutHolder(private var context: Context, private var markerListener: MapOverlayHelper.MarkerListener, private var inflater: LayoutInflater, private var container: ViewGroup?, private var statusBarHeight: Int, private val startLocation: Location, private val trackingState: Int) {

    /* Define log tag */
    private val TAG: String = LogHelper.makeLogTag(MapFragmentLayoutHolder::class.java)


    /* Main class variables */
    val rootView: View
    var userInteraction: Boolean = false
    val currentLocationButton: FloatingActionButton
    val mainButton: ExtendedFloatingActionButton
    private val mapView: MapView
    private val homepoint_overlays: ArrayList<ItemizedIconOverlay<OverlayItem>> = ArrayList()
    private var currentPositionOverlay: ItemizedIconOverlay<OverlayItem>
    private var currentTrackOverlay: SimpleFastPointOverlay?
    private var currentTrackSpecialMarkerOverlay: ItemizedIconOverlay<OverlayItem>?
    private val useImperial: Boolean = PreferencesHelper.loadUseImperialUnits()
    private var locationErrorBar: Snackbar
    private var controller: IMapController
    private var zoomLevel: Double


    /* Init block */
    init {
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
        app.homepoint_generator().forEach { homepoint -> mapView.overlays.add(MapOverlayHelper(markerListener).createHomepointOverlay(context, homepoint.location))}

        // add my location overlay
        currentPositionOverlay = MapOverlayHelper(markerListener).createMyLocationOverlay(context, startLocation, trackingState)
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
    fun markCurrentPosition(location: Location, trackingState: Int = Keys.STATE_TRACKING_STOPPED) {
        mapView.overlays.remove(currentPositionOverlay)
        currentPositionOverlay = MapOverlayHelper(markerListener).createMyLocationOverlay(context, location, trackingState)
        mapView.overlays.add(currentPositionOverlay)
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
            val mapOverlayHelper: MapOverlayHelper = MapOverlayHelper(markerListener)
            currentTrackOverlay = mapOverlayHelper.createTrackOverlay(context, track, trackingState)
            currentTrackSpecialMarkerOverlay = mapOverlayHelper.createSpecialMakersTrackOverlay(context, track, trackingState)
            mapView.overlays.add(currentTrackSpecialMarkerOverlay)
            mapView.overlays.add(currentTrackOverlay)
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
