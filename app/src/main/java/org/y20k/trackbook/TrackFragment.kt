/*
 * TrackFragment.kt
 * Implements the TrackFragment fragment
 * A TrackFragment displays a previously recorded track
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

import YesNoDialog
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.constraintlayout.widget.Group
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
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
import org.y20k.trackbook.helpers.AppThemeHelper
import org.y20k.trackbook.helpers.DateTimeHelper
import org.y20k.trackbook.helpers.LengthUnitHelper
import org.y20k.trackbook.helpers.PreferencesHelper
import org.y20k.trackbook.helpers.createTrackOverlay
import org.y20k.trackbook.helpers.create_start_end_markers
import org.y20k.trackbook.helpers.iso8601_format
import java.text.SimpleDateFormat
import java.util.*

class TrackFragment : Fragment(), MapListener, YesNoDialog.YesNoDialogListener
{
    lateinit var rootView: View
    lateinit var save_track_button: ImageButton
    lateinit var deleteButton: ImageButton
    lateinit var trackNameView: MaterialTextView
    lateinit var track_query_start_date: DatePicker
    lateinit var track_query_start_time: TimePicker
    lateinit var track_query_end_date: DatePicker
    lateinit var track_query_end_time: TimePicker
    var track_query_start_time_previous: Int = 0
    var track_query_end_time_previous: Int = 0
    private lateinit var mapView: MapView
    private lateinit var controller: IMapController
    private lateinit var statisticsSheetBehavior: BottomSheetBehavior<View>
    private lateinit var statisticsSheet: NestedScrollView
    private lateinit var statisticsView: View
    private lateinit var distanceView: MaterialTextView
    private lateinit var waypointsView: MaterialTextView
    private lateinit var durationView: MaterialTextView
    private lateinit var velocityView: MaterialTextView
    private lateinit var recordingStartView: MaterialTextView
    private lateinit var recordingStopView: MaterialTextView
    private lateinit var recordingPausedView: MaterialTextView
    private lateinit var recordingPausedLabelView: MaterialTextView
    private lateinit var maxAltitudeView: MaterialTextView
    private lateinit var minAltitudeView: MaterialTextView
    private lateinit var positiveElevationView: MaterialTextView
    private lateinit var negativeElevationView: MaterialTextView
    private lateinit var elevationDataViews: Group
    private lateinit var track: Track
    private var useImperialUnits: Boolean = false
    private val handler: Handler = Handler(Looper.getMainLooper())
    private var special_points_overlay: ItemizedIconOverlay<OverlayItem>? = null
    private var track_overlay: SimpleFastPointOverlay? = null
    val RERENDER_DELAY: Long = 1000

    /* Overrides onCreateView from Fragment */
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View
    {
        val database: Database = (requireActivity().applicationContext as Trackbook).database
        track = Track(
            database=database,
            name=this.requireArguments().getString(Keys.ARG_TRACK_TITLE, ""),
            device_id= this.requireArguments().getString(Keys.ARG_TRACK_DEVICE_ID, ""),
            start_time= iso8601_format.parse(this.requireArguments().getString(Keys.ARG_TRACK_START_TIME)!!),
            end_time=iso8601_format.parse(this.requireArguments().getString(Keys.ARG_TRACK_STOP_TIME)!!),
        )
        track.load_trkpts()
        rootView = inflater.inflate(R.layout.fragment_track, container, false)
        mapView = rootView.findViewById(R.id.map)
        save_track_button = rootView.findViewById(R.id.save_button)
        deleteButton = rootView.findViewById(R.id.delete_button)
        trackNameView = rootView.findViewById(R.id.statistics_track_name_headline)

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

        if (AppThemeHelper.isDarkModeOn(context as Activity))
        {
            mapView.overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)
        }

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

        save_track_button.setOnClickListener {
            openSaveGpxDialog()
        }

        deleteButton.setOnClickListener {
            val dialogMessage = "${getString(R.string.dialog_yes_no_message_delete_recording)}\n\n${track.trkpts.size} trackpoints"
            YesNoDialog(this@TrackFragment as YesNoDialog.YesNoDialogListener).show(
                context = activity as Context,
                type = Keys.DIALOG_DELETE_TRACK,
                messageString = dialogMessage,
                yesButton = R.string.dialog_yes_no_positive_button_delete_recording
            )
        }

        statisticsSheetBehavior = BottomSheetBehavior.from<View>(statisticsSheet)
        statisticsSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED

        render_track()

        return rootView
    }

    /* Overrides onResume from Fragment */
    override fun onResume()
    {
        super.onResume()
    }

    fun render_track()
    {
        Log.i("VOUSSOIR", "TrackFragment.render_track")
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
            track_overlay = createTrackOverlay(requireContext(), mapView, track.trkpts, Keys.STATE_TRACKING_STOPPED)
            special_points_overlay = create_start_end_markers(requireContext(), mapView, track.trkpts)
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
        durationView.text = DateTimeHelper.convertToReadableTime(requireContext(), stats.duration)
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

        trackNameView.setOnClickListener {
            toggleStatisticsSheetVisibility()
        }
    }

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
            Log.i("VOUSSOIR", "TrackFragment.requery_and_render")
            track.start_time = get_datetime(track_query_start_date, track_query_start_time, seconds=0)
            track.end_time = get_datetime(track_query_end_date, track_query_end_time, seconds=59)
            track.load_trkpts()
            Log.i("VOUSSOIR", "TrackFragment.requery_and_render: Reloaded ${track.trkpts.size} trkpts.")
            render_track()
            mapView.invalidate()
        }
    }

    /* Register the ActivityResultLauncher for saving GPX */
    private val requestSaveGpxLauncher = registerForActivityResult(StartActivityForResult(), this::requestSaveGpxResult)

    private fun requestSaveGpxResult(result: ActivityResult)
    {
        if (result.resultCode != Activity.RESULT_OK || result.data == null)
        {
            return
        }

        val targetUri: Uri? = result.data?.data
        if (targetUri == null)
        {
            return
        }

        val outputsuccess: Uri? = track.export_gpx(activity as Context, targetUri)
        if (outputsuccess == null)
        {
            Toast.makeText(activity as Context, "failed to export for some reason", Toast.LENGTH_LONG).show()
        }
    }

    /* Overrides onYesNoDialog from YesNoDialogListener */
    override fun onYesNoDialog(type: Int, dialogResult: Boolean, payload: Int, payloadString: String)
    {
        when (type)
        {
            Keys.DIALOG_DELETE_TRACK -> {
                when (dialogResult)
                {
                    // user tapped remove track
                    true -> {
                        track.delete()
                        handler.removeCallbacks(requery_and_render)
                        handler.postDelayed(requery_and_render, RERENDER_DELAY)
                        // switch to TracklistFragment and remove track there
                        // val bundle: Bundle = bundleOf(Keys.ARG_TRACK_ID to layout.track.id)
                        // findNavController().navigate(R.id.tracklist_fragment, bundle)
                    }
                    else ->
                    {
                        ;
                    }
                }
            }
        }
    }

    /* Opens up a file picker to select the save location */
    private fun openSaveGpxDialog()
    {
        val export_name: String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(track.start_time) + " " + track.device_id + Keys.GPX_FILE_EXTENSION
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = Keys.MIME_TYPE_GPX
            putExtra(Intent.EXTRA_TITLE, export_name)
        }
        // file gets saved in the ActivityResult
        try
        {
            requestSaveGpxLauncher.launch(intent)
        }
        catch (e: Exception)
        {
            Log.e("VOUSSOIR", "Unable to save GPX.")
            Toast.makeText(activity as Context, R.string.toast_message_install_file_helper, Toast.LENGTH_LONG).show()
        }
    }
}
