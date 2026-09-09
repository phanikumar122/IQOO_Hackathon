# Prahari — Problem Statement & Project Abstract

---

## Problem Statement

India loses hundreds of crores of rupees every year to phone-based financial fraud.
The attack pattern is consistent: a stranger calls, manufactures urgency or authority,
keeps the victim on the line, and coaches them step-by-step through a UPI or bank
transfer — often while simultaneously taking remote control of the device.

Existing defences fail at the moment they are most needed:

| Gap | Why it matters |
|---|---|
| **Call-screening apps** block known spam numbers | Scam SIMs rotate constantly; novel numbers are invisible to blocklists |
| **Bank fraud detection** fires after money moves | The fraud is complete before the alert arrives |
| **User education campaigns** rely on recall under stress | Victims know the warnings; they comply anyway because fear overrides reason |
| **OS-level call features** (e.g., Google Call Screen) are cloud-dependent | They require an internet connection at the moment of the call, exposing metadata, and are unavailable for India-English accents on low-end hardware |

The gap is not in detection — it is in **intervention at the right moment, on the right
device, without requiring a network round-trip or a cloud call**. No production app
today combines real-time on-call behavioural analysis with on-device speech understanding
and an interruption mechanism that gives the victim time to think.

---

## Project Abstract

**Prahari** (Hindi: guardian / watchman) is an on-device Android application that
detects active scam calls in real time and intervenes with a 60-second friction screen
before any financial action is completed — entirely without an internet connection.

### Core Insight

A phone scam is not a single event; it is a sequence of observable signals that
accumulate over the duration of a call. Each signal alone is ambiguous. Together,
at sufficient weight, they constitute near-certain evidence of an ongoing attack.
Prahari scores those signals continuously, and acts only when the accumulated score
crosses a threshold that cannot be reached by coincidence.

### Architecture

Six on-device collectors feed a central scoring engine:

```
CallStateMonitor     call state, caller known/unknown, duration
SmsSignalReceiver    OTP arrival during a call
PaymentNotifListener --> SignalStore --> RiskEngine.assess --> status chip
PrahariA11yService   (SignalSnapshot)       |               alert notification
RemoteAccessWatcher        ^               |               InterventionActivity
ForegroundAppPoller        |  armed only   |
                           |               |
       StreamingTranscriber -- TacticClassifier
```

- **`SignalSnapshot`** is an immutable, thread-safe contract. All collectors write to it;
  `RiskEngine` reads it as a pure function. Replay mode is a one-line substitution.
- **`RiskEngine`** applies transparent, documented point weights to seven behavioural signals
  and up to ten language tactics. Thresholds: WATCH = 45 · WARN = 65 · INTERVENE = 85.
- **Gemma3-1B int4** (on-device LLM via MediaPipe) classifies live transcribed speech into
  ten known scam tactics: authority impersonation, urgency manufacture, secrecy demand, etc.
- **Vosk ASR** (on-device, India-English model) transcribes the caller side of the
  conversation from the device microphone — no cloud, no streaming to any server.

### The Arming Gate

The engine stays completely dormant until three conditions are simultaneously true:

1. An active phone call is in progress
2. The caller's number is not in the device contacts
3. The call has lasted more than **180 seconds**

Below this gate: no microphone access, no model in memory, no scoring, no battery impact.
The LLM loads on the arming *edge* — not at service start — and is released from memory
the moment the call ends. This is the entire battery answer and most of the false-positive answer.

### Scoring Design (Key Properties)

Two structural properties are pinned by automated tests:

| Property | Value | What it guarantees |
|---|---|---|
| **Language alone can never intervene** | All 10 tactics cap at 40 pts — below WATCH (45) | The SLM can escalate a case; it cannot create one |
| **Behaviour alone can intervene** | 7 behavioural signals total 150 pts — above INTERVENE (85) | A device with no model pushed is a working, honest product |

Worked example that must survive contact with a judge:
> Payment app in foreground (30) + first-time payee (15) + OTP arrives during call (20)
> + call over 3 minutes (10) = **75 points** -> WARN, with zero language-model contribution.

### Privacy Architecture

- **No `INTERNET` permission** — verifiable in one tap from the system App Info screen.
  All inference runs locally. No audio, transcript, or metadata ever leaves the device.
- **No call audio tap** — Android reserves `VOICE_CALL` capture for privileged, pre-installed
  apps. Prahari reads the device microphone (the victim's side); the scam audio is audible
  on speaker. This is a deliberate restriction, not an oversight — and it is the argument
  for why this capability ultimately belongs inside the OS rather than as a sideloaded app.
- **The accessibility service never acts** — it reads screen content (payment amounts);
  it never taps, scrolls, or blocks any UI action.
- **The intervention delays, never denies** — sixty seconds of friction, then the user
  may proceed. An app that could hard-block a transfer would carry more risk than the
  fraud it prevents.

### Intervention UX

When `RiskEngine` returns INTERVENE, a full-screen `InterventionActivity` fires over
whatever app the user is in (requires overlay permission). It shows:

- A plain-language headline: **"Wait."**
- The specific reason lines that triggered the alert (not a generic warning)
- **"Hang up now"** — one tap, ends the call
- **"Call [Trusted Contact]"** — pre-set by the user; scammers always say *tell nobody*
- **"I understand the risk — wait 60 s"** — a countdown that must fully elapse before
  a "continue anyway" option unlocks

The 60-second delay is the core UX safety claim. It breaks the scammer's time pressure.

### Verification

- **17 unit tests** cover the scoring engine, arming gate, threshold boundaries, and
  null-evidence handling — no emulator, ~1 second to run.
- **`tools/verify_risk_engine.py`** is a line-by-line Python port of `RiskEngine.kt`
  that independently confirms every test case: **17/17 pass**.
- **4 built-in replay scenarios** (debug builds) replay fixture signals without needing
  a real call — including two innocent controls that must not fire.

### Target Hardware

iQOO and Vivo Android devices (OriginOS), Android 12+ (minSdk 31). The OEM-specific
behaviours — aggressive background-service killing, accessibility revocation after reboot,
missing `EXTRA_INCOMING_NUMBER` on some builds — are documented and handled explicitly
in the codebase.

### Why This Matters Beyond the Hackathon

Prahari is intentionally built as an app to demonstrate the architecture and prove the
scoring model. The long-term argument is that a guardian of this kind — one that has
microphone access from the lock screen, is immune to removal, and survives an OEM reboot —
can only ship as part of the operating system itself. This submission is the evidence
that the hard parts are solved, the architecture is sound, and the model is honest enough
to explain to a non-technical victim in ten seconds.

---

*Prahari — on-device scam-call intervention. No internet. No cloud. No permission to move your money.*
