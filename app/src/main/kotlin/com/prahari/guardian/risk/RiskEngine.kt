package com.prahari.guardian.risk

import com.prahari.guardian.model.SignalSnapshot
import com.prahari.guardian.model.Tactic

/**
 * Transparent weighted scoring. Deliberately NOT a learned model.
 *
 * Three reasons, and say all three if a judge asks why it is not "real ML":
 *  1. You can explain it in ten seconds.
 *  2. You can retune a threshold live when the demo misbehaves.
 *  3. With a forty-clip labelled set, a trained classifier would overfit and
 *     you would have no honest way to report accuracy.
 *
 * Swap in a trained classifier only if the final Green Light block is quiet.
 *
 * Pure Kotlin. No Android imports. Unit-test this on the JVM.
 */
object RiskEngine {

    // ---- Arming gate ------------------------------------------------------
    // Below this, Prahari does nothing at all: no scoring, no audio capture,
    // no model in memory. This gate is what makes the battery answer easy and
    // keeps the false-positive rate near zero. It is the most important four
    // lines in the project.
    const val ARM_MIN_CALL_SECONDS = 180L

    // ---- Weights ----------------------------------------------------------
    const val W_REMOTE_ACCESS = 35
    const val W_PAYMENT_FOREGROUND = 30
    const val W_SCREEN_SHARE = 25
    const val W_OTP_DURING_CALL = 20
    const val W_NEW_PAYEE = 15
    const val W_AMOUNT_ANOMALY = 15
    const val W_LONG_CALL = 10
    const val W_PER_TACTIC = 10
    const val W_TACTIC_CAP = 40

    // ---- Thresholds -------------------------------------------------------
    const val T_WATCH = 45
    const val T_WARN = 65
    const val T_INTERVENE = 85

    fun assess(s: SignalSnapshot): RiskAssessment {
        if (!isArmed(s)) return RiskAssessment.dormant(s)

        val reasons = mutableListOf<RiskReason>()

        if (s.remoteAccessAppActive) {
            reasons += RiskReason(
                Factor.REMOTE_ACCESS_APP, W_REMOTE_ACCESS,
                s.remoteAccessAppLabel ?: "a remote-access app"
            )
        }
        if (s.paymentAppForeground) {
            reasons += RiskReason(
                Factor.PAYMENT_APP_OPEN, W_PAYMENT_FOREGROUND,
                s.paymentAppLabel ?: "a payment app"
            )
        }
        if (s.screenShareActive) {
            reasons += RiskReason(Factor.SCREEN_SHARED, W_SCREEN_SHARE)
        }
        if (s.otpDuringCall) {
            reasons += RiskReason(Factor.OTP_DURING_CALL, W_OTP_DURING_CALL)
        }
        // Note the `== true`: null means "we could not tell", which must not
        // be scored as safe. Kotlin's Boolean? makes that explicit — keep it.
        if (s.payeeIsNew == true) {
            reasons += RiskReason(Factor.NEW_PAYEE, W_NEW_PAYEE)
        }
        if (s.amountExceedsBaseline) {
            reasons += RiskReason(Factor.AMOUNT_ANOMALY, W_AMOUNT_ANOMALY)
        }
        if (s.callLong) {
            reasons += RiskReason(Factor.LONG_CALL, W_LONG_CALL)
        }

        val tacticPoints = minOf(s.tactics.size * W_PER_TACTIC, W_TACTIC_CAP)
        if (tacticPoints > 0) {
            reasons += RiskReason(
                Factor.COERCION_LANGUAGE, tacticPoints,
                s.tactics.joinToString(", ") { it.wireName }
            )
        }

        val score = reasons.sumOf { it.points }
        return RiskAssessment(
            score = score,
            level = levelFor(score),
            reasons = reasons.sortedByDescending { it.points },
            tactics = s.tactics,
            armed = true,
            snapshot = s
        )
    }

    fun isArmed(s: SignalSnapshot): Boolean =
        s.callActive && s.callerUnknown && s.callDurationSeconds > ARM_MIN_CALL_SECONDS

    fun levelFor(score: Int): RiskLevel = when {
        score >= T_INTERVENE -> RiskLevel.INTERVENE
        score >= T_WARN -> RiskLevel.WARN
        score >= T_WATCH -> RiskLevel.WATCH
        else -> RiskLevel.DORMANT
    }

    /**
     * Would this snapshot still act if the language model contributed nothing?
     *
     * This exists so the UI (and you, on stage) can prove that detection does
     * not depend on the SLM. Call it in the demo. It is the graceful-degradation
     * argument made checkable.
     */
    fun behaviouralOnlyScore(s: SignalSnapshot): Int =
        assess(s.copy(tactics = emptySet<Tactic>())).score
}
