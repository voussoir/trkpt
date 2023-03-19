/*
 * MapHelper.kt
 * Implements the MapOverlayHelper class
 * A MapOverlayHelper offers helper methods for creating osmdroid map overlays
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

package org.y20k.trackbook.helpers

import android.content.Context
import android.graphics.Paint
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import org.osmdroid.api.IGeoPoint
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.ItemizedIconOverlay
import org.osmdroid.views.overlay.OverlayItem
import org.osmdroid.views.overlay.simplefastpoint.LabelledGeoPoint
import org.osmdroid.views.overlay.simplefastpoint.SimpleFastPointOverlay
import org.osmdroid.views.overlay.simplefastpoint.SimpleFastPointOverlayOptions
import org.osmdroid.views.overlay.simplefastpoint.SimplePointTheme
import org.y20k.trackbook.Keys
import org.y20k.trackbook.R
import org.y20k.trackbook.Trkpt
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*

fun createTrackOverlay(context: Context, map_view: MapView, geopoints: MutableList<IGeoPoint>, trackingState: Int): SimpleFastPointOverlay
{
    Log.i("VOUSSOIR", "MapOverlayHelper.createTrackOverlay")
    val pointTheme = SimplePointTheme(geopoints, false)
    val style = Paint()
    style.style = Paint.Style.FILL
    style.color = if (trackingState == Keys.STATE_TRACKING_ACTIVE) context.getColor(R.color.default_red) else context.getColor(R.color.default_blue)
    style.flags = Paint.ANTI_ALIAS_FLAG
    val overlayOptions: SimpleFastPointOverlayOptions = SimpleFastPointOverlayOptions.getDefaultStyle()
        .setAlgorithm(SimpleFastPointOverlayOptions.RenderingAlgorithm.MAXIMUM_OPTIMIZATION)
        .setSymbol(SimpleFastPointOverlayOptions.Shape.CIRCLE)
        .setPointStyle(style)
        .setRadius(6F * UiHelper.getDensityScalingFactor(context)) // radius is set in px - scaling factor makes that display density independent (= dp)
        .setIsClickable(true)
        .setCellSize(12) // Sets the grid cell size used for indexing, in pixels. Larger cells result in faster rendering speed, but worse fidelity. Default is 10 pixels, for large datasets (>10k points), use 15.
    var overlay = SimpleFastPointOverlay(pointTheme, overlayOptions)

    overlay.setOnClickListener(object : SimpleFastPointOverlay.OnClickListener {
        override fun onClick(points: SimpleFastPointOverlay.PointAdapter?, point: Int?)
        {
            if (points == null || point == null || point == 0)
            {
                return
            }
            val trkpt = (points[point]) as Trkpt
            Log.i("VOUSSOIR", "Clicked ${trkpt.device_id} ${trkpt.time}")
            // trackpoints.remove(points[point])
            // map_view.overlays.remove(overlay)
            // overlay = SimpleFastPointOverlay(pointTheme, overlayOptions)
            // overlay.setOnClickListener(this)
            // map_view.overlays.add(overlay)
            // map_view.postInvalidate()
            return
        }
    })

    map_view.overlays.add(overlay)
    return overlay
}

fun create_start_end_markers(context: Context, map_view: MapView, trkpts: Collection<Trkpt>): ItemizedIconOverlay<OverlayItem>?
{
    Log.i("VOUSSOIR", "MapOverlayHelper.create_start_end_markers")
    if (trkpts.size == 0)
    {
        return null
    }

    val overlayItems: ArrayList<OverlayItem> = ArrayList<OverlayItem>()
    val startpoint = trkpts.first()
    val endpoint = trkpts.last()
    val startmarker: OverlayItem = createOverlayItem(context, startpoint.latitude, startpoint.longitude, startpoint.accuracy, startpoint.provider, startpoint.time)
    startmarker.setMarker(ContextCompat.getDrawable(context, R.drawable.ic_marker_track_start_blue_48dp)!!)
    overlayItems.add(startmarker)
    if (trkpts.size > 1)
    {
        val endmarker: OverlayItem = createOverlayItem(context, endpoint.latitude, endpoint.longitude, endpoint.accuracy, endpoint.provider, endpoint.time)
        endmarker.setMarker(ContextCompat.getDrawable(context, R.drawable.ic_marker_track_end_blue_48dp)!!)
        overlayItems.add(endmarker)
    }
    val overlay: ItemizedIconOverlay<OverlayItem> = createOverlay(context, overlayItems)
    map_view.overlays.add(overlay)
    return overlay
}

fun createOverlayItem(context: Context, latitude: Double, longitude: Double, accuracy: Float, provider: String, time: Long): OverlayItem
{
    val title = "${context.getString(R.string.marker_description_time)}: ${SimpleDateFormat.getTimeInstance(SimpleDateFormat.MEDIUM, Locale.getDefault()).format(time)}"
    val description = "${context.getString(R.string.marker_description_time)}: ${SimpleDateFormat.getTimeInstance(SimpleDateFormat.MEDIUM, Locale.getDefault()).format(time)} | ${context.getString(R.string.marker_description_accuracy)}: ${DecimalFormat("#0.00").format(accuracy)} (${provider})"
    val position = GeoPoint(latitude, longitude)
    val item = OverlayItem(title, description, position)
    item.markerHotspot = OverlayItem.HotspotPlace.CENTER
    return item
}

fun createOverlay(context: Context, overlayItems: ArrayList<OverlayItem>): ItemizedIconOverlay<OverlayItem>
{
    return ItemizedIconOverlay<OverlayItem>(context, overlayItems,
        object : ItemizedIconOverlay.OnItemGestureListener<OverlayItem> {
            override fun onItemSingleTapUp(index: Int, item: OverlayItem): Boolean
            {
                return false
            }
            override fun onItemLongPress(index: Int, item: OverlayItem): Boolean
            {
                Toast.makeText(context, item.snippet, Toast.LENGTH_LONG).show()
                return true
            }
        }
    )
}
