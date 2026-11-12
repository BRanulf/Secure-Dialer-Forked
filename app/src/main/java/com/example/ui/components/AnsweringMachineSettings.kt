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

package com.example.ui.components

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Voicemail
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.ui.viewmodel.DialerViewModel
import com.example.util.AnsweringMachineGreeting
import com.example.util.RichHapticEngine
import com.example.util.RootShell
import com.example.util.RootStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AnsweringMachineSettings(
    viewModel: DialerViewModel,
    cardBgColor: Color
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val timeoutSeconds by viewModel.answeringMachineTimeoutSeconds
    val maxSeconds by viewModel.answeringMachineMaxSeconds
    val greetingPath by viewModel.answeringMachineGreetingPath
    val greetingDisplayName by viewModel.answeringMachineGreetingDisplayName
    val rootStatus by RootShell.status.collectAsStateWithLifecycle()

    var previewing by remember { mutableStateOf(false) }
    var previewPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    fun stopPreview() {
        try {
            previewPlayer?.stop()
        } catch (_: Exception) {}
        try {
            previewPlayer?.release()
        } catch (_: Exception) {}
        previewPlayer = null
        previewing = false
    }

    fun startPreview() {
        stopPreview()
        val player = MediaPlayer()
        previewPlayer = player
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            AnsweringMachineGreeting.setDataSource(
                context,
                player,
                AnsweringMachineGreeting.resolve(context)
            )
            player.setOnPreparedListener {
                try {
                    it.start()
                    previewing = true
                } catch (_: Exception) {
                    stopPreview()
                }
            }
            player.setOnCompletionListener { stopPreview() }
            player.setOnErrorListener { _, _, _ ->
                stopPreview()
                true
            }
            player.prepareAsync()
        } catch (_: Exception) {
            stopPreview()
            Toast.makeText(
                context,
                context.getString(R.string.answering_machine_greeting_preview_failed),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    DisposableEffect(Unit) {
        onDispose { stopPreview() }
    }

    val greetingPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        stopPreview()
        scope.launch {
            val imported = withContext(Dispatchers.IO) {
                AnsweringMachineGreeting.importFromUri(context, uri)
            }
            if (imported != null) {
                viewModel.refreshAnsweringMachineGreeting(context)
                Toast.makeText(
                    context,
                    context.getString(R.string.answering_machine_greeting_imported),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    context,
                    context.getString(R.string.answering_machine_greeting_import_failed),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Root / privacy notice
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp).padding(top = 2.dp)
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.answering_machine_root_required),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 17.sp
                    )
                    Text(
                        text = stringResource(R.string.answering_machine_mic_muted),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 17.sp
                    )
                }
            }
        }

        // Auto-answer timeout
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = cardBgColor,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.answering_machine_timeout_title),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.answering_machine_timeout_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(10, 20, 30, 45, 60).forEach { seconds ->
                        val selected = timeoutSeconds == seconds
                        FilterChip(
                            selected = selected,
                            onClick = {
                                RichHapticEngine.performHaptic(context, RichHapticEngine.HapticStyle.KEY_TICK)
                                viewModel.updateAnsweringMachineTimeoutSeconds(seconds)
                            },
                            label = {
                                Text(
                                    text = "${seconds}s",
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                )
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // Maximum message length
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = cardBgColor,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.answering_machine_max_duration_title),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.answering_machine_max_duration_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(60, 120, 180, 300).forEach { seconds ->
                        val selected = maxSeconds == seconds
                        FilterChip(
                            selected = selected,
                            onClick = {
                                RichHapticEngine.performHaptic(context, RichHapticEngine.HapticStyle.KEY_TICK)
                                viewModel.updateAnsweringMachineMaxSeconds(seconds)
                            },
                            label = {
                                Text(
                                    text = stringResource(
                                        R.string.answering_machine_minutes_format,
                                        seconds / 60
                                    ),
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                )
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // Greeting audio
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = cardBgColor,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Voicemail,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.answering_machine_greeting_title),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (greetingPath.isNotBlank()) {
                                greetingDisplayName.ifBlank {
                                    greetingPath.substringAfterLast('/')
                                }
                            } else {
                                stringResource(R.string.answering_machine_greeting_bundled)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            lineHeight = 16.sp
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.answering_machine_greeting_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TextButton(
                        onClick = {
                            RichHapticEngine.performHaptic(context, RichHapticEngine.HapticStyle.CLICK)
                            greetingPicker.launch(arrayOf("audio/*"))
                        }
                    ) {
                        Text(stringResource(R.string.answering_machine_choose_audio))
                    }
                    TextButton(
                        onClick = {
                            RichHapticEngine.performHaptic(context, RichHapticEngine.HapticStyle.CLICK)
                            if (previewing) stopPreview() else startPreview()
                        }
                    ) {
                        Text(
                            stringResource(
                                if (previewing) R.string.answering_machine_stop_preview
                                else R.string.answering_machine_preview
                            )
                        )
                    }
                    if (greetingPath.isNotBlank()) {
                        TextButton(
                            onClick = {
                                RichHapticEngine.performHaptic(context, RichHapticEngine.HapticStyle.CLICK)
                                stopPreview()
                                AnsweringMachineGreeting.clear(context)
                                viewModel.refreshAnsweringMachineGreeting(context)
                            }
                        ) {
                            Text(stringResource(R.string.answering_machine_reset_greeting))
                        }
                    }
                }
            }
        }

        // Root status
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = cardBgColor,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.root_status),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = when (rootStatus) {
                            RootStatus.GRANTED -> stringResource(R.string.root_status_granted)
                            RootStatus.DENIED -> stringResource(R.string.root_status_denied)
                            RootStatus.UNAVAILABLE -> stringResource(R.string.root_status_unavailable)
                            RootStatus.CHECKING -> stringResource(R.string.root_status_checking)
                            RootStatus.UNKNOWN -> stringResource(R.string.root_status_unknown)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (rootStatus == RootStatus.GRANTED) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                TextButton(
                    onClick = {
                        scope.launch {
                            val result = RootShell.ensureAccess(context, prepare = true)
                            if (!result.granted) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.root_required_toast),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    },
                    enabled = rootStatus != RootStatus.CHECKING
                ) {
                    Text(
                        text = if (rootStatus == RootStatus.CHECKING) {
                            stringResource(R.string.root_status_checking)
                        } else {
                            stringResource(R.string.root_check_button)
                        }
                    )
                }
            }
        }
    }
}
