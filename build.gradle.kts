// Root build file.
//
// VERSION SET — verified compatible, do not mix and match:
//
//   AGP 8.7.3  ·  Kotlin 2.0.21  ·  Gradle 8.9  ·  JDK 17  ·  compileSdk 35
//
// AGP 8.7's maximum supported API level is 35. If you want compileSdk 36
// (Android 16) you must move to AGP 8.9 or newer *and* raise the Gradle wrapper
// with it — 36 on AGP 8.7 is an error, not a warning. The scaffold targets 35
// deliberately: nothing here needs an Android 16 API, and a lower targetSdk
// means fewer new platform restrictions to fight during a 30-hour build.
//
// If your Android Studio ships a newer AGP, change AGP and compileSdk together,
// then run `./gradlew build` ONCE at home so Gradle caches every artifact.
// Venue Wi-Fi is not something you want on your critical path.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
