// ---------------------------------------------------------------------------
// Repository setup.
//
// ROOT CAUSE of "1.2.6 builds fine in a scratch repository but fails in the
// real one": nothing in the source tree. The stale-file problem that used to
// break such builds is already handled by 1.2.6 (StatusLine.kt,
// TrafficPanel.kt and ConnectionMeta.kt are listed in
// .github/removed-sources.txt and the purge step deletes them before the
// build; the CI log confirms all three were removed and the string-resource
// check passed). What actually killed the release build was artifact
// DOWNLOAD: Maven Central (repo.maven.apache.org, which is also what
// plugins.gradle.org/m2 proxies to) answered every single request from the
// hosted runner with "status code 403 from server: Forbidden", so the
// buildscript ':classpath' could not be resolved and Gradle failed while
// configuring the root project - kotlin-gradle-plugin, kotlin-reflect,
// kotlin-stdlib, asm, commons-io, httpmime, jsr305 and friends. That is an
// upstream / IP-level block, not a compile error, and because every one of
// those artifacts had exactly ONE source, a single bad minute at Sonatype was
// enough to fail a release.
//
// FIX: declare an independent, byte-identical mirror of Maven Central (the
// Google-hosted GCS mirror) AFTER mavenCentral(). Gradle falls through to the
// next repository when one fails, so a 403/429/5xx from Sonatype can no longer
// stop the build. The CI build step additionally retries download-related
// failures - and only those - see .github/workflows/build.yml.
// ---------------------------------------------------------------------------
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        // Fallback for Maven Central: same artifacts, independent
        // infrastructure. See the note at the top of this file.
        maven {
            name = "MavenCentralMirrorGoogle"
            url = uri("https://maven-central.storage-download.googleapis.com/maven2/")
        }
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Fallback for Maven Central: same artifacts, independent
        // infrastructure. See the note at the top of this file.
        maven {
            name = "MavenCentralMirrorGoogle"
            url = uri("https://maven-central.storage-download.googleapis.com/maven2/")
        }
    }
}

rootProject.name = "AetherMobile"
include(":app")
