/*
 * TrackFragmentLayoutHolder.kt
 * Implements the TrackFragmentLayoutHolder class
 * A TrackFragmentLayoutHolder hold references to the main views of a track fragment
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

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.DatePicker
import android.widget.ImageButton
import android.widget.TimePicker
import android.widget.Toast
import androidx.constraintlayout.widget.Group
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.textview.MaterialTextView
import org.osmdroid.api.IMapController
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.ItemizedIconOverlay
import org.osmdroid.views.overlay.OverlayItem
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.views.overlay.simplefastpoint.SimpleFastPointOverlay
import org.y20k.trackbook.Keys
import org.y20k.trackbook.R
import org.y20k.trackbook.Track
import org.y20k.trackbook.TrackStatistics
import org.y20k.trackbook.helpers.*
import java.util.*

data class TrackFragmentLayoutHolder(
    private var context: Context,
    private var inflater: LayoutInflater,
    private var container: ViewGroup?,
    var track: Track
): MapListener
{
    val rootView: View
    val save_track_button: ImageButton
    val deleteButton: ImageButton
    val trackNameView: MaterialTextView
    val track_query_start_date: DatePicker
    val track_query_start_time: TimePicker
    val track_query_end_date: DatePicker
    val track_query_end_time: TimePicker
    var track_query_start_time_previous: Int
    var track_query_end_time_previous: Int
    private val mapView: MapView
    private var controller: IMapController
    private val statisticsSheetBehavior: BottomSheetBehavior<View>
    private val statisticsSheet: NestedScrollView
    private val statisticsView: View
    private val distanceView: MaterialTextView
    private val waypointsView: MaterialTextView
    private val durationView: MaterialTextView
    private val velocityView: MaterialTextView
    private val recordingStartView: MaterialTextView
    private val recordingStopView: MaterialTextView
    private val recordingPausedView: MaterialTextView
    private val recordingPausedLabelView: MaterialTextView
    private val maxAltitudeView: MaterialTextView
    private val minAltitudeView: MaterialTextView
    private val positiveElevationView: MaterialTextView
    private val negativeElevationView: MaterialTextView
    private val elevationDataViews: Group
    private val useImperialUnits: Boolean
    private val handler: Handler = Handler(Looper.getMainLooper())
    private var special_points_overlay: ItemizedIconOverlay<OverlayItem>? = null
    private var track_overlay: SimpleFastPointOverlay? = null
    val RERENDER_DELAY: Long = 1000

    init
    {
        rootView = inflater.inflate(R.layout.fragment_track, container, false)
        mapView = rootView.findViewById(R.id.map)
        save_track_button = rootView.findViewById(R.id.save_button)
        deleteButton = rootView.findViewById(R.id.delete_button)
        trackNameView = rootView.findViewById(R.id.statistics_track_name_headline)

        track.load_trkpts()
        val actual_start_time: Date = if (track.trkpts.isEmpty()) track.start_time else Date(track.trkpts.first().time)
        val actual_end_time: Date = if (track.trkpts.isEmpty()) track.end_time else Date(track.trkpts.last().time)

        track_query_start_date = rootView.findViewById(R.id.track_query_start_date)
        val start_cal = GregorianCalendar()
        start_cal.time = actual_start_time
        track_query_start_date.init(start_cal.get(Calendar.YEAR), start_cal.get(Calendar.MONTH), start_cal.get(Calendar.DAY_OF_MONTH), object: DatePicker.OnDateChangedListener {
            override fun onDateChanged(p0: DatePicker?, p1: Int, p2: Int, p3: Int)
            {
                handler.removeCallbacks(requery_and_render)
                handler.postDelayed(requery_and_render, RERENDER_DELAY)
            }
        })

        track_query_start_time = rootView.findViewById(R.id.track_query_start_time)
        track_query_start_time.setIs24HourView(true)
        track_query_start_time.hour = actual_start_time.hours
        track_query_start_time.minute = actual_start_time.minutes
        track_query_start_time_previous = (actual_start_time.hours * 60) + actual_start_time.minutes
        track_query_start_time.setOnTimeChangedListener(object : TimePicker.OnTimeChangedListener{
            override fun onTimeChanged(p0: TimePicker?, p1: Int, p2: Int)
            {
                handler.removeCallbacks(requery_and_render)
                val newminute = (p1 * 60) + p2
                Log.i("VOUSSOIR", "End time changed $newminute")
                if (newminute < track_query_start_time_previous && (track_query_start_time_previous - newminute > 60))
                {
                    increment_datepicker(track_query_start_date)
                }
                else if (newminute > track_query_start_time_previous && (newminute - track_query_start_time_previous > 60))
                {
                    decrement_datepicker(track_query_start_date)
                }
                track_query_start_time_previous = newminute
                handler.postDelayed(requery_and_render, RERENDER_DELAY)
            }
        })

        track_query_end_date = rootView.findViewById(R.id.track_query_end_date)
        val end_cal = GregorianCalendar()
        end_cal.time = actual_end_time
        track_query_end_date.init(end_cal.get(Calendar.YEAR), end_cal.get(Calendar.MONTH), end_cal.get(Calendar.DAY_OF_MONTH), object: DatePicker.OnDateChangedListener {
            override fun onDateChanged(p0: DatePicker?, p1: Int, p2: Int, p3: Int)
            {
                handler.removeCallbacks(requery_and_render)
                handler.postDelayed(requery_and_render, RERENDER_DELAY)
            }
        })

        track_query_end_time = rootView.findViewById(R.id.track_query_end_time)
        track_query_end_time.setIs24HourView(true)
        track_query_end_time.hour = actual_end_time.hours
        track_query_end_time.minute = actual_end_time.minutes
        track_query_end_time_previous = (actual_end_time.hours * 60) + actual_end_time.minutes
        track_query_end_time.setOnTimeChangedListener(object : TimePicker.OnTimeChangedListener{
            override fun onTimeChanged(p0: TimePicker?, p1: Int, p2: Int)
            {
                handler.removeCallbacks(requery_and_render)
                val newminute = (p1 * 60) + p2
                Log.i("VOUSSOIR", "End time changed $newminute")
                if (newminute < track_query_end_time_previous && (track_query_end_time_previous - newminute > 60))
                {
                    increment_datepicker(track_query_end_date)
                }
                else if (newminute > track_query_end_time_previous && (newminute - track_query_end_time_previous > 60))
                {
                    decrement_datepicker(track_query_end_date)
                }
                track_query_end_time_previous = newminute
                handler.postDelayed(requery_and_render, RERENDER_DELAY)
            }
        })

        controller = mapView.controller
        mapView.addMapListener(this)
        mapView.isTilesScaledToDpi = true
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.isVerticalMapRepetitionEnabled = false
        mapView.setMultiTouchControls(true)
        mapView.zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
        controller.setCenter(GeoPoint(track.view_latitude, track.view_longitude))
        controller.setZoom(Keys.DEFAULT_ZOOM_LEVEL)

        statisticsSheet = rootView.findViewById(R.id.statistics_sheet)
        statisticsView = rootView.findViewById(R.id.statistics_view)
        distanceView = rootView.findViewById(R.id.statistics_data_distance)
        waypointsView = rootView.findViewById(R.id.statistics_data_waypoints)
        durationView = rootView.findViewById(R.id.statistics_data_duration)
        velocityView = rootView.findViewById(R.id.statistics_data_velocity)
        recordingStartView = rootView.findViewById(R.id.statistics_data_recording_start)
        recordingStopView = rootView.findViewById(R.id.statistics_data_recording_stop)
        recordingPausedLabelView = rootView.findViewById(R.id.statistics_p_recording_paused)
        recordingPausedView = rootView.findViewById(R.id.statistics_data_recording_paused)
        maxAltitudeView = rootView.findViewById(R.id.statistics_data_max_altitude)
        minAltitudeView = rootView.findViewById(R.id.statistics_data_min_altitude)
        positiveElevationView = rootView.findViewById(R.id.statistics_data_positive_elevation)
        negativeElevationView = rootView.findViewById(R.id.statistics_data_negative_elevation)
        elevationDataViews = rootView.findViewById(R.id.elevation_data)

        useImperialUnits = PreferencesHelper.loadUseImperialUnits()

        if (AppThemeHelper.isDarkModeOn(context as Activity)) {
            mapView.overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)
        }

        statisticsSheetBehavior = BottomSheetBehavior.from<View>(statisticsSheet)
        statisticsSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED

        render_track()
    }

    fun render_track()
    {
        if (special_points_overlay != null)
        {
            mapView.overlays.remove(special_points_overlay)
        }
        if (track_overlay != null)
        {
            mapView.overlays.remove(track_overlay)
        }
        if (track.trkpts.isNotEmpty())
        {
            track_overlay = createTrackOverlay(context, mapView, track.trkpts, Keys.STATE_TRACKING_STOPPED)
            special_points_overlay = create_start_end_markers(context, mapView, track.trkpts)
        }
        setupStatisticsViews()
    }

    fun get_datetime(datepicker: DatePicker, timepicker: TimePicker, seconds: Int): Date
    {
        val cal = GregorianCalendar.getInstance()
        cal.set(datepicker.year, datepicker.month, datepicker.dayOfMonth, timepicker.hour, timepicker.minute, seconds)
        Log.i("VOUSSOIR", cal.time.toString())
        return cal.time
    }

    fun decrement_datepicker(picker: DatePicker)
    {
        val cal = GregorianCalendar.getInstance()
        cal.set(picker.year, picker.month, picker.dayOfMonth)
        cal.add(Calendar.DATE, -1)
        picker.updateDate(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
    }

    fun increment_datepicker(picker: DatePicker)
    {
        val cal = GregorianCalendar.getInstance()
        cal.set(picker.year, picker.month, picker.dayOfMonth)
        cal.add(Calendar.DATE, 1)
        picker.updateDate(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
    }

    private fun setupStatisticsViews()
    {
        val stats: TrackStatistics = track.statistics()
        trackNameView.text = track.name
        distanceView.text = LengthUnitHelper.convertDistanceToString(stats.distance, useImperialUnits)
        waypointsView.text = track.trkpts.size.toString()
        durationView.text = DateTimeHelper.convertToReadableTime(context, stats.duration)
        velocityView.text = LengthUnitHelper.convertToVelocityString(stats.velocity, useImperialUnits)
        recordingStartView.text = DateTimeHelper.convertToReadableDateAndTime(track.start_time)
        recordingStopView.text = DateTimeHelper.convertToReadableDateAndTime(track.end_time)
        maxAltitudeView.text = LengthUnitHelper.convertDistanceToString(stats.max_altitude, useImperialUnits)
        minAltitudeView.text = LengthUnitHelper.convertDistanceToString(stats.min_altitude, useImperialUnits)
        positiveElevationView.text = LengthUnitHelper.convertDistanceToString(stats.total_ascent, useImperialUnits)
        negativeElevationView.text = LengthUnitHelper.convertDistanceToString(stats.total_descent, useImperialUnits)

        // inform user about possible accuracy issues with altitude measurements
        elevationDataViews.referencedIds.forEach { id ->
            (rootView.findViewById(id) as View).setOnClickListener{
                Toast.makeText(context, R.string.toast_message_elevation_info, Toast.LENGTH_LONG).show()
            }
        }
        // make track name on statistics sheet clickable
        trackNameView.setOnClickListener {
            toggleStatisticsSheetVisibility()
        }
    }

    /* Shows/hides the statistics sheet */
    private fun toggleStatisticsSheetVisibility()
    {
        when (statisticsSheetBehavior.state) {
            BottomSheetBehavior.STATE_EXPANDED -> statisticsSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
            else -> statisticsSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    /* Overrides onZoom from MapListener */
    override fun onZoom(event: ZoomEvent?): Boolean
    {
        return (event != null)
    }

    /* Overrides onScroll from MapListener */
    override fun onScroll(event: ScrollEvent?): Boolean
    {
        return (event != null)
    }

    private val requery_and_render: Runnable = object : Runnable {
        override fun run()
        {
            Log.i("VOUSSOIR", "requery_and_render")
            track.start_time = get_datetime(track_query_start_date, track_query_start_time, seconds=0)
            track.end_time = get_datetime(track_query_end_date, track_query_end_time, seconds=59)
            track.load_trkpts()
            Log.i("VOUSSOIR", "Reloaded ${track.trkpts.size} trkpts.")
            render_track()
            mapView.invalidate()
        }
    }
}

