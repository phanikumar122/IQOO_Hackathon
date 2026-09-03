package com.prahari.guardian.inference

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import com.prahari.guardian.model.Tactic
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tier 2 proper: a ~1B instruction-tuned model, int4, resident on the phone.
 *
 * Four design decisions worth defending out loud, because each one is a place
 * teams usually go wrong:
 *
 *  1. **The model classifies; it never writes to the user.** Output is a label
 *     set, matched against [Tactic]. Every word a frightened person reads comes
 *     from `strings.xml`, reviewed by a human. A 1B model asked to write calm
 *     safety copy in Hinglish under load will eventually produce something
 *     wrong, and "the model said it" is not a defence when someone loses money.
 *
 *  2. **It is never on the critical path.** If this returns nothing, behaviour
 *     alone still reaches WARN. See
 *     [RiskEngine.behaviouralOnlyScore][com.prahari.guardian.risk.RiskEngine.behaviouralOnlyScore].
 *
 *  3. **The model file is not in the APK.** It is `adb push`-ed to app-external
 *     storage. A ~550 MB asset makes the build slow, the install slower, and
 *     turns "one more Gradle sync" into a ten-minute wait at hour 26.
 *
 *  4. **It is not loaded until the guard arms.** [warmUp] is called on the
 *     arming edge, not at service start, and [release] runs on disarm. This is
 *     what makes "no model in memory while dormant" a true statement about the
 *     process rather than a description of intent. The cost is a few seconds of
 *     load latency at the 180-second mark; the benefit is that the battery and
 *     privacy answers are both checkable with `dumpsys meminfo`.
 *
 * Push it before the event:
 * ```
 * adb push gemma3-1b-it-int4.task /sdcard/Android/data/com.prahari.guardian/files/
 * ```
 */
class LlmTacticClassifier(
    private val context: Context,
    private val modelFileName: String = DEFAULT_MODEL_FILE
) : TacticClassifier {

    override val engineName: String get() = "slm:${modelFileName.substringBefore('.')}"

    @Volatile
    private var engine: LlmInference? = null

    @Volatile
    private var ready = false
    override val isReady: Boolean get() = ready

    /** True while a decode is running. See the note in [classify]. */
    private val busy = AtomicBoolean(false)

    /**
     * One thread, owned by this object, for every native call.
     *
     * Not `Dispatchers.Default`: a blocking JNI decode parked on a shared pool
     * thread competes with everything else that pool is for, and a stalled one
     * cannot be taken back. On a private thread a stall is contained — it costs
     * us the language signal and nothing else.
     */
    private val inferenceDispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "prahari-slm") }
            .asCoroutineDispatcher()

    /** Absolute path of the pushed model, or null if it is not on the device. */
    fun modelPathOrNull(): String? =
        File(context.getExternalFilesDir(null), modelFileName)
            .takeIf { it.isFile && it.length() > 0L }
            ?.absolutePath

    /**
     * Loads the model and runs one throwaway inference.
     *
     * The warm-up call is not optional. First-token latency on a cold graph is
     * several times steady state, and you do not want that penalty landing on
     * the one inference the judges are watching.
     *
     * @return false on any failure. Callers must fall back, not crash — this is
     *         the hour-3 gate in code.
     */
    override suspend fun warmUp(): Boolean = withContext(inferenceDispatcher) {
        val path = modelPathOrNull() ?: run {
            Log.w(TAG, "Model not found at ${context.getExternalFilesDir(null)}/$modelFileName")
            return@withContext false
        }
        runCatching {
            val options = LlmInferenceOptions.builder()
                .setModelPath(path)
                .setMaxTokens(MAX_TOKENS)
                .setMaxTopK(TOP_K)
                .build()
            val created = LlmInference.createFromOptions(context, options)
            // Throwaway pass: pays the graph-build cost now, not on stage.
            created.generateResponse(buildPrompt("hello"))
            engine = created
            ready = true
            Log.i(TAG, "SLM ready: $path")
            true
        }.getOrElse { t ->
            // Most likely causes, in the order you should check them:
            // wrong .task conversion, insufficient RAM, or an ABI mismatch.
            Log.e(TAG, "SLM load failed — falling back to keywords", t)
            ready = false
            false
        }
    }

    override suspend fun classify(transcriptWindow: String): Set<Tactic> {
        val e = engine ?: return emptySet()
        if (transcriptWindow.isBlank()) return emptySet()

        // In-flight guard. Read the timeout note below for why this, and not
        // withTimeoutOrNull alone, is what actually protects the pipeline.
        if (!busy.compareAndSet(false, true)) {
            Log.w(TAG, "previous inference still running — skipping this window")
            return emptySet()
        }

        val raw = try {
            withContext(inferenceDispatcher) {
                // HONEST NOTE ON THIS TIMEOUT. `withTimeoutOrNull` cancels a
                // coroutine at suspension points, and `generateResponse` is a
                // single blocking JNI call with none. So this does NOT interrupt
                // a stalled decode — it stops us *waiting* on one. The stall
                // keeps the dedicated thread until the native call returns.
                //
                // That is why there are two other mechanisms here, and why the
                // combination is the real guarantee: inference runs on its own
                // single thread, so a stall cannot block the transcription
                // collector or the UI; and `busy` means a stalled call is skipped
                // rather than queued behind. Worst case is that the language
                // signal goes quiet, which is the documented degradation path —
                // behaviour alone still scores.
                withTimeoutOrNull(INFERENCE_TIMEOUT_MS) {
                    runCatching { e.generateResponse(buildPrompt(transcriptWindow)) }
                        .onFailure { Log.e(TAG, "inference failed", it) }
                        .getOrNull()
                }
            } ?: return emptySet()
        } finally {
            busy.set(false)
        }

        return parseTactics(raw)
    }

    override fun release() {
        runCatching { engine?.close() }
        engine = null
        ready = false
        // The executor is ours, so closing it is ours too. Skipping this leaks a
        // thread per load, which you will not notice in a demo and will notice in
        // a day of use.
        runCatching { inferenceDispatcher.close() }
    }

    private fun buildPrompt(window: String): String = """
        You are a classifier. Read the phone call transcript below and decide
        which coercion tactics appear in it.

        Reply with ONLY a comma-separated list of labels from this exact list:
        ${Tactic.entries.joinToString(", ") { it.wireName }}

        If none apply, reply with the single word: none
        Do not explain. Do not add any other words.

        Transcript:
        ${window.take(MAX_WINDOW_CHARS)}

        Labels:
    """.trimIndent()

    /**
     * Tolerant on purpose.
     *
     * A 1B model will sometimes wrap the list in prose, add a label you never
     * defined, or return an empty string. Split on anything non-label-ish, map
     * through [Tactic.fromWire] (which drops unknowns), and move on.
     */
    private fun parseTactics(raw: String): Set<Tactic> {
        val body = raw.substringAfterLast("Labels:").ifBlank { raw }
        if (body.contains("none", ignoreCase = true) && !body.contains('_')) return emptySet()
        return Tactic.parseAll(body.split(',', '\n', ';', '[', ']', '"').map { it.trim() })
    }

    // One companion object — Kotlin allows exactly one — with the tuning
    // constants private and the two things the rest of the app needs public.
    companion object {
        private const val TAG = "PrahariSLM"

        /** Short by design: we want ~10 labels, not paragraphs. */
        private const val MAX_TOKENS = 256
        private const val TOP_K = 40
        private const val MAX_WINDOW_CHARS = 1600
        private const val INFERENCE_TIMEOUT_MS = 6_000L

        /**
         * Any MediaPipe-compatible `.task` bundle works. Verify the exact
         * filename and the current recommended build on the phone during the
         * pre-event checklist — do not assume this string is correct.
         */
        const val DEFAULT_MODEL_FILE = "gemma3-1b-it-int4.task"

        /**
         * Does the model file exist? A stat call, nothing more.
         *
         * This is how the UI can say "SLM available, loads when armed" without
         * loading half a gigabyte to find out. Keeping the *check* and the *load*
         * separate is what lets the dormancy claim be literally true rather than
         * approximately true.
         */
        fun isModelPresent(
            context: Context,
            modelFileName: String = DEFAULT_MODEL_FILE
        ): Boolean = File(context.getExternalFilesDir(null), modelFileName)
            .let { it.isFile && it.length() > 0L }
    }
}
