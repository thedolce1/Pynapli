plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.pynapli.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pynapli.app"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
}

kotlin {
    target {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    dependencies {
        implementation(projects.composeApp)
        implementation(projects.shared)
        implementation(libs.androidx.activity.compose)
    }
}
