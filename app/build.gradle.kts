import com.android.build.api.variant.FilterConfiguration.FilterType.ABI
import java.util.Base64
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Human-friendly ABI -> versionCode offset so each split APK gets a unique code.
val abiCodes = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "universal" to 3)

// ---------------------------------------------------------------------------
// Release signing (see docs/SIGNING.md).
//
// ROOT CAUSE of "App not installed as package conflicts with an existing
// package": the old build silently fell back to the DEBUG keystore whenever no
// KEYSTORE_PATH env var was set. Every machine / clean CI runner has a
// DIFFERENT auto-generated debug key, and Android refuses to install an update
// whose signature differs from the installed APK — so users had to uninstall
// first. The fix: sign every release with ONE stable, private keystore.
//
// Credential sources, in priority order:
//   1. keystore.properties in the repo root (local builds; git-ignored).
//      Create it with:  bash scripts/generate-keystore.sh
//   2. KEYSTORE_PATH / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD
//      environment variables (CI secrets).
//   3. The CI keystore persisted in the repo (.github/ci-keystore.jks.b64) —
//      the exact key CI signs with, so local builds match the published
//      signature and updates always install in place.
//
// NOTE: `Properties` / `Base64` are imported at the top of this file. Never
// write `java.util.Properties()` inline here — inside build.gradle.kts the
// `java {}` accessor shadows the `java` package and script compilation fails
// with "Unresolved reference 'util'".
// ---------------------------------------------------------------------------
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingValue(propKey: String, envKey: String): String? =
    (keystoreProps.getProperty(propKey) ?: System.getenv(envKey))?.takeIf { it.isNotBlank() }

val releaseStorePath: String? = signingValue("storeFile", "KEYSTORE_PATH")
val hasReleaseKeystore: Boolean =
    releaseStorePath != null && rootProject.file(releaseStorePath).exists()

// Source 3: the repo-persisted CI keystore. Decode it once at configuration
// time so plain local `gradle assembleRelease` produces the SAME signature as
// the APKs published by GitHub Actions.
val ciKeystoreB64 = rootProject.file(".github/ci-keystore.jks.b64")
val useCiKeystore: Boolean = !hasReleaseKeystore && ciKeystoreB64.exists()
val ciKeystoreFile = rootProject.file("build/ci-release.keystore")
if (useCiKeystore) {
    ciKeystoreFile.parentFile.mkdirs()
    ciKeystoreFile.writeBytes(
        Base64.getMimeDecoder().decode(ciKeystoreB64.readText().trim()),
    )
}

android {
    namespace = "studio.cluvex.aether"
    compileSdk = 35

    defaultConfig {
        applicationId = "studio.cluvex.aether"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "1.2.6"

        ndk {
            // We ship arm64 (primary) and arm builds.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        // 1.2.2: the in-app updater (APK download + system installer handoff)
        // was REMOVED. The app no longer downloads executable code at runtime
        // from anywhere. What remains is a read-only pointer to the official,
        // signed GitHub Releases page that the About card can open in the
        // browser -- no network call, no download, no installer intent.
        val githubRepo = System.getenv("GITHUB_REPOSITORY")
            ?: (project.findProperty("githubRepo") as? String ?: "")
        val releasesUrl =
            if (githubRepo.isNotBlank()) "https://github.com/$githubRepo/releases/latest" else ""
        buildConfigField("String", "RELEASES_URL", "\"$releasesUrl\"")

        // Aether engine (core) version compiled into this build. CI keeps this
        // in sync with native/aether/CORE_VERSION via scripts/sync-core.sh.
        val coreVersion = rootProject.file("native/aether/CORE_VERSION")
            .takeIf { it.exists() }?.readText()?.trim().orEmpty().ifBlank { "unknown" }
        buildConfigField("String", "CORE_VERSION", "\"$coreVersion\"")
    }

    // Both native cores (libhev-socks5-tunnel.so + libaether.so) are prebuilt by
    // scripts/build-natives.sh into src/main/jniLibs, so there is NO
    // externalNativeBuild / CMake step in the Gradle build.

    signingConfigs {
        create("release") {
            // PLAY-PROTECT FIX: sign with the FULL modern scheme chain.
            // AGP leaves v3 signing OFF by default; a complete v1+v2+v3
            // signature protects the whole archive from tampering and is
            // what reputable sideloaded apps ship with.
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            if (hasReleaseKeystore) {
                storeFile = rootProject.file(releaseStorePath!!)
                storePassword = signingValue("storePassword", "KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "KEY_PASSWORD")
            } else if (useCiKeystore) {
                storeFile = ciKeystoreFile
                storePassword = "aether-ci-keystore"
                keyAlias = "aether-ci"
                keyPassword = "aether-ci-keystore"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // PLAY-PROTECT FIX (root cause of the Google Play Protect
            // "App blocked to protect your device" / "hasn't seen an app
            // from this developer before" install warning): the old build
            // silently fell back to the DEBUG key here. Debug certificates
            // are auto-generated and DIFFERENT on every machine/CI runner,
            // so to Google every release looked like a brand-new unknown
            // developer and installs got flagged. A debug-signed release
            // must never ship again: without a stable keystore the release
            // build now FAILS FAST (guard below) instead of producing a
            // flag-magnet APK. See docs/SIGNING.md.
            signingConfig = if (hasReleaseKeystore || useCiKeystore) {
                signingConfigs.getByName("release")
            } else {
                null
            }
        }
    }

    // Produce one APK per ABI + a universal one -> exactly the 3 release files.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
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
        // IMPORTANT: extract native libs on install so the bundled `aether` and
        // `hev` executables live on disk in nativeLibraryDir and can be exec()'d.
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// PLAY-PROTECT FIX, part 2: hard gate. If neither a private keystore nor the
// persisted CI keystore is available, ANY release-producing task fails with a
// clear message instead of quietly emitting an unsigned/debug-signed APK that
// Google Play Protect then blocks as coming from an "unknown developer".
if (!hasReleaseKeystore && !useCiKeystore) {
    tasks.configureEach {
        if (name.contains("Release") &&
            (name.startsWith("assemble") || name.startsWith("package") || name.startsWith("bundle"))
        ) {
            doFirst {
                throw GradleException(
                    "No stable release keystore configured — refusing to build a " +
                        "debug-signed release (it triggers the Play Protect install " +
                        "warning and breaks in-place updates). Run " +
                        "scripts/generate-keystore.sh, or provide KEYSTORE_* env vars / " +
                        ".github/ci-keystore.jks.b64. See docs/SIGNING.md."
                )
            }
        }
    }
}

// Give every generated split APK a distinct, monotonic versionCode.
// IMPORTANT: derived from defaultConfig.versionCode (versionCode * 1000 + ABI
// offset) so each release's codes are strictly HIGHER than the previous
// release's. Android only allows installing an update when the new
// versionCode is greater — the old fixed base of 1000 froze the codes forever
// and silently broke in-place updates.
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abiName = output.filters.find { it.filterType == ABI }?.identifier
            val base = (android.defaultConfig.versionCode ?: 1) * 1000
            val offset = abiCodes[abiName ?: "universal"] ?: 0
            output.versionCode.set(base + offset)
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
