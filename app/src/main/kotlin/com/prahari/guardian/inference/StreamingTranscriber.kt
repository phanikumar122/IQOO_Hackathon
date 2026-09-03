package com.prahari.guardian.inference

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File

/**
 * Tier 1 input: streaming offline speech-to-text.
 *
 * **The honest part of the demo, and you must say it out loud.** Android does
 * not give a third-party app the far-end audio of a phone call. The `VOICE_CALL`
 * audio source requires `CAPTURE_AUDIO_OUTPUT`, which is signature/privileged —
 * available to a pre-installed OriginOS component, not to an APK you sideload.
 * So this reads the microphone, and the scam side of the conversation reaches it
 * from a second phone playing the recording on speaker.
 *
 * Every layer above this line is the real thing. Only the audio tap is
 * substituted, and the substitution is precisely the reason the product has to
 * ship inside the OS. Lead with that instead of letting a judge find it.
 *
 * Vosk over Whisper here because it streams: partial results arrive while the
 * sentence is still being spoken, which is what you need when the goal is to
 * interrupt before the money moves rather than to produce a good transcript.
 *
 * Model setup (do this at home, not at the venue):
 * ```
 * adb push vosk-model-small-en-in-0.4 \
 *   /sdcard/Android/data/com.prahari.guardian/files/vosk-model-small-en-in
 * ```
 */
class StreamingTranscriber(private val context: Context) {

    private var model: Model? = null
    private var speech: SpeechService? = null

    val isModelPresent: Boolean get() = modelDir()?.isDirectory == true

    private fun modelDir(): File? =
        File(context.getExternalFilesDir(null), MODEL_DIR_NAME).takeIf { it.exists() }

    /**
     * Emits recognised text as it arrives. Partial hypotheses are included:
     * "he told me not to tell anyone" is worth acting on before the sentence is
     * finalised.
     *
     * Cold-flow by design — collection starts the mic, cancellation stops it.
     * That means the arming gate in the service can turn audio capture off
     * simply by cancelling the collecting coroutine, and no microphone stays
     * open by accident. That property is the entire battery answer.
     */
    fun transcribe(): Flow<String> = callbackFlow {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "RECORD_AUDIO not granted")
            close(); return@callbackFlow
        }
        val dir = modelDir() ?: run {
            Log.w(TAG, "Vosk model missing at $MODEL_DIR_NAME — push it first")
            close(); return@callbackFlow
        }

        val listener = object : RecognitionListener {
            override fun onPartialResult(hypothesis: String?) {
                textOf(hypothesis, "partial")?.let { trySend(it) }
            }

            override fun onResult(hypothesis: String?) {
                textOf(hypothesis, "text")?.let { trySend(it) }
            }

            override fun onFinalResult(hypothesis: String?) {
                textOf(hypothesis, "text")?.let { trySend(it) }
            }

            override fun onError(e: Exception?) {
                Log.e(TAG, "ASR error", e)
                close(e)
            }

            override fun onTimeout() = Unit
        }

        runCatching {
            val m = Model(dir.absolutePath)
            val rec = Recognizer(m, SAMPLE_RATE)
            val svc = SpeechService(rec, SAMPLE_RATE)
            svc.startListening(listener)
            model = m
            speech = svc
        }.onFailure {
            Log.e(TAG, "Vosk init failed", it)
            close(it); return@callbackFlow
        }

        awaitClose { stop() }
    }

    fun stop() {
        runCatching { speech?.stop() }
        runCatching { speech?.shutdown() }
        speech = null
        runCatching { model?.close() }
        model = null
    }

    private companion object {
        const val TAG = "PrahariAsr"
        const val MODEL_DIR_NAME = "vosk-model-small-en-in"
        const val SAMPLE_RATE = 16_000f

        fun textOf(hypothesis: String?, key: String): String? = runCatching {
            JSONObject(hypothesis ?: return null)
                .optString(key)
                .takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}
