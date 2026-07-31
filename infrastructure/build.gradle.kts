plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-library`
}

dependencies {
    implementation(project(":application"))

    api(libs.java.jwt)
    implementation(libs.hikari)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.bcrypt)
    implementation(libs.livekit.server)
    implementation(libs.lettuce)
    implementation(platform(libs.aws.bom))
    implementation(libs.aws.s3)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)
    implementation(libs.google.auth.oauth2.http)
    implementation(libs.google.api.services.drive)
    implementation(libs.metadata.extractor)

    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.h2)
    testImplementation(kotlin("test"))
    testImplementation(libs.embedded.postgres)
    testImplementation(enforcedPlatform(libs.embedded.postgres.binaries.bom))
}

tasks.register<JavaExec>("authorizeGoogleDrive") {
    group = "application"
    description = "Authorizes a personal Google Drive account and writes an authorized-user credential JSON."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("life.fxs.purr.server.recording.GoogleDriveOAuthAuthorizer")
}
