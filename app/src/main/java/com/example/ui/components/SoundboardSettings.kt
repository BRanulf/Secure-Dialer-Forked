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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.util.SoundboardEffects
import com.example.util.SoundboardPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Compact Advanced Tools content for the in-call soundboard and the bleep:
 * local-monitoring switches plus a shortcut to the dedicated effect library page.
 */
@Composable
fun SoundboardSettings(onManageEffects: () -> Unit) {
    val context = LocalContext.current
    var monitorEnabled by remember {
        mutableStateOf(SoundboardPreferences.isMonitorEnabled(context))
    }
    var bleepMonitorEnabled by remember {
        mutableStateOf(SoundboardPreferences.isBleepMonitorEnabled(context))
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        SettingsRowToggle(
            title = stringResource(R.string.soundboard_monitor_title),
            subtitle = stringResource(R.string.soundboard_monitor_desc),
            checked = monitorEnabled,
            onCheckedChange = { enabled ->
                monitorEnabled = enabled
                SoundboardPreferences.setMonitorEnabled(context, enabled)
            },
            icon = Icons.Default.GraphicEq,
            iconBgColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
            iconTint = MaterialTheme.colorScheme.primary
        )

        SettingsRowToggle(
            title = stringResource(R.string.bleep_monitor_title),
            subtitle = stringResource(R.string.bleep_monitor_desc),
            checked = bleepMonitorEnabled,
            onCheckedChange = { enabled ->
                bleepMonitorEnabled = enabled
                SoundboardPreferences.setBleepMonitorEnabled(context, enabled)
            },
            icon = Icons.AutoMirrored.Filled.VolumeUp,
            iconBgColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
            iconTint = MaterialTheme.colorScheme.secondary
        )

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        SettingsRowNav(
            title = stringResource(R.string.soundboard_manage_title),
            subtitle = stringResource(R.string.soundboard_manage_sub),
            onClick = onManageEffects,
            icon = Icons.Default.MusicNote,
            iconBgColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
            iconTint = MaterialTheme.colorScheme.primary
        )

        Text(
            text = stringResource(R.string.soundboard_root_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )
    }
}

/**
 * Dedicated full-page effect library, opened from Advanced Tools. Keeping the
 * potentially long effect list out of the main settings scroll makes both pages
 * easier to manage.
 */
@Composable
fun SoundboardLibraryPage(cardBgColor: Color) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = stringResource(R.string.soundboard_library_sub),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
        )
        Surface(
            shape = MaterialTheme.shapes.large,
            color = cardBgColor,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            SoundboardLibraryContent()
        }
    }
}

@Composable
private fun SoundboardLibraryContent() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var effects by remember {
        mutableStateOf<List<SoundboardEffects.SoundboardEffect>>(emptyList())
    }
    var refreshKey by remember { mutableIntStateOf(0) }
    var previewingId by remember { mutableStateOf<String?>(null) }
    var previewPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var deleteTarget by remember {
        mutableStateOf<SoundboardEffects.SoundboardEffect?>(null)
    }

    fun stopPreview() {
        try {
            previewPlayer?.stop()
        } catch (_: Exception) {
        }
        try {
            previewPlayer?.release()
        } catch (_: Exception) {
        }
        previewPlayer = null
        previewingId = null
    }

    DisposableEffect(Unit) {
        onDispose { stopPreview() }
    }

    LaunchedEffect(refreshKey) {
        effects = withContext(Dispatchers.IO) { SoundboardEffects.all(context) }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                SoundboardEffects.importFromUri(context, uri)
            }
            when (result) {
                is SoundboardEffects.ImportResult.Success -> {
                    refreshKey++
                    Toast.makeText(
                        context,
                        context.getString(R.string.soundboard_imported),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                is SoundboardEffects.ImportResult.Failure -> {
                    val message = when (result.reason) {
                        SoundboardEffects.ImportFailure.TOO_LARGE ->
                            R.string.soundboard_import_too_large
                        else -> R.string.soundboard_import_failed
                    }
                    Toast.makeText(context, context.getString(message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun startPreview(effect: SoundboardEffects.SoundboardEffect) {
        stopPreview()
        previewingId = effect.id
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                SoundboardEffects.playableFile(context, effect)
            }
            if (previewingId != effect.id) return@launch
            if (file == null) {
                stopPreview()
                Toast.makeText(
                    context,
                    context.getString(R.string.soundboard_preview_failed),
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            try {
                val player = MediaPlayer()
                previewPlayer = player
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                player.setDataSource(file.absolutePath)
                player.setOnPreparedListener { prepared ->
                    if (previewPlayer === prepared) prepared.start()
                }
                player.setOnCompletionListener { stopPreview() }
                player.setOnErrorListener { _, _, _ ->
                    stopPreview()
                    Toast.makeText(
                        context,
                        context.getString(R.string.soundboard_preview_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                    true
                }
                player.prepareAsync()
            } catch (_: Exception) {
                stopPreview()
                Toast.makeText(
                    context,
                    context.getString(R.string.soundboard_preview_failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.soundboard_manage_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.soundboard_manage_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(
                onClick = {
                    importLauncher.launch(
                        arrayOf("audio/*", "application/octet-stream")
                    )
                }
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.soundboard_import))
            }
        }

        if (effects.isEmpty()) {
            Text(
                text = stringResource(R.string.soundboard_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            )
        } else {
            effects.forEach { effect ->
                SoundboardEffectSettingsRow(
                    effect = effect,
                    isPreviewing = previewingId == effect.id,
                    onPreview = {
                        if (previewingId == effect.id) {
                            stopPreview()
                        } else {
                            startPreview(effect)
                        }
                    },
                    onDelete = { deleteTarget = effect }
                )
            }
        }

        Text(
            text = stringResource(R.string.soundboard_root_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.soundboard_delete_title)) },
            text = {
                Text(stringResource(R.string.soundboard_delete_message, target.name))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val deleted = withContext(Dispatchers.IO) {
                                SoundboardEffects.deleteImported(context, target)
                            }
                            deleteTarget = null
                            if (deleted) {
                                refreshKey++
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.soundboard_deleted),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }
}

@Composable
private fun SoundboardEffectSettingsRow(
    effect: SoundboardEffects.SoundboardEffect,
    isPreviewing: Boolean,
    onPreview: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = effect.name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (effect.builtIn) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
            ) {
                Text(
                    text = stringResource(R.string.soundboard_builtin_badge),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        IconButton(onClick = onPreview) {
            Icon(
                imageVector = if (isPreviewing) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = stringResource(R.string.soundboard_preview),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
        }
        if (!effect.builtIn) {
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = stringResource(R.string.soundboard_delete_effect),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
