import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

allprojects {
    group = "life.fxs.purr"
    version = providers.gradleProperty("PURR_SERVER_VERSION").orElse("0.1.2").get()

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(17)
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
            }
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}

application {
    mainClass.set("life.fxs.purr.server.ApplicationKt")
}

dependencies {
    implementation(project(":application"))
    implementation(project(":infrastructure"))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.call.id)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.request.validation)
    implementation(libs.ktor.server.rate.limit)
    implementation(libs.ktor.server.forwarded.header)
    implementation(libs.ktor.server.metrics.micrometer)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.config.yaml)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.serialization.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.logback.classic)
    implementation(libs.bcrypt)
    implementation(libs.lettuce)
    implementation(libs.micrometer.prometheus)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.websockets)
    testImplementation(kotlin("test"))
}

val verifyArchitecture by tasks.registering {
    group = "verification"
    description = "Verifies dependency rules for domain and application modules."
    val protectedSources = files(
        project(":domain").layout.projectDirectory.dir("src/main/kotlin"),
        project(":application").layout.projectDirectory.dir("src/main/kotlin"),
    )
    inputs.files(protectedSources)
    doLast {
        val forbiddenImports = listOf(
            "io.ktor",
            "org.jetbrains.exposed",
            "io.livekit",
            "io.lettuce",
            "software.amazon.awssdk",
            "kotlinx.serialization",
            "life.fxs.purr.server.api",
            "life.fxs.purr.server.auth",
            "life.fxs.purr.server.config",
            "life.fxs.purr.server.db",
            "life.fxs.purr.server.livekit",
            "life.fxs.purr.server.realtime",
            "life.fxs.purr.server.recording",
            "life.fxs.purr.server.redis",
            "life.fxs.purr.server.repository",
            "life.fxs.purr.server.service",
        )
        val violations = protectedSources.asFileTree
            .matching { include("**/*.kt") }
            .files
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    val imported = line.removePrefix("import ").takeIf { line.startsWith("import ") }
                    imported
                        ?.takeIf { candidate -> forbiddenImports.any(candidate::startsWith) }
                        ?.let { "${file.relativeTo(rootDir)}:${index + 1}: $line" }
                }
            }
        check(violations.isEmpty()) {
            "Architecture boundary violations:\n${violations.joinToString("\n")}"
        }
    }
}

tasks.named("check") {
    dependsOn(verifyArchitecture)
}
