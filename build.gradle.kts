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
    alias(libs.plugins.spotless) apply false
}

// Spotless: Kotlin formatting gate. Adopted incrementally via `ratchetFrom` so only files
// changed relative to origin/main are enforced — no big-bang reformat of the existing tree.
// detekt is intentionally NOT added yet: no stable detekt release supports Kotlin 2.3 (only
// 2.0.0-alpha), which the version policy in docs/OFFICIAL_TECH_REFERENCE_GUIDE.md disallows.
allprojects {
    apply(plugin = "com.diffplug.spotless")
    val ktlintVersion =
        rootProject.libs.versions.ktlint
            .get()
    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        ratchetFrom = "origin/main"
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
