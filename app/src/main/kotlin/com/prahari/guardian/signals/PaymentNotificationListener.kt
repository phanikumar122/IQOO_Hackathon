package com.prahari.guardian.signals

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * The belt to the accessibility service's braces.
 *
 * UPI apps and banks post a notification for a debit, and OEM builds vary in
 * what they let an accessibility service read. Having two independent paths to
 * "an amount is in play" is the difference between a demo that works on the
 * phone you brought and a demo that works on the phone you were given.
 *
 * Also the cheapest place to detect a *new payee*: "Paid ₹49,000 to
 * VIJAY KUMAR" tells you the payee name without touching the screen at all.
 *
 * This listener does double duty. Outside a call it *teaches*: every payment
 * notification feeds [TransferHistory], which is how the app learns what a
 * normal transfer looks like for this person. During a call it *judges*: the
 * same parse is scored against that baseline. A transfer made while a stranger
 * is on the line is never folded into the baseline, so a scam cannot quietly
 * raise the bar for the next one.
 *
 * As with everything else here: parse, extract, drop. The notification text is
 * never stored and never logged, and [TransferHistory] keeps amounts plus salted
 * hashes rather than payee names.
 */
class PaymentNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        if (!KnownPackages.isPayment(n.packageName)) return

        val extras = n.notification?.extras ?: return
        val text = listOfNotNull(
            extras.getCharSequence("android.title")?.toString(),
            extras.getCharSequence("android.text")?.toString(),
            extras.getCharSequence("android.bigText")?.toString()
        ).joinToString(" ")
        if (text.isBlank()) return

        val paise = parsePaise(text) ?: return
        val payee = parsePayee(text)

        if (!SignalStore.current.callActive) {
            // Ordinary use. Learn from it, score nothing.
            TransferHistory.record(this, paise, payee)
            return
        }

        Log.i(TAG, "payment notification with amount from ${n.packageName}")
        SignalStore.onPaymentAppForeground(KnownPackages.paymentLabel(n.packageName))
        SignalStore.onAmountObserved(
            paise = paise,
            payeeIsNew = TransferHistory.isNewPayee(this, payee),
            p95Paise = TransferHistory.p95Paise(this)
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = Unit

    /**
     * The payee name if the notification contains one in a shape we recognise,
     * null otherwise — and null travels all the way to the engine, which scores
     * `payeeIsNew == true` only, so unknown never masquerades as safe.
     */
    private fun parsePayee(text: String): String? {
        val raw = PAYEE.find(text)?.groupValues?.get(1)?.trim() ?: return null
        val cleaned = raw.split(Regex("""\b(successfully|via|using|from|on|ref|upi|credited|debited|is|for|with)\b""", RegexOption.IGNORE_CASE))
            .firstOrNull()?.trim() ?: raw
        return cleaned.takeIf { it.length >= 3 }
    }

    private companion object {
        const val TAG = "PrahariNotif"

        val AMOUNT = Regex("""(?:₹|rs\.?|inr)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""",
            RegexOption.IGNORE_CASE)

        val PAYEE = Regex("""\bto\s+([A-Za-z][A-Za-z .'-]{2,40})""", RegexOption.IGNORE_CASE)

        fun parsePaise(text: String): Long? {
            val raw = AMOUNT.find(text)?.groupValues?.get(1) ?: return null
            val rupees = raw.replace(",", "").toDoubleOrNull() ?: return null
            if (rupees <= 0) return null
            return Math.round(rupees * 100)
        }
    }
}
