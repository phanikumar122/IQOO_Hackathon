// NOTE ON ORDER: `pluginManagement` must be the first block in this file.
// Gradle fails the build outright if anything — including rootProject.name —
// precedes it. Easy twenty minutes to lose at hour one.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // Vosk's Android bindings are on Maven Central. whisper.cpp is NOT —
        // if you switch ASR engines you will need to add JitPack here, or build
        // the AAR from the upstream repo yourself. Deliberately absent so that
        // switching is a decision someone makes on purpose.
    }
}

rootProject.name = "Prahari"

include(":app")
