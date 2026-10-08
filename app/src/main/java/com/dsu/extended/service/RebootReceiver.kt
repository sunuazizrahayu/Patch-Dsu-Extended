package com.dsu.extended.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dsu.extended.MainActivity
import com.dsu.extended.core.DsuRebootManager
import com.dsu.extended.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Handles the "Reboot" action of the installation notification.
 *
 * Smart (context-aware) behavior via [DsuRebootManager.rebootToDsu]:
 * - running the stock system with a DSU installed -> enable + reboot into the DSU.
 * - already running inside the DSU -> plain reboot, letting the oneShot
 *   expire so the device returns to the stock system image.
 */
class RebootReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_SMART_REBOOT = "com.dsu.extended.action.SMART_REBOOT"
    }

    private val tag = this.javaClass.simpleName

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SMART_REBOOT) {
            return
        }
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val appContext = context.applicationContext
                val rebootInitiated = DsuRebootManager.rebootToDsu(appContext)
                if (!rebootInitiated) {
                    // E.g. ADB (unrooted) mode cannot reboot on its own:
                    // fall back to opening the app, mirroring the widget.
                    AppLogger.w(tag, "Smart reboot unavailable, opening app instead")
                    val launch = Intent(appContext, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    appContext.startActivity(launch)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
