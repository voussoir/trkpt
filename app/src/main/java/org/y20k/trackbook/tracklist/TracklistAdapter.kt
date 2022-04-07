/*
 * TracklistAdapter.kt
 * Implements the TracklistAdapter class
 * A TracklistAdapter is a custom adapter for a RecyclerView
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


package org.y20k.trackbook.tracklist


import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import org.y20k.trackbook.Keys
import org.y20k.trackbook.R
import org.y20k.trackbook.core.Track
import org.y20k.trackbook.core.Tracklist
import org.y20k.trackbook.core.load_tracklist
import org.y20k.trackbook.helpers.*


/*
 * TracklistAdapter class
 */
class TracklistAdapter(private val fragment: Fragment) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /* Define log tag */
    private val TAG: String = LogHelper.makeLogTag(TracklistAdapter::class.java)


    /* Main class variables */
    private val context: Context = fragment.activity as Context
    private lateinit var tracklistListener: TracklistAdapterListener
    private var useImperial: Boolean = PreferencesHelper.loadUseImperialUnits()
    private var tracklist: Tracklist = Tracklist()


    /* Listener Interface */
    interface TracklistAdapterListener {
        fun onTrackElementTapped(track: Track) {  }
    }


    override fun onAttachedToRecyclerView(recyclerView: RecyclerView)
    {
        tracklistListener = fragment as TracklistAdapterListener
        tracklist = load_tracklist(context)
    }


    /* Overrides onCreateViewHolder from RecyclerView.Adapter */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder
    {
        when (viewType) {
            Keys.VIEW_TYPE_STATISTICS -> {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.element_statistics, parent, false)
                return ElementStatisticsViewHolder(v)
            }
            else -> {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.element_track, parent, false)
                return ElementTrackViewHolder(v)
            }
        }
    }


    /* Overrides getItemViewType */
    override fun getItemViewType(position: Int): Int {
        if (position == 0) {
            return Keys.VIEW_TYPE_STATISTICS
        } else {
            return Keys.VIEW_TYPE_TRACK
        }
    }


    /* Overrides getItemCount from RecyclerView.Adapter */
    override fun getItemCount(): Int {
        // +1 because of the total statistics element
        return tracklist.tracks.size + 1
    }


    /* Overrides onBindViewHolder from RecyclerView.Adapter */
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int)
    {
        when (holder)
        {
            // CASE STATISTICS ELEMENT
            is ElementStatisticsViewHolder -> {
                val elementStatisticsViewHolder: ElementStatisticsViewHolder = holder
                elementStatisticsViewHolder.totalDistanceView.text = LengthUnitHelper.convertDistanceToString(tracklist.get_total_distance(), useImperial)
            }

            // CASE TRACK ELEMENT
            is ElementTrackViewHolder -> {
                val positionInTracklist: Int = position - 1 // Element 0 is the statistics element.
                val elementTrackViewHolder: ElementTrackViewHolder = holder
                elementTrackViewHolder.trackNameView.text = tracklist.tracks[positionInTracklist].name
                elementTrackViewHolder.trackDataView.text = createTrackDataString(positionInTracklist)
                when (tracklist.tracks[positionInTracklist].starred) {
                    true -> elementTrackViewHolder.starButton.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_star_filled_24dp))
                    false -> elementTrackViewHolder.starButton.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_star_outline_24dp))
                }
                elementTrackViewHolder.trackElement.setOnClickListener {
                    tracklistListener.onTrackElementTapped(tracklist.tracks[positionInTracklist])
                }
                elementTrackViewHolder.starButton.setOnClickListener {
                    toggleStarred(it, positionInTracklist)
                }
            }
        }
    }


    /* Get track name for given position */
    fun getTrackName(positionInRecyclerView: Int): String
    {
        // Minus 1 because first position is always the statistics element
        return tracklist.tracks[positionInRecyclerView - 1].name
    }

    fun delete_track_at_position(context: Context, ui_index: Int)
    {
        val track_index = ui_index - 1 // position 0 is the statistics element
        val track = tracklist.tracks[track_index]
        track.delete(context)
        tracklist.tracks.remove(track)
        notifyItemChanged(0)
        notifyItemRemoved(ui_index)
        notifyItemRangeChanged(ui_index, tracklist.tracks.size)
    }

    suspend fun delete_track_at_position_suspended(context: Context, position: Int)
    {
        return suspendCoroutine { cont ->
            cont.resume(delete_track_at_position(context, position))
        }
    }

    fun delete_track_by_id(context: Context, trackId: Long)
    {
        val index: Int = tracklist.tracks.indexOfFirst {it.id == trackId}
        if (index == -1) {
            return
        }
        delete_track_at_position(context, index + 1)
    }

    /* Returns if the adapter is empty */
    fun isEmpty(): Boolean {
        return tracklist.tracks.size == 0
    }

    /* Toggles the starred state of tracklist element - and saves tracklist */
    private fun toggleStarred(view: View, position: Int) {
        val starButton: ImageButton = view as ImageButton
        if (tracklist.tracks[position].starred)
        {
            starButton.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_star_outline_24dp))
            tracklist.tracks[position].starred = false
        }
        else
        {
            starButton.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_star_filled_24dp))
            tracklist.tracks[position].starred = true
        }
        tracklist.tracks[position].save_json(context)
    }

    /* Creates the track data string */
    private fun createTrackDataString(position: Int): String {
        val track: Track = tracklist.tracks[position]
        val track_duration_string = DateTimeHelper.convertToReadableTime(context, track.duration)
        val trackDataString: String
        when (track.name == track.dateString) {
            // CASE: no individual name set - exclude date
            true -> trackDataString = "${LengthUnitHelper.convertDistanceToString(track.distance, useImperial)} • ${track_duration_string}"
            // CASE: no individual name set - include date
            false -> trackDataString = "${track.dateString} • ${LengthUnitHelper.convertDistanceToString(track.distance, useImperial)} • ${track_duration_string}"
        }
        return trackDataString
    }


    /*
     * Inner class: DiffUtil.Callback that determines changes in data - improves list performance
     */
    private inner class DiffCallback(val oldList: Tracklist, val newList: Tracklist): DiffUtil.Callback() {

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val oldItem = oldList.tracks[oldItemPosition]
            val newItem = newList.tracks[newItemPosition]
            return oldItem.id == newItem.id
        }

        override fun getOldListSize(): Int {
            return oldList.tracks.size
        }

        override fun getNewListSize(): Int {
            return newList.tracks.size
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val oldItem = oldList.tracks[oldItemPosition]
            val newItem = newList.tracks[newItemPosition]
            return (oldItem.id == newItem.id) && (oldItem.distance == newItem.distance)
        }
    }
    /*
     * End of inner class
     */


    /*
     * Inner class: ViewHolder for a track element
     */
    inner class ElementTrackViewHolder (elementTrackLayout: View): RecyclerView.ViewHolder(elementTrackLayout) {
        val trackElement: ConstraintLayout = elementTrackLayout.findViewById(R.id.track_element)
        val trackNameView: TextView = elementTrackLayout.findViewById(R.id.track_name)
        val trackDataView: TextView = elementTrackLayout.findViewById(R.id.track_data)
        val starButton: ImageButton = elementTrackLayout.findViewById(R.id.star_button)

    }
    /*
     * End of inner class
     */


    /*
     * Inner class: ViewHolder for a statistics element
     */
    inner class ElementStatisticsViewHolder (elementStatisticsLayout: View): RecyclerView.ViewHolder(elementStatisticsLayout) {
        val totalDistanceView: TextView = elementStatisticsLayout.findViewById(R.id.total_distance_data)
    }
    /*
     * End of inner class
     */

}
