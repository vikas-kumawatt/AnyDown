import java.net.URL

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Fetches Plus Jakarta Sans into res/font at build time.
 *
 * The font isn't committed — it's an OFL-licensed upstream artefact, and
 * checking a binary into the repo to be silently forked is worse than fetching
 * a known version. Gradle caches the output directory, so this only touches the
 * network once, and Android Studio picks it up because preBuild depends on it.
 *
 * One variable file covers every weight: Compose selects along the `wght` axis
 * via FontVariation (API 26+). On API 24-25 the axis is ignored and the font
 * renders at its default weight — still Plus Jakarta Sans, just uniform.
 */
val fontFile = layout.projectDirectory.file("src/main/res/font/plus_jakarta_sans.ttf")

val downloadFonts by tasks.registering {
    description = "Downloads the Plus Jakarta Sans variable font into res/font."
    outputs.file(fontFile)

    doLast {
        val target = fontFile.asFile
        if (target.exists() && target.length() > 50_000) return@doLast
        target.parentFile.mkdirs()

        val sources = listOf(
            "https://raw.githubusercontent.com/google/fonts/main/ofl/plusjakartasans/PlusJakartaSans%5Bwght%5D.ttf",
            "https://cdn.jsdelivr.net/gh/google/fonts@main/ofl/plusjakartasans/PlusJakartaSans%5Bwght%5D.ttf",
        )

        val failures = mutableListOf<String>()
        for (source in sources) {
            try {
                URL(source).openStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                if (target.length() > 50_000) {
                    logger.lifecycle("Fetched Plus Jakarta Sans (${target.length()} bytes)")
                    return@doLast
                }
                failures += "$source returned ${target.length()} bytes"
            } catch (e: Exception) {
                failures += "$source: ${e.message}"
            }
        }

        target.delete()
        throw GradleException(
            "Couldn't download Plus Jakarta Sans.\n" +
                failures.joinToString("\n") { "  - $it" } +
                "\n\nFix: download PlusJakartaSans[wght].ttf from " +
                "https://fonts.google.com/specimen/Plus+Jakarta+Sans and save it as\n" +
                "  ${target.relativeTo(rootDir)}"
        )
    }
}

tasks.named("preBuild") { dependsOn(downloadFonts) }

android {
    namespace = "com.anydown.downloader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.anydown.downloader"
        minSdk = 24
        targetSdk = 35
        // Bump both when tagging a release, and keep versionName equal to the
        // git tag minus its "v". versionCode must only ever increase — Android
        // refuses to install an APK whose versionCode is lower than the one
        // already on the device.
        versionCode = 3
        versionName = "1.2.0"

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
    implementation(libs.coil.compose)

    implementation(libs.youtubedl.android)
    // Without this, yt-dlp can't merge separate video+audio streams, which caps
    // most platforms at roughly 720p.
    implementation(libs.youtubedl.ffmpeg)

    testImplementation(libs.junit)
}
