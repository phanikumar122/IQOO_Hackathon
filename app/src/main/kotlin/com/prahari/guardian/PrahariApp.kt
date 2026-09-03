package com.prahari.guardian

import android.app.Application
import com.prahari.guardian.signals.SignalStore

/**
 * Nothing clever here on purpose.
 *
 * The service is not auto-started from here. Prahari only runs after the user
 * has walked the permission wizard in [ui.MainActivity][com.prahari.guardian.ui.MainActivity]
 * and pressed the switch — an app that silently starts listening on first launch
 * is the exact thing this product is supposed to be the opposite of.
 */
class PrahariApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Defensive: a debug build left in replay mode from a previous run
        // would otherwise ignore every live signal and look "broken".
        SignalStore.exitReplay()
    }
}
