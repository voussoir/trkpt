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

/*
 * Modified by voussoir for trkpt, forked from Trackbook.
 */

package net.voussoir.trkpt

import YesNoDialog
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Paint
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
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textview.MaterialTextView
import org.osmdroid.api.IGeoPoint
import org.osmdroid.api.IMapController
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.views.overlay.simplefastpoint.SimpleFastPointOverlay
import org.osmdroid.views.overlay.simplefastpoint.SimpleFastPointOverlayOptions
import org.osmdroid.views.overlay.simplefastpoint.SimplePointTheme
import net.voussoir.trkpt.helpers.AppThemeHelper
import net.voussoir.trkpt.helpers.DateTimeHelper
import net.voussoir.trkpt.helpers.LengthUnitHelper
import net.voussoir.trkpt.helpers.PreferencesHelper
import net.voussoir.trkpt.helpers.UiHelper
import net.voussoir.trkpt.helpers.create_start_end_markers
import net.voussoir.trkpt.helpers.iso8601_local
import java.text.SimpleDateFormat
import java.util.*

class TrackFragment : Fragment(), MapListener, YesNoDialog.YesNoDialogListener
{
    private lateinit var trackbook: Trackbook

    lateinit var rootView: View
    lateinit var save_track_button: ImageButton
    lateinit var deleteButton: ImageButton
    lateinit var zoom_in_button: FloatingActionButton
    lateinit var zoom_out_button: FloatingActionButton
    lateinit var trackNameView: MaterialTextView
    lateinit var selected_trkpt_info: MaterialTextView
    lateinit var track_query_start_date: DatePicker
    lateinit var track_query_start_time: TimePicker
    lateinit var track_query_end_date: DatePicker
    lateinit var track_query_end_time: TimePicker
    private lateinit var datepicker_changed_listener: DatePicker.OnDateChangedListener
    private lateinit var timepicker_changed_listener: TimePicker.OnTimeChangedListener
    lateinit var delete_selected_trkpt_button: ImageButton
    lateinit var when_was_i_here_button: ImageButton
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
    private lateinit var track_segment_overlays: ArrayDeque<Polyline>
    private var track_geopoints: MutableList<IGeoPoint> = mutableListOf()
    private var track_points_overlay: SimpleFastPointOverlay? = null
    // private lateinit var trkpt_infowindow: InfoWindow
    private var useImperialUnits: Boolean = false
    private val handler: Handler = Handler(Looper.getMainLooper())
    val RERENDER_DELAY: Long = 1000

    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState)
        datepicker_changed_listener = object: DatePicker.OnDateChangedListener {
            override fun onDateChanged(p0: DatePicker?, p1: Int, p2: Int, p3: Int)
            {
                handler.removeCallbacks(requery_and_render)
                handler.postDelayed(requery_and_render, RERENDER_DELAY)
            }
        }
        timepicker_changed_listener = object : TimePicker.OnTimeChangedListener{
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
        }
    }

    /* Overrides onCreateView from Fragment */
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View
    {
        this.trackbook = (requireContext().applicationContext as Trackbook)
        val requested_start_time = this.requireArguments().getString(Keys.ARG_TRACK_START_TIME)!!.toLong()
        val requested_end_time = this.requireArguments().getString(Keys.ARG_TRACK_STOP_TIME)!!.toLong()
        track = Track(
            database=this.trackbook.database,
            device_id= this.requireArguments().getString(Keys.ARG_TRACK_DEVICE_ID, ""),
            name=this.requireArguments().getString(Keys.ARG_TRACK_TITLE, ""),
        )
        track.load_trkpts(this.trackbook.database.select_trkpt_start_end(
            device_id= this.requireArguments().getString(Keys.ARG_TRACK_DEVICE_ID, ""),
            start_time=requested_start_time,
            end_time=requested_end_time,
        ))
        rootView = inflater.inflate(R.layout.fragment_track, container, false)
        mapView = rootView.findViewById(R.id.map)
        save_track_button = rootView.findViewById(R.id.save_button)
        deleteButton = rootView.findViewById(R.id.delete_button)
        zoom_in_button = rootView.findViewById(R.id.zoom_in_button)
        zoom_out_button = rootView.findViewById(R.id.zoom_out_button)
        trackNameView = rootView.findViewById(R.id.statistics_track_name_headline)

        controller = mapView.controller
        mapView.addMapListener(this)
        mapView.isTilesScaledToDpi = true
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.isVerticalMapRepetitionEnabled = false
        mapView.setMultiTouchControls(true)
        mapView.zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
        if (track.trkpts.size > 0)
        {
            val first = track.trkpts.first()
            controller.setCenter(GeoPoint(first.latitude, first.longitude))
        }
        controller.setZoom(Keys.DEFAULT_ZOOM_LEVEL)

        // trkpt_infowindow = MarkerInfoWindow(R.layout.trkpt_infowindow, mapView)

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

        val actual_start_time: Date = if (track.trkpts.isEmpty()) Date(requested_start_time) else Date(track.trkpts.first().time)
        val actual_end_time: Date = if (track.trkpts.isEmpty()) Date(requested_end_time) else Date(track.trkpts.last().time)

        track_query_start_date = rootView.findViewById(R.id.track_query_start_date)
        track_query_start_time = rootView.findViewById(R.id.track_query_start_time)
        track_query_start_time.setIs24HourView(true)
        set_datetime(track_query_start_date, track_query_start_time, actual_start_time, _ending=false)
        track_query_start_time.setOnTimeChangedListener(timepicker_changed_listener)

        track_query_end_date = rootView.findViewById(R.id.track_query_end_date)
        track_query_end_time = rootView.findViewById(R.id.track_query_end_time)
        track_query_end_time.setIs24HourView(true)
        set_datetime(track_query_end_date, track_query_end_time, actual_end_time, _ending=true)
        track_query_end_time.setOnTimeChangedListener(timepicker_changed_listener)

        selected_trkpt_info = rootView.findViewById(R.id.selected_trkpt_info)
        delete_selected_trkpt_button = rootView.findViewById(R.id.delete_selected_trkpt_button)
        delete_selected_trkpt_button.setOnClickListener {
            Log.i("VOUSSOIR", "delete selected trkpt button.")
            if (track_points_overlay != null)
            {
                val selected = (track_geopoints[track_points_overlay!!.selectedPoint] as Trkpt)
                track_geopoints.remove(selected)
                track_points_overlay!!.selectedPoint = null
                Log.i("VOUSSOIR", selected.rendered_by_polyline?.actualPoints?.size.toString())
                selected.rendered_by_polyline?.actualPoints?.remove(selected)
                Log.i("VOUSSOIR", selected.rendered_by_polyline?.actualPoints?.size.toString())
                selected.rendered_by_polyline?.setPoints(ArrayList(selected.rendered_by_polyline?.actualPoints))
                Log.i("VOUSSOIR", selected.rendered_by_polyline?.actualPoints?.size.toString())
                trackbook.database.delete_trkpt(selected.device_id, selected.time)
                delete_selected_trkpt_button.visibility = View.GONE
                selected_trkpt_info.text = ""
                mapView.invalidate()
            }
        }
        when_was_i_here_button = rootView.findViewById(R.id.when_was_i_here_button)
        when_was_i_here_button.setOnClickListener {
            Log.i("VOUSSOIR", "when_was_i_here_button.")
            track.load_trkpts(trackbook.database.select_trkpt_bounding_box(
                device_id=track.device_id,
                north=mapView.boundingBox.actualNorth,
                south=mapView.boundingBox.actualSouth,
                east=mapView.boundingBox.lonEast,
                west=mapView.boundingBox.lonWest,
            ))
            render_track()
        }

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

        zoom_in_button.setOnClickListener {
            mapView.controller.zoomTo(mapView.zoomLevelDouble + 0.5, 0)
        }
        zoom_out_button.setOnClickListener {
            mapView.controller.zoomTo(mapView.zoomLevelDouble - 0.5, 0)
        }

        statisticsSheetBehavior = BottomSheetBehavior.from<View>(statisticsSheet)
        statisticsSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED

        track_segment_overlays = ArrayDeque<Polyline>(10)
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
        mapView.invalidate()
        mapView.overlays.clear()
        track_segment_overlays.clear()
        delete_selected_trkpt_button.visibility = View.GONE

        setupStatisticsViews()

        if (track.trkpts.isEmpty())
        {
            return
        }
        Log.i("VOUSSOIR", "MapOverlayHelper.createTrackOverlay")
        track_geopoints = mutableListOf()
        for (trkpt in track.trkpts)
        {
            track_geopoints.add(trkpt)
        }

        var pl = new_track_segment_overlay()
        var previous_time: Long = 0
        for (trkpt in track.trkpts)
        {
            if (previous_time > 0 && (trkpt.time - previous_time) > Keys.STOP_OVER_THRESHOLD)
            {
                pl = new_track_segment_overlay()
            }
            pl.addPoint(trkpt)
            trkpt.rendered_by_polyline = pl
            previous_time = trkpt.time
        }

        for (pl in track_segment_overlays)
        {
            create_start_end_markers(requireContext(), mapView, pl.actualPoints.first() as Trkpt, pl.actualPoints.last() as Trkpt)
        }

        val pointTheme = SimplePointTheme(track_geopoints, false)
        val style = Paint()
        style.style = Paint.Style.FILL
        style.color = requireContext().getColor(R.color.fuchsia)
        style.flags = Paint.ANTI_ALIAS_FLAG
        val overlayOptions: SimpleFastPointOverlayOptions = SimpleFastPointOverlayOptions.getDefaultStyle()
            .setAlgorithm(SimpleFastPointOverlayOptions.RenderingAlgorithm.MEDIUM_OPTIMIZATION)
            .setSymbol(SimpleFastPointOverlayOptions.Shape.CIRCLE)
            .setPointStyle(style)
            .setRadius(((Keys.POLYLINE_THICKNESS + 1 ) / 2) * UiHelper.getDensityScalingFactor(requireContext()))
            .setIsClickable(true)
            .setCellSize(12)
        track_points_overlay = SimpleFastPointOverlay(pointTheme, overlayOptions)
        mapView.overlays.add(track_points_overlay)

        track_points_overlay!!.setOnClickListener(object : SimpleFastPointOverlay.OnClickListener {
            override fun onClick(points: SimpleFastPointOverlay.PointAdapter?, point: Int?)
            {
                if (points == null || point == null || point == 0)
                {
                    return
                }
                val trkpt = (points[point]) as Trkpt
                Log.i("VOUSSOIR", "Clicked ${trkpt.device_id} ${trkpt.time}")
                selected_trkpt_info.text = "${trkpt.time}\n${iso8601_local(trkpt.time)}\n${trkpt.latitude}\n${trkpt.longitude}"
                delete_selected_trkpt_button.visibility = View.VISIBLE
                return
            }
        })
    }

    fun new_track_segment_overlay(): Polyline
    {
        var pl = Polyline(mapView)
        pl.outlinePaint.strokeWidth = Keys.POLYLINE_THICKNESS
        pl.outlinePaint.color = requireContext().getColor(R.color.fuchsia)
        pl.infoWindow = null
        track_segment_overlays.add(pl)
        mapView.overlays.add(pl)
        return pl
    }

    fun set_datetime(datepicker: DatePicker, timepicker: TimePicker, setdate: Date, _ending: Boolean)
    {
        val start_cal = GregorianCalendar()
        start_cal.time = setdate
        datepicker.init(start_cal.get(Calendar.YEAR), start_cal.get(Calendar.MONTH), start_cal.get(Calendar.DAY_OF_MONTH), datepicker_changed_listener)

        timepicker.hour = setdate.hours
        timepicker.minute = setdate.minutes

        if (_ending)
        {
            track_query_end_time_previous = (setdate.hours * 60) + setdate.minutes
        }
        else
        {
            track_query_start_time_previous = (setdate.hours * 60) + setdate.minutes
        }
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
        val stats: TrackStatistics = TrackStatistics(track.trkpts)
        trackNameView.text = track.name
        distanceView.text = LengthUnitHelper.convertDistanceToString(stats.distance, useImperialUnits)
        waypointsView.text = track.trkpts.size.toString()
        durationView.text = DateTimeHelper.convertToReadableTime(requireContext(), stats.duration)
        velocityView.text = LengthUnitHelper.convertToVelocityString(stats.velocity, useImperialUnits)
        if (track.trkpts.isNotEmpty())
        {
            recordingStartView.text = DateTimeHelper.convertToReadableDateAndTime(Date(track.trkpts.first().time))
            recordingStopView.text = DateTimeHelper.convertToReadableDateAndTime(Date(track.trkpts.last().time))
        }
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
        if (event == null)
        {
            return false
        }
        if (track_points_overlay == null)
        {
            return false
        }
        track_points_overlay!!.isEnabled = event.zoomLevel >= 16
        return true
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
            track.load_trkpts(trackbook.database.select_trkpt_start_end(
                track.device_id,
                start_time=get_datetime(track_query_start_date, track_query_start_time, seconds=0).time,
                end_time=get_datetime(track_query_end_date, track_query_end_time, seconds=59).time,
            ))
            Log.i("VOUSSOIR", "TrackFragment.requery_and_render: Reloaded ${track.trkpts.size} trkpts.")
            render_track()
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
        if (type == Keys.DIALOG_DELETE_TRACK && dialogResult && track.trkpts.isNotEmpty())
        {
            trackbook.database.delete_trkpt_start_end(track.device_id, track.trkpts.first().time, track.trkpts.last().time)
            handler.removeCallbacks(requery_and_render)
            handler.postDelayed(requery_and_render, RERENDER_DELAY)
        }
    }

    /* Opens up a file picker to select the save location */
    private fun openSaveGpxDialog()
    {
        if (track.trkpts.isEmpty())
        {
            return
        }
        val export_name: String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(track.trkpts.first().time) + " " + track.device_id + Keys.GPX_FILE_EXTENSION
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
