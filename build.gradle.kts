plugins {
    alias(libs.plugins.android.application) apply false
    // Not applied: only puts Kotlin Gradle plugin 2.4.21 on the classpath (AGP 9 bundles an older one).
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.androidx.room) apply false
}
