plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    toolchain {
        languageVersion.set(
            org.gradle.jvm.toolchain.JavaLanguageVersion
                .of(17),
        )
    }
}

application {
    mainClass.set("com.pynapli.server.ApplicationKt")
}

dependencies {
    implementation(projects.shared)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.cloud.sql.postgres.socket.factory)
    implementation(libs.postgres)
    implementation(libs.hikari)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.lettuce.core)
    implementation(libs.firebase.admin)
    implementation(libs.logback.classic)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.websockets)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.mockk)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.junit.vintage.engine)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
