plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.anydown.downloader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.anydown.downloader"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        // youtubedl-android ships a Python runtime and yt-dlp/ffmpeg binaries per
        // ABI. Every ABI you keep is roughly another 25-35 MB of APK. arm64-v8a
        // covers essentially every phone since ~2017; armeabi-v7a is here only
        // for older 32-bit devices and can be dropped. x86/x86_64 are emulator
        // targets — add them back if you want to run this in an emulator.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        debug {
            // The CI workflow builds this variant; it's signed with the
            // auto-generated debug key, which is fine for sideloading.
            applicationIdSuffix = ""
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    // Per-ABI APKs so you can install just the one your phone needs.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            // Also emit a universal APK, for when you don't know the ABI.
            isUniversalApk = true
        }
    }

    packaging {
        jniLibs {
            // Required by youtubedl-android: the bundled binaries have to be
            // extracted to disk to be executable, so they can't be loaded
            // straight from the compressed APK.
            useLegacyPackaging = true
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
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    implementation(libs.youtubedl.android)
    // Without this, yt-dlp can't merge separate video+audio streams, which caps
    // most platforms at roughly 720p.
    implementation(libs.youtubedl.ffmpeg)

    testImplementation(libs.junit)
}
