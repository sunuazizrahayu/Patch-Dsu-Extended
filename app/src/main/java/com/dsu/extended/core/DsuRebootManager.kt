package com.dsu.extended.core

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.PowerManager
import com.dsu.extended.service.PrivilegedProvider
import com.dsu.extended.util.AppLogger
import com.dsu.extended.util.DevicePropUtils
import com.dsu.extended.util.OperationMode
import com.dsu.extended.util.OperationModeUtils
import com.rosan.dhizuku.api.Dhizuku
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

object DsuRebootManager {
    private const val TAG = "DsuRebootManager"

    suspend fun rebootToDsu(context: Context): Boolean = withContext(Dispatchers.IO) {
        AppLogger.i(TAG, "Reboot to DSU requested from subsystem")

        // Guard: when already running inside the DSU, re-arming oneShot
        // (setEnable(true, true) / reboot("dsu") / gsi_tool enable -s) keeps
        // the device stuck in the DSU and looks like a mere soft reboot.
        // A plain reboot lets the oneShot expire and returns to stock.
        if (isRunningInDsu()) {
            AppLogger.i(TAG, "Already running in DSU; falling back to plain reboot to stock")
            return@withContext rebootToSystem(context)
        }

        if (PrivilegedProvider.isConnected()) {
            val enabled = runCatching {
                PrivilegedProvider.run {
                    setEnable(true, true)
                }
                true
            }.getOrDefault(false)

            if (enabled) {
                executeSystemReboot(context)
                return@withContext true
            }
        }

        val mode = OperationModeUtils.getOperationMode(
            context = context,
            checkShizuku = Shizuku.pingBinder(),
            checkDhizuku = runCatching { Dhizuku.init(context) && Dhizuku.isPermissionGranted() }.getOrDefault(false),
        )

        AppLogger.i(TAG, "Executing reboot strategy for mode: $mode")

        when (mode) {
            OperationMode.SYSTEM_AND_ROOT,
            OperationMode.ROOT -> {
                val res = Shell.cmd("gsi_tool enable -s && (svc power reboot || reboot)").exec()
                res.isSuccess
            }

            OperationMode.SYSTEM -> {
                runCatching {
                    val pm = context.getSystemService(PowerManager::class.java)
                    pm?.reboot("dsu")
                    true
                }.getOrDefault(false)
            }

            OperationMode.SHIZUKU -> {
                // Shizuku shell pre-11 newProcess API is not public in 13.1.5;
                // Shizuku mode operates through the bound PrivilegedService (handled above).
                // If the service is not connected here, fall through to in-app flow.
                AppLogger.w(TAG, "Shizuku reboot requires bound privileged service; delegating to app")
                false
            }

            OperationMode.DHIZUKU -> {
                // Dhizuku-API has no Dhizuku.reboot(); DeviceOwner reboots via DevicePolicyManager.
                runCatching {
                    val dpm = context.getSystemService(DevicePolicyManager::class.java)
                    val admin = Dhizuku.getOwnerComponent()
                    if (dpm != null && admin != null) {
                        dpm.reboot(admin)
                        true
                    } else {
                        val pm = context.getSystemService(PowerManager::class.java)
                        pm?.reboot("dsu")
                        true
                    }
                }.getOrDefault(false)
            }

            OperationMode.ADB -> {
                AppLogger.w(TAG, "ADB unrooted mode cannot trigger reboot autonomously")
                false
            }
        }
    }

    /**
     * Plain reboot without re-arming the DSU. On a oneShot (single-boot)
     * installation this returns the device to the stock system image,
     * which is exactly what a user inside the DSU expects from "reboot".
     */
    suspend fun rebootToSystem(context: Context): Boolean = withContext(Dispatchers.IO) {
        AppLogger.i(TAG, "Reboot to system requested (plain reboot, DSU not re-armed)")

        if (PrivilegedProvider.isConnected()) {
            executePlainReboot(context)
            return@withContext true
        }

        val mode = OperationModeUtils.getOperationMode(
            context = context,
            checkShizuku = Shizuku.pingBinder(),
            checkDhizuku = runCatching { Dhizuku.init(context) && Dhizuku.isPermissionGranted() }.getOrDefault(false),
        )

        AppLogger.i(TAG, "Executing plain reboot strategy for mode: $mode")

        when (mode) {
            OperationMode.SYSTEM_AND_ROOT,
            OperationMode.ROOT -> {
                val res = Shell.cmd("svc power reboot || reboot").exec()
                res.isSuccess
            }

            OperationMode.SYSTEM -> {
                runCatching {
                    val pm = context.getSystemService(PowerManager::class.java)
                    // null reason = plain reboot, does NOT boot into the DSU.
                    pm?.reboot(null)
                    true
                }.getOrDefault(false)
            }

            OperationMode.SHIZUKU -> {
                AppLogger.w(TAG, "Shizuku reboot requires bound privileged service; delegating to app")
                false
            }

            OperationMode.DHIZUKU -> {
                runCatching {
                    val dpm = context.getSystemService(DevicePolicyManager::class.java)
                    val admin = Dhizuku.getOwnerComponent()
                    if (dpm != null && admin != null) {
                        dpm.reboot(admin)
                        true
                    } else {
                        val pm = context.getSystemService(PowerManager::class.java)
                        pm?.reboot(null)
                        true
                    }
                }.getOrDefault(false)
            }

            OperationMode.ADB -> {
                AppLogger.w(TAG, "ADB unrooted mode cannot trigger reboot autonomously")
                false
            }
        }
    }

    /** Best-effort check whether we are currently running inside the DSU. */
    private suspend fun isRunningInDsu(): Boolean {
        if (DevicePropUtils.isGsiRunning()) {
            return true
        }
        if (PrivilegedProvider.isConnected()) {
            val inUse = runCatching {
                var result = false
                PrivilegedProvider.run { result = isInUse }
                result
            }.getOrDefault(false)
            if (inUse) {
                return true
            }
        }
        return false
    }

    private fun executeSystemReboot(context: Context) {
        runCatching {
            val pm = context.getSystemService(PowerManager::class.java)
            pm?.reboot("dsu")
        }.onFailure {
            Shell.cmd("svc power reboot || reboot").exec()
        }
    }

    private fun executePlainReboot(context: Context) {
        runCatching {
            val pm = context.getSystemService(PowerManager::class.java)
            pm?.reboot(null)
        }.onFailure {
            Shell.cmd("svc power reboot || reboot").exec()
        }
    }
}
