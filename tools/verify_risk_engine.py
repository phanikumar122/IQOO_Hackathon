"""
Verification harness for RiskEngine.kt.

Why this exists: there is no Kotlin compiler in this workspace, so the Kotlin
tests cannot be executed here. This is a line-by-line port of the scoring logic
plus every assertion from RiskEngineTest.kt, run on the JVM-free path.

What it proves: the arithmetic, the thresholds, the arming gate and the demo
scenario expectations are internally consistent.
What it does NOT prove: that the Kotlin compiles. Run `./gradlew test` at home.

There is one check here per `@Test` in RiskEngineTest.kt, in the same order and
under the same names. If the two files ever disagree in count, one of them was
edited without the other. Keep this in sync if you retune a weight — it is the
fastest way to check that the pitch, the tests and the engine still agree.
"""

from dataclasses import dataclass, replace, field
from typing import Optional, FrozenSet

# ---- Mirror of RiskEngine constants ---------------------------------------
ARM_MIN_CALL_SECONDS = 180
W_REMOTE_ACCESS = 35
W_PAYMENT_FOREGROUND = 30
W_SCREEN_SHARE = 25
W_OTP_DURING_CALL = 20
W_NEW_PAYEE = 15
W_AMOUNT_ANOMALY = 15
W_LONG_CALL = 10
W_PER_TACTIC = 10
W_TACTIC_CAP = 40
T_WATCH, T_WARN, T_INTERVENE = 45, 65, 85

DORMANT, WATCH, WARN, INTERVENE = "DORMANT", "WATCH", "WARN", "INTERVENE"

ALL_TACTICS = frozenset([
    "authority_claim", "urgency", "secrecy", "stay_on_line", "otp_request",
    "arrest_threat", "account_freeze_threat", "transfer_to_verify",
    "isolation", "impersonation_of_official",
])


@dataclass(frozen=True)
class Snapshot:
    call_active: bool = False
    caller_unknown: bool = False
    call_duration_seconds: int = 0
    payment_app_foreground: bool = False
    pending_amount_paise: Optional[int] = None
    payee_is_new: Optional[bool] = None
    user_p95_amount_paise: Optional[int] = None
    remote_access_app_active: bool = False
    screen_share_active: bool = False
    otp_during_call: bool = False
    tactics: FrozenSet[str] = field(default_factory=frozenset)

    @property
    def amount_exceeds_baseline(self) -> bool:
        if self.pending_amount_paise is None or self.user_p95_amount_paise is None:
            return False
        return self.pending_amount_paise > self.user_p95_amount_paise

    @property
    def call_long(self) -> bool:
        return self.call_duration_seconds > 15 * 60


def is_armed(s: Snapshot) -> bool:
    return s.call_active and s.caller_unknown and s.call_duration_seconds > ARM_MIN_CALL_SECONDS


def level_for(score: int) -> str:
    if score >= T_INTERVENE:
        return INTERVENE
    if score >= T_WARN:
        return WARN
    if score >= T_WATCH:
        return WATCH
    return DORMANT


def assess(s: Snapshot):
    """Returns (score, level, reasons) where reasons is [(factor, points)]."""
    if not is_armed(s):
        return 0, DORMANT, []
    reasons = []
    if s.remote_access_app_active:
        reasons.append(("REMOTE_ACCESS_APP", W_REMOTE_ACCESS))
    if s.payment_app_foreground:
        reasons.append(("PAYMENT_APP_OPEN", W_PAYMENT_FOREGROUND))
    if s.screen_share_active:
        reasons.append(("SCREEN_SHARED", W_SCREEN_SHARE))
    if s.otp_during_call:
        reasons.append(("OTP_DURING_CALL", W_OTP_DURING_CALL))
    if s.payee_is_new is True:          # `== true` in Kotlin: null is not safe
        reasons.append(("NEW_PAYEE", W_NEW_PAYEE))
    if s.amount_exceeds_baseline:
        reasons.append(("AMOUNT_ANOMALY", W_AMOUNT_ANOMALY))
    if s.call_long:
        reasons.append(("LONG_CALL", W_LONG_CALL))
    tactic_points = min(len(s.tactics) * W_PER_TACTIC, W_TACTIC_CAP)
    if tactic_points > 0:
        reasons.append(("COERCION_LANGUAGE", tactic_points))
    score = sum(p for _, p in reasons)
    reasons.sort(key=lambda r: -r[1])
    return score, level_for(score), reasons


def behavioural_only_score(s: Snapshot) -> int:
    return assess(replace(s, tactics=frozenset()))[0]


# ---- Demo scenarios, mirroring DemoScenarios.kt ---------------------------
DIGITAL_ARREST = Snapshot(
    call_active=True, caller_unknown=True, call_duration_seconds=22 * 60,
    payment_app_foreground=True, pending_amount_paise=49_000_000,
    payee_is_new=True, user_p95_amount_paise=2_500_000,
    remote_access_app_active=True, screen_share_active=True, otp_during_call=True,
    tactics=frozenset(["authority_claim", "arrest_threat", "secrecy",
                       "stay_on_line", "transfer_to_verify"]),
)
BEHAVIOUR_ONLY = Snapshot(
    call_active=True, caller_unknown=True, call_duration_seconds=19 * 60,
    payment_app_foreground=True, pending_amount_paise=2_000_000,
    payee_is_new=True, user_p95_amount_paise=2_500_000, otp_during_call=True,
    tactics=frozenset(),
)
INNOCENT_LONG_CALL = Snapshot(
    call_active=True, caller_unknown=False, call_duration_seconds=41 * 60,
)
LEGITIMATE_BANK_CALL = Snapshot(
    call_active=True, caller_unknown=True, call_duration_seconds=6 * 60,
    payment_app_foreground=True, pending_amount_paise=1_200_000,
    payee_is_new=False, user_p95_amount_paise=2_500_000,
)


def armed_base() -> Snapshot:
    return Snapshot(call_active=True, caller_unknown=True,
                    call_duration_seconds=ARM_MIN_CALL_SECONDS + 1)


CHECKS = []


def check(name):
    def deco(fn):
        CHECKS.append((name, fn))
        return fn
    return deco


@check("no call is dormant regardless of everything else")
def _():
    s = Snapshot(call_active=False, remote_access_app_active=True,
                 payment_app_foreground=True, otp_during_call=True,
                 tactics=ALL_TACTICS)
    score, level, _r = assess(s)
    assert (score, level) == (0, DORMANT), (score, level)


@check("known caller never arms")
def _():
    assert not is_armed(replace(armed_base(), caller_unknown=False))


@check("call exactly at the gate does not arm")
def _():
    assert not is_armed(replace(armed_base(),
                                call_duration_seconds=ARM_MIN_CALL_SECONDS))


@check("one second past the gate arms")
def _():
    assert is_armed(armed_base())


@check("behaviour alone reaches WARN at 75 with no tactics")
def _():
    s = replace(armed_base(), call_duration_seconds=19 * 60,
                payment_app_foreground=True, payee_is_new=True,
                otp_during_call=True, tactics=frozenset())
    score, level, reasons = assess(s)
    assert score == 75, score
    assert level == WARN, level
    assert all(f != "COERCION_LANGUAGE" for f, _ in reasons)


@check("two tactics on that behaviour escalates to INTERVENE at 95")
def _():
    s = replace(armed_base(), call_duration_seconds=19 * 60,
                payment_app_foreground=True, payee_is_new=True,
                otp_during_call=True,
                tactics=frozenset(["arrest_threat", "secrecy"]))
    score, level, _r = assess(s)
    assert (score, level) == (95, INTERVENE), (score, level)


@check("tactic points are capped and language alone cannot intervene")
def _():
    score, level, _r = assess(replace(armed_base(), tactics=ALL_TACTICS))
    assert score == W_TACTIC_CAP, score
    assert score < T_INTERVENE
    assert level == DORMANT, level


@check("unknown payee scores like a known payee, not like a new one")
def _():
    base = replace(armed_base(), payment_app_foreground=True)
    null_p = assess(replace(base, payee_is_new=None))[0]
    old_p = assess(replace(base, payee_is_new=False))[0]
    new_p = assess(replace(base, payee_is_new=True))[0]
    assert null_p == old_p == 30, (null_p, old_p)
    assert new_p == null_p + W_NEW_PAYEE, (new_p, null_p)


@check("amount anomaly needs both an amount and a baseline")
def _():
    assert not replace(armed_base(), pending_amount_paise=49_000_000).amount_exceeds_baseline
    assert not replace(armed_base(), pending_amount_paise=2_000_000,
                       user_p95_amount_paise=2_500_000).amount_exceeds_baseline
    assert replace(armed_base(), pending_amount_paise=49_000_000,
                   user_p95_amount_paise=2_500_000).amount_exceeds_baseline


@check("reasons are ordered heaviest first")
def _():
    _s, _l, reasons = assess(DIGITAL_ARREST)
    pts = [p for _, p in reasons]
    assert pts == sorted(pts, reverse=True), pts


@check("behavioural-only strips exactly the language contribution")
def _():
    full = assess(DIGITAL_ARREST)[0]
    behaviour = behavioural_only_score(DIGITAL_ARREST)
    assert full == 190, full
    assert behaviour == full - W_TACTIC_CAP == 150, behaviour
    assert behaviour >= T_INTERVENE


@check("scenario: digital arrest intervenes")
def _():
    assert assess(DIGITAL_ARREST)[1] == INTERVENE


@check("scenario: behaviour-only warns at 75")
def _():
    score, level, _r = assess(BEHAVIOUR_ONLY)
    assert (score, level) == (75, WARN), (score, level)


@check("scenario: innocent long call stays dormant")
def _():
    score, level, _r = assess(INNOCENT_LONG_CALL)
    assert (score, level) == (0, DORMANT), (score, level)


@check("scenario: legitimate bank call scores 30, under the WATCH line")
def _():
    score, level, _r = assess(LEGITIMATE_BANK_CALL)
    assert (score, level) == (30, DORMANT), (score, level)


@check("behavioural weights alone can reach the intervention threshold")
def _():
    # Mirror of the Kotlin test of the same name. Every behavioural signal at
    # once, no tactics: 35+30+25+20+15+15+10 = 150 against T_INTERVENE = 85.
    # This is the "the model is not load-bearing" claim, run through assess()
    # rather than added up by hand.
    every_signal = replace(
        armed_base(), call_duration_seconds=20 * 60,
        payment_app_foreground=True, payee_is_new=True,
        pending_amount_paise=50_000_000, user_p95_amount_paise=2_500_000,
        remote_access_app_active=True, screen_share_active=True,
        otp_during_call=True, tactics=frozenset(),
    )
    score, level, _r = assess(every_signal)
    assert (score, level) == (150, INTERVENE), (score, level)
    assert behavioural_only_score(every_signal) >= T_INTERVENE


@check("thresholds are strictly ordered and reachable")
def _():
    assert T_WATCH < T_WARN < T_INTERVENE
    behavioural_max = (W_REMOTE_ACCESS + W_PAYMENT_FOREGROUND + W_SCREEN_SHARE +
                       W_OTP_DURING_CALL + W_NEW_PAYEE + W_AMOUNT_ANOMALY + W_LONG_CALL)
    assert behavioural_max == 150
    # The claim that matters: behaviour can reach INTERVENE unaided, and
    # language cannot reach even WATCH unaided.
    assert behavioural_max >= T_INTERVENE
    assert W_TACTIC_CAP < T_WATCH


if __name__ == "__main__":
    failed = 0
    for name, fn in CHECKS:
        try:
            fn()
            print(f"  PASS  {name}")
        except AssertionError as exc:
            failed += 1
            print(f"  FAIL  {name}  -> {exc}")
    print(f"\n{len(CHECKS) - failed}/{len(CHECKS)} checks passed")
    raise SystemExit(1 if failed else 0)
