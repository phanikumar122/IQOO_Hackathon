package com.prahari.guardian.signals

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

/**
 * Catches the OTP arriving *while the call is connected*.
 *
 * This is a cheap signal with a very high information content. A one-time
 * password is not suspicious. A one-time password that lands during minute
 * eleven of a call from a number you do not know, with a payment app already
 * open, is close to conclusive — the victim is being walked through a transfer
 * by someone on the line.
 *
 * We never store the message, never store the code, and never store the
 * sender. The only thing that leaves this class is a single boolean.
 *
 * Distribution note: Play restricts `RECEIVE_SMS` to default SMS/Phone/
 * Assistant handlers. That is a store policy, not an OS block — a sideloaded
 * debug build with the permission granted works, which is the hackathon case,
 * and a pre-installed OriginOS service is exempt by construction.
 */
class SmsSignalReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        // No call, no signal. Outside a call this is just an SMS and none of
        // our business, so we do not even look at the body.
        if (!SignalStore.current.callActive) return

        val body = runCatching {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
                .joinToString(" ") { it.displayMessageBody ?: "" }
        }.getOrElse {
            Log.w(TAG, "could not parse SMS intent", it); return
        }

        if (looksLikeOtp(body)) {
            Log.i(TAG, "OTP-shaped message during active call")
            SignalStore.onOtpObserved()
        }
    }

    private companion object {
        const val TAG = "PrahariSms"

        /** 4–8 standalone digits. Deliberately loose — a false positive here
         *  contributes 20 points, not an intervention. */
        val OTP_DIGITS = Regex("""(?<!\d)\d{4,8}(?!\d)""")

        val OTP_WORDS = listOf(
            "otp", "one time password", "one-time password", "verification code",
            "security code", "do not share", "kisi ko share"
        )

        fun looksLikeOtp(body: String): Boolean {
            val lower = body.lowercase()
            return OTP_DIGITS.containsMatchIn(body) && OTP_WORDS.any { it in lower }
        }
    }
}
