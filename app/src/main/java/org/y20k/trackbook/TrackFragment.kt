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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.fragment.app.Fragment
import org.y20k.trackbook.helpers.LogHelper
import org.y20k.trackbook.helpers.iso8601_format
import org.y20k.trackbook.ui.TrackFragmentLayoutHolder
import java.text.SimpleDateFormat
import java.util.*

class TrackFragment : Fragment(), YesNoDialog.YesNoDialogListener
{
    /* Define log tag */
    private val TAG: String = LogHelper.makeLogTag(TrackFragment::class.java)

    /* Main class variables */
    private lateinit var layout: TrackFragmentLayoutHolder

    /* Overrides onCreateView from Fragment */
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        // initialize layout
        val database: Database = (requireActivity().applicationContext as Trackbook).database
        val track = Track(
            database=database,
            name=this.requireArguments().getString(Keys.ARG_TRACK_TITLE, ""),
            device_id= this.requireArguments().getString(Keys.ARG_TRACK_DEVICE_ID, ""),
            start_time= iso8601_format.parse(this.requireArguments().getString(Keys.ARG_TRACK_START_TIME)!!),
            stop_time=iso8601_format.parse(this.requireArguments().getString(Keys.ARG_TRACK_STOP_TIME)!!),
        )
        track.load_trkpts()
        layout = TrackFragmentLayoutHolder(activity as Context, inflater, container, track)

        // set up share button
        layout.save_track_button.setOnClickListener {
            openSaveGpxDialog()
        }
        // set up delete button
        layout.deleteButton.setOnClickListener {
            val dialogMessage = "${getString(R.string.dialog_yes_no_message_delete_recording)}\n\n${layout.trackNameView.text}"
            YesNoDialog(this@TrackFragment as YesNoDialog.YesNoDialogListener).show(
                context = activity as Context,
                type = Keys.DIALOG_DELETE_TRACK,
                messageString = dialogMessage,
                yesButton = R.string.dialog_yes_no_positive_button_delete_recording
            )
        }

        return layout.rootView
    }

    /* Overrides onResume from Fragment */
    override fun onResume()
    {
        super.onResume()
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

        val outputsuccess: Uri? = layout.track.export_gpx(activity as Context, targetUri)
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
        val export_name: String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(layout.track.start_time) + " " + layout.track.device_id + Keys.GPX_FILE_EXTENSION
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
            LogHelper.e(TAG, "Unable to save GPX.")
            Toast.makeText(activity as Context, R.string.toast_message_install_file_helper, Toast.LENGTH_LONG).show()
        }
    }
}
