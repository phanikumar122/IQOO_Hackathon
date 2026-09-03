plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.prahari.guardian"

    // compileSdk 35 is not arbitrary: AGP 8.7's maximum supported API level is
    // 35, and compiling against 36 on AGP 8.7 is a hard error. Raise both
    // together or neither — see the note in the root build.gradle.kts.
    //
    // minSdk 31 lets us use registerTelephonyCallback directly instead of the
    // deprecated PhoneStateListener — worth it, and the iQOO 15 is far above
    // this anyway. targetSdk 35 is also a deliberate choice: an app running on
    // Android 16 with targetSdk 35 keeps the Android 15 behaviours, which is one
    // fewer set of new platform restrictions to discover at hour 20.
    compileSdk = 35
    defaultConfig {
        applicationId = "com.prahari.guardian"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-hack"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // Flip this off for the final demo build so the hidden replay
            // trigger cannot fire by accident on stage.
            buildConfigField("boolean", "DEMO_MODE_ENABLED", "true")
        }
        release {
            isMinifyEnabled = false // no time for proguard surprises
            buildConfigField("boolean", "DEMO_MODE_ENABLED", "false")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    sourceSets["main"].java.srcDir("src/main/kotlin")
    sourceSets["test"].java.srcDir("src/test/kotlin")

    // Do NOT let Gradle compress the model if you ever do bundle it in assets.
    androidResources { noCompress += listOf("task", "tflite", "bin") }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // activity-ktx for onBackPressedDispatcher.addCallback {}, and
    // lifecycle-runtime-ktx for Activity.lifecycleScope. Both are transitive
    // through appcompat today, but pin them: relying on a transitive for an API
    // you call directly is how a dependency bump breaks your build at hour 27.
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Every layout here is LinearLayout/ScrollView, so there is no
    // constraintlayout dependency. Add it if you rewrite a layout; do not carry
    // it unused.

    // ---- On-device SLM (Tier 2) -------------------------------------------
    // MediaPipe LLM Inference API, wrapping LiteRT. NOT VERIFIED: 0.10.24 was
    // current when this was written and this artifact moves fast. Check
    // https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference/android
    // for the version in the official snippet before the event, and resolve it
    // at home. If it fails to resolve, the app still runs — LlmTacticClassifier
    // failing is a designed-for path, not a crash.
    implementation("com.google.mediapipe:tasks-genai:0.10.24")

    // ---- Offline ASR: pick ONE at the venue, after testing both on device --
    // Option A — Vosk. Streaming, small, low RAM, Indian English model. Start
    // here. 0.3.75 is the newest on Maven Central; the snippet on
    // alphacephei.com/vosk/install still says 0.3.47, so if 0.3.75 misbehaves
    // on device, dropping back to 0.3.47 is a known-good move.
    implementation("com.alphacephei:vosk-android:0.3.75")
    // Option B — whisper.cpp. There is no official prebuilt AAR on Maven
    // Central; the community builds are on JitPack or you build the AAR from
    // the upstream repo yourself. If you switch, add the JitPack repository to
    // settings.gradle.kts first — it is not there now, on purpose.

    // SMS parsing is plain regex in SmsSignalReceiver. ML Kit entity-extraction
    // was considered and dropped: it is a ~20 MB download for something two
    // regexes do, and it needs a model fetch on first use, which an app with no
    // INTERNET permission cannot do.

    testImplementation("junit:junit:4.13.2")
}
