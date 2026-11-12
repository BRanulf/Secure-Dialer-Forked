/*
 * Copyright (C) 2026 MovStore
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.example

import android.app.Application
import com.example.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.sqlcipher.database.SQLiteDatabase

class DialerApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        com.example.util.DynamicIslandOverlayManager.startCallMonitoring(this)
        // Fast-path non-blocking database and security warm-up
        appScope.launch {
            try {
                SQLiteDatabase.loadLibs(this@DialerApplication)
                AppDatabase.warmUpAsync(this@DialerApplication)
            } catch (_: Throwable) {}

            // Warm the cached root status once per process if the user previously
            // granted root and has a root-powered feature enabled. A previously
            // granted su request does not show a prompt again on normal root managers.
            try {
                val prefs = getSharedPreferences("dialer_prefs", MODE_PRIVATE)
                val wantsRoot = com.example.util.CallAudioRecorder.isRootEngineSelected(this@DialerApplication) ||
                        prefs.getBoolean("is_answering_machine_enabled", false)
                if (wantsRoot && com.example.util.RootShell.wasGrantedBefore(this@DialerApplication)) {
                    com.example.util.RootShell.ensureAccess(this@DialerApplication, prepare = true)
                }
            } catch (_: Throwable) {}
        }
    }
}
