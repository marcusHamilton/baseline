import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

// Release signing lives outside the repo; keystore.properties (git-ignored) points at it. Without it, release is unsigned.
val keystoreProps =
    Properties().apply {
        rootProject
            .file("keystore.properties")
            .takeIf { it.exists() }
            ?.inputStream()
            ?.use { load(it) }
    }

// -PgarminBaseUrl=http://127.0.0.1:8080 (with adb reverse tcp:8080 tcp:8080) points a build at tools/fake_garmin.py (emulator testing). Never ship one:
// it allows cleartext traffic and its versionName ends in "-fake".
val fakeGarminUrl = providers.gradleProperty("garminBaseUrl").orNull

android {
    namespace = "dev.baseline"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.baseline"
        minSdk = 29
        targetSdk = 37
        versionCode = 4
        versionName = "0.04"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GARMIN_BASE_URL", "\"${fakeGarminUrl ?: "https://personal.connectapi.garmin.com"}\"")
        manifestPlaceholders["cleartext"] = fakeGarminUrl != null
        if (fakeGarminUrl != null) versionNameSuffix = "-fake"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 plus resource shrinking; the keep rules (and why each library needs none) are in proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // The tree is lint-clean: any new finding fails `lintDebug`. Fix it, or disable it here with a reason.
        warningsAsErrors = true
        // No Timber dependency: android.util.Log is the logger, and none of the messages carry the key.
        // No core-ktx dependency, so the KTX extension suggestions have nothing to point at.
        disable += setOf("LogNotTimber", "UseKtx")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            // Robolectric reaches into JDK internals; the toolchain here is JDK 25 (Android Studio JBR).
            it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--enable-native-access=ALL-UNNAMED")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.garmin.fit)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.vico.compose.m3)
    implementation(libs.maplibre)
    implementation(libs.androidx.room.runtime)
    // Installs src/main/baseline-prof.txt (AOT-compiles start-up and tab paths) on sideloaded installs too; spec §12.
    implementation(libs.androidx.profileinstaller)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
