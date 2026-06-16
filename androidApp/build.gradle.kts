plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

// Firebase plugins only activate when the (gitignored) google-services.json is present, so CI and
// contributors without Firebase config still build. Crashlytics requires the google-services plugin.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
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
        implementation(platform(libs.firebase.bom))
        implementation(libs.firebase.messaging)
        implementation(libs.firebase.crashlytics)
        implementation(libs.firebase.appcheck)
        implementation(libs.play.billing)
    }
}

dependencies {
    debugImplementation(libs.firebase.appcheck.debug)
    releaseImplementation(libs.firebase.appcheck.playintegrity)
}
