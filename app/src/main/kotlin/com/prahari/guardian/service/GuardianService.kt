package com.prahari.guardian.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.prahari.guardian.BuildConfig
import com.prahari.guardian.R
import com.prahari.guardian.inference.KeywordTacticClassifier
import com.prahari.guardian.inference.LlmTacticClassifier
import com.prahari.guardian.inference.StreamingTranscriber
import com.prahari.guardian.inference.TacticClassifier
import com.prahari.guardian.model.SignalSnapshot
import com.prahari.guardian.risk.RiskAssessment
import com.prahari.guardian.risk.RiskEngine
import com.prahari.guardian.risk.RiskLevel
import com.prahari.guardian.signals.CallStateMonitor
import com.prahari.guardian.signals.ForegroundAppPoller
import com.prahari.guardian.signals.RemoteAccessWatcher
import com.prahari.guardian.signals.SignalStore
import com.prahari.guardian.ui.InterventionActivity
import com.prahari.guardian.ui.ReasonText
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The orchestrator. Everything else in the project is a leaf hanging off this.
 *
 * The shape that matters:
 *
 * ```
 * collectors ──▶ SignalStore ──▶ RiskEngine.assess ──▶ chip / heads-up / overlay
 *                    ▲
 *                    │  (only while armed)
 *              ASR ──┴── TacticClassifier
 * ```
 *
 * **The arming gate is the whole design.** Below 180 seconds on an unknown
 * number, no microphone is opened, no model is resident, and no scoring runs.
 * That single condition is what lets you answer the battery question in one
 * sentence and what keeps the false-positive rate near zero, because the
 * overwhelming majority of calls never reach it.
 *
 * When a judge asks what this costs when idle, the answer is: a foreground
 * notification and a call-state callback. Nothing else is running — and that is
 * a checkable claim, not a slogan. [classifier] is null until [observeArming]
 * sees the arming edge, and it is released on disarm, so
 * `dumpsys meminfo com.prahari.guardian` during an ordinary call shows no model
 * mapped. An earlier version of this file loaded the SLM in [onCreate], which
 * made that sentence false from the moment the user flipped the switch.
 *
 * Three levels, three degrees of interruption: WATCH updates the ongoing chip
 * silently, WARN posts a heads-up notification naming the top two reasons,
 * INTERVENE launches the full-screen hold. Nothing can escalate on language
 * alone — the engine's tactic cap (40) sits below the WATCH threshold (45)
 * precisely so this file cannot make that mistake.
 */
class GuardianService : LifecycleService() {

    private lateinit var callMonitor: CallStateMonitor
    private lateinit var transcriber: StreamingTranscriber
    private lateinit var foregroundPoller: ForegroundAppPoller

    /**
     * Null while dormant. Non-null only between the arming edge and disarm.
     *
     * This field *is* the dormancy claim. Do not "optimise" it by keeping the
     * instance alive between calls to save the load: a few seconds of latency at
     * the 180-second mark is a cheap price for being able to say truthfully that
     * nothing is loaded while nothing is happening.
     */
    private var classifier: TacticClassifier? = null

    private var audioJob: Job? = null
    private var lastInterventionAtMs = 0L

    /** Registered only while a call is active. See [registerRemoteWatcher]. */
    private val remoteAccessWatcher = RemoteAccessWatcher()
    private var remoteWatcherRegistered = false

    /** What the alert notification currently says, so it is not re-posted on
     *  every one-second tick. See [postAlert]. */
    private var lastAlertSignature: String? = null

    /** Rolling ~45 s of speech. In memory only. Never written to disk. */
    private val window = ArrayDeque<String>()

    companion object {
        private val _assessment =
            MutableStateFlow(RiskAssessment.dormant(SignalSnapshot()))

        /** The UI observes this. A service-owned StateFlow is enough here; the
         *  alternative is a repository layer we do not have time to justify. */
        val assessment: StateFlow<RiskAssessment> = _assessment.asStateFlow()

        private val _engineLabel = MutableStateFlow("off")

        /**
         * Which tactic engine is live right now.
         *
         * Reads `dormant · …` while nothing is loaded, which is both the honest
         * description and a useful demo tell: you can watch it flip to
         * `slm:gemma3-1b-it-int4` at the 180-second mark, on stage, as evidence
         * that the load really is deferred.
         */
        val engineLabel: StateFlow<String> = _engineLabel.asStateFlow()

        const val ACTION_STOP = "com.prahari.guardian.STOP"

        /** Silent, always-visible status chip. */
        private const val CHANNEL_STATUS = "prahari_guardian"

        /** A second channel, IMPORTANCE_HIGH, for WARN. One channel cannot be
         *  both silent-all-day and able to interrupt, and splitting them lets
         *  the user mute alerts without losing the chip that proves the service
         *  is alive. */
        private const val CHANNEL_ALERT = "prahari_alert"

        private const val NOTIFICATION_ID = 1001
        private const val ALERT_NOTIFICATION_ID = 1002
        private const val TAG = "PrahariService"

        /** Minimum gap between two full-screen interventions. Being shouted at
         *  twice in ten seconds makes a frightened person distrust the app. */
        private const val INTERVENTION_COOLDOWN_MS = 30_000L

        /** How often to wake the SLM while armed. Every utterance is wasteful;
         *  every 6 s is fast enough to beat a transfer. */
        private const val CLASSIFY_EVERY_MS = 6_000L

        private const val WINDOW_MAX_CHUNKS = 24

        fun start(context: Context) {
            context.startForegroundService(Intent(context, GuardianService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, GuardianService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        // On Android 14+ a `microphone` foreground service cannot be started
        // from the background, and it throws if RECORD_AUDIO is not already
        // granted. Both are satisfied because the only way in is the switch in
        // MainActivity, after the permission wizard. Do not add a BOOT_COMPLETED
        // auto-start without rethinking this.
        startForeground(NOTIFICATION_ID, buildNotification(RiskLevel.DORMANT, null))

        callMonitor = CallStateMonitor(this, lifecycleScope)
        transcriber = StreamingTranscriber(this)
        foregroundPoller = ForegroundAppPoller(this, lifecycleScope)
        callMonitor.start()

        // Note what is *not* here: no classifier load. See [classifier].
        _engineLabel.value = dormantEngineLabel()
        observeSignals()
        observeArming()
        observeCall()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // stopAudio() rather than a bare cancel: it also unloads the model and
        // clears the transcript window, and "turned it off" should mean all
        // three, not just the first.
        stopAudio()
        clearAlert()
        foregroundPoller.stop()
        unregisterRemoteWatcher()
        callMonitor.stop()
        _engineLabel.value = "off"
        super.onDestroy()
    }

    // ---- Tier 2 lifecycle -------------------------------------------------

    /**
     * Loads the tactic engine, once, on the arming edge.
     *
     * Try the SLM, fall back forever. Deliberately not retried: if the model did
     * not load the first time it will not load the fourth time, and a retry loop
     * mid-call burns RAM and attention you need elsewhere. The chosen engine's
     * name goes straight to [engineLabel] so you never have to guess which tier
     * is live.
     */
    private suspend fun ensureClassifier(): TacticClassifier {
        classifier?.let { return it }
        val chosen: TacticClassifier = if (LlmTacticClassifier.isModelPresent(this)) {
            val llm = LlmTacticClassifier(this)
            if (llm.warmUp()) {
                llm
            } else {
                llm.release()
                Log.w(TAG, "model present but would not load — keyword fallback")
                KeywordTacticClassifier()
            }
        } else {
            // Not an error, and not worth a warning dialog. A phone with no
            // pushed model still gets all of Tier 1 and a keyword Tier 2, which
            // is the documented degradation path rather than a broken install.
            Log.i(TAG, "no model file on device — keyword fallback")
            KeywordTacticClassifier()
        }
        classifier = chosen
        _engineLabel.value = chosen.engineName
        Log.i(TAG, "tactic engine = ${chosen.engineName}")
        return chosen
    }

    private fun releaseClassifier() {
        classifier?.let {
            Log.i(TAG, "releasing ${it.engineName}")
            it.release()
        }
        classifier = null
        _engineLabel.value = dormantEngineLabel()
    }

    /**
     * What the UI shows while nothing is loaded — and it distinguishes the two
     * reasons, because "the model was never pushed" is by far the most common
     * cause of a demo quietly running on keywords while you narrate an SLM.
     */
    private fun dormantEngineLabel(): String =
        if (LlmTacticClassifier.isModelPresent(this)) {
            "dormant · SLM loads when armed"
        } else {
            "dormant · keyword fallback (no model pushed)"
        }

    // ---- Observers --------------------------------------------------------

    /** Re-scores on every signal change and decides how loudly to speak. */
    private fun observeSignals() = lifecycleScope.launch {
        SignalStore.state.collect { snapshot ->
            val assessment = RiskEngine.assess(snapshot)
            _assessment.value = assessment
            notifyStatus(assessment)
            escalate(assessment)
        }
    }

    /**
     * Starts and stops the audio pipeline on the arming edge.
     *
     * `map { isArmed }.distinctUntilChanged()` rather than reacting to every
     * snapshot: the duration ticks once a second, and without this the mic would
     * be torn down and rebuilt sixty times a minute — and now that the model
     * loads here too, that would also mean sixty half-gigabyte loads.
     */
    private fun observeArming() = lifecycleScope.launch {
        SignalStore.state
            .map { RiskEngine.isArmed(it) }
            .distinctUntilChanged()
            .collect { armed -> if (armed) startAudio() else stopAudio() }
    }

    /**
     * The foreground-app poller runs for the whole call, not just while armed.
     *
     * It has to. It is the only collector that can see the user *leave* a payment
     * app, and by the time we arm at 180 s the payment screen may already have
     * been opened and closed. A `queryEvents` call every two seconds is cheap
     * enough to run for the length of a call and too wasteful to run all day,
     * which is exactly why it is tied to `callActive` and not to service start.
     *
     * The package-install watcher rides along on the same edge, for the same
     * reason: an install only means something if it happens during a call.
     */
    private fun observeCall() = lifecycleScope.launch {
        SignalStore.state
            .map { it.callActive }
            .distinctUntilChanged()
            .collect { active ->
                if (active) {
                    foregroundPoller.start()
                    registerRemoteWatcher()
                } else {
                    foregroundPoller.stop()
                    unregisterRemoteWatcher()
                }
            }
    }

    /**
     * Registers [RemoteAccessWatcher] at runtime, for the duration of the call.
     *
     * The manifest entry stays; this is the braces to its belt. Whether a
     * manifest-declared `ACTION_PACKAGE_ADDED` receiver reliably wakes a
     * background app on current Android — and on OriginOS in particular — is
     * something I could not confirm, and "AnyDesk was installed while you were
     * on this call" is too valuable a sentence to leave resting on an unverified
     * assumption. A context-registered receiver is not subject to the Android 8+
     * implicit-broadcast restriction at all, so registering it here removes the
     * question. `RECEIVER_NOT_EXPORTED` is correct: `PACKAGE_ADDED` comes from
     * the system, which is delivered regardless, and no other app has any
     * business sending us this.
     */
    private fun registerRemoteWatcher() {
        if (remoteWatcherRegistered) return
        val filter = IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply { addDataScheme("package") }
        runCatching {
            ContextCompat.registerReceiver(
                this, remoteAccessWatcher, filter, ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }.onSuccess { remoteWatcherRegistered = true }
            .onFailure { Log.e(TAG, "could not register package watcher", it) }
    }

    private fun unregisterRemoteWatcher() {
        if (!remoteWatcherRegistered) return
        remoteWatcherRegistered = false
        runCatching { unregisterReceiver(remoteAccessWatcher) }
    }

    // ---- Audio + Tier 2 ---------------------------------------------------

    private fun startAudio() {
        if (audioJob?.isActive == true) return
        Log.i(TAG, "ARMED — loading engine, opening mic")
        audioJob = lifecycleScope.launch {
            // The load happens *here*, at the 180-second mark. This one line is
            // the difference between the dormancy claim being true and being
            // aspirational, so keep it inside the armed job.
            val engine = ensureClassifier()
            var lastClassifyMs = 0L
            transcriber.transcribe().collect { chunk ->
                window.addLast(chunk)
                while (window.size > WINDOW_MAX_CHUNKS) window.removeFirst()

                val now = System.currentTimeMillis()
                if (now - lastClassifyMs < CLASSIFY_EVERY_MS) return@collect
                lastClassifyMs = now

                val text = window.joinToString(" ")
                val tactics = engine.classify(text)
                if (tactics.isNotEmpty()) SignalStore.onTacticsClassified(tactics, text)
            }
        }
    }

    private fun stopAudio() {
        if (audioJob == null && classifier == null) return
        Log.i(TAG, "DISARMED — closing mic, unloading engine, clearing window")
        audioJob?.cancel()
        audioJob = null
        transcriber.stop()
        releaseClassifier()
        // The transcript exists only while it is needed. Nothing to leak,
        // nothing to subpoena, nothing to explain.
        window.clear()
    }

    // ---- Escalation -------------------------------------------------------

    /**
     * One function decides how loudly to speak, so the whole escalation policy
     * is readable on one screen instead of scattered across the observers.
     *
     * WATCH is deliberately silent: the status chip has already changed, and
     * anything louder would train the user to ignore Prahari. A user who ignores
     * it is worse off than one who never installed it.
     */
    private fun escalate(assessment: RiskAssessment) {
        when {
            assessment.shouldIntervene -> {
                // Both, on purpose. If the overlay permission was revoked — and
                // OriginOS does revoke things after a reboot — the heads-up is
                // the only thing the user will see.
                postAlert(assessment)
                maybeIntervene(assessment)
            }
            assessment.level == RiskLevel.WARN -> postAlert(assessment)
            else -> clearAlert()
        }
    }

    /**
     * The middle rung: a heads-up notification naming the top two reasons.
     *
     * Two, not eight. A frightened person reads the first line and maybe the
     * second, and [RiskAssessment.reasons] is already ordered by weight, so the
     * two shown here are the two most convincing things Prahari noticed. The
     * full list is on the intervention screen if it comes to that.
     *
     * Re-posted only when the wording would actually change, and
     * `setOnlyAlertOnce` means such a change updates the text without buzzing
     * again. Without both guards this fires once a second for the rest of the
     * call, which is precisely how you teach someone to swipe your app away.
     */
    private fun postAlert(assessment: RiskAssessment) {
        val top = assessment.reasons.sortedByDescending { it.points }.take(2)
        if (top.isEmpty()) return
        val signature = "${assessment.level}:${top.joinToString(",") { it.factor.name }}"
        if (signature == lastAlertSignature) return
        lastAlertSignature = signature

        val body = top.joinToString(" ") { ReasonText.forFactor(this, it.factor, it.detail) }
        val title = getString(
            if (assessment.shouldIntervene) R.string.status_intervene
            else R.string.status_warn
        )
        // Tapping opens the same intervention screen the overlay would have
        // shown. FLAG_IMMUTABLE because nothing downstream needs to fill in
        // extras, and a mutable PendingIntent handed to the system is a hole for
        // no benefit.
        val tap = PendingIntent.getActivity(
            this,
            0,
            InterventionActivity.intentFor(this, assessment),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alert = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(ALERT_NOTIFICATION_ID, alert)
    }

    /** Called when the score drops back below WARN — the call recovered, or it
     *  ended. Leaving a stale "this call looks unsafe" on screen after a call
     *  that turned out fine is its own kind of false positive. */
    private fun clearAlert() {
        if (lastAlertSignature == null) return
        lastAlertSignature = null
        getSystemService(NotificationManager::class.java).cancel(ALERT_NOTIFICATION_ID)
    }

    /**
     * Launches the full-screen intervention.
     *
     * This works from the background because we hold `SYSTEM_ALERT_WINDOW`,
     * which exempts the app from the background activity-launch restriction.
     * That is load-bearing: the payment app is in the foreground at this exact
     * moment, which is the only moment worth interrupting.
     */
    private fun maybeIntervene(assessment: RiskAssessment) {
        val now = System.currentTimeMillis()
        if (now - lastInterventionAtMs < INTERVENTION_COOLDOWN_MS) return
        lastInterventionAtMs = now
        runCatching {
            startActivity(InterventionActivity.intentFor(this, assessment))
        }.onFailure { Log.e(TAG, "could not show intervention", it) }
    }

    // ---- Notifications ----------------------------------------------------

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STATUS,
                getString(R.string.channel_guardian),
                NotificationManager.IMPORTANCE_LOW // silent: the chip is status, not an alert
            ).apply { description = getString(R.string.channel_guardian_desc) }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERT,
                getString(R.string.channel_alert),
                NotificationManager.IMPORTANCE_HIGH // must be able to interrupt
            ).apply {
                description = getString(R.string.channel_alert_desc)
                enableVibration(true)
            }
        )
    }

    private fun notifyStatus(assessment: RiskAssessment) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(assessment.level, assessment))
    }

    private fun buildNotification(level: RiskLevel, assessment: RiskAssessment?): Notification {
        val title = when (level) {
            RiskLevel.DORMANT -> getString(R.string.status_dormant)
            RiskLevel.WATCH -> getString(R.string.status_watch)
            RiskLevel.WARN -> getString(R.string.status_warn)
            RiskLevel.INTERVENE -> getString(R.string.status_intervene)
        }
        // Engine label in the subtitle, read from the StateFlow rather than from
        // `classifier` — which is null while dormant, and "dormant · …" is the
        // more useful thing to show anyway.
        val body = buildString {
            append(_engineLabel.value)
            if (BuildConfig.DEMO_MODE_ENABLED && assessment != null && assessment.armed) {
                append(" · score ").append(assessment.score)
                append(" · behaviour-only ")
                append(RiskEngine.behaviouralOnlyScore(assessment.snapshot))
            }
        }
        return NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
