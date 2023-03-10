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
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.preference.*
import org.y20k.trackbook.helpers.AppThemeHelper
import org.y20k.trackbook.helpers.LengthUnitHelper
import org.y20k.trackbook.helpers.LogHelper
import org.y20k.trackbook.helpers.PreferencesHelper
import org.y20k.trackbook.helpers.random_device_id

/*
 * SettingsFragment class
 */
class SettingsFragment : PreferenceFragmentCompat(), YesNoDialog.YesNoDialogListener {

    /* Define log tag */
    private val TAG: String = LogHelper.makeLogTag(SettingsFragment::class.java)

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

        val preferenceCategoryGeneral: PreferenceCategory = PreferenceCategory(activity as Context)
        preferenceCategoryGeneral.title = getString(R.string.pref_general_title)
        screen.addPreference(preferenceCategoryGeneral)

        // set up "Restrict to GPS" preference
        val preferenceGpsOnly: SwitchPreferenceCompat = SwitchPreferenceCompat(activity as Context)
        preferenceGpsOnly.isSingleLineTitle = false
        preferenceGpsOnly.title = getString(R.string.pref_gps_only_title)
        preferenceGpsOnly.setIcon(R.drawable.ic_gps_24dp)
        preferenceGpsOnly.key = Keys.PREF_GPS_ONLY
        preferenceGpsOnly.summaryOn = getString(R.string.pref_gps_only_summary_gps_only)
        preferenceGpsOnly.summaryOff = getString(R.string.pref_gps_only_summary_gps_and_network)
        preferenceGpsOnly.setDefaultValue(false)
        preferenceCategoryGeneral.contains(preferenceGpsOnly)
        screen.addPreference(preferenceGpsOnly)

        // set up "Use Imperial Measurements" preference
        val preferenceImperialMeasurementUnits: SwitchPreferenceCompat = SwitchPreferenceCompat(activity as Context)
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
        val preferenceThemeSelection: ListPreference = ListPreference(activity as Context)
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

        // set up "Recording Accuracy" preference
        val DEFAULT_OMIT_RESTS = true
        val preferenceOmitRests: SwitchPreferenceCompat = SwitchPreferenceCompat(activity as Context)
        preferenceOmitRests.isSingleLineTitle = false
        preferenceOmitRests.title = getString(R.string.pref_omit_rests_title)
        preferenceOmitRests.setIcon(R.drawable.ic_timeline_24dp)
        preferenceOmitRests.key = Keys.PREF_OMIT_RESTS
        preferenceOmitRests.summaryOn = getString(R.string.pref_omit_rests_on)
        preferenceOmitRests.summaryOff = getString(R.string.pref_omit_rests_off)
        preferenceOmitRests.setDefaultValue(DEFAULT_OMIT_RESTS)
        preferenceCategoryGeneral.contains(preferenceOmitRests)
        screen.addPreference(preferenceOmitRests)

        val preferenceDeviceID: EditTextPreference = EditTextPreference(activity as Context)
        preferenceDeviceID.title = getString(R.string.pref_device_id)
        preferenceDeviceID.setIcon(R.drawable.ic_smartphone_24dp)
        preferenceDeviceID.key = Keys.PREF_DEVICE_ID
        preferenceDeviceID.summary = getString(R.string.pref_device_id_summary) + "\n" + PreferencesHelper.load_device_id()
        preferenceDeviceID.setDefaultValue(random_device_id())
        preferenceCategoryGeneral.contains(preferenceDeviceID)
        screen.addPreference(preferenceDeviceID)

        val preferenceCategoryAbout: PreferenceCategory = PreferenceCategory(context)
        preferenceCategoryAbout.title = getString(R.string.pref_about_title)
        screen.addPreference(preferenceCategoryAbout)

        // set up "App Version" preference
        val preferenceAppVersion: Preference = Preference(context)
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

