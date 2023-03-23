/*
 * SettingsFragment.kt
 * Implements the SettingsFragment fragment
 * A SettingsFragment displays the user accessible settings of the app
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
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import androidx.preference.contains
import get_path_from_uri
import org.y20k.trackbook.helpers.AppThemeHelper
import org.y20k.trackbook.helpers.LengthUnitHelper
import org.y20k.trackbook.helpers.PreferencesHelper
import org.y20k.trackbook.helpers.random_device_id

class SettingsFragment : PreferenceFragmentCompat(), YesNoDialog.YesNoDialogListener
{
    /* Overrides onViewCreated from PreferenceFragmentCompat */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // set the background color
        view.setBackgroundColor(resources.getColor(R.color.app_window_background, null))
    }

    /* Overrides onCreatePreferences from PreferenceFragmentCompat */
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {

        val context = preferenceManager.context
        val screen = preferenceManager.createPreferenceScreen(context)

        val preferenceCategoryGeneral = PreferenceCategory(activity as Context)
        preferenceCategoryGeneral.title = getString(R.string.pref_general_title)
        screen.addPreference(preferenceCategoryGeneral)

        val prefLocationGPS = SwitchPreferenceCompat(activity as Context)
        prefLocationGPS.isSingleLineTitle = false
        prefLocationGPS.title = getString(R.string.pref_location_gps_title)
        prefLocationGPS.setIcon(R.drawable.ic_gps_24dp)
        prefLocationGPS.key = Keys.PREF_LOCATION_GPS
        prefLocationGPS.summaryOn = getString(R.string.pref_location_gps_summary_on)
        prefLocationGPS.summaryOff = getString(R.string.pref_location_gps_summary_off)
        prefLocationGPS.setDefaultValue(true)
        preferenceCategoryGeneral.contains(prefLocationGPS)
        screen.addPreference(prefLocationGPS)

        val prefLocationNetwork = SwitchPreferenceCompat(activity as Context)
        prefLocationNetwork.isSingleLineTitle = false
        prefLocationNetwork.title = getString(R.string.pref_location_network_title)
        prefLocationNetwork.setIcon(R.drawable.ic_gps_24dp)
        prefLocationNetwork.key = Keys.PREF_LOCATION_NETWORK
        prefLocationNetwork.summaryOn = getString(R.string.pref_location_network_summary_on)
        prefLocationNetwork.summaryOff = getString(R.string.pref_location_network_summary_off)
        prefLocationNetwork.setDefaultValue(false)
        preferenceCategoryGeneral.contains(prefLocationNetwork)
        screen.addPreference(prefLocationNetwork)

        // set up "Use Imperial Measurements" preference
        val preferenceImperialMeasurementUnits = SwitchPreferenceCompat(activity as Context)
        preferenceImperialMeasurementUnits.isSingleLineTitle = false
        preferenceImperialMeasurementUnits.title = getString(R.string.pref_imperial_measurement_units_title)
        preferenceImperialMeasurementUnits.setIcon(R.drawable.ic_square_foot_24px)
        preferenceImperialMeasurementUnits.key = Keys.PREF_USE_IMPERIAL_UNITS
        preferenceImperialMeasurementUnits.summaryOn = getString(R.string.pref_imperial_measurement_units_summary_imperial)
        preferenceImperialMeasurementUnits.summaryOff = getString(R.string.pref_imperial_measurement_units_summary_metric)
        preferenceImperialMeasurementUnits.setDefaultValue(LengthUnitHelper.useImperialUnits())
        preferenceCategoryGeneral.contains(preferenceImperialMeasurementUnits)
        screen.addPreference(preferenceImperialMeasurementUnits)

        // set up "App Theme" preference
        val preferenceThemeSelection = ListPreference(activity as Context)
        preferenceThemeSelection.title = getString(R.string.pref_theme_selection_title)
        preferenceThemeSelection.setIcon(R.drawable.ic_smartphone_24dp)
        preferenceThemeSelection.key = Keys.PREF_THEME_SELECTION
        preferenceThemeSelection.summary = "${getString(R.string.pref_theme_selection_summary)} ${AppThemeHelper.getCurrentTheme(activity as Context)}"
        preferenceThemeSelection.entries = arrayOf(getString(R.string.pref_theme_selection_mode_device_default), getString(R.string.pref_theme_selection_mode_light), getString(R.string.pref_theme_selection_mode_dark))
        preferenceThemeSelection.entryValues = arrayOf(Keys.STATE_THEME_FOLLOW_SYSTEM, Keys.STATE_THEME_LIGHT_MODE, Keys.STATE_THEME_DARK_MODE)
        preferenceThemeSelection.setOnPreferenceChangeListener { preference, newValue ->
            if (preference is ListPreference) {
                val index: Int = preference.entryValues.indexOf(newValue)
                preferenceThemeSelection.summary = "${getString(R.string.pref_theme_selection_summary)} ${preference.entries[index]}"
                return@setOnPreferenceChangeListener true
            } else {
                return@setOnPreferenceChangeListener false
            }
        }
        screen.addPreference(preferenceThemeSelection)

        val preferenceOmitRests = SwitchPreferenceCompat(activity as Context)
        preferenceOmitRests.isSingleLineTitle = false
        preferenceOmitRests.title = getString(R.string.pref_omit_rests_title)
        preferenceOmitRests.setIcon(R.drawable.ic_timeline_24dp)
        preferenceOmitRests.key = Keys.PREF_OMIT_RESTS
        preferenceOmitRests.summaryOn = getString(R.string.pref_omit_rests_on)
        preferenceOmitRests.summaryOff = getString(R.string.pref_omit_rests_off)
        preferenceOmitRests.setDefaultValue(Keys.DEFAULT_OMIT_RESTS)
        preferenceCategoryGeneral.contains(preferenceOmitRests)
        screen.addPreference(preferenceOmitRests)

        val preferenceDeviceID = EditTextPreference(activity as Context)
        preferenceDeviceID.title = getString(R.string.pref_device_id)
        preferenceDeviceID.setIcon(R.drawable.ic_smartphone_24dp)
        preferenceDeviceID.key = Keys.PREF_DEVICE_ID
        preferenceDeviceID.summary = getString(R.string.pref_device_id_summary) + "\n" + PreferencesHelper.load_device_id()
        preferenceDeviceID.setDefaultValue(random_device_id())
        preferenceDeviceID.setOnPreferenceChangeListener { preference, newValue ->
            preferenceDeviceID.summary = getString(R.string.pref_device_id_summary) + "\n" + newValue
            return@setOnPreferenceChangeListener true
        }
        preferenceCategoryGeneral.contains(preferenceDeviceID)
        screen.addPreference(preferenceDeviceID)

        val preferenceDatabaseFolder = Preference(context)
        var resultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            Log.i("VOUSSOIR", "I'm not dead yet.")
            if (result.resultCode != Activity.RESULT_OK)
            {
                return@registerForActivityResult
            }
            if (result.data == null)
            {
                return@registerForActivityResult
            }
            if (result.data!!.data == null)
            {
                return@registerForActivityResult
            }
            val uri: Uri = result.data!!.data!!
            val docUri = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
            val path: String = get_path_from_uri(context, docUri) ?: ""
            Log.i("VOUSSOIR", "We got " + path)
            PreferencesHelper.save_database_folder(path)
            preferenceDatabaseFolder.summary = (getString(R.string.pref_database_folder_summary) + "\n" + path).trim()
        }
        preferenceDatabaseFolder.title = getString(R.string.pref_database_folder)
        preferenceDatabaseFolder.setIcon(R.drawable.ic_save_to_storage_24dp)
        preferenceDatabaseFolder.key = Keys.PREF_DATABASE_DIRECTORY
        preferenceDatabaseFolder.summary = (getString(R.string.pref_database_folder_summary) + "\n" + PreferencesHelper.load_database_folder()).trim()
        preferenceDatabaseFolder.setOnPreferenceClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                resultLauncher.launch(intent)
            }

            return@setOnPreferenceClickListener true
        }
        preferenceDatabaseFolder.setOnPreferenceChangeListener { preference, newValue ->
            preferenceDatabaseFolder.summary = getString(R.string.pref_database_folder_summary) + "\n" + newValue
            return@setOnPreferenceChangeListener true
        }
        preferenceCategoryGeneral.contains(preferenceDatabaseFolder)
        screen.addPreference(preferenceDatabaseFolder)

        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.LOLLIPOP_MR1)
        {
            if (!context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName))
            {
                val battery_optimization_button = Preference(context)
                battery_optimization_button.title = "Disable battery optimization"
                battery_optimization_button.summary = "If your device kills the app, you can give this a try."
                battery_optimization_button.setOnPreferenceClickListener {
                    val i: Intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:${context.packageName}"))
                    context.startActivity(i)
                    return@setOnPreferenceClickListener true
                }
                preferenceCategoryGeneral.contains(battery_optimization_button)
                screen.addPreference(battery_optimization_button)
            }
        }

        val preferenceCategoryAbout = PreferenceCategory(context)
        preferenceCategoryAbout.title = getString(R.string.pref_about_title)
        screen.addPreference(preferenceCategoryAbout)

        // set up "App Version" preference
        val preferenceAppVersion = Preference(context)
        preferenceAppVersion.title = getString(R.string.pref_app_version_title)
        preferenceAppVersion.setIcon(R.drawable.ic_info_24dp)
        preferenceAppVersion.summary = getString(R.string.pref_app_version_summary)
        preferenceAppVersion.setOnPreferenceClickListener {
            // copy to clipboard
            val clip: ClipData = ClipData.newPlainText("simple text", preferenceAppVersion.summary)
            val cm: ClipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(clip)
            Toast.makeText(activity as Context, R.string.toast_message_copied_to_clipboard, Toast.LENGTH_LONG).show()
            return@setOnPreferenceClickListener true
        }
        preferenceCategoryAbout.contains(preferenceAppVersion)
        screen.addPreference(preferenceAppVersion)

        // setup preference screen
        preferenceScreen = screen
    }

    /* Overrides onYesNoDialog from YesNoDialogListener */
    override fun onYesNoDialog(type: Int, dialogResult: Boolean, payload: Int, payloadString: String) {
        when (type) {
            Keys.DIALOG_DELETE_NON_STARRED -> {
            }
            else -> {
                super.onYesNoDialog(type, dialogResult, payload, payloadString)
            }
        }
    }
}

