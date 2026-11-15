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

import com.example.model.CallRecording
import java.util.Locale

/**
 * Pure ordering logic for the call-recording library. Kept separate from Compose so
 * the time/phone/duration comparators can be unit-tested.
 */
object RecordingSorting {

    fun sort(
        recordings: List<CallRecording>,
        field: RecordingSortField,
        ascending: Boolean,
        timeKeys: Map<Int, Long>
    ): List<CallRecording> {
        val comparator = when (field) {
            RecordingSortField.TIME -> compareBy<CallRecording> {
                timeKeys[it.id] ?: it.id.toLong()
            }.thenByDescending { it.id }

            RecordingSortField.PHONE -> compareBy<CallRecording> {
                it.number.lowercase(Locale.ROOT)
            }.thenBy { it.name.lowercase(Locale.ROOT) }

            RecordingSortField.DURATION -> compareBy<CallRecording> { it.duration }
                .thenByDescending { timeKeys[it.id] ?: it.id.toLong() }
        }
        return if (ascending) {
            recordings.sortedWith(comparator)
        } else {
            recordings.sortedWith(comparator.reversed())
        }
    }
}
