// Top-level build file. Plugin versions are declared once, in gradle/libs.versions.toml.
//
// AGP 9 has built-in Kotlin support (the `org.jetbrains.kotlin.android` plugin is no longer
// applied), so the Kotlin version is the one AGP's built-in Kotlin plugin provides and the
// Compose compiler comes from the Kotlin plugin's own Compose support.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
