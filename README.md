# Prahari — scaffold

On-device scam-call intervention for Android. This is the code skeleton that
goes with `01-PRAHARI-pitch.md` and `02-PRAHARI-build-plan.md`.

It is a **scaffold, not a finished app**. Every architectural decision is made
and every hard part is stubbed with a comment explaining what to do and why. The
point is that four people can start work in hour one without arguing about
structure, and that nothing on the critical path is left as a surprise.

---

## What actually works right now

| Layer | State |
|---|---|
| `model/` + `risk/` — the scoring engine | **Complete and verified.** Pure Kotlin, no Android imports, 17 unit tests. |
| `demo/DemoScenarios.kt` — replay fixtures | **Complete.** Four scenarios including two that must *not* fire. |
| `signals/` — the six collectors | **Wired, needs device testing.** Real APIs, real callbacks; package lists and OEM behaviour need checking on hardware. |
| `inference/` — ASR + SLM | **Wired, needs models pushed.** Fails soft to the keyword classifier by design. |
| `ui/` — onboarding + intervention | **Complete enough to demo.** Real layouts, trusted-contact entry, real 60-second hold. |
| Gradle wrapper | **Missing — you must generate it.** See below. |

## First run

1. **Generate the Gradle wrapper.** It is not in this scaffold. Either open the
   folder in Android Studio and let it offer to create one, or from a machine
   with Gradle installed:

   ```
   gradle wrapper --gradle-version 8.9
   ```

2. **Pin your plugin versions.** `build.gradle.kts` declares AGP 8.7.3 and
   Kotlin 2.0.21. Change them to whatever your Android Studio actually ships
   with rather than fighting it — then run `./gradlew build` **once, at home, on
   good Wi-Fi**, so every artifact is in the Gradle cache. The hackathon format
   assumes nothing is downloaded at the venue.

3. **Run the tests.** These need no emulator and finish in about a second:

   ```
   ./gradlew :app:testDebugUnitTest
   ```

4. **Push the models.** Neither is in the APK, deliberately — a several-hundred-MB
   asset makes every build and install painfully slow. (The Gemma3-1B int4 `.task`
   bundle was around 550 MB when this was written; check the real size after you
   download it rather than trusting that number.)

   ```
   adb shell mkdir -p /sdcard/Android/data/com.prahari.guardian/files
   adb push gemma3-1b-it-int4.task \
     /sdcard/Android/data/com.prahari.guardian/files/
   adb push vosk-model-small-en-in-0.4 \
     /sdcard/Android/data/com.prahari.guardian/files/vosk-model-small-en-in
   ```

   Filenames are set in `LlmTacticClassifier.DEFAULT_MODEL_FILE` and
   `StreamingTranscriber.MODEL_DIR_NAME`. If the SLM does not load, the app logs
   it and falls back to the keyword classifier — it will not crash and the demo
   will not die.

5. **Grant permissions in order**, using the buttons on the main screen. Five
   grants across four Settings screens; there is no API that batches them.

6. **Set a trusted contact** on the main screen. Optional, but the "Call <name>"
   button on the intervention screen is one of the better things to have working
   when you demo it, and it is stored on the device only.

## How it fits together

```
CallStateMonitor ────┐   call state, caller known/unknown, duration
SmsSignalReceiver ───┤   OTP arrival during a call
PaymentNotifListener ├──▶ SignalStore ──▶ RiskEngine.assess ──▶ status chip
PrahariA11yService ──┤   (SignalSnapshot)        │              alert notification
RemoteAccessWatcher ─┤          ▲                │              InterventionActivity
ForegroundAppPoller ─┘          │  armed only    │
                                │                │
        StreamingTranscriber ───┴─ TacticClassifier
```

Six collectors write; `SignalStore` is the only writer of `SignalSnapshot`;
`RiskEngine` is a pure function of that snapshot. Nothing reads a signal
directly, which is what makes replay mode a one-line substitution.

`SignalSnapshot` is the contract. Ship it in hour one and both halves of the
team code against it independently — one filling it from real collectors, the
other from fixtures.

**The arming gate is the design.** `RiskEngine.isArmed()` requires an active
call, an unknown caller, and more than 180 seconds elapsed. Below that: no
microphone, no model in memory, no scoring. That one condition is the whole
battery answer and most of the false-positive answer.

The SLM load is on the arming *edge*, not on service start — `GuardianService`
holds `classifier` as a nullable and calls `ensureClassifier()` inside the armed
job, then `releaseClassifier()` on disarm. The engine label on the main screen
reads `dormant · SLM loads when armed` until then, and flips on stage at the
180-second mark. That flip is the evidence for the dormancy claim; do not
"optimise" it into a warm start.

## Verification status

`tools/verify_risk_engine.py` is a line-by-line Python port of `RiskEngine` with
one check per `@Test` in `RiskEngineTest.kt`, same names, same order. **17/17
pass.** It exists because the scaffold was authored in an environment with no
Kotlin compiler, so it verifies the *arithmetic, thresholds and arming gate* —
**not** that the Kotlin compiles. Run `./gradlew :app:testDebugUnitTest` for
that, at home, before the event. If the two files ever disagree in count, one was
edited without the other.

The claim that has to survive contact with a judge, and the test that guards it
(`behaviour alone reaches WARN with no model contribution`):

> Payment app in foreground (30) + first-time payee (15) + OTP during the call
> (20) + long call (10) = **75**, against a WARN threshold of 65, with the
> language model contributing **zero**.

Two structural properties are pinned by tests of their own, because they are the
things a late-night tuning pass would quietly destroy:

- **Language alone can never intervene** — `tactic cap sits below the watch
  threshold`. Ten tactics cap at 40 points, below the WATCH line of 45, so all
  ten firing at once on an armed call still renders DORMANT and shows the user
  nothing. The SLM can escalate a case; it cannot create one.
- **Behaviour alone can intervene** — `behavioural weights alone can reach the
  intervention threshold`. All seven behavioural signals total exactly 150
  against an INTERVENE threshold of 85, asserted through `assess()` rather than
  added up by hand. A phone with no model pushed is a working product.

Also worth knowing: `payeeIsNew` is `Boolean?`, and `null` means "we could not
check", scored identically to a known payee. The engine never invents evidence.

## Things to check on hardware before you trust them

These are the parts a laptop cannot verify. Work through them during the
pre-event checklist, not at hour 26.

- **Package names.** `KnownPackages.kt` and the `packageNames` list in
  `res/xml/accessibility_service_config.xml` must agree, and both must match
  reality. Confirm with `adb shell pm list packages | grep -i pay`. A typo here
  silently removes the heaviest behavioural signal and you will not get an error.
- **The caller's number.** `TelephonyCallback.CallStateListener` deliberately
  does not provide it, so `CallStateMonitor` also listens for the legacy
  `PHONE_STATE` broadcast and reads `EXTRA_INCOMING_NUMBER`. That extra is empty
  on some OEM builds and some carriers. When it is, the caller reads as unknown,
  which biases toward arming — acceptable, but know that it is happening.
- **Accessibility on OriginOS.** Vivo/iQOO builds are aggressive about killing
  background services and about revoking accessibility grants after a reboot.
  Test a reboot. Test leaving the app alone for an hour.
- **Foreground-service type.** A `microphone` FGS cannot be started from the
  background on Android 14+ and throws if `RECORD_AUDIO` is not already granted.
  The only entry point is the switch in `MainActivity`, after the wizard. Do not
  add a `BOOT_COMPLETED` auto-start without rethinking this.
- **Library versions — the one thing in this repo that is not verified.**
  `com.google.mediapipe:tasks-genai:0.10.24` and
  `com.alphacephei:vosk-android:0.3.75` were the current versions when this was
  written, and neither could be resolved from the machine that wrote it. Resolve
  both at home, on good Wi-Fi, and write down what actually worked. If
  `tasks-genai` will not resolve, the app still runs: `LlmTacticClassifier`
  failing is a designed path, not a crash.
- **The package-added broadcast.** `RemoteAccessWatcher` is registered both in
  the manifest and at runtime for the length of each call, because whether a
  manifest-declared `ACTION_PACKAGE_ADDED` receiver still wakes a background app
  on current Android and OriginOS could not be confirmed. The runtime
  registration is the one to rely on; the double registration is harmless
  because the guards are idempotent.

## Deliberate omissions

- **No `INTERNET` permission.** This is the privacy claim made checkable in one
  line of the manifest. A judge can verify it from the app info screen. Do not
  add it "just for logging" — you would be trading the strongest thing about the
  submission for a `Log.d`.
- **No call audio tap.** Android reserves `VOICE_CALL` capture for privileged,
  pre-installed apps. Prahari reads the microphone; the scam side of the
  conversation comes from a second phone on speaker. **Say this before a judge
  finds it.** Everything above the audio tap is real, and the restriction is
  precisely why this has to ship inside the OS rather than as an app — which is
  the argument the whole pitch rests on.
- **No trained classifier.** Transparent weights instead, because you can
  explain them in ten seconds, retune one live when the demo misbehaves, and
  because a model trained on forty labelled clips would overfit and give you no
  honest way to report accuracy.
- **The accessibility service never acts.** It reads; it never taps. Prahari has
  no authority to move or block anyone's money.
- **The intervention delays, never denies.** Sixty seconds of friction, then the
  user may proceed. An app that could hard-block a transfer would be a worse
  thing to install than the risk it prevents.

## Ethics note on the demo clips

Script and record the scam audio yourselves, with your own team's voices. Do not
use recordings of real victims — no consent, and it is not necessary. Include
four or five innocent controls too; `INNOCENT_LONG_CALL` exists because
demonstrating that the guard stays silent during a long call with family answers
the only question a good judge really has.



