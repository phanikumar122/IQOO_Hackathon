package com.prahari.guardian.inference

import com.prahari.guardian.model.Tactic

/**
 * Tier 2. Two implementations, same interface, chosen at runtime.
 *
 * This interface is the hour-3 gate made concrete: if [LlmTacticClassifier]
 * will not load on the physical phone, you swap in [KeywordTacticClassifier]
 * and everything downstream is untouched. Build both. Decide on device.
 */
interface TacticClassifier {
    /** Human-readable, for the "which engine am I running" chip in the UI. */
    val engineName: String

    /** True once the model is resident and has completed one warm-up inference. */
    val isReady: Boolean

    suspend fun warmUp(): Boolean

    /**
     * @param transcriptWindow the last ~45 seconds. Never the whole call —
     *        a 1B model degrades badly past a short window and latency matters
     *        more than completeness here.
     */
    suspend fun classify(transcriptWindow: String): Set<Tactic>

    fun release()
}
