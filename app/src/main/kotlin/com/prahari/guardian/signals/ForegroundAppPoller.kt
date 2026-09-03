package com.prahari.guardian.signals

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Collector six: which app is actually in front, polled from usage stats.
 *
 * There are two reasons this exists rather than leaving foreground detection to
 * [PrahariAccessibilityService], and both are the kind of thing that decides
 * whether a demo works on the phone you were handed.
 *
 * **1. The accessibility service cannot see the user leave.** Its config scopes
 * `packageNames` to the seventeen apps we care about, which is a real privacy
 * property worth keeping — but it also means no event ever arrives for
 * "Settings is now in the foreground". Without a second source,
 * `paymentAppForeground` latches true for the rest of the call and the heaviest
 * routinely-reachable weight over-reports. This poller sees the exit.
 *
 * **2. Vivo and iQOO builds revoke accessibility grants.** OriginOS is
 * aggressive about killing background services and about dropping accessibility
 * permission after a reboot. When that happens the accessibility path is simply
 * gone, and usage access — which the OEM treats differently — is the only way
 * left to know a payment app is open.
 *
 * It also gives `screenShareActive` a live producer. See
 * [SignalStore.onScreenShareChanged] for the honest caveat on what that signal
 * can and cannot observe from inside an app.
 *
 * **Cost discipline.** The loop only runs while a call is active, and it queries
 * a ten-second window every two seconds. Outside a call it is not scheduled at
 * all, which is the same rule the rest of the app follows.
 */
class ForegroundAppPoller(
    private val context: Context,
    private val scope: CoroutineScope
) {

    private var job: Job? = null

    /** Last state we wrote, so an unchanged poll costs nothing downstream. */
    private var lastWritten: String? = null

    fun start() {
        if (job?.isActive == true) return
        if (!hasUsageAccess(context)) {
            // Not an error. The user may have skipped this grant, and the app is
            // designed to work without it — just with one fewer path to the
            // foreground app. Say so in the log and move on.
            Log.i(TAG, "usage access not granted — accessibility is the only foreground path")
            return
        }
        job = scope.launch {
            while (isActive) {
                if (SignalStore.current.callActive) poll()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        lastWritten = null
    }

    private fun poll() {
        val pkg = foregroundPackage() ?: return
        if (pkg == lastWritten) return
        lastWritten = pkg

        when {
            KnownPackages.isPayment(pkg) -> {
                SignalStore.onPaymentAppForeground(KnownPackages.paymentLabel(pkg))
            }
            KnownPackages.isRemoteAccess(pkg) -> {
                val label = KnownPackages.remoteAccessLabel(pkg)
                SignalStore.onRemoteAccessActive(label ?: "a remote-access app")
                SignalStore.onScreenShareChanged(true)
            }
            else -> {
                // The user navigated away from the payment app. This is the event
                // the accessibility service structurally cannot deliver.
                if (SignalStore.current.paymentAppForeground) {
                    SignalStore.onPaymentAppBackground()
                }
                SignalStore.onScreenShareChanged(false)
            }
        }
    }

    /**
     * The most recent app to come to the foreground inside the query window.
     *
     * `queryEvents` is used rather than `queryUsageStats` because only the event
     * stream tells you *ordering*; the aggregated stats tell you totals, which
     * cannot answer "what is on screen right now".
     */
    private fun foregroundPackage(): String? {
        val usage = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        val events = runCatching { usage.queryEvents(now - QUERY_WINDOW_MS, now) }
            .getOrNull() ?: return null

        var latestPackage: String? = null
        var latestAt = 0L
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            // ACTIVITY_RESUMED rather than the deprecated MOVE_TO_FOREGROUND
            // alias; they share a value but minSdk 31 means we can name the
            // current one.
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED &&
                event.timeStamp >= latestAt
            ) {
                latestAt = event.timeStamp
                latestPackage = event.packageName
            }
        }
        return latestPackage
    }

    companion object {
        private const val TAG = "PrahariUsage"
        private const val POLL_INTERVAL_MS = 2_000L

        /**
         * Ten seconds, not two. Usage events are delivered in batches and a
         * window exactly the size of the poll interval drops events on the
         * boundary; overlapping is cheap and losing the one event that mattered
         * is not.
         */
        private const val QUERY_WINDOW_MS = 10_000L

        /**
         * There is no `checkSelfPermission` for usage access — it is an app-op,
         * granted from a Settings screen, and this is the only way to read it.
         */
        fun hasUsageAccess(context: Context): Boolean {
            val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
            val mode = runCatching {
                ops.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            }.getOrDefault(AppOpsManager.MODE_ERRORED)
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }
}
