import com.android.build.api.variant.FilterConfiguration.FilterType.ABI
import java.util.Base64
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Human-friendly ABI -> versionCode offset so each split APK gets a unique code.
//
// 1.3.1, issue #8: x86_64 added. Chromebooks and Android emulators are x86_64,
// and with no such split the APK either refused to install or ran the engine
// through ARM translation. Offset 4, appended rather than inserted, so the codes
// already published for the three existing ABIs do not move - a versionCode that
// goes BACKWARDS for an installed user is an update that can never be offered.
val abiCodes = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "universal" to 3, "x86_64" to 4)

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
// ---------------------------------------------------------------------------
// SECURITY FINDING (audit 1.2.7-r2) — CRITICAL, and deliberately NOT silently
// changed here, because removing this key breaks in-place updates for everyone
// who already installed a release signed with it.
//
// `.github/ci-keystore.jks.b64` is the RELEASE SIGNING KEY, committed to the
// repository, and the password below is in this file in clear text. Anyone who
// can read the repo can therefore produce an APK that Android accepts as an
// in-place UPDATE of this app — same package name, same certificate, no warning,
// full VPN privileges over every byte the victim's device sends. `.gitignore`
// excludes `*.jks`, and the `.b64` suffix is what got this past it.
//
// For a censorship-circumvention VPN this is the highest-impact issue in the
// project. It cannot be fixed by editing a file: it needs a key rotation.
// docs/SECURITY_AUDIT_1.2.7-r2.md §1.1 has the migration plan. Until that is
// done, every release-producing build prints the warning below, so it can never
// happen again without somebody being told.
// ---------------------------------------------------------------------------
// ---------------------------------------------------------------------------
// 1.2.9 HARDENING, ported from WhiteAestherMobile's signing setup.
//
// WhiteAestherMobile takes its release key from Gradle properties fed by CI
// secrets and NOWHERE else: there is no key in that repository, and a build with
// no credentials produces no signed release. This build used to fall back to the
// committed `.github/ci-keystore.jks.b64` SILENTLY on any machine that had the
// repo checked out, which means a clone of this repo is a working release-signing
// setup for anyone who runs `gradle assembleRelease`.
//
// The fallback is now OPT-IN. CI is unaffected: the workflow writes a keystore to
// a temp path and exports KEYSTORE_PATH, so it takes branch 1 or 2 above and
// never reaches this one. What changes is that a local release build no longer
// quietly signs with the public key - it fails and says why - unless the person
// running it explicitly asks for that key with:
//
//     gradle assembleRelease -PaetherAllowPublicCiKey=true
//
// The file itself is deliberately NOT deleted. It is the certificate every
// already-published release was signed with, so removing it would break in-place
// updates for every existing user; that is a key rotation with a migration plan
// (docs/SECURITY_AUDIT_1.2.7-r2.md 1.1), not a build-file edit.
// ---------------------------------------------------------------------------
val ciKeystoreB64 = rootProject.file(".github/ci-keystore.jks.b64")
val allowPublicCiKey: Boolean =
    (project.findProperty("aetherAllowPublicCiKey") as? String)?.toBoolean() == true ||
        System.getenv("AETHER_ALLOW_PUBLIC_CI_KEY")?.toBoolean() == true
val useCiKeystore: Boolean = !hasReleaseKeystore && ciKeystoreB64.exists() && allowPublicCiKey
val ciKeystoreFile = rootProject.file("build/ci-release.keystore")
if (useCiKeystore) {
    ciKeystoreFile.parentFile.mkdirs()
    ciKeystoreFile.writeBytes(
        Base64.getMimeDecoder().decode(ciKeystoreB64.readText().trim()),
    )
    logger.warn(
        "\n" +
            "*******************************************************************\n" +
            "  WARNING: signing with the PUBLIC CI keystore committed to this\n" +
            "  repository (.github/ci-keystore.jks.b64, password in\n" +
            "  app/build.gradle.kts). Anyone can sign an APK that installs OVER\n" +
            "  this app as a legitimate update. Do not use this key for anything\n" +
            "  users install. Provide a private keystore via keystore.properties\n" +
            "  or the KEYSTORE_* environment variables.\n" +
            "  See docs/SECURITY_AUDIT_1.2.7-r2.md section 1.1.\n" +
            "*******************************************************************",
    )
}

android {
    namespace = "studio.cluvex.aether"
    compileSdk = 35

    defaultConfig {
        applicationId = "studio.cluvex.aether"
        minSdk = 26
        targetSdk = 35
        versionCode = 16
        versionName = "1.4.0"

        ndk {
            // arm64 (primary), arm, and x86_64 for Chromebooks and emulators
            // (1.3.1, issue #8). The Rust engine is cross-compiled for the
            // matching triplet by scripts/build-natives.sh.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
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

        // ------------------------------------------------------------------
        // 1.2.8-r5 BUILD IDENTITY.
        //
        // 1.4.0 ships as versionName "1.4.0" / versionCode 16, and PATCHLEVEL is
        // "1.4.0" with it. The field stays, because it is what identifies a build
        // beyond its version name: r2,
        // r3 and r4 were all "1.2.8 (12)", the engine banner printed only the
        // upstream core version (1.8.0) which is identical in all of them, and
        // so an APK from two rounds ago is indistinguishable from today's - in
        // the UI, in the log, and on the releases page.
        //
        // The r4 field log is the receipt: five separate strings in it belong to
        // the r3 build, so a whole diagnosis round analysed a binary that
        // predates the fix under test. PATCH_LEVEL is the identity that was
        // missing. It is written into the log on every connect, shown in the
        // About card, cross-checked against the stamp inside libaether.so, and
        // asserted by CI before a release is published.
        val patchLevel = rootProject.file("PATCHLEVEL")
            .takeIf { it.exists() }?.readText()?.trim().orEmpty().ifBlank { "unstamped" }
        buildConfigField("String", "PATCH_LEVEL", "\"$patchLevel\"")

        // ------------------------------------------------------------------
        // 1.2.9-r3 SIGNER IDENTITY (audit F-1 mitigation).
        //
        // F-1 cannot be closed by editing a file: the release key is committed to
        // this repository, and rotating it out is forbidden here because that
        // certificate is what every published release was signed with - a new one
        // would break in-place updates for every existing user.
        //
        // What is possible without touching the key is to make a build's identity
        // CHECKABLE. The published signer fingerprint is compiled in, and
        // studio.cluvex.aether.core.SignerIdentity compares it at runtime with the
        // certificate that actually signed the running APK. A repackaged "Aether"
        // signed by somebody else - the realistic attack on a VPN user in a
        // censored network, handed out through a mirror or a Telegram channel -
        // then says so in the About card and writes an ERROR line into the log on
        // every launch, instead of looking exactly like the real thing.
        //
        // Source order: the real release certificate if this repository pins one,
        // otherwise the CI certificate it actually publishes with. An unpinned
        // build compiles in "" and reports "authenticity cannot be checked".
        val expectedSigner = listOf(
            rootProject.file(".github/expected-signer.txt"),
            rootProject.file(".github/expected-signer-ci.txt"),
        ).asSequence()
            .filter { it.exists() }
            .mapNotNull { file ->
                Regex("(?m)^[0-9a-fA-F]{64}\$").find(file.readText())?.value?.lowercase()
            }
            .firstOrNull()
            .orEmpty()
        buildConfigField("String", "EXPECTED_SIGNER", "\"$expectedSigner\"")

        // How this APK was signed, as known at configuration time. CI exports
        // AETHER_SIGNING_MODE=release when the four signing secrets are present,
        // and =test when it falls back to the public key committed to the repo, so
        // the About card can tell the user which of the two they are holding.
        val signingMode = System.getenv("AETHER_SIGNING_MODE")?.takeIf { it.isNotBlank() }
            ?: when {
                hasReleaseKeystore -> "release"
                useCiKeystore -> "public-ci"
                else -> "unsigned"
            }
        buildConfigField("String", "SIGNING_MODE", "\"$signingMode\"")
    }

    // Both native cores (libhev-socks5-tunnel.so + libaether.so) are prebuilt by
    // scripts/build-natives.sh into src/main/jniLibs, so there is NO
    // externalNativeBuild / CMake step in the Gradle build.

    signingConfigs {
        create("release") {
            // Signature schemes: v2 + v3, NOT v1.
            //
            // The previous comment here claimed v1 was part of a "Play Protect
            // fix". It is not, and it was the one line in this block that made the
            // APK weaker. v1 is JAR signing: it authenticates individual entries
            // rather than the archive, it is the scheme the Janus class of
            // attacks targets, and it is only consulted by Android 6 and older.
            // minSdk here is 26, so on every device this app can be installed on,
            // v1 is dead weight that widens the attack surface and slows
            // installation. WhiteAestherMobile ships v2+v3 for exactly this
            // reason.
            //
            // Turning it off does not change the certificate, so in-place updates
            // over previously published releases still install.
            enableV1Signing = false
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
            // A release must never be debug-signed: debug certificates are
            // auto-generated and different on every machine and CI runner, so
            // each build looks like a different developer AND cannot install
            // over the last one. Without a stable keystore the release build
            // FAILS FAST (guard below) instead of producing such an APK.
            //
            // CORRECTION (1.2.9): the comment that used to sit here called this
            // the "root cause of the Play Protect install warning". It is not.
            // Stable signing fixes the update path and the "different developer
            // every build" signal, and it is worth doing for both - but the
            // dialog in the a1 report, "Play Protect hasn't seen this app before
            // ... send this app to Google for a security scan", is Play Protect's
            // unknown-APK scan prompt. It keys on the app being absent from
            // Google's corpus, not on how it is signed, and no build
            // configuration removes it. See docs/PLAY_PROTECT.md.
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
            include("arm64-v8a", "armeabi-v7a", "x86_64")
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
                    "No release keystore configured - refusing to build a " +
                        "debug-signed release (it breaks in-place updates and makes " +
                        "every build look like a different developer). Run " +
                        "scripts/generate-keystore.sh, or provide the KEYSTORE_* env " +
                        "vars. The public CI key in .github/ci-keystore.jks.b64 is no " +
                        "longer used automatically; pass " +
                        "-PaetherAllowPublicCiKey=true if you really want it. " +
                        "See docs/SIGNING.md and docs/PLAY_PROTECT.md."
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

    // Local JVM tests only. Deliberately the smallest possible addition: JUnit 4
    // and nothing else, no Robolectric and no instrumentation, because the two
    // things under test - AiRedaction and AiModelPolicy - are pure Kotlin by
    // design. Test dependencies do not enter the APK and do not affect
    // assembleRelease. Run with: gradle :app:testReleaseUnitTest
    testImplementation("junit:junit:4.13.2")
}
