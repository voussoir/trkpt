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

package net.voussoir.trkpt

import YesNoDialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.Main
import net.voussoir.trkpt.tracklist.TracklistAdapter

/*
 * TracklistFragment class
 */
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
            // handle delete request from TrackFragment - after layout calculations are complete
            val deleteTrackId: Long = arguments?.getLong(Keys.ARG_TRACK_ID, -1L) ?: -1L
            arguments?.putLong(Keys.ARG_TRACK_ID, -1L)
            if (deleteTrackId == -1L)
            {
                return
            }
            CoroutineScope(Main). launch {
                tracklistAdapter.delete_track_by_id(this@TracklistFragment.activity as Context, deleteTrackId)
                toggleOnboardingLayout()
            }
        }

    }
    /*
     * End of inner class
     */

}
