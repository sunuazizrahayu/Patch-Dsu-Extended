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

        // Probe both sources so a wrong guard decision is visible in logs.
        val propRunning = DevicePropUtils.isGsiRunning()
        val serviceInUse = if (PrivilegedProvider.isConnected()) {
            runCatching {
                var result = false
                PrivilegedProvider.run { result = isInUse }
                result
            }.getOrDefault(false)
        } else {
            false
        }
        AppLogger.i(TAG, "DSU state probe", "propRunning" to propRunning, "serviceInUse" to serviceInUse)

        // Guard: when already running inside the DSU, re-arming oneShot
        // (setEnable(true, true) / reboot("dsu") / gsi_tool enable -s) keeps
        // the device stuck in the DSU and looks like a mere soft reboot.
        // A plain reboot lets the oneShot expire and returns to stock.
        if (propRunning || serviceInUse) {
            AppLogger.i(TAG, "Already running in DSU; falling back to plain reboot to stock")
            return@withContext rebootToSystem(context)
        }

        // Re-arm oneShot via the privileged service when available.
        // The setEnable() return value matters: rebooting with the "dsu"
        // reason while the DSU is NOT enabled lands back on stock.
        var enableOk = false
        if (PrivilegedProvider.isConnected()) {
            enableOk = runCatching {
                var result = false
                PrivilegedProvider.run { result = setEnable(true, true) }
                result
            }.getOrDefault(false)
            AppLogger.i(TAG, "Privileged setEnable finished", "enabled" to enableOk)
        }

        // Reboot only after the DSU is armed. Reference behavior
        // (DSU-Sideloader): a plain full `reboot` after arming oneShot.
        // PowerManager.reboot("dsu") is only a fallback: the app process
        // usually lacks the REBOOT permission, and a silent fallback to a
        // plain reboot would boot stock instead of the DSU.
        if (enableOk) {
            if (shellReboot()) {
                return@withContext true
            }
            AppLogger.w(TAG, "Shell reboot failed; trying PowerManager dsu reboot")
            val dsuRebootOk = runCatching {
                val pm = context.getSystemService(PowerManager::class.java)
                    ?: throw IllegalStateException("PowerManager unavailable")
                pm.reboot("dsu")
                true
            }.getOrDefault(false)
            AppLogger.i(TAG, "Reboot with dsu reason finished", "ok" to dsuRebootOk)
            if (dsuRebootOk) {
                return@withContext true
            }
            AppLogger.w(TAG, "DSU armed but dsu reboot failed; trying mode fallback, never plain reboot")
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
                AppLogger.i(TAG, "Root re-arm reboot finished", "success" to res.isSuccess, "err" to res.err.take(200))
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
     * Return to the stock system image.
     *
     * Only disables the DSU when it is still enabled (sticky installs).
     * When it is already disabled (consumed oneShot), the enable state is
     * left untouched and a plain reboot follows: on some devices calling
     * setEnable(false) while running corrupts the next boot target and the
     * reboot loops back into the DSU instead of stock.
     */
    suspend fun rebootToSystem(context: Context): Boolean = withContext(Dispatchers.IO) {
        AppLogger.i(TAG, "Reboot to system requested")

        if (PrivilegedProvider.isConnected()) {
            val needsDisable = runCatching {
                var enabled = true
                PrivilegedProvider.run { enabled = isEnabled }
                enabled
            }.getOrDefault(true)
            AppLogger.i(TAG, "DSU enabled probe", "needsDisable" to needsDisable)
            if (needsDisable) {
                val disabled = runCatching {
                    var result = false
                    PrivilegedProvider.run { result = setEnable(false, false) }
                    result
                }.getOrDefault(false)
                AppLogger.i(TAG, "Privileged setEnable(false) finished", "disabled" to disabled)
            } else {
                AppLogger.i(TAG, "DSU already disabled; plain reboot without touching enable state")
            }
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

    /**
     * Plain `reboot` via shell (reference behavior). The command may not
     * report success even when the reboot was accepted, callers treat a
     * "device is going down" as best-effort.
     */
    private fun shellReboot(): Boolean {
        return runCatching {
            val res = Shell.cmd("reboot").exec()
            AppLogger.i(TAG, "Shell reboot finished", "success" to res.isSuccess, "err" to res.err.take(200))
            res.isSuccess
        }.getOrDefault(false)
    }

    private fun executePlainReboot(context: Context) {
        if (shellReboot()) {
            return
        }
        runCatching {
            val pm = context.getSystemService(PowerManager::class.java)
                ?: throw IllegalStateException("PowerManager unavailable")
            pm.reboot(null)
        }.onFailure {
            AppLogger.w(TAG, "Plain reboot via PowerManager failed", "error" to (it.message ?: "unknown"))
        }
    }
}
