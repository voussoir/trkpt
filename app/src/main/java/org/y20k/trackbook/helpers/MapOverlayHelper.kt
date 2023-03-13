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

/* Creates icon overlay for track */
fun createTrackOverlay(context: Context, map_view: MapView, trkpts: Collection<Trkpt>, trackingState: Int): SimpleFastPointOverlay
{
    val color = if (trackingState == Keys.STATE_TRACKING_ACTIVE) context.getColor(R.color.default_red) else context.getColor(R.color.default_blue)
    val points: MutableList<IGeoPoint> = mutableListOf()
    trkpts.forEach { trkpt ->
        val label = "${context.getString(R.string.marker_description_time)}: ${SimpleDateFormat.getTimeInstance(SimpleDateFormat.MEDIUM, Locale.getDefault()).format(trkpt.time)} | ${context.getString(R.string.marker_description_accuracy)}: ${DecimalFormat("#0.00").format(trkpt.accuracy)} (${trkpt.provider})"
        // only add normal points
        if (!trkpt.starred)
        {
            points.add(LabelledGeoPoint(trkpt.latitude, trkpt.longitude, trkpt.altitude, label))
        }
    }
    val pointTheme: SimplePointTheme = SimplePointTheme(points, false)
    val style = Paint()
    style.style = Paint.Style.FILL
    style.color = color
    style.flags = Paint.ANTI_ALIAS_FLAG
    val scalingFactor: Float = UiHelper.getDensityScalingFactor(context)
    val overlayOptions: SimpleFastPointOverlayOptions = SimpleFastPointOverlayOptions.getDefaultStyle()
        .setAlgorithm(SimpleFastPointOverlayOptions.RenderingAlgorithm.MAXIMUM_OPTIMIZATION)
        .setSymbol(SimpleFastPointOverlayOptions.Shape.CIRCLE)
        .setPointStyle(style)
        .setRadius(6F * scalingFactor) // radius is set in px - scaling factor makes that display density independent (= dp)
        .setIsClickable(false)
        .setCellSize(12) // Sets the grid cell size used for indexing, in pixels. Larger cells result in faster rendering speed, but worse fidelity. Default is 10 pixels, for large datasets (>10k points), use 15.
    val overlay = SimpleFastPointOverlay(pointTheme, overlayOptions)
    map_view.overlays.add(overlay)
    return overlay
}

/* Creates overlay containing start, stop, stopover and starred markers for track */
fun createSpecialMakersTrackOverlay(context: Context, map_view: MapView, trkpts: Collection<Trkpt>, trackingState: Int, displayStartEndMarker: Boolean = false): ItemizedIconOverlay<OverlayItem>
{
    val overlayItems: ArrayList<OverlayItem> = ArrayList<OverlayItem>()
    val trackingActive: Boolean = trackingState == Keys.STATE_TRACKING_ACTIVE
    val maxIndex: Int = trkpts.size - 1

    trkpts.forEachIndexed { index: Int, trkpt: Trkpt ->
        var overlayItem: OverlayItem? = null
        if (!trackingActive && index == 0 && displayStartEndMarker && trkpt.starred)
        {
            overlayItem = createOverlayItem(context, trkpt.latitude, trkpt.longitude, trkpt.accuracy, trkpt.provider, trkpt.time)
            overlayItem.setMarker(ContextCompat.getDrawable(context, R.drawable.ic_marker_track_start_starred_blue_48dp)!!)
        }
        else if (!trackingActive && index == 0 && displayStartEndMarker && !trkpt.starred)
        {
            overlayItem = createOverlayItem(context, trkpt.latitude, trkpt.longitude, trkpt.accuracy, trkpt.provider, trkpt.time)
            overlayItem.setMarker(ContextCompat.getDrawable(context, R.drawable.ic_marker_track_start_blue_48dp)!!)
        }
        else if (!trackingActive && index == maxIndex && displayStartEndMarker && trkpt.starred)
        {
            overlayItem = createOverlayItem(context, trkpt.latitude, trkpt.longitude, trkpt.accuracy, trkpt.provider, trkpt.time)
            overlayItem.setMarker(ContextCompat.getDrawable(context, R.drawable.ic_marker_track_end_starred_blue_48dp)!!)
        }
        else if (!trackingActive && index == maxIndex && displayStartEndMarker && !trkpt.starred)
        {
            overlayItem = createOverlayItem(context, trkpt.latitude, trkpt.longitude, trkpt.accuracy, trkpt.provider, trkpt.time)
            overlayItem.setMarker(ContextCompat.getDrawable(context, R.drawable.ic_marker_track_end_blue_48dp)!!)
        }
        else if (!trackingActive && trkpt.starred)
        {
            overlayItem = createOverlayItem(context, trkpt.latitude, trkpt.longitude, trkpt.accuracy, trkpt.provider, trkpt.time)
            overlayItem.setMarker(ContextCompat.getDrawable(context, R.drawable.ic_star_blue_24dp)!!)
        }
        else if (trackingActive && trkpt.starred)
        {
            overlayItem = createOverlayItem(context, trkpt.latitude, trkpt.longitude, trkpt.accuracy, trkpt.provider, trkpt.time)
            overlayItem.setMarker(ContextCompat.getDrawable(context, R.drawable.ic_star_red_24dp)!!)
        }
        if (overlayItem != null)
        {
            overlayItems.add(overlayItem)
        }
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
