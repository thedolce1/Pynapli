plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    jvmToolchain(17)

    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    android {
        namespace = "com.pynapli.shared"
        compileSdk = 36
        minSdk = 23
        withHostTest {}
    }

    // No iosX64: Compose Multiplatform 1.11 stopped publishing Intel-simulator artifacts;
    // Apple Silicon simulators use iosSimulatorArm64 and the iOS phase is deferred anyway.
    iosArm64()
    iosSimulatorArm64()

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.logging)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
        }

        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.sqldelight.android.driver)
        }

        // OkHttp on the JVM as well, so the desktop shell's WebSocket path is the same engine the
        // Android shell already exercises — a desktop-only engine difference would make the
        // fast-iteration target diverge from the thing it stands in for.
        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.sqldelight.native.driver)
        }

        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
        }
    }
}

sqldelight {
    databases {
        create("PynapliDatabase") {
            packageName.set("com.pynapli.shared.storage")
        }
    }
}
