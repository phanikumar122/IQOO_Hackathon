package com.prahari.guardian.risk

import com.prahari.guardian.model.SignalSnapshot
import com.prahari.guardian.model.Tactic

enum class RiskLevel {
    /**
     * Nothing worth telling the user about.
     *
     * Note what this does *not* mean. An armed call that scores below 45 is
     * DORMANT, and while armed the microphone is open and the classifier is
     * loaded — arming is decided by
     * [RiskEngine.isArmed], not by the level. DORMANT is a statement about the
     * user's screen, not about the process. `LEGITIMATE_BANK_CALL` in
     * [DemoScenarios][com.prahari.guardian.demo.DemoScenarios] is exactly this
     * case: listening, scoring 30, saying nothing.
     */
    DORMANT,
    /** Quiet status chip only. Do not interrupt. */
    WATCH,
    /** Heads-up notification naming the top two reasons. Dismissible. */
    WARN,
    /** Full-screen intervention with the 60-second hold. */
    INTERVENE
}

enum class Factor {
    REMOTE_ACCESS_APP,
    PAYMENT_APP_OPEN,
    SCREEN_SHARED,
    OTP_DURING_CALL,
    NEW_PAYEE,
    AMOUNT_ANOMALY,
    LONG_CALL,
    COERCION_LANGUAGE
}

/**
 * One thing that fired, and what it contributed.
 *
 * Keeping the reasons rather than only the total is what lets the UI say
 * "he told you to stay on the line and tell no one" instead of "risk: 87%".
 * A score teaches a frightened person nothing.
 */
data class RiskReason(
    val factor: Factor,
    val points: Int,
    val detail: String? = null
)

data class RiskAssessment(
    val score: Int,
    val level: RiskLevel,
    val reasons: List<RiskReason>,
    val tactics: Set<Tactic>,
    val armed: Boolean,
    val snapshot: SignalSnapshot
) {
    val shouldIntervene: Boolean get() = level == RiskLevel.INTERVENE
    val shouldWarn: Boolean get() = level >= RiskLevel.WARN

    /** True when behaviour alone was enough — i.e. the SLM was not load-bearing. */
    val behaviourAloneSufficient: Boolean
        get() = reasons.filter { it.factor != Factor.COERCION_LANGUAGE }
            .sumOf { it.points } >= RiskEngine.T_WARN

    companion object {
        fun dormant(s: SignalSnapshot) = RiskAssessment(
            score = 0,
            level = RiskLevel.DORMANT,
            reasons = emptyList(),
            tactics = emptySet(),
            armed = false,
            snapshot = s
        )
    }
}
