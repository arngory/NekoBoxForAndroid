package io.niko

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.niko.bg.SubscriptionUpdater
import io.niko.database.DataStore
import io.niko.ktx.runOnDefaultDispatcher

/**
 * Restores what an update can lose: the enabled state of [BootReceiver] and the periodic
 * subscription update are stored by class name, so a renamed class (the Niko rebrand) left
 * the receiver disabled and the work pointing at a class that no longer exists. Unlike
 * [BootReceiver] this receiver is always enabled.
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // exported for the system's protected broadcast; ignore anything else
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        runOnDefaultDispatcher {
            try {
                BootReceiver.enabled = DataStore.persistAcrossReboot
                SubscriptionUpdater.reconfigureUpdater()
                // as BootReceiver did on an update: reconnect when the user asked to persist
                if (DataStore.persistAcrossReboot && DataStore.selectedProxy > 0) SagerNet.startService()
            } finally {
                pending.finish()
            }
        }
    }
}
