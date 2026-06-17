// Root build file. Plugins are declared here (apply false) and applied per-module
// so the version catalog (gradle/libs.versions.toml) stays the single source of
// truth for versions.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
