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
import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.constraintlayout.widget.Group
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textview.MaterialTextView
import net.voussoir.trkpt.helpers.*
import org.osmdroid.api.IGeoPoint
import org.osmdroid.api.IMapController
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.*
import org.osmdroid.views.overlay.simplefastpoint.SimpleFastPointOverlay
import org.osmdroid.views.overlay.simplefastpoint.SimpleFastPointOverlayOptions
import org.osmdroid.views.overlay.simplefastpoint.SimplePointTheme
import java.lang.Math.*
import java.util.*

class TrackFragment : Fragment(), MapListener, YesNoDialog.YesNoDialogListener
{
    private lateinit var trackbook: Trackbook

    lateinit var rootView: View
    lateinit var save_track_button: ImageButton
    lateinit var delete_whole_track_button: ImageButton
    lateinit var zoom_in_button: FloatingActionButton
    lateinit var zoom_out_button: FloatingActionButton
    lateinit var trackNameView: MaterialTextView
    lateinit var selected_trkpt_info: MaterialTextView
    lateinit var track_query_start_date: DatePicker
    lateinit var track_query_start_time: TimePicker
    lateinit var track_query_end_date: DatePicker
    lateinit var track_query_end_time: TimePicker
    private lateinit var datepicker_changed_listener: DatePicker.OnDateChangedListener
    private var datetime_change_listener_enabled: Boolean = true
    lateinit var delete_selected_trkpt_button: ImageButton
    lateinit var use_trkpt_as_start_button: ImageButton
    lateinit var use_trkpt_as_end_button: ImageButton
    lateinit var isolate_trkseg_button: ImageButton
    lateinit var when_was_i_here_button: ImageButton
    lateinit var interpolate_points_button: ImageButton
    lateinit var straighten_points_button: ImageButton
    var ready_to_interpolate: Boolean = false
    var ready_to_straighten: Boolean = false
    var track_query_start_time_previous: Int = 0
    var track_query_end_time_previous: Int = 0
    var selection_mode: Int = Keys.SELECTION_MODE_STARTSTOP

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
    private var current_position_overlays = ArrayList<Overlay>()
    private var homepoints_overlays = ArrayList<Overlay>()
    // private lateinit var trkpt_infowindow: InfoWindow
    private var useImperialUnits: Boolean = false
    private val handler: Handler = Handler(Looper.getMainLooper())
    val RERENDER_DELAY: Long = 1000

    var selected_trkpt: Trkpt? = null
    lateinit var selected_trkpt_marker: Marker

    private lateinit var locationManager: LocationManager
    private lateinit var gpsLocationListener: LocationListener
    private var gpslistenerregistered = false

    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState)
        datepicker_changed_listener = object: DatePicker.OnDateChangedListener {
            override fun onDateChanged(p0: DatePicker?, p1: Int, p2: Int, p3: Int)
            {
                if (! datetime_change_listener_enabled)
                {
                    return
                }
                handler.removeCallbacks(requery_and_render)
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
            max_accuracy=PreferencesHelper.load_max_accuracy(),
        ))
        selection_mode = Keys.SELECTION_MODE_STARTSTOP

        rootView = inflater.inflate(R.layout.fragment_track, container, false)
        mapView = rootView.findViewById(R.id.map)
        save_track_button = rootView.findViewById(R.id.save_button)
        delete_whole_track_button = rootView.findViewById(R.id.delete_button)
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
            val last = track.trkpts.last()
            controller.setCenter(GeoPoint(last.latitude, last.longitude))
        }
        controller.setZoom(Keys.DEFAULT_ZOOM_LEVEL)

        val has_permission: Boolean = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (has_permission)
        {
            locationManager = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
            gpsLocationListener = createLocationListener()
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                0,
                0f,
                gpsLocationListener,
            )
            gpslistenerregistered = true
        }

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
        track_query_start_time.setOnTimeChangedListener(object : TimePicker.OnTimeChangedListener{
            override fun onTimeChanged(p0: TimePicker?, p1: Int, p2: Int)
            {
                if (! datetime_change_listener_enabled)
                {
                    return
                }
                handler.removeCallbacks(requery_and_render)
                val newminute = (p1 * 60) + p2
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
        track_query_end_time = rootView.findViewById(R.id.track_query_end_time)
        track_query_end_time.setIs24HourView(true)
        set_datetime(track_query_end_date, track_query_end_time, actual_end_time, _ending=true)
        track_query_end_time.setOnTimeChangedListener(object : TimePicker.OnTimeChangedListener{
            override fun onTimeChanged(p0: TimePicker?, p1: Int, p2: Int)
            {
                if (! datetime_change_listener_enabled)
                {
                    return
                }
                handler.removeCallbacks(requery_and_render)
                val newminute = (p1 * 60) + p2
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

        selected_trkpt_info = rootView.findViewById(R.id.selected_trkpt_info)
        delete_selected_trkpt_button = rootView.findViewById(R.id.delete_selected_trkpt_button)
        delete_selected_trkpt_button.setOnClickListener {
            Log.i("VOUSSOIR", "delete selected trkpt button.")
            if (track_points_overlay != null && track_points_overlay!!.selectedPoint != null)
            {
                val selected = (track_geopoints[track_points_overlay!!.selectedPoint] as Trkpt)
                track_geopoints.remove(selected)
                Log.i("VOUSSOIR", selected.rendered_by_polyline?.actualPoints?.size.toString())
                selected.rendered_by_polyline?.actualPoints?.remove(selected)
                Log.i("VOUSSOIR", selected.rendered_by_polyline?.actualPoints?.size.toString())
                selected.rendered_by_polyline?.setPoints(ArrayList(selected.rendered_by_polyline?.actualPoints))
                Log.i("VOUSSOIR", selected.rendered_by_polyline?.actualPoints?.size.toString())
                trackbook.database.delete_trkpt(selected)
                trackbook.database.commit()
                deselect_trkpt()
                mapView.invalidate()
            }
        }

        use_trkpt_as_start_button = rootView.findViewById(R.id.use_trkpt_as_start_button)
        use_trkpt_as_start_button.setOnClickListener {
            Log.i("VOUSSOIR", "use selected trkpt as start.")
            if (track_points_overlay != null && track_points_overlay!!.selectedPoint != null)
            {
                val selected = (track_geopoints[track_points_overlay!!.selectedPoint] as Trkpt)
                set_datetime(track_query_start_date, track_query_start_time, Date(selected.time), _ending=false)
                track.load_trkpts(trackbook.database.select_trkpt_start_end(
                    track.device_id,
                    start_time=selected.time,
                    end_time=track.trkpts.last().time,
                    max_accuracy=PreferencesHelper.load_max_accuracy(),
                ))
                selection_mode = Keys.SELECTION_MODE_STARTSTOP
                deselect_trkpt()
                render_track()
            }
        }

        use_trkpt_as_end_button = rootView.findViewById(R.id.use_trkpt_as_end_button)
        use_trkpt_as_end_button.setOnClickListener {
            Log.i("VOUSSOIR", "use selected trkpt as end.")
            if (track_points_overlay != null && track_points_overlay!!.selectedPoint != null)
            {
                val selected = (track_geopoints[track_points_overlay!!.selectedPoint] as Trkpt)
                set_datetime(track_query_end_date, track_query_end_time, Date(selected.time), _ending=true)
                track.load_trkpts(trackbook.database.select_trkpt_start_end(
                    track.device_id,
                    start_time=track.trkpts.first().time,
                    end_time=selected.time,
                    max_accuracy=PreferencesHelper.load_max_accuracy(),
                ))
                selection_mode = Keys.SELECTION_MODE_STARTSTOP
                deselect_trkpt()
                render_track()
            }
        }

        isolate_trkseg_button = rootView.findViewById(R.id.isolate_trkseg_button)
        isolate_trkseg_button.setOnClickListener {
            Log.i("VOUSSOIR", "isolate selected trkseg button.")
            if (track_points_overlay != null && track_points_overlay!!.selectedPoint != null)
            {
                val selected = (track_geopoints[track_points_overlay!!.selectedPoint] as Trkpt)
                val polyline = selected.rendered_by_polyline
                if (polyline != null)
                {
                    track.device_id = selected.device_id
                    track.load_trkpts(trackbook.database.select_trkpt_start_end(
                        selected.device_id,
                        start_time=(polyline.actualPoints.first() as Trkpt).time,
                        end_time=(polyline.actualPoints.last() as Trkpt).time,
                        max_accuracy=PreferencesHelper.load_max_accuracy(),
                    ))
                    selection_mode = Keys.SELECTION_MODE_STARTSTOP

                    track.expand_to_trkseg_bounds()

                    set_datetimes_from_track()
                    render_track()
                }
            }
        }

        when_was_i_here_button = rootView.findViewById(R.id.when_was_i_here_button)
        when_was_i_here_button.setOnClickListener {
            Log.i("VOUSSOIR", "when_was_i_here_button.")
            track.load_trkpts(trackbook.database.select_trkpt_bounding_box(
                device_id=null,
                north=mapView.boundingBox.actualNorth,
                south=mapView.boundingBox.actualSouth,
                east=mapView.boundingBox.lonEast,
                west=mapView.boundingBox.lonWest,
                max_accuracy=PreferencesHelper.load_max_accuracy(),
            ))
            selection_mode = Keys.SELECTION_MODE_SPATIAL
            set_datetimes_from_track()
            render_track()
        }

        interpolate_points_button = rootView.findViewById(R.id.interpolate_points_button)
        interpolate_points_button.setOnClickListener {
            Log.i("VOUSSOIR", "interpolate_points_button.")
            if (ready_to_straighten)
            {
                ready_to_straighten = false
                straighten_points_button.setColorFilter(null)
            }
            if (ready_to_interpolate)
            {
                interpolate_points_button.setColorFilter(null)
            }
            else
            {
                interpolate_points_button.setColorFilter(resources.getColor(R.color.fuchsia))
            }
            ready_to_interpolate = !ready_to_interpolate
        }

        straighten_points_button = rootView.findViewById(R.id.straighten_points_button)
        straighten_points_button.setOnClickListener {
            Log.i("VOUSSOIR", "straighten_points_button.")
            if (ready_to_interpolate)
            {
                ready_to_interpolate = false
                interpolate_points_button.setColorFilter(null)
            }
            if (ready_to_straighten)
            {
                straighten_points_button.setColorFilter(null)
            }
            else
            {
                straighten_points_button.setColorFilter(resources.getColor(R.color.fuchsia))
            }
            ready_to_straighten = !ready_to_straighten
        }

        save_track_button.setOnClickListener {
            val dialog = Dialog(activity as Context)
            dialog.setContentView(R.layout.dialog_rename_track)
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            dialog.setTitle("Track name")

            val input = dialog.findViewById(R.id.dialog_rename_track_input_edit_text) as EditText
            input.setText(track.name)

            val save_button = dialog.findViewById(R.id.name_track_save_button) as Button
            save_button.setOnClickListener {
                track.name = input.text.toString()
                openSaveGpxDialog()
                dialog.dismiss()
            }

            val cancel_button = dialog.findViewById(R.id.name_track_cancel_button) as Button
            cancel_button.setOnClickListener {
                dialog.cancel()
            }
            dialog.show()
        }

        delete_whole_track_button.setOnClickListener {
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

        selected_trkpt_marker = Marker(mapView, requireContext())
        selected_trkpt_marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        selected_trkpt_marker.icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_marker_location_fuchsia_24dp)!!
        selected_trkpt_marker.isDraggable = true
        selected_trkpt_marker.infoWindow = null
        selected_trkpt_marker.setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
            override fun onMarkerDrag(marker: Marker?)
            {
                if (marker == null || selected_trkpt == null)
                {
                    return
                }
                selected_trkpt?.latitude = marker.position.latitude
                selected_trkpt?.longitude = marker.position.longitude
                selected_trkpt?.rendered_by_polyline?.setPoints(ArrayList(selected_trkpt?.rendered_by_polyline?.actualPoints))
            }
            override fun onMarkerDragStart(marker: Marker?)
            {
            }
            override fun onMarkerDragEnd(marker: Marker?)
            {
                selected_trkpt?.let {
                    trackbook.database.update_trkpt(it)
                    trackbook.database.commit()
                }
            }
        })

        track_segment_overlays = ArrayDeque<Polyline>(10)
        render_track()

        return rootView
    }

    override fun onDestroyView()
    {
        super.onDestroyView()
        if (gpslistenerregistered)
        {
            locationManager.removeUpdates(gpsLocationListener)
            gpslistenerregistered = false
        }
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

    fun create_current_position_overlays(location: Location)
    {
        clear_current_position_overlays()

        val newMarker = ContextCompat.getDrawable(requireContext(), R.drawable.ic_marker_location_blue_24dp)!!
        val fillcolor = Color.argb(64, 60, 152, 219)

        val current_location_radius = Polygon()
        current_location_radius.points = Polygon.pointsAsCircle(
            GeoPoint(location.latitude, location.longitude),
            location.accuracy.toDouble()
        )
        current_location_radius.fillPaint.color = fillcolor
        current_location_radius.outlinePaint.color = Color.argb(0, 0, 0, 0)
        current_position_overlays.add(current_location_radius)

        val overlayItems: java.util.ArrayList<OverlayItem> = java.util.ArrayList<OverlayItem>()
        val overlayItem: OverlayItem = createOverlayItem(
            location.latitude,
            location.longitude,
            title="Current location",
            description="Current location",
        )
        overlayItem.setMarker(newMarker)
        overlayItems.add(overlayItem)
        current_position_overlays.add(createOverlay(requireContext(), overlayItems))

        for (ov in current_position_overlays)
        {
            mapView.overlays.add(ov)
        }
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
                homepoint.location.latitude,
                homepoint.location.longitude,
                title=homepoint.name,
                description=homepoint.name,
            )
            overlayItem.setMarker(newMarker)
            overlayItems.add(overlayItem)
            val homepoint_overlay = createOverlay(context, overlayItems)
            homepoints_overlays.add(homepoint_overlay)
        }

        for (ov in homepoints_overlays)
        {
            mapView.overlays.add(ov)
        }
    }

    /* Overrides onResume from Fragment */
    override fun onResume()
    {
        super.onResume()
    }

    private fun createLocationListener(): LocationListener
    {
        return object : LocationListener
        {
            override fun onLocationChanged(location: Location)
            {
                create_current_position_overlays(location)
            }
        }
    }

    fun select_trkpt(trkpt: Trkpt)
    {
        selected_trkpt = trkpt
        selected_trkpt_info.text = "${trkpt.time}\n${iso8601_local(trkpt.time)}\n${trkpt.latitude}\n${trkpt.longitude}\n${trkpt.accuracy}"
        selected_trkpt_marker.position = trkpt
        if (selected_trkpt_marker !in mapView.overlays)
        {
            mapView.overlays.add(selected_trkpt_marker)
        }
        interpolate_points_button.visibility = View.VISIBLE
        straighten_points_button.visibility = View.VISIBLE
        delete_selected_trkpt_button.visibility = View.VISIBLE
        use_trkpt_as_start_button.visibility = View.VISIBLE
        use_trkpt_as_end_button.visibility = View.VISIBLE
        isolate_trkseg_button.visibility = View.VISIBLE
    }

    fun select_trkpt_by_timestamp(timestamp: Long)
    {
        for (trkpt in track.trkpts)
        {
            if (trkpt.time == timestamp)
            {
                select_trkpt(trkpt)
                break
            }
        }
        if (selected_trkpt == null)
        {
            return
        }
        for ((index, trkpt) in track_geopoints.withIndex())
        {
            if (trkpt.longitude == selected_trkpt!!.longitude && trkpt.latitude == selected_trkpt!!.latitude)
            {
                track_points_overlay!!.selectedPoint = index
            }
        }
    }

    fun deselect_trkpt()
    {
        if (track_points_overlay != null)
        {
            track_points_overlay!!.selectedPoint = null
        }
        if (selected_trkpt_marker in mapView.overlays)
        {
            mapView.overlays.remove(selected_trkpt_marker)
            mapView.invalidate()
        }
        ready_to_interpolate = false
        ready_to_straighten = false
        interpolate_points_button.setColorFilter(null)
        straighten_points_button.setColorFilter(null)
        delete_selected_trkpt_button.visibility = View.GONE
        interpolate_points_button.visibility = View.GONE
        straighten_points_button.visibility = View.GONE
        use_trkpt_as_start_button.visibility = View.GONE
        use_trkpt_as_end_button.visibility = View.GONE
        isolate_trkseg_button.visibility = View.GONE
        selected_trkpt_info.text = ""
    }

    fun create_event_receiver_overlay()
    {
        val receiver: MapEventsReceiver = object: MapEventsReceiver
        {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean
            {
                Log.i("VOUSSOIR", "MapEventsReceiver.singletap")
                deselect_trkpt()
                return true
            }

            override fun longPressHelper(point: GeoPoint): Boolean
            {
                return false
            }
        }
        mapView.overlays.add(MapEventsOverlay(receiver))
    }

    fun render_track()
    {
        Log.i("VOUSSOIR", "TrackFragment.render_track")
        mapView.invalidate()
        mapView.overlays.clear()
        track_segment_overlays.clear()
        deselect_trkpt()

        setupStatisticsViews()

        create_homepoint_overlays()

        if (track.trkpts.isEmpty())
        {
            return
        }

        create_event_receiver_overlay()

        Log.i("VOUSSOIR", "MapOverlayHelper.createTrackOverlay")
        track_geopoints = mutableListOf()
        for (trkpt in track.trkpts)
        {
            track_geopoints.add(trkpt)
        }

        var pl = new_track_segment_overlay()
        var previous_time: Long = 0
        var previous_device: String = "";
        for (trkpt in track.trkpts)
        {
            val need_new_polyline = (
                (previous_time > 0 && (trkpt.time - previous_time) > Keys.STOP_OVER_THRESHOLD)
                ||
                (previous_device != "" && trkpt.device_id != previous_device)
            )
            if (need_new_polyline)
            {
                pl = new_track_segment_overlay()
            }
            pl.addPoint(trkpt)
            trkpt.rendered_by_polyline = pl
            previous_time = trkpt.time
        }

        val pointTheme = SimplePointTheme(track_geopoints, false)
        val style = Paint()
        style.style = Paint.Style.FILL
        style.color = requireContext().getColor(R.color.fuchsia)
        style.flags = Paint.ANTI_ALIAS_FLAG
        val density_scaling_factor = requireContext().resources.displayMetrics.density
        val overlayOptions: SimpleFastPointOverlayOptions = SimpleFastPointOverlayOptions.getDefaultStyle()
            .setAlgorithm(SimpleFastPointOverlayOptions.RenderingAlgorithm.MEDIUM_OPTIMIZATION)
            .setSymbol(SimpleFastPointOverlayOptions.Shape.CIRCLE)
            .setPointStyle(style)
            .setRadius(((Keys.POLYLINE_THICKNESS + 1 ) / 2) * density_scaling_factor)
            .setIsClickable(true)
            .setCellSize(12)
        track_points_overlay = SimpleFastPointOverlay(pointTheme, overlayOptions)
        mapView.overlays.add(track_points_overlay)
        track_points_overlay!!.isEnabled = mapView.zoomLevel >= 16

        track_points_overlay!!.setOnClickListener(object : SimpleFastPointOverlay.OnClickListener {
            override fun onClick(points: SimpleFastPointOverlay.PointAdapter?, point: Int?)
            {
                if (points == null || point == null)
                {
                    return
                }
                if (mapView.zoomLevelDouble < 16)
                {
                    deselect_trkpt()
                    return
                }
                var trkpt = (points[point]) as Trkpt
                Log.i("VOUSSOIR", "Clicked ${trkpt.device_id} ${trkpt.time}")
                if (ready_to_interpolate)
                {
                    val mid_location = Location("interpolated")
                    mid_location.latitude = (selected_trkpt!!.latitude + trkpt.latitude) / 2
                    mid_location.longitude = (selected_trkpt!!.longitude + trkpt.longitude) / 2
                    mid_location.altitude = (selected_trkpt!!.altitude + trkpt.altitude) / 2
                    mid_location.time = (selected_trkpt!!.time + trkpt.time) / 2
                    mid_location.accuracy = 0f
                    val mid_trkpt = Trkpt(trkpt.device_id, mid_location)
                    deselect_trkpt()
                    trackbook.database.insert_trkpt(mid_trkpt)
                    trackbook.database.commit()
                    requery_and_render.run()
                    select_trkpt_by_timestamp(mid_trkpt.time)
                    return
                }
                else if (ready_to_straighten && selected_trkpt != null)
                {
                    straighten_points(selected_trkpt!!, trkpt)
                    deselect_trkpt()
                    handler.post(requery_and_render)
                    return
                }
                select_trkpt(trkpt)
                return
            }
        })

        for (pl in track_segment_overlays)
        {
            create_start_end_markers(requireContext(), mapView, pl.actualPoints.first() as Trkpt, pl.actualPoints.last() as Trkpt)
        }

        for (ov in current_position_overlays)
        {
            mapView.overlays.add(ov)
        }
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

    fun straighten_points(a: Trkpt, b: Trkpt)
    {
        val al = a.toLocation()
        val bl = b.toLocation()
        val subtrack = Track(track.database, track.device_id)
        subtrack.load_trkpts(trackbook.database.select_trkpt_start_end(
            track.device_id,
            start_time=min(a.time, b.time),
            end_time=max(a.time, b.time),
            max_accuracy=PreferencesHelper.load_max_accuracy(),
        ))
        val lat_span = b.latitude - a.latitude
        val lon_span = b.longitude - a.longitude
        val ele_span = b.altitude - a.altitude
        for (trkpt in subtrack.trkpts)
        {
            val new_location = Location("test")
            val tl = trkpt.toLocation()
            val dist_a = tl.distanceTo(al)
            val dist_b = tl.distanceTo(bl)
            val proportion = dist_a / (dist_a + dist_b)
            new_location.latitude = a.latitude + (lat_span * proportion)
            new_location.longitude = a.longitude + (lon_span * proportion)
            if (trkpt.toLocation().distanceTo(new_location) > 30.0)
            {
                continue
            }
            trkpt.latitude = new_location.latitude
            trkpt.longitude = new_location.longitude
            trkpt.altitude = a.altitude + (ele_span * proportion)
            trackbook.database.update_trkpt(trkpt)
        }
        trackbook.database.commit()
    }

    fun set_datetime(datepicker: DatePicker, timepicker: TimePicker, setdate: Date, _ending: Boolean)
    {
        datetime_change_listener_enabled = false
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
        datetime_change_listener_enabled = true
    }

    fun set_datetimes_from_track()
    {
        if (track.trkpts.size == 0)
        {
            return
        }
        set_datetime(track_query_start_date, track_query_start_time, Date(track.trkpts.first().time), _ending=false)
        set_datetime(track_query_end_date, track_query_end_time, Date(track.trkpts.last().time), _ending=true)
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
        val stats = TrackStatistics(track.trkpts)
        trackNameView.text = track.name
        distanceView.text = LengthUnitHelper.convertDistanceToString(stats.distance, useImperialUnits)
        waypointsView.text = track.trkpts.size.toString()
        durationView.text = DateTimeHelper.convertToReadableTime(requireContext(), stats.duration)
        recordingPausedView.text = DateTimeHelper.convertToReadableTime(requireContext(), stats.pause_duration)
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
                max_accuracy=PreferencesHelper.load_max_accuracy(),
            ))
            selection_mode = Keys.SELECTION_MODE_STARTSTOP
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
            if (selection_mode == Keys.SELECTION_MODE_STARTSTOP)
            {
                trackbook.database.delete_trkpt_start_end(
                    track.device_id,
                    track.trkpts.first().time,
                    track.trkpts.last().time,
                )
                trackbook.database.commit()
                handler.removeCallbacks(requery_and_render)
                handler.postDelayed(requery_and_render, RERENDER_DELAY)
            }
            else if (selection_mode == Keys.SELECTION_MODE_SPATIAL)
            {
                // If the user clicked the "when was I here" button to select an area of points, and
                // then presses the Track delete button, we don't want to delete all points between
                // the earliest start and latest stop -- we just want to delete the points that are
                // shown on screen.
                for (trkpt in track.trkpts)
                {
                    trackbook.database.delete_trkpt(trkpt)
                }
                trackbook.database.commit()
                handler.removeCallbacks(requery_and_render)
                handler.postDelayed(requery_and_render, RERENDER_DELAY)
            }
        }
    }

    /* Opens up a file picker to select the save location */
    private fun openSaveGpxDialog()
    {
        if (track.trkpts.isEmpty())
        {
            return
        }
        val export_name: String = track.name + Keys.GPX_FILE_EXTENSION
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
