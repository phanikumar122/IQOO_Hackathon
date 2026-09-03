package com.prahari.guardian.model

/**
 * The contract between the plumbing (A) and the inference (B).
 *
 * Ship this file in hour one, then both sides code against it independently.
 * A feeds it from real collectors; B feeds it fake values until ASR works.
 * Neither waits for the other. This is the single most important file for
 * keeping two people productive in parallel.
 *
 * Pure Kotlin on purpose — no Android imports anywhere in this package, so
 * the risk engine stays unit-testable on the JVM without an emulator.
 */
data class SignalSnapshot(

    // ---- Call context -----------------------------------------------------
    val callActive: Boolean = false,
    /** True when the number is in neither contacts nor the user's trusted list. */
    val callerUnknown: Boolean = false,
    val callDurationSeconds: Long = 0,
    /** Masked for display. Never log the full number. */
    val callerMasked: String = "",

    // ---- Payment context --------------------------------------------------
    /** A UPI or banking app is in the foreground right now. */
    val paymentAppForeground: Boolean = false,
    val paymentAppLabel: String? = null,
    /** Read from the accessibility node tree, or from notification text. */
    val pendingAmountPaise: Long? = null,
    /** Null when we could not determine it — treat null as "unknown", not "safe". */
    val payeeIsNew: Boolean? = null,
    /** This user's 95th-percentile transfer, learned locally over time. */
    val userP95AmountPaise: Long? = null,

    // ---- Attack-step context ----------------------------------------------
    /** A remote-access app was installed or opened during this call. */
    val remoteAccessAppActive: Boolean = false,
    val remoteAccessAppLabel: String? = null,
    /** Screen casting or sharing started during this call. */
    val screenShareActive: Boolean = false,
    /** An OTP-shaped SMS arrived while the call was connected. */
    val otpDuringCall: Boolean = false,

    // ---- Language context (Tier 2 output) ---------------------------------
    val tactics: Set<Tactic> = emptySet(),
    /** Rolling transcript window. Held in memory, never persisted. */
    val transcriptWindow: String = "",

    val timestampMs: Long = 0
) {
    val amountExceedsBaseline: Boolean
        get() {
            val amount = pendingAmountPaise ?: return false
            val p95 = userP95AmountPaise ?: return false
            return amount > p95
        }

    val callLong: Boolean get() = callDurationSeconds > 15 * 60
}
