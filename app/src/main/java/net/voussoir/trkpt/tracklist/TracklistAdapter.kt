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

package net.voussoir.trkpt.tracklist

import android.content.Context
import android.database.Cursor
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import net.voussoir.trkpt.Keys
import net.voussoir.trkpt.R
import net.voussoir.trkpt.Database
import net.voussoir.trkpt.Track
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*

class TracklistAdapter(val fragment: Fragment, val database: net.voussoir.trkpt.Database) : RecyclerView.Adapter<RecyclerView.ViewHolder>()
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
        val cursor: Cursor = database.connection.rawQuery(
            "SELECT distinct(date(time/1000, 'unixepoch', 'localtime')) as thedate, device_id FROM trkpt ORDER BY thedate DESC",
            arrayOf()
        )
        try
        {
            val df: DateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS")
            while (cursor.moveToNext())
            {
                val trackdate = cursor.getString(0)
                val device_id = cursor.getString(1)
                val start_time: Date? = df.parse(trackdate + "T00:00:00.000")
                val stop_time: Date? = df.parse(trackdate + "T23:59:59.999")
                Log.i("VOUSSOIR", "TracklistAdapter prep track ${trackdate}")
                if (start_time != null && stop_time != null)
                {
                    val track = Track(database=database, device_id=device_id, start_time=start_time, end_time=stop_time)
                    track.name = "$trackdate $device_id"
                    tracks.add(track)
                }
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
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(tracks[positionInRecyclerView].start_time)
    }

    fun delete_track_at_position(context: Context, index: Int)
    {
        // val track = tracklist.tracks[index]
        // track.delete()
        // tracklist.tracks.remove(track)
        // notifyItemRemoved(index)
        // notifyItemRangeChanged(index, this.itemCount);
    }

    fun delete_track_by_id(context: Context, trackId: Long)
    {
        // val index: Int = tracklist.tracks.indexOfFirst {it.id == trackId}
        // if (index == -1) {
        //     return
        // }
        // tracklist.tracks[index].delete()
        // tracklist.tracks.removeAt(index)
        // notifyItemRemoved(index)
        // notifyItemRangeChanged(index, this.itemCount);
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
        // val track_duration_string = DateTimeHelper.convertToReadableTime(context, track.duration)
        // val trackDataString: String
        // if (track.name == track.dateString)
        // {
        //     trackDataString = "${LengthUnitHelper.convertDistanceToString(track.distance, useImperial)} • ${track_duration_string}"
        // }
        // else
        // {
        //     trackDataString = "${track.dateString} • ${LengthUnitHelper.convertDistanceToString(track.distance, useImperial)} • ${track_duration_string}"
        // }
        // return trackDataString
    }

    inner class ElementTrackViewHolder (elementTrackLayout: View): RecyclerView.ViewHolder(elementTrackLayout) {
        val trackElement: ConstraintLayout = elementTrackLayout.findViewById(R.id.track_element)
        val trackNameView: TextView = elementTrackLayout.findViewById(R.id.track_name)
        val trackDataView: TextView = elementTrackLayout.findViewById(R.id.track_data)
    }
}
