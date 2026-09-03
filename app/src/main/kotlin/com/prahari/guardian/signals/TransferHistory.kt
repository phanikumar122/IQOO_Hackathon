package com.prahari.guardian.signals

import android.content.Context
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.random.Random

/**
 * The user's own transfer baseline, learned on the device.
 *
 * Two of the eight behavioural weights need history to mean anything:
 * `amountExceedsBaseline` needs to know what a normal transfer looks like for
 * *this* person, and `payeeIsNew` needs to know who they have paid before.
 * Without a baseline both are dead weight — and a scoring engine with dead
 * weights is worse than one that never claimed to have them.
 *
 * **How the baseline is built.** Every payment notification that arrives while
 * no call is in progress is recorded here. That is the whole trick: ordinary
 * use teaches the app what ordinary looks like, and the transfers that happen
 * *during* an unknown call — the ones we care about — are scored against it
 * rather than folded into it. A scammed transfer never becomes part of the
 * user's normal.
 *
 * **What is stored, precisely.** A list of amounts in paise, and a list of
 * truncated salted hashes of payee names. Not the names. The salt is random per
 * install and never leaves the device, so the stored digests are useless to
 * anyone who obtains the file, including us. This matters because it is the one
 * thing in the app that touches disk at all.
 *
 * **Why p95 and not mean.** Indian household payments are wildly skewed — a
 * hundred ₹40 autorickshaw payments and one ₹60,000 school fee. A mean would
 * flag the school fee every term; the 95th percentile treats it as the normal
 * upper end of this person's life, which is what it is.
 */
object TransferHistory {

    private const val PREFS = "prahari_history"
    private const val KEY_SALT = "salt"
    private const val KEY_AMOUNTS = "amounts"
    private const val KEY_PAYEES = "payees"

    /** Keep the window short. Someone's spending in 2024 is not evidence now. */
    private const val MAX_AMOUNTS = 120
    private const val MAX_PAYEES = 200

    /**
     * Below this, [p95Paise] returns null and the amount signal scores zero.
     *
     * A 95th percentile computed from four samples is a number with no meaning
     * attached, and shipping one would put 15 points behind a coin flip. Twelve
     * is still small, but it is enough for the shape of the distribution to be
     * real. The engine never invents evidence; neither does this.
     */
    private const val MIN_SAMPLES = 12

    private const val HASH_CHARS = 12

    // ---- Writes ------------------------------------------------------------

    /**
     * Records one ordinary transfer. Call this only when no call is active —
     * see the class note on why.
     */
    fun record(context: Context, paise: Long, payeeName: String?) {
        if (paise <= 0) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val amounts = (readLongs(prefs.getString(KEY_AMOUNTS, null)) + paise)
            .takeLast(MAX_AMOUNTS)
        val editor = prefs.edit().putString(KEY_AMOUNTS, amounts.joinToString(","))

        val key = payeeKey(context, payeeName)
        if (key != null) {
            val payees = (readStrings(prefs.getString(KEY_PAYEES, null)) + key)
                .distinct()
                .takeLast(MAX_PAYEES)
            editor.putString(KEY_PAYEES, payees.joinToString(","))
        }
        editor.apply()
    }

    // ---- Reads -------------------------------------------------------------

    /**
     * The 95th-percentile transfer size, or null when there is not enough
     * history to answer honestly.
     *
     * Null propagates all the way through: `SignalSnapshot.amountExceedsBaseline`
     * returns false when either side is null, so a fresh install simply does not
     * use this signal. It does not guess, and it does not default to suspicious.
     */
    fun p95Paise(context: Context): Long? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val amounts = readLongs(prefs.getString(KEY_AMOUNTS, null)).sorted()
        if (amounts.size < MIN_SAMPLES) return null
        val index = (ceil(0.95 * amounts.size).toInt() - 1).coerceIn(0, amounts.size - 1)
        return amounts[index]
    }

    /**
     * True when this payee is not in the local history, false when they are,
     * null when we cannot tell — no usable name, or no history to compare
     * against.
     *
     * Null is a real answer here and is scored identically to a known payee.
     * "We have never seen this person" and "we have no idea" are different
     * claims and the engine is not allowed to confuse them.
     */
    fun isNewPayee(context: Context, payeeName: String?): Boolean? {
        val key = payeeKey(context, payeeName) ?: return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val payees = readStrings(prefs.getString(KEY_PAYEES, null))
        if (payees.isEmpty()) return null
        return key !in payees
    }

    fun sampleCount(context: Context): Int =
        readLongs(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_AMOUNTS, null)).size

    /** For the demo: seeds a plausible baseline so the signals are live on a
     *  freshly flashed phone. Real use needs no seeding. */
    fun seedForDemo(context: Context) {
        val everyday = listOf(
            40L, 120L, 250L, 60L, 899L, 1_500L, 320L, 75L, 2_400L, 180L,
            650L, 45L, 12_000L, 210L, 90L, 3_500L, 25_000L, 140L, 500L, 70L
        ).map { it * 100 }
        everyday.forEach { record(context, it, null) }
        listOf("Amma", "Landlord", "Airtel Prepaid", "BESCOM", "Ravi Kumar")
            .forEach { record(context, 100_00L, it) }
    }

    // ---- Internals ---------------------------------------------------------

    /**
     * Salted, truncated SHA-256 of the normalised name.
     *
     * Truncation to 48 bits is deliberate and is a *feature*: it makes the
     * stored value useless for confirming a guess about who someone pays, while
     * remaining more than sufficient to match against a list of a few hundred.
     * A collision would mean one payee reads as familiar when they are not,
     * which costs 15 points in one direction — the safe direction.
     */
    private fun payeeKey(context: Context, payeeName: String?): String? {
        val normalised = payeeName
            ?.lowercase()
            ?.filter { it.isLetter() || it.isWhitespace() }
            ?.trim()
            ?.replace(Regex("\\s+"), " ")
            ?: return null
        if (normalised.length < 3) return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((salt(context) + normalised).toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(HASH_CHARS)
    }

    private fun salt(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_SALT, null)?.let { return it }
        val fresh = (1..16).joinToString("") { "%02x".format(Random.nextInt(256)) }
        prefs.edit().putString(KEY_SALT, fresh).apply()
        return fresh
    }

    private fun readLongs(raw: String?): List<Long> =
        raw?.split(",")?.mapNotNull { it.trim().toLongOrNull() } ?: emptyList()

    private fun readStrings(raw: String?): List<String> =
        raw?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
}
