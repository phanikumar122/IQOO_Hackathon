package com.prahari.guardian.signals

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Fires when a package is installed. If it is a remote-access tool and a call
 * is currently active, that is the signal.
 *
 * **Read the pitch again if you are tempted to cut this file.** "AnyDesk was
 * installed while you were on this call" is the most legible sentence the
 * product ever says to a user, and the sequence — unknown caller, then an
 * install, then a payment screen — is something no bank and no telco can see.
 * A bank sees a transfer. A telco sees a call. Only the OS sees the install
 * land between them.
 *
 * **Registered twice, on purpose.** There is a manifest entry for this receiver,
 * and [GuardianService][com.prahari.guardian.service.GuardianService] also
 * registers it at runtime for the length of every call. The reason is honest
 * uncertainty: manifest-declared receivers for implicit broadcasts have been
 * restricted since Android 8, and I could not confirm that
 * `ACTION_PACKAGE_ADDED` is still delivered to a background app on current
 * Android — still less on OriginOS. A context-registered receiver is exempt from
 * that restriction, so the runtime path is the one to rely on; the manifest entry
 * is a free fallback. Being registered from both places is harmless — the guards
 * in [onReceive] are idempotent and
 * [SignalStore.onRemoteAccessActive][SignalStore] simply sets a flag.
 */
class RemoteAccessWatcher : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != Intent.ACTION_PACKAGE_ADDED) return
        // Reinstalls and updates of an app that was already there are not the
        // signal we mean.
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return

        val pkg = intent.data?.schemeSpecificPart ?: return
        val label = KnownPackages.remoteAccessLabel(pkg) ?: return

        if (!SignalStore.current.callActive) {
            // Installing AnyDesk on a Tuesday afternoon is a normal thing for a
            // normal person to do. Not our business.
            Log.i(TAG, "$label installed outside a call — ignored")
            return
        }

        Log.w(TAG, "$label installed DURING an active call")
        SignalStore.onRemoteAccessActive(label)
    }

    private companion object {
        const val TAG = "PrahariRemote"
    }
}
