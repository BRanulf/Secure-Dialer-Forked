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

enum class RecordingSortField {
    TIME,
    PHONE,
    DURATION
}

/**
 * Persists the user's preferred ordering for the call-recording library.
 * Defaults to newest first, matching the previous database order.
 */
object RecordingSortPreferences {

    private const val PREFS = "dialer_prefs"
    private const val KEY_FIELD = "recording_sort_field"
    private const val KEY_ASCENDING = "recording_sort_ascending"

    fun field(context: Context): RecordingSortField {
        val stored = prefs(context).getString(KEY_FIELD, RecordingSortField.TIME.name)
        return RecordingSortField.entries.firstOrNull { it.name == stored }
            ?: RecordingSortField.TIME
    }

    fun setField(context: Context, field: RecordingSortField) {
        prefs(context).edit().putString(KEY_FIELD, field.name).apply()
    }

    fun ascending(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_ASCENDING, false)
    }

    fun setAscending(context: Context, ascending: Boolean) {
        prefs(context).edit().putBoolean(KEY_ASCENDING, ascending).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(
        PREFS,
        Context.MODE_PRIVATE
    )
}
