plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing comes from the environment (CI secrets). Without it, assembleRelease
// still works and produces an unsigned APK, so a clean machine can build every variant.
val releaseKeystore: String? = System.getenv("HAUSFUNK_KEYSTORE_FILE")

android {
    namespace = "dev.wendelin.hausfunk"
    compileSdk = 37

    defaultConfig {
        // Permanent: changing it breaks updates of installed copies.
        applicationId = "dev.wendelin.hausfunk"
        minSdk = 31
        targetSdk = 37
        // Bump both for every release; CI checks that the release tag equals versionName.
        versionCode = 2
        versionName = "0.1.1"
        // Phones only: x86 and x86_64 would add ~29 MB of libwebrtc for emulators and Chromebooks.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        // Shared, public debug key: every CI test build is signed alike, so one installs as an
        // update over the previous one. It can only ever sign the ".debug" app, never a release.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("HAUSFUNK_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("HAUSFUNK_KEY_ALIAS")
                keyPassword = System.getenv("HAUSFUNK_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Installs next to the release app, with its own data, name and red-ringed icon.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = false
    }

    dependenciesInfo {
        // F-Droid rejects the encrypted dependency metadata block Google Play uses.
        includeInApk = false
        includeInBundle = false
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        // Versions are pinned at least 14 days old on purpose, and lint must not need the network.
        disable += setOf("NewerVersionAvailable", "GradleDependency", "AndroidGradlePluginVersion")
        // Phones only on purpose (see abiFilters); ChromeOS is not a target.
        disable += "ChromeOsAbiSupport"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.webrtc)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
