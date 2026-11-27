plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    android {
        namespace = "com.pynapli.compose"
        compileSdk = 36
        minSdk = 23
        // No withHostTest {}: commonTest already runs on the JVM (desktop/jvm) and on wasm, and the
        // Android actuals (Keystore cipher, SQLite driver) need a device, which emulator QA covers.
        androidResources {
            enable = true
        }
    }

    jvm("desktop")

    // No iosX64: Compose Multiplatform 1.11 stopped publishing Intel-simulator artifacts;
    // Apple Silicon simulators use iosSimulatorArm64 and the iOS phase is deferred anyway.
    val iosTargets =
        listOf(
            iosArm64(),
            iosSimulatorArm64(),
        )

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.shared)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(libs.compose.ui.backhandler)
            implementation(compose.components.resources)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)
            implementation(libs.kotlinx.serialization.json)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }

        val desktopTest by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
                implementation(compose.uiTest)
            }
        }

        androidMain.dependencies {}
    }

    iosTargets.forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "PynapliCompose"
            isStatic = true
        }
    }
}

// Compose resources hold only the sign-in providers' own logo files, which the founder drops into
// src/commonMain/composeResources/files/brand/ (PATH_TO_TESTERS A10; none is committed). The Res
// class is generated even while that folder is empty, so the code that reads the files compiles
// before they exist; a missing file just means no mark (and the launch guards keep the buttons off).
compose.resources {
    packageOfResClass = "com.pynapli.app.resources"
    generateResClass = always
}
