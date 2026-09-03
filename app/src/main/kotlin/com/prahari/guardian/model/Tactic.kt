package com.prahari.guardian.model

/**
 * The closed list of coercion tactics the Tier 2 model classifies against.
 *
 * Closed on purpose. A 1B model is reliable at picking from a fixed list and
 * unreliable at inventing categories or writing calm safety copy. The model
 * classifies; our own reviewed strings do the talking.
 *
 * [wireName] is the literal token the model is asked to emit and the key the
 * reply is parsed back through. The prompt builds its own label list from
 * `entries`, so these strings and the prompt cannot drift apart — but keep them
 * snake_case and stable anyway, because a rename silently changes the contract
 * the model was tuned against.
 */
enum class Tactic(val wireName: String, val displayResKey: String) {
    AUTHORITY_CLAIM("authority_claim", "tactic_authority"),
    URGENCY("urgency", "tactic_urgency"),
    SECRECY("secrecy", "tactic_secrecy"),
    STAY_ON_LINE("stay_on_line", "tactic_stay_on_line"),
    OTP_REQUEST("otp_request", "tactic_otp"),
    ARREST_THREAT("arrest_threat", "tactic_arrest"),
    ACCOUNT_FREEZE_THREAT("account_freeze_threat", "tactic_freeze"),
    TRANSFER_TO_VERIFY("transfer_to_verify", "tactic_transfer_verify"),
    ISOLATION("isolation", "tactic_isolation"),
    IMPERSONATION_OF_OFFICIAL("impersonation_of_official", "tactic_impersonation");

    companion object {
        private val byWire = entries.associateBy { it.wireName }

        /** Unknown names are dropped rather than throwing — a 1B model will
         *  occasionally hallucinate a label and that must not crash the guard. */
        fun fromWire(name: String): Tactic? = byWire[name.trim().lowercase()]

        fun parseAll(names: Iterable<String>): Set<Tactic> =
            names.mapNotNull { fromWire(it) }.toSet()
    }
}
