package com.prahari.guardian.demo

import com.prahari.guardian.model.SignalSnapshot
import com.prahari.guardian.model.Tactic

/**
 * Scripted snapshots. This file is your insurance policy.
 *
 * At hour 29 the mic will pick up the room instead of the recording, or the
 * borrowed phone will not have the model pushed, or the Wi-Fi captive portal
 * will eat your adb session. When that happens you press a button and the same
 * pipeline runs against a fixed snapshot: real [com.prahari.guardian.risk.RiskEngine],
 * real reasons, real intervention screen. Only the signal source is scripted.
 *
 * Say so if you use it. "This is our replay mode, the scoring and the screen
 * are live" is a fine sentence. Being caught pretending is not survivable.
 *
 * [INNOCENT_LONG_CALL] matters as much as the scam cases. Demoing the app
 * *not* firing during a forty-minute call with your mother is the answer to the
 * only question a good judge really has: how often does this cry wolf?
 */
object DemoScenarios {

    data class Scenario(
        val id: String,
        val title: String,
        val expectation: String,
        val snapshot: SignalSnapshot
    )

    /** The canonical digital-arrest sequence. Ends at INTERVENE. */
    val DIGITAL_ARREST = Scenario(
        id = "digital_arrest",
        title = "Digital arrest — CBI impersonation",
        expectation = "INTERVENE",
        snapshot = SignalSnapshot(
            callActive = true,
            callerUnknown = true,
            callDurationSeconds = 22 * 60L,
            callerMasked = "••••••4471",
            paymentAppForeground = true,
            paymentAppLabel = "Google Pay",
            pendingAmountPaise = 4_90_000_00L,   // ₹4,90,000
            payeeIsNew = true,
            userP95AmountPaise = 25_000_00L,     // ₹25,000
            remoteAccessAppActive = true,
            remoteAccessAppLabel = "AnyDesk",
            screenShareActive = true,
            otpDuringCall = true,
            tactics = setOf(
                Tactic.AUTHORITY_CLAIM,
                Tactic.ARREST_THREAT,
                Tactic.SECRECY,
                Tactic.STAY_ON_LINE,
                Tactic.TRANSFER_TO_VERIFY
            ),
            transcriptWindow = "…this is a non-bailable case, do not disconnect the " +
                "call and do not tell your family. Transfer the amount to the " +
                "government account for verification…",
            timestampMs = 0L
        )
    )

    /**
     * The important one. No tactics at all — the SLM contributes zero — and the
     * behavioural signals alone still reach WARN.
     *
     * The arithmetic, because you will be asked for it: payment app in the
     * foreground 30, first-time payee 15, OTP during the call 20, long call 10.
     * Seventy-five, against a WARN threshold of 65, with the language model
     * switched off. Note the amount is deliberately *ordinary* — ₹20,000, below
     * this user's own baseline — so not one of those points comes from the size
     * of the transfer.
     *
     * Run this second in the demo. It is the graceful-degradation argument made
     * visible, and it pre-empts "so your whole product is a prompt".
     */
    val BEHAVIOUR_ONLY = Scenario(
        id = "behaviour_only",
        title = "Same attack, language model disabled",
        expectation = "WARN at 75 with zero model contribution",
        snapshot = SignalSnapshot(
            callActive = true,
            callerUnknown = true,
            callDurationSeconds = 19 * 60L,
            callerMasked = "••••••4471",
            paymentAppForeground = true,
            paymentAppLabel = "PhonePe",
            pendingAmountPaise = 20_000_00L,     // ₹20,000 — under baseline, on purpose
            payeeIsNew = true,
            userP95AmountPaise = 25_000_00L,
            otpDuringCall = true,
            tactics = emptySet(),
            timestampMs = 0L
        )
    )

    /**
     * Forty minutes with family, mid-afternoon, no payment app, known number.
     * Must stay DORMANT. If this ever fires, stop and fix it before adding
     * anything else — a guard that interrupts ordinary life gets uninstalled,
     * and an uninstalled guard protects nobody.
     */
    val INNOCENT_LONG_CALL = Scenario(
        id = "innocent",
        title = "Long call with a known contact",
        expectation = "DORMANT — never fires",
        snapshot = SignalSnapshot(
            callActive = true,
            callerUnknown = false,
            callDurationSeconds = 41 * 60L,
            callerMasked = "••••••1029",
            timestampMs = 0L
        )
    )

    /**
     * A genuine bank call about a genuine transfer: unknown number, payment app
     * open, but no remote access, no OTP, no coercion language, an amount within
     * this user's normal range.
     *
     * Scores 30 against a WATCH line of 45, so Prahari stays completely silent.
     * This is the scenario that shows the thresholds are conservative rather
     * than tuned to fire.
     */
    val LEGITIMATE_BANK_CALL = Scenario(
        id = "bank_call",
        title = "Real bank calling about a real transfer",
        expectation = "DORMANT — 30 points, under the 45 WATCH line",
        snapshot = SignalSnapshot(
            callActive = true,
            callerUnknown = true,
            callDurationSeconds = 6 * 60L,
            callerMasked = "••••••8800",
            paymentAppForeground = true,
            paymentAppLabel = "iMobile Pay",
            pendingAmountPaise = 12_000_00L,
            payeeIsNew = false,
            userP95AmountPaise = 25_000_00L,
            timestampMs = 0L
        )
    )

    val ALL = listOf(
        DIGITAL_ARREST,
        BEHAVIOUR_ONLY,
        LEGITIMATE_BANK_CALL,
        INNOCENT_LONG_CALL
    )
}
