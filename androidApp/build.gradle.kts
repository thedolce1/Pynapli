import java.util.Properties

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

val localProperties =
    Properties().also { properties ->
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localPropertiesFile.inputStream().use { input -> properties.load(input) }
        }
    }

fun stringProperty(
    name: String,
    fallback: String,
): String = providers.gradleProperty(name).orNull ?: localProperties.getProperty(name) ?: fallback

fun buildConfigString(value: String): String = "\"${value.trim().replace("\\", "\\\\").replace("\"", "\\\"")}\""

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

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            buildConfigField(
                "String",
                "PYNAPLI_ENVIRONMENT",
                buildConfigString("local"),
            )
            buildConfigField(
                "String",
                "PYNAPLI_API_BASE_URL",
                buildConfigString(stringProperty("pynapli.android.apiBaseUrl", "http://10.0.2.2:8080")),
            )
            buildConfigField(
                "String",
                "PYNAPLI_WEBSOCKET_BASE_URL",
                buildConfigString(stringProperty("pynapli.android.websocketBaseUrl", "ws://10.0.2.2:8080")),
            )
        }
        create("staging") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
            isDebuggable = false
            versionNameSuffix = "-staging"
            buildConfigField(
                "String",
                "PYNAPLI_ENVIRONMENT",
                buildConfigString("staging"),
            )
            buildConfigField(
                "String",
                "PYNAPLI_API_BASE_URL",
                buildConfigString(stringProperty("pynapli.android.stagingApiBaseUrl", "https://api-staging.pynapli.com")),
            )
            buildConfigField(
                "String",
                "PYNAPLI_WEBSOCKET_BASE_URL",
                buildConfigString(stringProperty("pynapli.android.stagingWebsocketBaseUrl", "wss://api-staging.pynapli.com")),
            )
        }
        release {
            buildConfigField(
                "String",
                "PYNAPLI_ENVIRONMENT",
                buildConfigString("prod"),
            )
            buildConfigField(
                "String",
                "PYNAPLI_API_BASE_URL",
                buildConfigString(stringProperty("pynapli.android.releaseApiBaseUrl", "https://api.pynapli.com")),
            )
            buildConfigField(
                "String",
                "PYNAPLI_WEBSOCKET_BASE_URL",
                buildConfigString(stringProperty("pynapli.android.releaseWebsocketBaseUrl", "wss://api.pynapli.com")),
            )
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
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
        implementation(libs.androidx.fragment)
        implementation(platform(libs.firebase.bom))
        implementation(libs.firebase.messaging)
        implementation(libs.firebase.crashlytics)
        implementation(libs.firebase.appcheck)
        implementation(libs.play.billing)
    }
}

dependencies {
    debugImplementation(libs.firebase.appcheck.debug)
    add("stagingImplementation", libs.firebase.appcheck.debug)
    releaseImplementation(libs.firebase.appcheck.playintegrity)
}
