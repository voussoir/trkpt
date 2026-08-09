/*
 * TracklistFragment.kt
 * Implements the TracklistFragment fragment
 * A TracklistFragment displays a list recorded tracks
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

package net.voussoir.trkpt.fragments

import android.content.Context
import android.database.Cursor
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*
import net.voussoir.trkpt.*
import net.voussoir.trkpt.Database
import net.voussoir.trkpt.helpers.PreferencesHelper
import net.voussoir.trkpt.Keys
import net.voussoir.trkpt.R
import net.voussoir.trkpt.Track
import YesNoDialog

class TracklistFragment : Fragment(), TracklistAdapter.TracklistAdapterListener, YesNoDialog.YesNoDialogListener
{
    /* Main class variables */
    private lateinit var tracklistAdapter: TracklistAdapter
    private lateinit var trackElementList: RecyclerView
    private lateinit var tracklistOnboarding: ConstraintLayout

    /* Overrides onCreate from Fragment */
    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState)
        // create tracklist adapter
        tracklistAdapter = TracklistAdapter(this, (requireActivity().applicationContext as Trackbook).database)
    }

    /* Overrides onCreateView from Fragment */
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View?
    {
        // find views
        val rootView = inflater.inflate(R.layout.fragment_tracklist, container, false)
        trackElementList = rootView.findViewById(R.id.track_element_list)
        tracklistOnboarding = rootView.findViewById(R.id.track_list_onboarding)

        // set up recycler view
        trackElementList.layoutManager = CustomLinearLayoutManager(activity as Context)
        trackElementList.itemAnimator = DefaultItemAnimator()
        trackElementList.adapter = tracklistAdapter

        // toggle onboarding layout
        toggleOnboardingLayout()

        return rootView
    }

    /* Overrides onTrackElementTapped from TracklistElementAdapterListener */
    override fun onTrackElementTapped(track: Track) {
        val bundle: Bundle = bundleOf(
            Keys.ARG_TRACK_TITLE to track.name,
            Keys.ARG_TRACK_DEVICE_ID to track.device_id,
            Keys.ARG_TRACK_START_TIME to track._start_time.toString(),
            Keys.ARG_TRACK_STOP_TIME to track._end_time.toString(),
        )
        findNavController().navigate(R.id.fragment_track, bundle)
    }

    // toggle onboarding layout
    private fun toggleOnboardingLayout()
    {
        when (tracklistAdapter.isEmpty()) {
            true -> {
                // show onboarding layout
                tracklistOnboarding.visibility = View.VISIBLE
                trackElementList.visibility = View.GONE
            }
            false -> {
                // hide onboarding layout
                tracklistOnboarding.visibility = View.GONE
                trackElementList.visibility = View.VISIBLE
            }
        }
    }

    /*
     * Inner class: custom LinearLayoutManager that overrides onLayoutCompleted
     */
    inner class CustomLinearLayoutManager(context: Context): LinearLayoutManager(context, VERTICAL, false)
    {
        override fun supportsPredictiveItemAnimations(): Boolean
        {
            return true
        }

        override fun onLayoutCompleted(state: RecyclerView.State?)
        {
            super.onLayoutCompleted(state)
        }

    }
    /*
     * End of inner class
     */
}

class TracklistAdapter(val fragment: Fragment, val database: Database) : RecyclerView.Adapter<RecyclerView.ViewHolder>()
{
    private lateinit var tracklistListener: TracklistAdapterListener
    val tracks: ArrayList<Track> = ArrayList<Track>()

    /* Listener Interface */
    interface TracklistAdapterListener
    {
        fun onTrackElementTapped(track: Track) {  }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView)
    {
        tracklistListener = fragment as TracklistAdapterListener
        tracks.clear()
        if (! database.ready)
        {
            return
        }
        val max_accuracy = PreferencesHelper.load_max_accuracy()
        val cursor: Cursor = database.connection.rawQuery(
            "SELECT distinct(date(time/1000, 'unixepoch', 'localtime')) as thedate, device_id FROM trkpt WHERE accuracy <= ? ORDER BY thedate DESC",
            arrayOf(max_accuracy.toString())
        )
        try
        {
            val df: DateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS")
            while (cursor.moveToNext())
            {
                val trackdate = cursor.getString(0)
                val device_id = cursor.getString(1)
                val start_time: Long = df.parse(trackdate + "T00:00:00.000").time
                val stop_time: Long = df.parse(trackdate + "T23:59:59.999").time
                Log.i("VOUSSOIR", "TracklistAdapter prep track ${trackdate}")
                val track = Track(database=database, device_id=device_id, _start_time=start_time, _end_time=stop_time)
                track.name = "$trackdate $device_id"
                tracks.add(track)
            }
        }
        finally
        {
            cursor.close()
        }
    }

    /* Overrides onCreateViewHolder from RecyclerView.Adapter */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder
    {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.element_track, parent, false)
        return ElementTrackViewHolder(v)
    }

    /* Overrides getItemViewType */
    override fun getItemViewType(position: Int): Int
    {
        return Keys.VIEW_TYPE_TRACK
    }

    /* Overrides getItemCount from RecyclerView.Adapter */
    override fun getItemCount(): Int
    {
        return tracks.size
    }

    /* Overrides onBindViewHolder from RecyclerView.Adapter */
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int)
    {
        val positionInTracklist: Int = position
        val elementTrackViewHolder: ElementTrackViewHolder = holder as ElementTrackViewHolder
        elementTrackViewHolder.trackNameView.text = getTrackName(positionInTracklist)
        elementTrackViewHolder.trackDataView.text = createTrackDataString(positionInTracklist)
        elementTrackViewHolder.trackElement.setOnClickListener {
            tracklistListener.onTrackElementTapped(tracks[positionInTracklist])
        }
    }

    /* Get track name for given position */
    fun getTrackName(positionInRecyclerView: Int): String
    {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(tracks[positionInRecyclerView]._start_time)
    }

    fun isEmpty(): Boolean
    {
        return tracks.size == 0
    }

    /* Creates the track data string */
    private fun createTrackDataString(position: Int): String
    {
        val track: Track = tracks[position]
        return "device: " + track.device_id
    }

    inner class ElementTrackViewHolder (elementTrackLayout: View): RecyclerView.ViewHolder(elementTrackLayout) {
        val trackElement: ConstraintLayout = elementTrackLayout.findViewById(R.id.track_element)
        val trackNameView: TextView = elementTrackLayout.findViewById(R.id.track_name)
        val trackDataView: TextView = elementTrackLayout.findViewById(R.id.track_data)
    }
}

