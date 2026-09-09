package com.prahari.guardian.signals

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.prahari.guardian.TrustedContact
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the answer to "are we in a call, with whom, and for how long".
 *
 * Two mechanisms, because Android splits the information across them:
 *
 *  - **State** comes from [TelephonyCallback.CallStateListener] (API 31+, the
 *    non-deprecated path).
 *  - **The number** does not. `CallStateListener` deliberately omits it. The
 *    only supported way to see the other party's number in a background app is
 *    the legacy `android.intent.action.PHONE_STATE` broadcast plus
 *    `READ_CALL_LOG`, so we register for that dynamically.
 *
 * Expect the number to be absent on some OEM builds and on some carriers. When
 * it is, we treat the caller as unknown. That biases toward arming, which is
 * safe here: arming only starts *scoring*, and the 180-second gate plus the
 * WATCH threshold mean nothing reaches the user from arming alone.
 *
 * If contacts lookup gives you trouble, or you would rather not ask for
 * `READ_CONTACTS` at all, replace [isKnownNumber] with a user-curated trusted
 * list stored locally. Less code, better privacy story, same behaviour.
 */
class CallStateMonitor(
    private val context: Context,
    private val scope: CoroutineScope
) {

    private val telephony =
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    private var tickJob: Job? = null
    private var lastNumber: String? = null
    private var isStarted = false

    private val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            when (state) {
                TelephonyManager.CALL_STATE_OFFHOOK -> beginCall()
                TelephonyManager.CALL_STATE_IDLE -> endCall()
                // RINGING: do nothing. Nothing about this design cares until
                // the call is actually connected.
                else -> Unit
            }
        }
    }

    private val numberReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
            @Suppress("DEPRECATION")
            intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                ?.takeIf { it.isNotBlank() }
                ?.let { num ->
                    lastNumber = num
                    if (SignalStore.current.callActive && (SignalStore.current.callerMasked.isEmpty() || SignalStore.current.callerMasked == "unknown number")) {
                        val known = isKnownNumber(num)
                        SignalStore.onCallStarted(
                            unknownCaller = !known,
                            maskedNumber = mask(num)
                        )
                    }
                }
        }
    }

    fun start() {
        if (isStarted) return
        if (!hasPermission(Manifest.permission.READ_PHONE_STATE)) {
            Log.w(TAG, "READ_PHONE_STATE not granted — call signals will stay dormant")
            return
        }
        isStarted = true
        runCatching {
            telephony.registerTelephonyCallback(context.mainExecutor, callback)
        }.onFailure { Log.e(TAG, "registerTelephonyCallback failed", it) }

        runCatching {
            ContextCompat.registerReceiver(
                context,
                numberReceiver,
                IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED),
                ContextCompat.RECEIVER_EXPORTED
            )
        }.onFailure { Log.w(TAG, "PHONE_STATE receiver failed; caller will read as unknown", it) }
    }

    fun stop() {
        if (!isStarted) return
        isStarted = false
        tickJob?.cancel()
        runCatching { telephony.unregisterTelephonyCallback(callback) }
        runCatching { context.unregisterReceiver(numberReceiver) }
    }

    private fun beginCall() {
        if (SignalStore.current.callActive) return
        val number = lastNumber
        SignalStore.onCallStarted(
            unknownCaller = number == null || !isKnownNumber(number),
            maskedNumber = mask(number)
        )
        // One tick per second is plenty and costs nothing measurable. The
        // duration is what drives the arming gate, so it has to keep moving
        // even when no other signal changes.
        tickJob?.cancel()
        tickJob = scope.launch {
            var seconds = 0L
            while (isActive && SignalStore.current.callActive) {
                delay(1_000)
                seconds += 1
                SignalStore.onCallTick(seconds)
            }
        }
    }

    private fun endCall() {
        tickJob?.cancel()
        tickJob = null
        lastNumber = null
        SignalStore.onCallEnded()
    }

    private fun isKnownNumber(number: String): Boolean {
        val trusted = TrustedContact.number(context)
        if (trusted != null && numbersMatch(number, trusted)) return true
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) return false
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)
        )
        return runCatching {
            context.contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null
            )?.use { it.moveToFirst() } ?: false
        }.getOrDefault(false)
    }

    private fun numbersMatch(a: String, b: String): Boolean {
        val da = a.filter(Char::isDigit)
        val db = b.filter(Char::isDigit)
        if (da.isEmpty() || db.isEmpty()) return false
        return da == db || (da.length >= 7 && db.length >= 7 && (da.endsWith(db) || db.endsWith(da)))
    }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "PrahariCall"

        /**
         * Never let a full number reach a log, a notification, or the screen.
         * This is a small function doing a large amount of the project's
         * privacy work — keep it, and keep using it.
         */
        fun mask(number: String?): String {
            if (number.isNullOrBlank()) return "unknown number"
            val digits = number.filter(Char::isDigit)
            return if (digits.length <= 4) "••••" else "••••••" + digits.takeLast(4)
        }
    }
}
