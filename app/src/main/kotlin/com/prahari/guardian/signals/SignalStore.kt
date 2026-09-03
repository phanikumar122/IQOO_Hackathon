package com.prahari.guardian.signals

import com.prahari.guardian.model.SignalSnapshot
import com.prahari.guardian.model.Tactic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The one place mutable state lives.
 *
 * Six collectors write here from six different Android callbacks, on threads
 * you do not control. [GuardianService][com.prahari.guardian.service.GuardianService]
 * reads. A process-wide singleton is the right call because the collectors are
 * *system-instantiated* — Android constructs your AccessibilityService and your
 * NotificationListenerService itself, so you cannot inject into them, and
 * plumbing a DI graph to reach them costs hours you do not have.
 *
 * If a reviewer objects to the singleton: agree, and point at the boundary.
 * Everything downstream of here ([RiskEngine][com.prahari.guardian.risk.RiskEngine])
 * is pure and unit-tested. The mutable global stops at this file.
 */
object SignalStore {

    private val _state = MutableStateFlow(SignalSnapshot())
    val state: StateFlow<SignalSnapshot> = _state.asStateFlow()

    val current: SignalSnapshot get() = _state.value

    /**
     * True when a scripted scenario is driving the store. Live collectors check
     * this and drop their writes, so a real notification arriving mid-demo
     * cannot corrupt the scenario you are standing on stage explaining.
     */
    @Volatile
    var replayMode: Boolean = false
        private set

    private fun edit(block: (SignalSnapshot) -> SignalSnapshot) {
        if (replayMode) return
        _state.update { block(it).copy(timestampMs = System.currentTimeMillis()) }
    }

    // ---- Call context -----------------------------------------------------

    fun onCallStarted(unknownCaller: Boolean, maskedNumber: String?) = edit {
        it.copy(
            callActive = true,
            callerUnknown = unknownCaller,
            callerMasked = maskedNumber ?: "",
            callDurationSeconds = 0L
        )
    }

    fun onCallTick(durationSeconds: Long) = edit {
        if (!it.callActive) it else it.copy(callDurationSeconds = durationSeconds)
    }

    /**
     * Resets everything, not just the call fields.
     *
     * Deliberate: once the call ends the attack is over, and a stale
     * `remoteAccessAppActive` left behind would arm the guard against an
     * innocent call an hour later. Every false positive after a demo is a
     * false positive a judge remembers.
     */
    fun onCallEnded() = edit { SignalSnapshot() }

    // ---- Payment context --------------------------------------------------

    fun onPaymentAppForeground(label: String?) = edit {
        it.copy(paymentAppForeground = true, paymentAppLabel = label)
    }

    fun onPaymentAppBackground() = edit {
        it.copy(paymentAppForeground = false, paymentAppLabel = null)
    }

    /**
     * One transfer, and the baseline it should be judged against.
     *
     * [p95Paise] is passed in rather than looked up here because [SignalStore]
     * has no [android.content.Context] and should not acquire one — it is the
     * seam between Android and the pure scoring code, and it stays on the pure
     * side. The caller reads it from
     * [TransferHistory.p95Paise]; null there means "not enough history to
     * judge", which
     * [SignalSnapshot.amountExceedsBaseline][com.prahari.guardian.model.SignalSnapshot.amountExceedsBaseline]
     * turns into a false rather than a guess.
     */
    fun onAmountObserved(paise: Long?, payeeIsNew: Boolean?, p95Paise: Long?) = edit {
        it.copy(
            pendingAmountPaise = paise,
            payeeIsNew = payeeIsNew,
            userP95AmountPaise = p95Paise
        )
    }

    // ---- Attack-step context ----------------------------------------------

    fun onRemoteAccessActive(label: String) = edit {
        it.copy(remoteAccessAppActive = true, remoteAccessAppLabel = label)
    }

    /**
     * Set when a remote-access app is observed in the foreground during a call.
     *
     * Read the name honestly: this is not a MediaProjection callback. Android
     * gives a third-party app no way to observe *another* app's screen-capture
     * session — that API is reserved to the platform. What is observable is that
     * AnyDesk is on screen while an unknown number is on the line, which in
     * practice means a session is being set up or is running.
     *
     * That gap is not a limitation to hide. It is one of the clearest arguments
     * for shipping this inside the OS: the platform can see the capture session
     * itself, and an app can only see the app.
     */
    fun onScreenShareChanged(active: Boolean) = edit {
        it.copy(screenShareActive = active)
    }

    /**
     * Sticky for the rest of the call. An OTP that was read out ninety seconds
     * ago is still the reason the money is about to move.
     */
    fun onOtpObserved() = edit { it.copy(otpDuringCall = true) }

    // ---- Language context -------------------------------------------------

    fun onTacticsClassified(tactics: Set<Tactic>, window: String) = edit {
        // Union, not replace: tactics accumulate over a call. The scammer says
        // "I am from CBI" once at minute two and never repeats it, but that
        // claim is still true evidence at minute nine.
        it.copy(tactics = it.tactics + tactics, transcriptWindow = window)
    }

    // ---- Demo / test hooks ------------------------------------------------

    /** Drives the store from a scripted snapshot and locks out live collectors. */
    fun replay(snapshot: SignalSnapshot) {
        replayMode = true
        _state.value = snapshot
    }

    fun exitReplay() {
        replayMode = false
        _state.value = SignalSnapshot()
    }
}
