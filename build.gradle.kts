plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.ktor) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
    alias(libs.plugins.spotless) apply false
}

// Spotless: Kotlin formatting gate. The codebase was formatted to ktlint_official in one pass
// (see .git-blame-ignore-revs), so enforcement now covers all files — no ratchet needed.
// detekt is intentionally NOT added yet: no stable detekt release supports Kotlin 2.3 (only
// 2.0.0-alpha), which the version policy in docs/OFFICIAL_TECH_REFERENCE_GUIDE.md disallows.
allprojects {
    apply(plugin = "com.diffplug.spotless")
    val ktlintVersion =
        rootProject.libs.versions.ktlint
            .get()
    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        kotlin {
            target("src/**/*.kt")
            targetExclude("**/build/**", "**/generated/**")
            ktlint(ktlintVersion)
        }
        kotlinGradle {
            target("*.gradle.kts")
            ktlint(ktlintVersion)
        }
    }
}

// Compose compiler metrics/reports, opt-in via -Ppynapli.composeMetrics=true (see
// scripts/compose-metrics.ps1). Off by default on purpose: turning them on makes the compiler write
// per-module reports on every Compose compile, so it is a measurement tool rather than something to
// carry in normal builds and CI.
//
// Configured here rather than in each module so :androidApp, :composeApp and :webApp cannot drift
// apart — the same class of duplication that caused the QA-harness bugs. Output goes under the ROOT
// build directory so one script can read every module's report from a single place.
val composeMetricsEnabled = providers.gradleProperty("pynapli.composeMetrics").orNull == "true"

subprojects {
    plugins.withId("org.jetbrains.kotlin.plugin.compose") {
        extensions.configure<org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension> {
            if (composeMetricsEnabled) {
                val destination = rootProject.layout.buildDirectory.dir("compose-metrics/${project.name}")
                metricsDestination.set(destination)
                reportsDestination.set(destination)
            }
        }
    }
}

tasks.register("printProjectStructure") {
    group = "help"
    description = "Prints the initial Pynapli Gradle module structure."
    doLast {
        println(
            """
            Pynapli modules:
            - :shared     Kotlin Multiplatform product logic, networking, storage contracts
            - :composeApp Compose Multiplatform app shell for Android and iOS
            - :server     Ktor backend authority
            """.trimIndent(),
        )
    }
}
