/*
 * AppThemeHelper.kt
 * Implements the AppThemeHelper object
 * A AppThemeHelper can set the different app themes: Light Mode, Dark Mode, Follow System
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

package net.voussoir.trkpt.helpers

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import net.voussoir.trkpt.Keys
import net.voussoir.trkpt.R

/*
 * AppThemeHelper object
 */
object AppThemeHelper
{
    /* Sets app theme */
    fun setTheme(nightModeState: String) {
        when (nightModeState) {
            Keys.STATE_THEME_DARK_MODE -> {
                if (AppCompatDelegate.getDefaultNightMode() != AppCompatDelegate.MODE_NIGHT_YES) {
                    // turn on dark mode
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                    Log.i("VOUSSOIR", "Dark Mode activated.")
                }
            }
            Keys.STATE_THEME_LIGHT_MODE -> {
                if (AppCompatDelegate.getDefaultNightMode() != AppCompatDelegate.MODE_NIGHT_NO) {
                    // turn on light mode
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                    Log.i("VOUSSOIR", "Theme: Light Mode activated.")
                }
            }
            Keys.STATE_THEME_FOLLOW_SYSTEM -> {
                if (AppCompatDelegate.getDefaultNightMode() != AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) {
                    // turn on mode "follow system"
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                    Log.i("VOUSSOIR", "Theme: Follow System Mode activated.")
                }
            }
            else -> {
                // turn on mode "follow system"
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                Log.i("VOUSSOIR", "Theme: Follow System Mode activated.")
            }
        }
    }

    /* Return weather Night Mode is on, or not  */
    fun isDarkModeOn(context: Context): Boolean {
        val nightMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightMode == Configuration.UI_MODE_NIGHT_YES
    }

    /* Returns a readable String for currently selected App Theme */
    fun getCurrentTheme(context: Context): String {
        return when (PreferencesHelper.loadThemeSelection()) {
            Keys.STATE_THEME_LIGHT_MODE -> context.getString(R.string.pref_theme_selection_mode_light)
            Keys.STATE_THEME_DARK_MODE -> context.getString(R.string.pref_theme_selection_mode_dark)
            else -> context.getString(R.string.pref_theme_selection_mode_device_default)
        }
    }
}