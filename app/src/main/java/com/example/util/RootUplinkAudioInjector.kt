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
import android.media.AudioManager
import java.util.Locale

/**
 * Best-effort injection of media audio into the uplink (the audio the remote party hears)
 * by toggling the vendor mixer's "in-call music" / uplink loopback routes.
 *
 * There is no public Android API for injecting audio into a carrier call. Root call
 * recorders use the same vendor mixer controls for in-call beeps. Control names vary:
 * - Qualcomm: "Incall_Music Audio Mixer MultiMedia1"
 * - MediaTek: controls containing "Voice" + "Music"
 * - Some Samsung/Exynos devices expose similar "Incall music" paths
 *
 * If no such control can be discovered (common on recent devices where tinymix was
 * removed), [enable] returns false and the answering machine records the caller without
 * being able to play the greeting to them.
 */
object RootUplinkAudioInjector {

    private data class MixerControl(
        val card: Int,
        val name: String,
        val originalValue: String,
        val enableValue: String
    )

    private val lock = Any()
    private var enabledControls: List<MixerControl> = emptyList()
    private var mixerCard: Int? = null

    @Volatile
    private var active = false

    @Volatile
    private var lastError: String? = null

    fun isActive(): Boolean = active

    fun error(): String? = lastError

    /**
     * Enables the uplink route. The caller must pair this with [disable] in a finally block.
     * Requires root; the route is reverted before returning from [disable].
     */
    fun enable(context: Context): Boolean = synchronized(lock) {
        if (active) return true
        if (!RootShell.isGranted()) {
            lastError = "Root access is not granted"
            return false
        }

        val controls = discoverControls()
        if (controls.isEmpty()) {
            lastError = "No in-call music / uplink loopback mixer control was found"
            return false
        }

        var anyEnabled = false
        for (control in controls) {
            if (setControl(control, control.enableValue)) {
                anyEnabled = true
            }
        }

        if (anyEnabled) {
            enabledControls = controls
            active = true
            lastError = null
            // MediaPlayer playback is governed by the music stream. Save the current
            // index only to restore it after the greeting.
            try {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                if (audioManager != null) {
                    savedMusicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val target = (max * 0.75f).toInt().coerceIn(0, max)
                    if (savedMusicVolume < target) {
                        try {
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                        } catch (_: SecurityException) {}
                    }
                }
            } catch (_: Exception) {}
        } else {
            lastError = "The mixer rejected all uplink route changes"
        }
        return anyEnabled
    }

    private var savedMusicVolume: Int = -1

    fun disable(context: Context) = synchronized(lock) {
        if (!active && enabledControls.isEmpty()) {
            restoreVolume(context)
            return
        }
        for (control in enabledControls) {
            setControl(control, control.originalValue)
        }
        enabledControls = emptyList()
        active = false
        restoreVolume(context)
    }

    private fun restoreVolume(context: Context) {
        if (savedMusicVolume < 0) return
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVolume, 0)
        } catch (_: Exception) {
        } finally {
            savedMusicVolume = -1
        }
    }

    private fun discoverControls(): List<MixerControl> {
        // Most devices expose card 0. Try a small range for multi-card DSP setups.
        for (card in 0..2) {
            val listing = listMixer(card)
            if (listing.isBlank()) continue
            val controls = parseControls(listing, card)
            if (controls.isNotEmpty()) {
                mixerCard = card
                return controls
            }
        }
        return emptyList()
    }

    private fun listMixer(card: Int): String {
        val commands = if (card == 0) {
            listOf("tinymix", "/system/bin/tinymix", "/vendor/bin/tinymix", "/system_ext/bin/tinymix")
        } else {
            listOf(
                "tinymix -D $card",
                "/system/bin/tinymix -D $card",
                "/vendor/bin/tinymix -D $card",
                "/system_ext/bin/tinymix -D $card"
            )
        }
        for (command in commands) {
            val result = RootShell.runSu(command, timeoutMs = 3_000L)
            if (result.succeeded && result.output.length > 20) {
                return result.output
            }
        }
        return ""
    }

    private fun parseControls(listing: String, card: Int): List<MixerControl> {
        val controls = mutableListOf<MixerControl>()
        for (rawLine in listing.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            val lower = line.lowercase(Locale.ROOT)
            if (!lower.contains("music")) continue
            if (!(lower.contains("incall") || lower.contains("in_call") ||
                        lower.contains("voice") || lower.contains("call"))) {
                continue
            }
            if (lower.contains("record") || lower.contains("capture")) continue

            val tokens = when {
                line.contains('\t') -> line.split('\t').map { it.trim() }.filter { it.isNotEmpty() }
                else -> line.split(Regex("\\s{2,}")).map { it.trim() }.filter { it.isNotEmpty() }
            }
            if (tokens.size < 2) continue

            val name = extractControlName(tokens) ?: continue
            val currentValue = tokens.last()
            val lowerName = name.lowercase(Locale.ROOT)
            if (!lowerName.contains("music")) continue
            if (!(lowerName.contains("incall") || lowerName.contains("in_call") ||
                        lowerName.contains("voice") || lowerName.contains("call"))) {
                continue
            }

            val enableValue = when (currentValue.lowercase(Locale.ROOT)) {
                "off", "false", "disable", "disabled", "0" ->
                    if (currentValue.equals("Off", true) || currentValue.equals("false", true)) {
                        if (currentValue.equals("Off", true)) "On" else "true"
                    } else "1"
                "on", "true", "enable", "enabled", "1" -> currentValue
                else -> "1"
            }
            controls.add(MixerControl(card, name, currentValue, enableValue))
        }
        return controls.distinctBy { it.name }
    }

    private fun extractControlName(tokens: List<String>): String? {
        // tinymix: index | name | type | value  → name is tokens[1]
        // Some builds omit the index → name is tokens[0].
        // Names may contain spaces, but they stay inside a single tab/2-space column.
        val nameIndex = if (tokens.first().matches(Regex("\\d+"))) 1 else 0
        if (nameIndex >= tokens.size - 1) return null
        val name = tokens[nameIndex].trim().trim('"')
        return name.ifBlank { null }
    }

    private fun setControl(control: MixerControl, value: String): Boolean {
        val card = control.card
        val cardArg = if (card == 0) "" else " -D $card"
        val quotedName = RootShell.shQuote(control.name)
        val quotedValue = RootShell.shQuote(value)
        val commands = listOf(
            "tinymix$cardArg $quotedName $quotedValue",
            "/system/bin/tinymix$cardArg $quotedName $quotedValue",
            "/vendor/bin/tinymix$cardArg $quotedName $quotedValue"
        )
        for (command in commands) {
            val result = RootShell.runSu(command, timeoutMs = 3_000L)
            if (result.succeeded) return true
        }
        return false
    }
}
