# RUN.md — Prahari Android: Step-by-Step Verification Guide

This document walks you through **every step** needed to verify that the
Prahari project builds, tests pass, and the app runs correctly on a real device.
Follow all sections **in order** — later steps depend on earlier ones passing.

---

## Table of Contents

1. [Prerequisites Checklist](#1-prerequisites-checklist)
2. [Clone & Open Project](#2-clone--open-project)
3. [Verify local.properties](#3-verify-localproperties)
4. [Gradle Wrapper Check](#4-gradle-wrapper-check)
5. [Sync & Resolve Dependencies](#5-sync--resolve-dependencies)
6. [Run Unit Tests (No Device Needed)](#6-run-unit-tests-no-device-needed)
7. [Run Python Verification Script](#7-run-python-verification-script)
8. [Build the Debug APK](#8-build-the-debug-apk)
9. [Connect Your Device via ADB](#9-connect-your-device-via-adb)
10. [Install the APK on Device](#10-install-the-apk-on-device)
11. [Grant All Required Permissions](#11-grant-all-required-permissions)
12. [Verify Payment App Package Names](#12-verify-payment-app-package-names)
13. [Push On-Device AI Models (Optional but Recommended)](#13-push-on-device-ai-models-optional-but-recommended)
14. [Set a Trusted Contact](#14-set-a-trusted-contact)
15. [Enable the Guardian Service](#15-enable-the-guardian-service)
16. [Run Live Demo Scenarios](#16-run-live-demo-scenarios)
17. [Verify Logcat Output](#17-verify-logcat-output)
18. [Full Checklist Summary](#18-full-checklist-summary)
19. [Known Issues & Troubleshooting](#19-known-issues--troubleshooting)

---

## 1. Prerequisites Checklist

Before anything else, confirm that every tool is installed and at the correct
version. Mismatched versions are the most common cause of silent failures.

| Tool | Required Version | How to Check |
|---|---|---|
| **JDK** | 17 (exactly) | `java -version` |
| **Android Studio** | Ladybug / Meerkat or later | Help > About |
| **Android SDK** | compileSdk **35** (API 35 installed) | SDK Manager |
| **AGP (Android Gradle Plugin)** | **8.7.3** | Declared in `build.gradle.kts` |
| **Kotlin** | **2.0.21** | Declared in `build.gradle.kts` |
| **Gradle Wrapper** | **8.9** | `./gradlew --version` |
| **ADB** | Any recent version | `adb --version` |
| **Python** | 3.8 or later (for `tools/verify_risk_engine.py`) | `python --version` |
| **Physical Android Device** | Android 12+ (minSdk 31), iQOO/Vivo preferred | Check `adb devices` |

> **WARNING:** Do NOT mix AGP and Kotlin versions. The scaffold was verified
> with AGP 8.7.3 + Kotlin 2.0.21 + Gradle 8.9 + JDK 17 + compileSdk 35.
> Changing any one without the others can break the build.

### Check JDK version

```bash
java -version
# Expected: openjdk version "17.x.x" ...
```

If it is not 17, set JAVA_HOME to your JDK 17 installation, or configure it in
Android Studio under File > Project Structure > SDK Location > JDK.

---

## 2. Clone & Open Project

```bash
# If you have not cloned yet:
git clone <your-repo-url> prahari-android
cd prahari-android

# Or navigate to the existing clone:
cd "C:\Users\Phani Kumar\Documents\projects\IQOO\prahari-android"
```

**Open in Android Studio:**

1. Launch Android Studio
2. File > Open > select the `prahari-android` folder (the one containing `build.gradle.kts`)
3. Wait for Android Studio to detect the Gradle project
4. Do NOT click "Upgrade" if prompted to upgrade AGP unless you intend to change the version

**Expected result:** Android Studio shows the project with the `app` module in
the Project pane. No red error banners at the top of the IDE.

---

## 3. Verify `local.properties`

The file `local.properties` must point to your Android SDK. The current value is:

```
sdk.dir=C\:\\Android\\Sdk
```

> **IMPORTANT:** If your Android SDK is installed elsewhere (e.g.,
> `C:\Users\<YourName>\AppData\Local\Android\Sdk`), update this file accordingly.

To find your SDK path in Android Studio: File > Project Structure > SDK Location.

```bash
# Verify the SDK path exists and has the platform installed:
dir "C:\Android\Sdk\platforms\android-35"
# Expected: Shows android.jar and other platform files

dir "C:\Android\Sdk\build-tools"
# Expected: Lists one or more build-tool version folders
```

If `android-35` is missing, open SDK Manager in Android Studio and install it.

---

## 4. Gradle Wrapper Check

```bash
# From the project root (Windows):
gradlew.bat --version
```

**Expected output:**
```
------------------------------------------------------------
Gradle 8.9
------------------------------------------------------------
Kotlin:       2.x.x
...
```

**If this fails with "wrapper JAR not found":**

The scaffold does not ship the Gradle wrapper JAR (only the scripts are in git).
Run this once from a machine with Gradle installed:

```bash
gradle wrapper --gradle-version 8.9
```

Or let Android Studio fix it automatically on first sync — it detects the
missing JAR and offers to download it.

---

## 5. Sync & Resolve Dependencies

Download all Maven dependencies. Do this **at home on good Wi-Fi** before any
hackathon demo — venue internet is unreliable.

```bash
gradlew.bat dependencies --configuration debugRuntimeClasspath
```

Or trigger a Gradle sync in Android Studio: File > Sync Project with Gradle Files.

**Key dependencies to confirm resolved:**

| Dependency | Expected Status |
|---|---|
| `androidx.core:core-ktx:1.15.0` | Resolved |
| `com.google.android.material:material:1.12.0` | Resolved |
| `com.google.mediapipe:tasks-genai:0.10.24` | Resolved (or check latest) |
| `com.alphacephei:vosk-android:0.3.75` | Resolved |
| `junit:junit:4.13.2` | Resolved |

> **NOTE:** `tasks-genai` is a fast-moving library. If `0.10.24` fails to
> resolve, check the latest version at:
> https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference/android
> and update `app/build.gradle.kts` line 83.
> The app still runs if this library fails to resolve — LlmTacticClassifier
> has a graceful fallback to the keyword classifier.

---

## 6. Run Unit Tests (No Device Needed)

This is the single most important verification. The 17 unit tests cover the
RiskEngine scoring brain and run in ~1 second with no emulator or device.

```bash
gradlew.bat :app:testDebugUnitTest
```

**Expected output (all 17 must pass):**

```
> Task :app:testDebugUnitTest
Tests run: 17, Failures: 0, Errors: 0, Skipped: 0

BUILD SUCCESSFUL
```

**If any test fails:**

Open the HTML report in your browser:
`app\build\reports\tests\testDebugUnitTest\index.html`

Click the failing test to see the actual vs. expected values.

**What the 17 tests guard:**

| Test Group | What It Verifies |
|---|---|
| Arming gate | Engine stays DORMANT without an active call / unknown caller / 180s+ |
| Behaviour-only scoring | 7 behavioural signals alone total >=85 (INTERVENE) with no model |
| Language-alone cap | All 10 tactics firing still scores below 45 (WATCH threshold) |
| Individual signal weights | Each signal contributes the documented point value |
| Threshold boundaries | WATCH=45, WARN=65, INTERVENE=85 |
| Null payee handling | `null` payeeIsNew scores same as known payee (no invented evidence) |

---

## 7. Run Python Verification Script

This is a line-by-line Python port of `RiskEngine.kt` that independently
verifies the arithmetic, thresholds, and arming gate logic.

```bash
python tools\verify_risk_engine.py
```

**Expected output:**

```
17/17 pass
```

> **NOTE:** If the count here does not match the number of tests in
> `app\src\test\kotlin\...\RiskEngineTest.kt`, one file was edited without
> updating the other. Both files must always agree in test count.

---

## 8. Build the Debug APK

```bash
gradlew.bat :app:assembleDebug
```

**Expected output:**

```
> Task :app:assembleDebug

BUILD SUCCESSFUL in Xs
```

**Verify the APK was created:**

```bash
dir app\build\outputs\apk\debug\app-debug.apk
# Expected: File exists, size around 5-20 MB
```

**Verify NO internet permission (privacy claim check):**

```bash
"C:\Android\Sdk\build-tools\35.0.0\aapt" dump permissions app\build\outputs\apk\debug\app-debug.apk
```

The output MUST contain these permissions:
- `android.permission.READ_PHONE_STATE`
- `android.permission.READ_CALL_LOG`
- `android.permission.RECORD_AUDIO`
- `android.permission.RECEIVE_SMS`
- `android.permission.READ_SMS`
- `android.permission.SYSTEM_ALERT_WINDOW`
- `android.permission.FOREGROUND_SERVICE`
- `android.permission.PACKAGE_USAGE_STATS`

The output MUST NOT contain: `android.permission.INTERNET`

> **CAUTION:** If INTERNET appears, do NOT demo or submit. Remove it immediately.
> The entire privacy claim rests on its absence. A judge can verify it from the
> system App Info screen in one tap.

---

## 9. Connect Your Device via ADB

Use a **physical Android device** (Android 12+, minSdk 31). Emulators cannot
properly simulate phone calls, SMS, or accessibility services.

```bash
# Enable USB Debugging:
# Settings > About Phone > tap Build Number 7 times
# > Developer Options > USB Debugging ON

# Connect via USB, then verify:
adb devices
```

**Expected output:**

```
List of devices attached
XXXXXXXXXXXXXXXX    device
```

If you see `unauthorized`, accept the ADB fingerprint dialog on your phone.

If you see `offline`:

```bash
adb kill-server
adb start-server
adb devices
```

---

## 10. Install the APK on Device

```bash
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

**Expected output:**

```
Performing Streamed Install
Success
```

**If "INSTALL_FAILED_MIN_SDK_VERSION":** Your device is below Android 12.
Prahari requires API 31+.

**If "INSTALL_FAILED_UPDATE_INCOMPATIBLE":** An old version exists. Uninstall
it first:

```bash
adb uninstall com.prahari.guardian
adb install app\build\outputs\apk\debug\app-debug.apk
```

Alternatively, click the green Run button in Android Studio with your device selected.

---

## 11. Grant All Required Permissions

Prahari needs 5 grants across 4 different Settings screens. There is no API to
batch them. The MainActivity onboarding screen has dedicated "Grant" buttons for each.

Open the Prahari app on your device and follow the steps below in order.

### Permission 1 — Phone, SMS and Microphone (Runtime)

Tap "Grant" next to "Phone, SMS and microphone".

Grant all system dialogs:
- READ_PHONE_STATE
- READ_CALL_LOG
- RECORD_AUDIO
- RECEIVE_SMS / READ_SMS

**Verify:** The row shows "Granted"

### Permission 2 — Show Over Other Apps (Overlay)

Tap "Grant" next to "Show the warning over other apps".

You will land in Settings > Display over other apps.
Find Prahari and toggle it ON.

Return to the app. **Verify:** Row shows "Granted"

### Permission 3 — Notification Access

Tap "Grant" next to "Read payment notifications".

You will land in Settings > Notification access.
Find Prahari and toggle it ON.

Return to the app. **Verify:** Row shows "Granted"

### Permission 4 — Accessibility Service

Tap "Grant" next to "See the amount on a payment screen".

You will land in Settings > Accessibility > Downloaded apps.
Find Prahari and toggle it ON. Read and accept the dialog.

Return to the app. **Verify:** Row shows "Granted"

> **IMPORTANT for iQOO / OriginOS:** Vivo/iQOO builds aggressively revoke
> accessibility grants after a reboot. Test a reboot. Re-grant if needed.

### Permission 5 — Usage Access

Tap "Grant" next to "Usage access".

You will land in Settings > Usage Access (or Digital Wellbeing on some builds).
Find Prahari and toggle it ON.

Return to the app. **Verify:** Row shows "Granted"

### Verify all 5 via ADB:

```bash
adb shell dumpsys package com.prahari.guardian | findstr "granted=true"
```

---

## 12. Verify Payment App Package Names

This is the most common silent failure. If package names in `KnownPackages.kt`
or `accessibility_service_config.xml` do not match what is actually installed
on the device, Prahari loses its heaviest behavioural signal (30 points) silently.

```bash
adb shell pm list packages | findstr /I "pay"
adb shell pm list packages | findstr /I "upi"
adb shell pm list packages | findstr /I "bank"
adb shell pm list packages | findstr /I "bhim"
adb shell pm list packages | findstr /I "paytm"
adb shell pm list packages | findstr /I "phonepe"
adb shell pm list packages | findstr /I "gpay"
```

Compare this output against:
- `app\src\main\kotlin\com\prahari\guardian\signals\KnownPackages.kt`
- `app\src\main\res\xml\accessibility_service_config.xml`

Both files must list exactly the same package names, and both must match reality.

---

## 13. Push On-Device AI Models (Optional but Recommended)

The models are NOT bundled in the APK (they are ~550 MB combined). The app
runs without them, falling back to keyword classification, but push them for
the best demo.

### Create storage directory on device:

```bash
adb shell mkdir -p /sdcard/Android/data/com.prahari.guardian/files
```

### Push Gemma3 LLM (tactic classifier):

Download from: https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference/android

```bash
adb push gemma3-1b-it-int4.task /sdcard/Android/data/com.prahari.guardian/files/
```

### Push Vosk ASR model (speech-to-text):

Download from: https://alphacephei.com/vosk/models
Use: vosk-model-small-en-in-0.4

```bash
adb push vosk-model-small-en-in-0.4 /sdcard/Android/data/com.prahari.guardian/files/vosk-model-small-en-in
```

### Verify models are on device:

```bash
adb shell ls -lh /sdcard/Android/data/com.prahari.guardian/files/
```

Expected output shows both `gemma3-1b-it-int4.task` and `vosk-model-small-en-in/` directory.

---

## 14. Set a Trusted Contact

On the main screen, scroll down to "Someone you trust":

1. Enter a name (e.g., Amma)
2. Enter their phone number (e.g., +91XXXXXXXXXX)
3. Tap "Save"

**Expected:** Toast shows "Saved. Prahari will offer to call Amma."
The button label changes to "Saved  tap to change".

This enables the "Call Amma" button on the intervention screen — the single most
powerful demo feature, because scammers always say "tell nobody".

---

## 15. Enable the Guardian Service

On the main screen, tap "Turn Prahari on".

**Expected:**
- Button label changes to "Turn Prahari off"
- A persistent notification appears: "Prahari is on. Nothing to watch."
- Engine status chip reads: `Engine: dormant · SLM loads when armed`

**If the service does not start:**

Verify RECORD_AUDIO is granted. A microphone foreground service cannot start
on Android 14+ without it already granted.

```bash
adb shell dumpsys activity services com.prahari.guardian
```

Look for `GuardianService` in the output. If listed, the service is running.

---

## 16. Run Live Demo Scenarios

The app ships 4 built-in replay scenarios (debug builds only). These replace
real collectors with fixture data — no actual phone call needed.

On the main screen, scroll down to "Demo scenarios" (visible in debug builds only).

### Step 1: Seed payment history first

Tap "Seed an ordinary payment history".
Expected toast: "Seeded. X past transfers on file."

This populates fake transfer history so the known/new payee logic works correctly.

### Step 2: Run each scenario

| Scenario Button | Expected Outcome |
|---|---|
| Scam — full signal set → INTERVENE | Intervention screen fires immediately |
| Scam — behaviour only, no model → INTERVENE | Score reaches 75+ on behaviour alone |
| Innocent — long family call → DORMANT | Engine stays silent (no false positive) |
| Innocent — payment with known payee → DORMANT | Engine stays silent |

### Step 3: Verify the intervention screen

When INTERVENE fires, you must see:
- Headline: "Wait."
- Subhead: "This looks like a scam call. Here is what Prahari noticed:"
- Reason lines matching the active signals
- "Hang up now" button
- "Call [TrustedName]" button (if you set one in step 14)
- "I understand the risk — wait 60s" countdown button

Tap the countdown button. It must count down from 60 to 0 before the
"continue anyway" button becomes tappable. This 60-second delay is the core
UX safety claim.

### Step 4: Exit demo

Tap "Exit replay" to restore real signal collectors.

---

## 17. Verify Logcat Output

Stream filtered logs while the app is running:

```bash
adb logcat -s GuardianService:D SignalStore:D RiskEngine:D LlmTacticClassifier:D StreamingTranscriber:D
```

**What to look for:**

| Log Message | Meaning |
|---|---|
| `GuardianService started` | Service is alive |
| `Armed — call active, unknown caller, >180s` | Engine entered armed state |
| `Disarmed` | Call ended; engine dormant; model released from memory |
| `Risk score: XX to DORMANT/WATCH/WARN/INTERVENE` | Scoring is running |
| `LLM loaded` | Gemma3 model initialized on arming edge |
| `LLM released` | Model removed from memory on disarm |
| `SLM loads when armed` | Correct pre-call dormant state |

**Critical: Verify the arming gate.**
The model must NOT appear in Logcat at service start. It must only load when
the engine transitions to armed (180s into an unknown caller call). Any
`LLM loaded` at service start is a regression.

---

## 18. Full Go/No-Go Checklist

Use this before any demo or submission.

```
PRE-DEVICE (laptop only)
[ ] java -version shows 17
[ ] gradlew.bat --version shows Gradle 8.9
[ ] gradlew.bat :app:testDebugUnitTest  -->  17/17 PASS
[ ] python tools\verify_risk_engine.py  -->  17/17 pass
[ ] gradlew.bat :app:assembleDebug  -->  BUILD SUCCESSFUL
[ ] aapt dump permissions  -->  INTERNET permission ABSENT

DEVICE SETUP
[ ] adb devices shows device (not unauthorized or offline)
[ ] adb install  -->  Success
[ ] All 5 permissions granted (app UI shows all as "Granted")
[ ] adb shell pm list packages | findstr pay  -->  matches KnownPackages.kt

MODELS (enhances demo, not required)
[ ] gemma3-1b-it-int4.task pushed to device
[ ] vosk-model-small-en-in pushed to device
[ ] Logcat shows "LLM loaded" after arming (not at service start)

APP FUNCTIONALITY
[ ] "Turn Prahari on"  -->  persistent notification appears
[ ] Engine chip shows "dormant · SLM loads when armed"
[ ] Trusted contact saved (name + number)
[ ] "Seed payment history"  -->  toast confirms seeded
[ ] Scam scenario  -->  InterventionActivity fires
[ ] Intervention screen: headline "Wait.", reason lines, trusted-contact button
[ ] 60-second countdown works before "continue anyway" unlocks
[ ] Innocent scenario  -->  engine stays DORMANT (no false positive)
[ ] "Exit replay"  -->  restores real collectors

HARDWARE CHECKS (iQOO / OriginOS specific)
[ ] After reboot, accessibility service still shows "Granted"
[ ] After 1 hour idle, GuardianService still listed in adb dumpsys
[ ] PHONE_STATE broadcast delivers incoming number (check Logcat)
```

---

## 19. Known Issues & Troubleshooting

### tasks-genai fails to resolve

`tasks-genai` moves fast. Check the latest version at:
https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference/android
Update line 83 of `app\build.gradle.kts` and re-sync.
The app still runs without it — graceful fallback to keyword classifier.

---

### Accessibility service revoked after reboot (iQOO / OriginOS)

OriginOS aggressively kills background services and revokes accessibility grants.

Fix:
1. Settings > Battery > Protected apps > add Prahari
2. Settings > Apps > Prahari > Battery > No restrictions
3. Re-grant accessibility after each reboot during testing

---

### Incoming caller number is always empty

`EXTRA_INCOMING_NUMBER` is empty on some OEM builds and carriers. When empty,
the caller reads as unknown — which biases toward arming. This is documented
acceptable behaviour in `CallStateMonitor.kt`, not a bug.

---

### INSTALL_FAILED_VERSION_DOWNGRADE

A newer versionCode is already installed. Either:
- Increment `versionCode` in `app\build.gradle.kts` (currently 1)
- Or uninstall first: `adb uninstall com.prahari.guardian`

---

### GuardianService killed after ~1 hour

Check if the service is still running:

```bash
adb shell dumpsys activity services com.prahari.guardian | findstr "GuardianService"
```

If not listed, the OS killed it. Re-enable from MainActivity.
Ensure the persistent foreground-service notification is not dismissed — swiping
it away lets OriginOS kill the service.

---

### Demo scenarios not visible

Demo controls only appear in debug builds. `DEMO_MODE_ENABLED` is `false` in
release builds. Use `assembleDebug`, not `assembleRelease`, for testing.

---

### Tests pass but APK build fails

The Python script and unit tests only verify arithmetic. Kotlin compilation
errors are only caught by the build. Run:

```bash
gradlew.bat :app:compileDebugKotlin
```

Check the output for compile errors before running `assembleDebug`.

---

*End of RUN.md — all 19 sections covered.*
