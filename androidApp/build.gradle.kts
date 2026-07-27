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

// Release signing is supplied at build time, never committed. Set these in ~/.gradle/gradle.properties,
// local.properties, or as -P flags / env vars in the release workflow. When the keystore is absent
// (every contributor build, CI, and any local `assembleRelease`) the release variant simply builds
// unsigned instead of failing, so the release path stays continuously verifiable.
val releaseKeystorePath: String? = stringProperty("pynapli.android.releaseKeystore", "").takeIf { it.isNotBlank() }
val releaseKeystore: File? = releaseKeystorePath?.let { rootProject.file(it) }?.takeIf { it.exists() }

android {
    namespace = "com.pynapli.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pynapli.app"
        minSdk = 23
        targetSdk = 36
        // Overridable so a release build can stamp the Play versionCode from CI without a commit:
        // -Ppynapli.android.versionCode=42 -Ppynapli.android.versionName=1.0.0
        versionCode = stringProperty("pynapli.android.versionCode", "1").toInt()
        versionName = stringProperty("pynapli.android.versionName", "0.1.0")
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        // Lint runs in the verify gates and in CI. Warnings stay visible but only errors stop a
        // build, so a new NewApi/security error fails fast instead of surfacing at release time.
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = true
        // OldTargetApi nags to raise targetSdk to the newest API (37). The version policy in
        // CLAUDE.md is stable-releases-only and targetSdk deliberately tracks stable platforms, so
        // this one is answered by policy rather than by a bump. GradleDependency stays ON — it also
        // reports genuinely stale third-party deps, which is signal worth keeping.
        disable += "OldTargetApi"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = stringProperty("pynapli.android.releaseKeystorePassword", "")
                keyAlias = stringProperty("pynapli.android.releaseKeyAlias", "")
                keyPassword = stringProperty("pynapli.android.releaseKeyPassword", "")
            }
        }
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
            signingConfig = signingConfigs.findByName("release")
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
        implementation(libs.androidx.core.ktx)
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
