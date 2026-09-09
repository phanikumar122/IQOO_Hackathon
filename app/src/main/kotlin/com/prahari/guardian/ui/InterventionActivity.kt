package com.prahari.guardian.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.VibrationEffect
import android.os.VibratorManager
import android.view.View
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.prahari.guardian.R
import com.prahari.guardian.TrustedContact
import com.prahari.guardian.databinding.ActivityInterventionBinding
import com.prahari.guardian.model.Tactic
import com.prahari.guardian.risk.Factor
import com.prahari.guardian.risk.RiskAssessment
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

/**
 * The screen the whole project exists to show.
 *
 * Four design decisions, each one arguable and each one deliberate:
 *
 *  1. **It states observations, not a verdict.** "He told you to stay on the
 *     line and tell nobody" is checkable against the user's own memory of the
 *     last ten minutes. "Fraud risk: 87%" teaches a frightened person nothing
 *     and invites them to argue with the number.
 *
 *  2. **The escape hatch is never removed, only delayed.** [HOLD_SECONDS] of
 *     friction, then the user may proceed. Prahari has no authority to block
 *     someone from moving their own money, and an app that could would be a
 *     worse thing to install than the risk it prevents. The delay is the whole
 *     intervention: digital-arrest scams work by never letting the victim stop
 *     to think, so sixty enforced seconds of thinking is the countermeasure.
 *
 *  3. **The easy button is the safe one.** Hang up is large and primary.
 *     Proceeding requires waiting and then deliberately choosing.
 *
 *  4. **Calling someone you trust is offered explicitly**, because secrecy is
 *     the tactic that makes these scams work, and the counter to "tell nobody"
 *     is a one-tap way to tell somebody.
 */
class InterventionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInterventionBinding
    private var remainingSeconds = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInterventionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val totalHold = if (com.prahari.guardian.BuildConfig.DEMO_MODE_ENABLED) 5 else HOLD_SECONDS
        remainingSeconds = savedInstanceState?.getInt(KEY_REMAINING_SECONDS) ?: totalHold

        renderReasons(
            intent.getStringArrayListExtra(EXTRA_REASONS).orEmpty(),
            decodeTactics(intent.getStringArrayListExtra(EXTRA_TACTICS).orEmpty())
        )
        renderContextLine()
        binding.behaviourOnlyNote.visibility =
            if (intent.getBooleanExtra(EXTRA_BEHAVIOUR_ONLY, false)) View.VISIBLE else View.GONE
        buzzOnce()

        binding.hangUp.setOnClickListener {
            // We do not end the call for the user: `ANSWER_PHONE_CALLS` /
            // `MODIFY_PHONE_STATE` is not a road worth going down, and taking
            // the action away from the user is the wrong instinct here anyway.
            // Dismissing returns them to the dialer with the decision made.
            finish()
        }

        binding.callTrusted.setOnClickListener {
            // ACTION_DIAL, not ACTION_CALL: it needs no permission and it leaves
            // the final tap with the user, which is the pattern for this whole
            // screen. Pre-filled so a frightened person does not have to search.
            val number = TrustedContact.number(this)
            val intent = Intent(Intent.ACTION_DIAL).apply {
                if (!number.isNullOrBlank()) {
                    val sanitized = number.filter { it.isDigit() || it == '+' }
                    data = Uri.fromParts("tel", sanitized, null)
                }
            }
            runCatching { startActivity(intent) }
            finish()
        }

        binding.callTrusted.text = TrustedContact.name(this)
            ?.let { getString(R.string.action_call_named, it) }
            ?: getString(R.string.action_call_someone)

        if (remainingSeconds <= 0) {
            binding.proceed.isEnabled = true
            binding.proceed.text = getString(R.string.proceed_now)
            binding.proceed.setOnClickListener { finish() }
        } else {
            binding.proceed.isEnabled = false
            startHold()
        }

        // Back must not dismiss. The one moment where removing the reflex exit
        // is correct — the user can still proceed, just not without choosing to.
        onBackPressedDispatcher.addCallback(this) { /* swallow */ }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_REMAINING_SECONDS, remainingSeconds)
    }

    private fun startHold() = lifecycleScope.launch {
        while (remainingSeconds > 0) {
            binding.proceed.text = getString(R.string.proceed_waiting, remainingSeconds)
            delay(1_000)
            remainingSeconds -= 1
        }
        binding.proceed.text = getString(R.string.proceed_now)
        binding.proceed.isEnabled = true
        binding.proceed.setOnClickListener { finish() }
    }

    /**
     * One line per reason, ordered by weight — the heaviest signal first, so the
     * most convincing sentence is the one the user reads first.
     *
     * The coercion-language reason gets special treatment: it expands into the
     * individual tactics underneath it. "The caller used pressure tactics" is a
     * category; "They threatened you with arrest" and "They told you not to tell
     * anyone" are the user's own last ten minutes read back to them, which is the
     * thing that actually breaks the spell. Indented and slightly dimmer, so the
     * behavioural reasons still lead.
     */
    private fun renderReasons(encoded: List<String>, tactics: List<Tactic>) {
        binding.reasonList.removeAllViews()
        encoded.forEach { line ->
            val parts = line.split(FIELD_SEP)
            val factor = runCatching { Factor.valueOf(parts[0]) }.getOrNull() ?: return@forEach
            val detail = parts.getOrNull(2)?.takeIf { it.isNotBlank() }
            val view = TextView(this).apply {
                text = "•  " + ReasonText.forFactor(context, factor, detail)
                setTextAppearance(R.style.TextAppearance_Prahari_Reason)
                setPadding(0, 12, 0, 12)
            }
            binding.reasonList.addView(view)

            if (factor == Factor.COERCION_LANGUAGE) {
                tactics.forEach { tactic ->
                    binding.reasonList.addView(
                        TextView(this).apply {
                            text = "–  " + ReasonText.forTactic(context, tactic)
                            setTextAppearance(R.style.TextAppearance_Prahari_Tactic)
                            setPadding(36, 6, 0, 6)
                        }
                    )
                }
            }
        }
    }

    /**
     * Unknown names are dropped rather than crashing the screen. If a future
     * build adds a tactic and an old activity is somehow still in the back stack,
     * the user should see one fewer line, not a stack trace at the worst possible
     * moment.
     */
    private fun decodeTactics(names: List<String>): List<Tactic> =
        names.mapNotNull { name -> runCatching { Tactic.valueOf(name) }.getOrNull() }

    /**
     * Caller, call length and amount, joined into one line, with any piece we do
     * not have simply left out.
     *
     * The point is verifiability. A frightened person deciding whether to trust
     * this screen checks it against what they already know: if it says eleven
     * minutes and they have been talking for eleven minutes, and it names the
     * amount they just typed, then the sentences underneath are worth reading.
     * A screen that could have been shown to anyone gets dismissed.
     */
    private fun renderContextLine() {
        val caller = intent.getStringExtra(EXTRA_CALLER).orEmpty()
        val seconds = intent.getLongExtra(EXTRA_DURATION_SECONDS, 0L)
        val paise = intent.getLongExtra(EXTRA_AMOUNT_PAISE, -1L)

        val parts = buildList {
            if (caller.isNotBlank()) {
                add(getString(R.string.context_caller, caller))
            } else {
                add(getString(R.string.context_caller_unknown))
            }
            if (seconds >= 60) add(getString(R.string.context_duration, seconds / 60))
            if (paise > 0) add(getString(R.string.context_amount, formatRupees(paise)))
        }
        binding.contextLine.text = parts.joinToString("  ·  ")
        binding.contextLine.visibility = View.VISIBLE
    }

    /**
     * One short buzz. The phone is against an ear, not in front of a face — a
     * silent full-screen activity is a warning the user may never see. Once, and
     * short: this screen is trying to interrupt a panic, not add to it.
     */
    private fun buzzOnce() {
        runCatching {
            getSystemService(VibratorManager::class.java)
                ?.defaultVibrator
                ?.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    /**
     * Indian digit grouping, always: ₹1,20,000, not ₹120,000. Paise are dropped —
     * nobody reading a warning in a hurry needs two decimal places.
     */
    private fun formatRupees(paise: Long): String =
        NumberFormat.getIntegerInstance(INDIA).format(paise / 100)

    companion object {
        /**
         * Sixty seconds. Long enough to break the spell, short enough that a
         * genuine urgent transfer is only inconvenienced. Tune it if user
         * testing says otherwise — but do not remove it, and do not make the
         * proceed button vanish instead. Delay, never deny.
         */
        const val HOLD_SECONDS = 60

        private const val EXTRA_REASONS = "reasons"
        private const val EXTRA_TACTICS = "tactics"
        private const val EXTRA_BEHAVIOUR_ONLY = "behaviour_only"
        private const val EXTRA_CALLER = "caller_masked"
        private const val EXTRA_DURATION_SECONDS = "duration_seconds"
        private const val EXTRA_AMOUNT_PAISE = "amount_paise"
        private const val KEY_REMAINING_SECONDS = "remaining_seconds"
        private const val FIELD_SEP = "|"

        /** `forLanguageTag` rather than the Locale constructor: same result, and
         *  it does not go through the constructors newer JDKs deprecate. */
        private val INDIA: Locale = Locale.forLanguageTag("en-IN")

        /**
         * Reasons travel as flat strings rather than as a Parcelable.
         *
         * A `Parcelable` [RiskAssessment] would drag the model layer into the
         * Android build and buy nothing — the activity needs eight labels, not
         * an object graph.
         *
         * Tactics travel as enum names, which is a closed ten-word vocabulary the
         * screen is about to print in full anyway. That is the line: labels yes,
         * transcript no.
         *
         * The three context extras are the exception: they are scalars, and the
         * screen is much more convincing when it can name the call it is about.
         * Note what is *not* passed: no transcript, not a word of what was said.
         * The intent is handed to the system, and a transcript in an Intent extra
         * is a transcript in a system log.
         */
        fun intentFor(context: Context, assessment: RiskAssessment): Intent {
            val reasons = ArrayList(assessment.reasons.map {
                listOf(it.factor.name, it.points.toString(), it.detail ?: "")
                    .joinToString(FIELD_SEP)
            })
            val tactics = ArrayList(assessment.tactics.map { it.name })
            val s = assessment.snapshot
            return Intent(context, InterventionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putStringArrayListExtra(EXTRA_REASONS, reasons)
                .putStringArrayListExtra(EXTRA_TACTICS, tactics)
                .putExtra(EXTRA_BEHAVIOUR_ONLY, assessment.behaviourAloneSufficient)
                .putExtra(EXTRA_CALLER, s.callerMasked)
                .putExtra(EXTRA_DURATION_SECONDS, s.callDurationSeconds)
                .putExtra(EXTRA_AMOUNT_PAISE, s.pendingAmountPaise ?: -1L)
        }
    }
}


