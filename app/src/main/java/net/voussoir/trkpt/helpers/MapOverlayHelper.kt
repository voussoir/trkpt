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

package net.voussoir.trkpt.helpers

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.ItemizedIconOverlay
import org.osmdroid.views.overlay.OverlayItem
import net.voussoir.trkpt.R
import net.voussoir.trkpt.Trkpt
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*

fun create_start_end_markers(context: Context, map_view: MapView, startpoint: Trkpt, endpoint: Trkpt): ItemizedIconOverlay<OverlayItem>?
{
    Log.i("VOUSSOIR", "MapOverlayHelper.create_start_end_markers")
    val overlayItems: ArrayList<OverlayItem> = ArrayList<OverlayItem>()
    val startmarker: OverlayItem = createOverlayItem(startpoint.latitude, startpoint.longitude, title="Start", iso8601_local_noms(startpoint.time))
    startmarker.setMarker(ContextCompat.getDrawable(context, R.drawable.ic_marker_track_start_48dp)!!)
    overlayItems.add(startmarker)
    if (startpoint != endpoint)
    {
        val endmarker: OverlayItem = createOverlayItem(endpoint.latitude, endpoint.longitude, title="End", description= iso8601_local_noms(endpoint.time))
        endmarker.setMarker(ContextCompat.getDrawable(context, R.drawable.ic_marker_track_end_48dp)!!)
        overlayItems.add(endmarker)
    }
    val overlay: ItemizedIconOverlay<OverlayItem> = createOverlay(context, overlayItems)
    map_view.overlays.add(overlay)
    return overlay
}

fun createOverlayItem(latitude: Double, longitude: Double, title: String, description: String): OverlayItem
{
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
