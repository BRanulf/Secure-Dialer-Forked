/*
 * Copyright (C) 2026 MovStore
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.example.util

import android.content.Context

/**
 * Preferences for the in-call soundboard and the hold-to-bleep censor.
 *
 * The master flag is mirrored by [com.example.ui.viewmodel.DialerViewModel] so the
 * in-call controls can react immediately; the monitoring flags are read directly at
 * playback time.
 */
object SoundboardPreferences {

    private const val PREFS = "dialer_prefs"
    private const val KEY_ENABLED = "soundboard_enabled"
    private const val KEY_MONITOR = "soundboard_monitor_enabled"
    private const val KEY_BLEEP_MONITOR = "bleep_monitor_enabled"
    private const val KEY_SEEDED = "soundboard_seeded"

    fun isEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_ENABLED, true)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun isMonitorEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_MONITOR, true)
    }

    fun setMonitorEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MONITOR, enabled).apply()
    }

    fun isBleepMonitorEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_BLEEP_MONITOR, true)
    }

    fun setBleepMonitorEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BLEEP_MONITOR, enabled).apply()
    }

    /**
     * The packaged example effects are copied into user storage exactly once. After
     * that they are ordinary files: deleting them is permanent, and an app update
     * does not silently bring them back.
     */
    fun isSeeded(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SEEDED, false)
    }

    fun markSeeded(context: Context) {
        prefs(context).edit().putBoolean(KEY_SEEDED, true).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(
        PREFS,
        Context.MODE_PRIVATE
    )
}
