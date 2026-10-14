plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing comes from the environment (CI secrets). Without it, assembleRelease
// still works and produces an unsigned APK, so a clean machine can build every variant.
val releaseKeystore: String? = System.getenv("HAUSFUNK_KEYSTORE_FILE")

android {
    namespace = "io.github.wendevlin.hausfunk"
    compileSdk = 36

    defaultConfig {
        // Permanent: changing it breaks updates of installed copies.
        applicationId = "io.github.wendevlin.hausfunk"
        minSdk = 31
        targetSdk = 36
        // Bump both for every release; CI checks that the tag equals "v$versionName".
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
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
    implementation(libs.compose.material3.window.size.class)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}
