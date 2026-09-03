package com.prahari.guardian.inference

import com.prahari.guardian.model.Tactic

/**
 * The hour-3 fallback. Not the pitch, but the reason you still have a demo.
 *
 * Also genuinely useful as a Tier 1.5: it is free, instant, and can pre-gate
 * whether the SLM is worth waking at all.
 *
 * Be honest about its limits if you end up shipping this: it catches the
 * scripts you thought of, in the languages you wrote out, and nothing else.
 * That is exactly the weakness the SLM exists to fix, and saying so is a
 * better answer than pretending the keyword list is the plan.
 */
class KeywordTacticClassifier : TacticClassifier {

    override val engineName = "keyword-fallback"
    override val isReady = true
    override suspend fun warmUp() = true
    override fun release() = Unit

    override suspend fun classify(transcriptWindow: String): Set<Tactic> {
        val text = transcriptWindow.lowercase()
        return PATTERNS.filterValues { phrases ->
            phrases.any { it in text }
        }.keys
    }

    private companion object {
        // Romanised Hindi included deliberately — real scam calls code-switch
        // mid-sentence and pure-Devanagari matching misses most of it.
        val PATTERNS: Map<Tactic, List<String>> = mapOf(
            Tactic.AUTHORITY_CLAIM to listOf(
                "cbi", "narcotics", "cyber cell", "enforcement directorate",
                "police station", "inspector", "customs", "trai", "income tax",
                "main police", "hum police"
            ),
            Tactic.ARREST_THREAT to listOf(
                "arrest", "warrant", "non-bailable", "custody", "fir",
                "giraftar", "jail bhej"
            ),
            Tactic.STAY_ON_LINE to listOf(
                "stay on the line", "do not disconnect", "don't cut the call",
                "keep the video on", "line par rahiye", "call mat kaato"
            ),
            Tactic.SECRECY to listOf(
                "do not tell", "don't tell anyone", "confidential",
                "kisi ko mat batao", "family ko mat"
            ),
            Tactic.OTP_REQUEST to listOf(
                "otp", "one time password", "verification code", "code batao"
            ),
            Tactic.TRANSFER_TO_VERIFY to listOf(
                "verify your funds", "transfer to verify", "refundable",
                "government account", "rbi account", "paisa transfer kar"
            ),
            Tactic.ACCOUNT_FREEZE_THREAT to listOf(
                "account will be frozen", "account block", "seize your account",
                "account band ho"
            ),
            Tactic.URGENCY to listOf(
                "immediately", "within the next", "last warning", "right now",
                "turant", "abhi karo"
            ),
            Tactic.ISOLATION to listOf(
                "go to a room alone", "close the door", "akele", "kamre mein"
            ),
            Tactic.IMPERSONATION_OF_OFFICIAL to listOf(
                "badge number", "case id", "official notice", "supreme court"
            )
        )
    }
}
