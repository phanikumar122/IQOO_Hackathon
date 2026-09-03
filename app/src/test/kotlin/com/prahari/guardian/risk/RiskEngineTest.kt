package com.prahari.guardian.risk

import com.prahari.guardian.demo.DemoScenarios
import com.prahari.guardian.model.SignalSnapshot
import com.prahari.guardian.model.Tactic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests. No emulator, no Robolectric, no Android at all.
 *
 * That is only possible because `model/` and `risk/` have zero Android imports,
 * and it is worth protecting: these run in about a second, which means you can
 * retune a weight at hour 26 and know immediately whether you broke the demo.
 *
 * Every number asserted here also appears in the pitch. If you change a weight,
 * change it in both places or the deck starts lying.
 */
class RiskEngineTest {

    // ---- The arming gate --------------------------------------------------

    @Test
    fun `no call means dormant regardless of everything else`() {
        val s = SignalSnapshot(
            callActive = false,
            remoteAccessAppActive = true,
            paymentAppForeground = true,
            otpDuringCall = true,
            tactics = Tactic.entries.toSet()
        )
        val a = RiskEngine.assess(s)
        assertEquals(RiskLevel.DORMANT, a.level)
        assertEquals(0, a.score)
        assertFalse(a.armed)
    }

    @Test
    fun `known caller never arms`() {
        val s = armedBase().copy(callerUnknown = false)
        assertFalse(RiskEngine.isArmed(s))
        assertEquals(RiskLevel.DORMANT, RiskEngine.assess(s).level)
    }

    @Test
    fun `short call never arms`() {
        val s = armedBase().copy(callDurationSeconds = RiskEngine.ARM_MIN_CALL_SECONDS)
        assertFalse(RiskEngine.isArmed(s))
    }

    @Test
    fun `one second past the gate arms`() {
        val s = armedBase().copy(callDurationSeconds = RiskEngine.ARM_MIN_CALL_SECONDS + 1)
        assertTrue(RiskEngine.isArmed(s))
    }

    // ---- The claim the pitch makes ----------------------------------------

    /**
     * The single most important test in the project.
     *
     * Payment app 30 + first-time payee 15 + OTP mid-call 20 + long call 10 = 75,
     * against a WARN threshold of 65, with `tactics` empty. If this ever fails,
     * the "detection does not depend on the language model" claim is dead and the
     * pitch has to change.
     */
    @Test
    fun `behaviour alone reaches WARN with no model contribution`() {
        val s = armedBase().copy(
            callDurationSeconds = 19 * 60L,
            paymentAppForeground = true,
            payeeIsNew = true,
            otpDuringCall = true,
            tactics = emptySet()
        )
        val a = RiskEngine.assess(s)
        assertEquals(75, a.score)
        assertEquals(RiskLevel.WARN, a.level)
        assertTrue(a.tactics.isEmpty())
        assertTrue(a.behaviourAloneSufficient)
    }

    @Test
    fun `two tactics on top of that behaviour escalates to INTERVENE`() {
        val s = armedBase().copy(
            callDurationSeconds = 19 * 60L,
            paymentAppForeground = true,
            payeeIsNew = true,
            otpDuringCall = true,
            tactics = setOf(Tactic.ARREST_THREAT, Tactic.SECRECY)
        )
        val a = RiskEngine.assess(s)
        assertEquals(95, a.score)
        assertEquals(RiskLevel.INTERVENE, a.level)
    }

    // ---- Scoring details ---------------------------------------------------

    @Test
    fun `tactic points are capped`() {
        val s = armedBase().copy(tactics = Tactic.entries.toSet())
        val a = RiskEngine.assess(s)
        // Ten tactics at 10 points each would be 100; the cap holds it to 40 so
        // language can never single-handedly trigger an intervention.
        assertEquals(RiskEngine.W_TACTIC_CAP, a.score)
        assertTrue(a.score < RiskEngine.T_INTERVENE)
    }

    @Test
    fun `unknown payee is not scored as safe`() {
        val nullPayee = armedBase().copy(paymentAppForeground = true, payeeIsNew = null)
        val newPayee = armedBase().copy(paymentAppForeground = true, payeeIsNew = true)
        val oldPayee = armedBase().copy(paymentAppForeground = true, payeeIsNew = false)

        // Null must behave like false for scoring (we do not invent evidence),
        // but the distinction has to survive into the snapshot so the UI can
        // stay silent about a payee it could not check.
        assertEquals(RiskEngine.assess(oldPayee).score, RiskEngine.assess(nullPayee).score)
        assertEquals(
            RiskEngine.assess(nullPayee).score + RiskEngine.W_NEW_PAYEE,
            RiskEngine.assess(newPayee).score
        )
    }

    @Test
    fun `amount anomaly needs both an amount and a baseline`() {
        val noBaseline = armedBase().copy(pendingAmountPaise = 5_00_000_00L)
        assertFalse(noBaseline.amountExceedsBaseline)

        val underBaseline = armedBase().copy(
            pendingAmountPaise = 20_000_00L, userP95AmountPaise = 25_000_00L
        )
        assertFalse(underBaseline.amountExceedsBaseline)

        val over = armedBase().copy(
            pendingAmountPaise = 4_90_000_00L, userP95AmountPaise = 25_000_00L
        )
        assertTrue(over.amountExceedsBaseline)
    }

    @Test
    fun `reasons are ordered heaviest first`() {
        val a = RiskEngine.assess(DemoScenarios.DIGITAL_ARREST.snapshot)
        val points = a.reasons.map { it.points }
        assertEquals(points.sortedDescending(), points)
    }

    @Test
    fun `behavioural only score strips language`() {
        val s = DemoScenarios.DIGITAL_ARREST.snapshot
        val full = RiskEngine.assess(s).score
        val behaviour = RiskEngine.behaviouralOnlyScore(s)
        assertEquals(full - RiskEngine.W_TACTIC_CAP, behaviour)
        // Even with the model contributing nothing, this attack still intervenes.
        assertTrue(behaviour >= RiskEngine.T_INTERVENE)
    }

    // ---- The two structural properties -------------------------------------
    // Everything else in this file tests a number. These two test a *property* —
    // the kind of thing that stays true while the numbers move, and the kind
    // whose loss a code review would not otherwise notice.

    /**
     * Language alone must never be able to create a case.
     *
     * As long as the tactic cap sits below the WATCH threshold, no combination of
     * things the model believes it heard can put a single word on the user's
     * screen: every visible outcome needs at least one behavioural signal the
     * phone can point at. That is the answer to "so it acts on what a 1B model
     * thinks it heard?" — no, structurally it cannot. If someone raises the cap
     * to 50 during a late-night tuning pass, this test is what tells them what
     * they just gave away.
     */
    @Test
    fun `tactic cap sits below the watch threshold`() {
        assertTrue(RiskEngine.W_TACTIC_CAP < RiskEngine.T_WATCH)

        // And the operational version of the same claim: every tactic firing at
        // once, on an armed call, still shows the user nothing.
        val allTalk = armedBase().copy(tactics = Tactic.entries.toSet())
        assertEquals(RiskLevel.DORMANT, RiskEngine.assess(allTalk).level)
    }

    /**
     * Behaviour alone must be able to reach INTERVENE.
     *
     * The mirror of the property above, and the reason the SLM is never on the
     * critical path: the behavioural weights sum to 150 against an intervention
     * threshold of 85, so a phone with no model pushed — or one whose model
     * failed to load — is still a working product rather than a broken one.
     */
    @Test
    fun `behavioural weights alone can reach the intervention threshold`() {
        val everySignal = armedBase().copy(
            callDurationSeconds = 20 * 60L,
            paymentAppForeground = true,
            payeeIsNew = true,
            pendingAmountPaise = 5_00_000_00L,
            userP95AmountPaise = 25_000_00L,
            remoteAccessAppActive = true,
            screenShareActive = true,
            otpDuringCall = true,
            tactics = emptySet()
        )
        val a = RiskEngine.assess(everySignal)
        assertEquals(150, a.score)
        assertEquals(RiskLevel.INTERVENE, a.level)
        assertTrue(RiskEngine.behaviouralOnlyScore(everySignal) >= RiskEngine.T_INTERVENE)
    }

    // ---- The demo scenarios must do what their labels claim ----------------

    @Test
    fun `digital arrest scenario intervenes`() {
        assertEquals(
            RiskLevel.INTERVENE,
            RiskEngine.assess(DemoScenarios.DIGITAL_ARREST.snapshot).level
        )
    }

    @Test
    fun `behaviour only scenario warns at seventy five`() {
        val a = RiskEngine.assess(DemoScenarios.BEHAVIOUR_ONLY.snapshot)
        assertEquals(75, a.score)
        assertEquals(RiskLevel.WARN, a.level)
    }

    /**
     * The false-positive test, and the one to run in front of a judge.
     * A long call with a known contact must never produce anything.
     */
    @Test
    fun `innocent long call stays dormant`() {
        val a = RiskEngine.assess(DemoScenarios.INNOCENT_LONG_CALL.snapshot)
        assertEquals(RiskLevel.DORMANT, a.level)
        assertFalse(a.shouldWarn)
    }

    @Test
    fun `legitimate bank call stays below the watch line`() {
        val a = RiskEngine.assess(DemoScenarios.LEGITIMATE_BANK_CALL.snapshot)
        assertEquals(30, a.score)
        assertEquals(RiskLevel.DORMANT, a.level)
    }

    private fun armedBase() = SignalSnapshot(
        callActive = true,
        callerUnknown = true,
        callDurationSeconds = RiskEngine.ARM_MIN_CALL_SECONDS + 1
    )
}
