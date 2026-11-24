buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // Override AGP 9.0.1's bundled Kotlin Gradle Plugin (2.2.10 -> 2.3.21)
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.androidx.room) apply false
}
