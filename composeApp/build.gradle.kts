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
