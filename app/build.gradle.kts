plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.jaredhq.dashboardandroid"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.jaredhq.dashboardandroid"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // The vendored IDO/VeryFit native libs (libVeryFitMulti.so, libprotocol.so)
        // ship only arm ABIs (ADR 0001). Constrain packaging to those so the build
        // doesn't expect x86 variants the SDK never provided.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        // Default safe value for any future custom build type; debug opts into local cleartext below.
        manifestPlaceholders["usesCleartextTraffic"] = "false"
    }

    buildTypes {
        debug {
            // Local-dev dashboards may use http://10.0.2.2 or LAN HTTP while iterating.
            manifestPlaceholders["usesCleartextTraffic"] = "true"
        }
        release {
            isMinifyEnabled = false
            // Private daily-use builds must not allow accidental cleartext dashboard traffic.
            manifestPlaceholders["usesCleartextTraffic"] = "false"
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Glance widgets
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    // Background refresh
    implementation(libs.androidx.work.runtime.ktx)

    // Offline cache
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Secure settings storage
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    // Remote camera (watch-triggered shutter — ui/camera)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // Networking + JSON
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization.converter)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // ── Vendored IDO/VeryFit watch SDK (ADR 0001) ────────────────────────────
    // Comprehensive set of compiled classes lifted from the VeryFit APK: com.ido.ble.*,
    // com.veryfit.*, the chip-vendor SDKs (com.realsil.*, com.sifli.*), and the SDK's
    // obfuscated helper classes (top-level wordlist packages acorn/basilisk/… + a-n)
    // which the obfuscator scattered across every dex. Excludes only what the app already
    // provides via Gradle (androidx/kotlin/kotlinx/okhttp3/okio/retrofit2) and the VeryFit
    // UI. Native .so files live in src/main/jniLibs/. Only IdoSdkWatchEngine imports com.ido.*.
    // OSS libs the SDK uses (greenDAO, gson) come from Gradle, not the jar, to avoid
    // duplicate-class conflicts with the app's transitive graph and to keep versions explicit.
    implementation(files("libs/ido-watch-sdk.jar"))
    implementation(libs.greendao)
    implementation(libs.gson)

    // Unit tests (pure JVM — run without a device/emulator)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
}
