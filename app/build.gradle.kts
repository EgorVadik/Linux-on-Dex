plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.crunzex.linuxondex"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.crunzex.linuxondex.privatebuild"
        // Android 13 (Tiramisu) through Android 16 — the supported window
        // for current Samsung Galaxy S Ultra firmware.
        minSdk = 33
        targetSdk = 36
        // versionCode keeps counting from the 0.x/2.x builds so devices that
        // already have the app update in place — Android forbids downgrading it.
        versionCode = 3
        versionName = "1.3.0-private.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Galaxy S Ultra devices and the ARM64 emulator are the only targets.
            abiFilters += "arm64-v8a"
        }

    }

    packaging {
        jniLibs {
            // The QEMU/PRoot payload is shipped as native libraries and must be
            // extracted to <nativeLibraryDir> on install: that is the only
            // location a modern-targetSdk app is allowed to exec() from.
            useLegacyPackaging = true
            keepDebugSymbols += "**/*.so"
        }
    }

    buildTypes {
        debug {
            // Installs beside the signed private build, so the emulator and
            // phone can keep both while a change is being tested.
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }
        release {
            // Shrinking is off: the payload dominates APK size, and keeping
            // stack traces readable matters more for an alpha build.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
